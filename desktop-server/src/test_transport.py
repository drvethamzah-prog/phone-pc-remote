import contextlib
import io
import json
import hashlib
import hmac
import socket
import subprocess
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch, MagicMock

import server
from capability_check import send_message


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="qareeb-transport-")
        root = Path(self.temp.name)
        mouse = server.MouseController()
        keyboard = server.KeyboardController()
        mouse.dry_run = keyboard.dry_run = True
        self.state = server.ServerState(local_ip="127.0.0.1")
        self.patches = patch.multiple(
            server, DATA_DIR=root, TRUSTED_DEVICES_PATH=root / "devices.json",
            LEGACY_TRUSTED_DEVICES_PATH=root / "legacy-devices.json",
            SETTINGS_PATH=root / "settings.json", EVENT_LOG_PATH=root / "events.log",
            STATE=self.state, MOUSE=mouse, KEYBOARD=keyboard,
            PAIRING_LIMITS=server.PairingLimits(),
            QR_ENROLLMENT=None,
        )
        self.patches.start()
        self.output = contextlib.redirect_stdout(io.StringIO())
        self.output.__enter__()
        self.token = self.state.trust_device("test-phone", "Test phone")
        self.tcp = server.ThreadedTcpServer(("127.0.0.1", 0), server.RemoteControlHandler)
        self.port = self.tcp.server_address[1]
        self.worker = threading.Thread(target=self.tcp.serve_forever, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.tcp.shutdown()
        self.tcp.server_close()
        self.worker.join()
        self.output.__exit__(None, None, None)
        self.patches.stop()
        self.temp.cleanup()

    def payload(self, kind, **fields):
        return {"type": kind, "deviceId": "test-phone", "token": self.token, **fields}

    def test_input_focus_requires_auth_and_enabled_control(self):
        with patch.object(server.INPUT_FOCUS, "snapshot", return_value={"editable": True, "focusId": "test", "supported": True}) as snapshot:
            response = send_message("127.0.0.1", self.port, {"type": "input_focus"})
            self.assertEqual(response["type"], "error")
            snapshot.assert_not_called()
            response = send_message("127.0.0.1", self.port, self.payload("input_focus"))
            self.assertTrue(response["editable"])
            snapshot.assert_called_once()
            self.state.disable_control()
            response = send_message("127.0.0.1", self.port, self.payload("input_focus"))
            self.assertEqual(response["reason"], "control_disabled")
            snapshot.assert_called_once()

    def test_large_unauthenticated_request_rejected_and_closed(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as client:
            client.sendall(b'{"type":"ping","padding":"' + b"x" * server.MAX_REQUEST_BYTES)
            reader = client.makefile("rb")
            self.assertEqual(json.loads(reader.readline())["reason"], "request_too_large")
            self.assertEqual(reader.readline(), b"")
        self.assertEqual(send_message("127.0.0.1", self.port, {"type": "ping"})["type"], "pong")

    def test_unterminated_request_rejected(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as client:
            client.sendall(b'{"type":"ping"}')
            client.shutdown(socket.SHUT_WR)
            self.assertEqual(json.loads(client.makefile("rb").readline())["reason"], "incomplete_request")

    def test_shutdown_blocked_when_controls_disabled_but_cancel_allowed(self):
        self.state.disable_control()
        with patch.object(server.subprocess, "Popen") as launch:
            response = send_message("127.0.0.1", self.port,
                                    self.payload("shutdown_computer", confirm="SHUTDOWN"))
            self.assertEqual(response["reason"], "control_disabled")
            response = send_message("127.0.0.1", self.port, self.payload("cancel_shutdown"))
            self.assertEqual(response["type"], "shutdown_cancelled")
            launch.assert_not_called()

    def test_worker_slot_released_after_invalid_request(self):
        budget = server.ConnectionAdmission(maximum=1, per_peer=1)
        self.tcp.connection_admission = budget
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as first:
            first.sendall(b"invalid\n")
            reader = first.makefile("rb")
            self.assertEqual(json.loads(reader.readline())["reason"], "invalid_json")
            self.assertEqual(reader.readline(), b"")
        import time
        for _ in range(100):
            with budget.lock:
                if budget.active == 0:
                    break
            time.sleep(0.01)
        self.assertEqual(budget.active, 0)
        self.assertEqual(send_message("127.0.0.1", self.port, {"type": "ping"})["type"], "pong")

    def test_excess_socket_is_closed_without_starting_worker(self):
        import time
        budget = server.ConnectionAdmission(maximum=1, per_peer=1)
        self.tcp.connection_admission = budget
        with socket.create_connection(("127.0.0.1", self.port), timeout=3) as first:
            for _ in range(100):
                with budget.lock:
                    if budget.active == 1:
                        break
                time.sleep(0.01)
            self.assertEqual(budget.active, 1)
            with socket.create_connection(("127.0.0.1", self.port), timeout=3) as excess:
                self.assertEqual(excess.recv(1), b"")
            self.assertEqual(budget.active, 1)
            first.sendall(b'{"type":"ping"}\n')
            self.assertEqual(json.loads(first.makefile("rb").readline())["type"], "pong")

    def test_dashboard_reads_real_legacy_server_without_credentials(self):
        from desktop_dashboard import probe_server
        response = probe_server(port=self.port)
        self.assertEqual(response["trustedDeviceCount"], 1)
        self.assertEqual(response["transport"], "Legacy (unencrypted)")
        self.assertNotIn("token", response)
        self.assertGreaterEqual(response["latencyMs"], 0)

    @unittest.skipUnless(sys.platform == "win32", "Windows DPAPI required")
    def test_dashboard_reads_real_tls_server(self):
        from desktop_dashboard import probe_server
        from local_identity import LocalIdentity
        path = Path(self.temp.name) / "local-identity.json"
        identity = LocalIdentity.load_or_create(path)
        self.tcp.tls_context = identity.server_context()
        response = probe_server(port=self.port, tls=True, identity_path=path)
        self.assertEqual(response["transport"], "TLS")
        self.assertEqual(response["trustedDeviceCount"], 1)

    def test_pairing_limit_does_not_block_trusted_reconnect(self):
        for index in range(5):
            reply = send_message("127.0.0.1", self.port,
                                 {"type": "hello", "deviceId": f"guess-{index}", "pairingCode": "wrong"})
            self.assertEqual(reply["reason"], "unauthorized")
        reply = send_message("127.0.0.1", self.port,
                             {"type": "hello", "deviceId": "new-phone", "pairingCode": self.state.pairing_code})
        self.assertEqual(reply["errorCode"], "pairing_rate_limited")
        self.assertGreater(reply["retryAfterSeconds"], 0)
        self.assertNotIn("new-phone", self.state.trusted_devices)
        self.assertEqual(send_message("127.0.0.1", self.port, self.payload("hello"))["type"], "hello_ok")

    def test_qr_ticket_disables_static_code_and_keeps_trusted_reconnect(self):
        from qr_pairing import Enrollment
        server.QR_ENROLLMENT = Enrollment()
        hello = {"type": "hello", "deviceId": "qr-phone", "requestedToken": "a" * 64,
                 "pairingCode": self.state.pairing_code}
        self.assertEqual(send_message("127.0.0.1", self.port, hello)["reason"], "unauthorized")
        hello["pairingCode"] = server.QR_ENROLLMENT.code
        self.assertEqual(send_message("127.0.0.1", self.port, hello)["type"], "paired")
        self.assertEqual(send_message("127.0.0.1", self.port, hello)["type"], "paired")
        hello["deviceId"] = "another-phone"
        self.assertEqual(send_message("127.0.0.1", self.port, hello)["reason"], "unauthorized")
        self.assertEqual(send_message("127.0.0.1", self.port, self.payload("hello"))["type"], "hello_ok")

    @unittest.skipUnless(sys.platform == "win32", "Windows DPAPI required")
    def test_java_pinned_tls_transport(self):
        from local_identity import LocalIdentity
        jdk = Path("C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin")
        if not (jdk / "javac.exe").exists():
            self.skipTest("JDK 17 not available")
        identity = LocalIdentity.load_or_create(Path(self.temp.name) / "local-identity.json")
        self.tcp.tls_context = identity.server_context()
        root = Path(__file__).resolve().parents[2]
        sources = root / "mobile-app/app/src/main/java/com/phonepcremote"
        tests = root / "mobile-app/tests/com/phonepcremote"
        subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "-d", self.temp.name,
                        str(sources / "ControlConnection.java"), str(sources / "PinnedTls.java"),
                        str(tests / "PinnedTlsTest.java")], check=True, capture_output=True, timeout=30)
        with patch.object(self.tcp, "handle_error"):
            result = subprocess.run([str(jdk / "java.exe"), "-cp", self.temp.name,
                                     "com.phonepcremote.PinnedTlsTest", str(self.port), identity.fingerprint,
                                     self.token], capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS pinned TLS", result.stdout)

    def test_ipv6_listener_coexists_with_ipv4_and_accepts_pairing(self):
        if not socket.has_ipv6:
            self.skipTest("IPv6 unavailable")
        try:
            listener = server.LocalIpv6TcpServer(("::1", self.port), server.RemoteControlHandler)
        except OSError as exc:
            self.skipTest(f"IPv6 loopback unavailable: {exc}")
        worker = threading.Thread(target=listener.serve_forever, daemon=True)
        worker.start()
        try:
            self.assertEqual(listener.socket.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY), 1)
            with socket.create_connection(("::1", self.port), timeout=2) as client:
                client.sendall((json.dumps(self.payload("hello")) + "\n").encode())
                self.assertEqual(json.loads(client.makefile("rb").readline())["type"], "hello_ok")
            self.assertEqual(send_message("127.0.0.1", self.port, {"type": "ping"})["type"], "pong")
        finally:
            listener.shutdown()
            listener.server_close()
            worker.join(2)

    def test_ipv6_enumeration_excludes_global_and_unscoped_addresses(self):
        values = [(None, None, None, None, (ip, 0, 0, scope)) for ip, scope in
                  [("fe80::1234", 13), ("2001:db8::1", 13), ("::1", 0), ("fe80::5678", 0)]]
        with patch.object(server.socket, "getaddrinfo", return_value=values):
            self.assertEqual(server.LocalIpv6Service.addresses(), {("fe80::1234", 13)})

    def test_ipv6_service_tracks_interfaces_and_removes_stale_endpoints(self):
        service = server.LocalIpv6Service(self.port)
        fake = MagicMock()
        with patch.object(service, "addresses", return_value={("fe80::1234", 13)}) as addresses, \
                patch.object(server, "LocalIpv6TcpServer", return_value=fake) as factory:
            try:
                service.refresh()
                service.refresh()
                factory.assert_called_once_with(("fe80::1234", self.port, 0, 13), server.RemoteControlHandler)
                self.assertEqual(server.advertised_local_ipv6(), ["fe80::1234"])
                addresses.return_value = set()
                service.refresh()
                self.assertEqual(server.advertised_local_ipv6(), [])
                fake.shutdown.assert_called_once()
                fake.server_close.assert_called_once()
            finally:
                service.stop()

    def test_discovery_proves_pairing_without_disclosing_token(self):
        nonce = "12345678-1234-1234-1234-123456789012"
        response = server.discovery_response_payload({"deviceId": "test-phone", "nonce": nonce})
        expected = hmac.new(self.token.encode(), f"qareeb-discovery-v1\n{nonce}\n{server.PORT}".encode(), hashlib.sha256).hexdigest()
        self.assertEqual(response["discoveryProof"], expected)
        self.assertNotIn(self.token, json.dumps(response))
        self.assertNotIn("discoveryProof", server.discovery_response_payload())
        self.assertNotIn("discoveryProof", server.discovery_response_payload({"deviceId": "unknown", "nonce": nonce}))
        self.assertNotIn("discoveryProof", server.discovery_response_payload({"deviceId": "test-phone", "nonce": "short"}))
        self.assertNotIn("discoveryProof", server.discovery_response_payload({"deviceId": [], "nonce": nonce}))

    def test_discovery_java_compatible_vector(self):
        nonce = "12345678-1234-1234-1234-123456789012"
        self.state.trusted_devices["test-phone"]["token"] = "test-secret"
        response = server.discovery_response_payload({"deviceId": "test-phone", "nonce": nonce})
        expected = hmac.new(b"test-secret", f"qareeb-discovery-v1\n{nonce}\n{server.PORT}".encode(), hashlib.sha256).hexdigest()
        self.assertEqual(response["discoveryProof"], expected)
        self.assertEqual(expected, "08309761b71b8c6534d46727e45bef310c945850cba923275afe184fa9bdb21e")

    def test_identify_then_hello_on_same_socket(self):
        nonce = "12345678-1234-1234-1234-123456789012"
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as client:
            with client.makefile("rb") as reader:
                identify = {"type": "identify", "deviceId": "test-phone", "nonce": nonce, "keepAlive": True}
                client.sendall((json.dumps(identify) + "\n").encode())
                proof = json.loads(reader.readline())["discoveryProof"]
                expected = hmac.new(self.token.encode(), f"qareeb-discovery-v1\n{nonce}\n{server.PORT}".encode(), hashlib.sha256).hexdigest()
                self.assertEqual(proof, expected)
                client.sendall((json.dumps(self.payload("hello")) + "\n").encode())
                self.assertEqual(json.loads(reader.readline())["type"], "hello_ok")

    def test_legacy_client_receives_response_and_eof(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as client:
            client.sendall(b'{"type":"ping"}\n')
            with client.makefile("rb") as reader:
                self.assertEqual(json.loads(reader.readline())["type"], "pong")

    def test_one_shot_pairing_reply_is_followed_by_eof(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as client:
            client.settimeout(2)
            client.sendall((json.dumps(self.payload("hello", keepAlive=False)) + "\n").encode())
            with client.makefile("rb") as reader:
                reply = json.loads(reader.readline())
                self.assertEqual(reply["type"], "hello_ok")
                self.assertEqual(reply["deviceId"], "test-phone")
                self.assertEqual(reader.read(), b"")
                self.assertEqual(reader.readline(), b"")

    def test_pipelined_inputs_are_processed_in_order(self):
        values = [str(i) for i in range(100)]
        with patch.object(server.KEYBOARD, "type_text") as type_text:
            with socket.create_connection(("127.0.0.1", self.port), timeout=3) as client:
                client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                client.sendall(b"".join(
                    (json.dumps(self.payload("type_text", text=value, keepAlive=True)) + "\n").encode()
                    for value in values
                ))
                with client.makefile("rb") as reader:
                    for _ in values:
                        self.assertEqual(json.loads(reader.readline())["type"], "keyboard_ok")
            self.assertEqual([call.args[0] for call in type_text.call_args_list], values)

    def test_concurrent_activity_has_no_file_collisions_or_per_command_writes(self):
        def request(_):
            return send_message("127.0.0.1", self.port, self.payload("get_settings"))

        self.state.last_saved_at = 0
        with patch.object(server, "save_json_file", wraps=server.save_json_file) as save:
            with ThreadPoolExecutor(max_workers=8) as pool:
                responses = list(pool.map(request, range(200)))
            self.assertTrue(all(item["type"] == "settings" for item in responses))
            self.assertEqual(save.call_count, 1)
        persisted = json.loads(server.TRUSTED_DEVICES_PATH.read_text())
        self.assertNotIn(self.token, json.dumps(persisted))
        self.assertEqual(server.credential_store.decode_devices(persisted)["test-phone"]["token"], self.token)

    def test_concurrent_pairing_saves_all_devices(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda i: self.state.trust_device(f"phone-{i}", "Test"), range(32)))
        persisted = json.loads(server.TRUSTED_DEVICES_PATH.read_text())
        self.assertEqual(len(persisted["devices"]), 33)
        self.assertTrue(self.state.forget_device("phone-1"))
        self.assertNotIn("phone-1", json.loads(server.TRUSTED_DEVICES_PATH.read_text())["devices"])

    def test_auth_is_rechecked_on_persistent_connection(self):
        with socket.create_connection(("127.0.0.1", self.port), timeout=2) as client:
            with client.makefile("rb") as reader:
                request = (json.dumps(self.payload("get_settings", keepAlive=True)) + "\n").encode()
                client.sendall(request)
                self.assertEqual(json.loads(reader.readline())["type"], "settings")
                self.state.forget_device("test-phone")
                client.sendall(request)
                self.assertEqual(json.loads(reader.readline())["reason"], "unauthorized")

    def test_lost_pairing_response_can_be_confirmed_without_rotating_token(self):
        requested = "test-pending-token-0123456789012345"
        request = {"type": "hello", "deviceId": "lost-reply-phone", "pairingCode": "111111",
                   "requestedToken": requested, "keepAlive": True, "requestId": "lost-pair"}
        original = server.RemoteControlHandler.send_json
        dropped = threading.Event()

        def drop_first(handler, response):
            if response.get("type") == "paired" and not dropped.is_set():
                dropped.set()
                return
            original(handler, response)

        with patch.object(server.RemoteControlHandler, "send_json", drop_first):
            with self.assertRaises(TimeoutError):
                send_message("127.0.0.1", self.port, request, timeout=.2)
            self.assertTrue(dropped.is_set())
            confirmed = send_message("127.0.0.1", self.port, {
                "type": "hello", "deviceId": "lost-reply-phone", "token": requested,
                "requestId": "confirm-pair", "keepAlive": True,
            })
        self.assertEqual(confirmed["type"], "hello_ok")
        self.assertEqual(confirmed["requestId"], "confirm-pair")
        self.assertTrue(self.state.is_trusted("lost-reply-phone", requested))

    def test_pairing_with_valid_saved_token_does_not_adopt_unused_candidate(self):
        response = send_message("127.0.0.1", self.port, self.payload(
            "hello", pairingCode="111111", requestedToken="unused-new-token-0123456789012345",
            keepAlive=True,
        ))
        self.assertEqual(response["type"], "hello_ok")
        self.assertTrue(self.state.is_trusted("test-phone", self.token))
        self.assertFalse(self.state.is_trusted("test-phone", "unused-new-token-0123456789012345"))

    def test_existing_capabilities_on_isolated_dry_run_server(self):
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("capability_check.py")), "--port", str(self.port)],
            capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("passed=14 failed=0", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
