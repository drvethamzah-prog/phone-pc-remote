from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import ssl
import tempfile
import threading
import unittest

from local_identity import LocalIdentity


@unittest.skipUnless(os.name == "nt", "Windows DPAPI required")
class IdentityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "local-identity.json"

    def tearDown(self):
        self.temp.cleanup()

    def test_reload_preserves_identity_and_private_key_is_protected(self):
        first = LocalIdentity.load_or_create(self.path)
        second = LocalIdentity.load_or_create(self.path)
        self.assertEqual(first.server_id, second.server_id)
        self.assertEqual(first.fingerprint, second.fingerprint)
        self.assertNotIn("PRIVATE KEY", self.path.read_text())
        self.assertEqual(json.loads(self.path.read_text())["version"], 1)

    def test_distinct_installations_have_distinct_identities(self):
        first = LocalIdentity.load_or_create(self.path)
        second = LocalIdentity.load_or_create(self.path.with_name("other.json"))
        self.assertNotEqual(first.server_id, second.server_id)

    def test_concurrent_creation_uses_one_identity(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            values = list(pool.map(lambda _: LocalIdentity.load_or_create(self.path).server_id, range(16)))
        self.assertEqual(len(set(values)), 1)

    def test_corruption_does_not_silently_replace_identity(self):
        self.path.write_text("broken")
        with self.assertRaises(ValueError):
            LocalIdentity.load_or_create(self.path)
        self.assertEqual(self.path.read_text(), "broken")

    def test_mismatched_certificate_is_rejected(self):
        LocalIdentity.load_or_create(self.path)
        other = LocalIdentity.load_or_create(self.path.with_name("other.json"))
        record = json.loads(self.path.read_text())
        record["certificate"] = other.certificate_pem.decode("ascii")
        self.path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "do not match"):
            LocalIdentity.load_or_create(self.path)

    def exchange(self, trusted):
        identity = LocalIdentity.load_or_create(self.path)
        context = identity.server_context()
        errors = []
        with socket.create_server(("127.0.0.1", 0)) as listener:
            listener.settimeout(3)

            def serve():
                try:
                    connection, _ = listener.accept()
                    with connection:
                        connection.settimeout(3)
                        with context.wrap_socket(connection, server_side=True) as secure:
                            self.assertEqual(secure.recv(4), b"ping")
                            secure.sendall(b"pong")
                except BaseException as error:
                    errors.append(error)

            worker = threading.Thread(target=serve)
            worker.start()
            try:
                client = identity if trusted else LocalIdentity.load_or_create(self.path.with_name("other.json"))
                with socket.create_connection(listener.getsockname(), timeout=3) as connection:
                    with client.trusted_client_context().wrap_socket(connection, server_hostname=client.hostname) as secure:
                        secure.sendall(b"ping")
                        self.assertEqual(secure.recv(4), b"pong")
            finally:
                worker.join(4)
                self.assertFalse(worker.is_alive())
            self.assertFalse(errors)

    def test_real_tls_roundtrip(self):
        self.exchange(trusted=True)

    def test_wrong_server_certificate_is_rejected(self):
        with self.assertRaises(ssl.SSLCertVerificationError):
            self.exchange(trusted=False)


if __name__ == "__main__":
    unittest.main()
