"""Run Android instrumentation against an isolated real server with a recording keyboard.

The test listener is loopback-only. It never sends input to Windows or modifies the
production server's device store. --preview holds the instrumented Activity for UI checks.
"""
import argparse
from pathlib import Path
import socketserver
import socket
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "desktop-server/src"))
import server
server.print = lambda *args, **kwargs: None


class RecordingKeyboard:
    dry_run = True

    def __init__(self):
        self.lock = threading.RLock()
        self.text = ""
        self.events = []
        self.errors = 0
        self.close_preview = False
        self.hello_keep_alive = None
        self.drop_next_input = False

    def is_available(self):
        return True

    def type_text(self, text):
        with self.lock:
            self.text += text
            self.events.append({"insert": text})

    def press_key(self, vk, modifiers=None, count=1):
        with self.lock:
            if vk == server.NAMED_KEYS["backspace"]:
                self.text = self.text[:-count]
            elif vk == server.NAMED_KEYS["enter"]:
                self.text += "\n" * count
            self.events.append({"key": vk, "modifiers": modifiers or [], "count": count})


class Handler(server.RemoteControlHandler):
    def send_json(self, payload):
        if payload.get("type") == "error":
            server.KEYBOARD.errors += 1
        super().send_json(payload)

    def handle_message(self, message):
        kind = message.get("type")
        if kind == "hello":
            server.KEYBOARD.hello_keep_alive = message.get("keepAlive")
        if kind == "type_text" and server.KEYBOARD.drop_next_input:
            server.KEYBOARD.drop_next_input = False
            server.KEYBOARD.type_text(message.get("text", ""))
            self.request.shutdown(socket.SHUT_RDWR)
            return
        if kind in ("test_snapshot", "test_finish_preview", "test_reset", "test_drop_next_input"):
            with server.KEYBOARD.lock:
                if kind == "test_drop_next_input":
                    server.KEYBOARD.drop_next_input = True
                if kind == "test_finish_preview":
                    server.KEYBOARD.close_preview = True
                if kind == "test_reset":
                    server.KEYBOARD.text = ""
                    server.KEYBOARD.events.clear()
                self.send_json({"type": "test_snapshot", "text": server.KEYBOARD.text,
                                "events": server.KEYBOARD.events, "errors": server.KEYBOARD.errors,
                                "controlEnabled": server.STATE.control_enabled,
                                "helloKeepAlive": server.KEYBOARD.hello_keep_alive,
                                "closePreview": server.KEYBOARD.close_preview})
            return
        super().handle_message(message)


class TestServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 128


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true")
    parser.add_argument("--language", choices=("ar", "en", "system"), default="ar")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="qareeb-native-test-") as folder:
        server.DATA_DIR = Path(folder)
        server.TRUSTED_DEVICES_PATH = server.DATA_DIR / "devices.json"
        server.LEGACY_TRUSTED_DEVICES_PATH = server.DATA_DIR / "legacy-devices.json"
        server.SETTINGS_PATH = server.DATA_DIR / "settings.json"
        server.EVENT_LOG_PATH = server.DATA_DIR / "events.log"
        server.STATE = server.ServerState()
        server.IPV6_ENDPOINTS = {"fe80::dead:beef", "2001:db8::1"}
        server.STATE.trust_device("dashboard-instrumentation", "Android test", "dashboard-test-token")
        server.KEYBOARD = RecordingKeyboard()
        server.MOUSE.dry_run = True
        with TestServer(("127.0.0.1", 0), Handler) as listener:
            port = listener.server_address[1]
            server.PORT = port
            discovery = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            discovery.bind(("127.0.0.1", port))
            discovery.settimeout(0.2)
            stopped = threading.Event()

            def answer_discovery():
                import json
                while not stopped.is_set():
                    try:
                        data, peer = discovery.recvfrom(2048)
                        request = json.loads(data)
                        if request.get("type") == server.DISCOVERY_MESSAGE_TYPE:
                            discovery.sendto(json.dumps(server.discovery_response_payload(request)).encode(), peer)
                    except socket.timeout:
                        continue
                    except OSError:
                        break

            discovery_thread = threading.Thread(target=answer_discovery, daemon=True)
            discovery_thread.start()
            thread = threading.Thread(target=listener.serve_forever, daemon=True)
            thread.start()
            print("TEST_PORT=" + str(port), flush=True)
            command = [str(ROOT / ".android-sdk/platform-tools/adb.exe"), "-s", "emulator-5554", "shell", "am", "instrument",
                       "-w", "-r", "-e", "port", str(port), "-e", "language", args.language]
            if args.preview:
                command += ["-e", "preview", "true"]
            command += ["com.phonepcremote.test/com.phonepcremote.KeyboardInstrumentation"]
            try:
                result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=420)
                print(result.stdout, flush=True)
                print(result.stderr, flush=True)
                if result.returncode or "PASS " not in result.stdout or "FAIL " in result.stdout:
                    raise RuntimeError("Android instrumentation failed")
                print("PASS isolated server errors=", server.KEYBOARD.errors)
            finally:
                stopped.set()
                discovery_thread.join(2)
                discovery.close()
                listener.shutdown()
                thread.join(5)


if __name__ == "__main__":
    main()
