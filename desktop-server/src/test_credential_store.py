import copy
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
from unittest.mock import patch

import credential_store
import server


class CredentialStoreTests(unittest.TestCase):
    def setUp(self):
        self.devices = {"phone": {"deviceName": "Test phone", "token": "test-only-secret-123456"}}

    def test_round_trip_has_no_plaintext_secret(self):
        record = credential_store.encode_devices(self.devices)
        self.assertNotIn(self.devices["phone"]["token"], json.dumps(record))
        self.assertEqual(credential_store.decode_devices(record), self.devices)

    def test_ciphertext_cannot_move_to_another_device(self):
        record = credential_store.encode_devices(self.devices)
        record["devices"]["other"] = record["devices"].pop("phone")
        with self.assertRaises(ValueError):
            credential_store.decode_devices(record)

    def test_plaintext_downgrade_is_rejected(self):
        with self.assertRaises(ValueError):
            credential_store.decode_devices({"version": 2, "devices": self.devices})

    def test_corruption_is_rejected(self):
        record = credential_store.encode_devices(self.devices)
        original = copy.deepcopy(record)
        record["devices"]["phone"]["protectedToken"] = "invalid!"
        with self.assertRaises(ValueError):
            credential_store.decode_devices(record)
        self.assertEqual(credential_store.decode_devices(original), self.devices)

    def test_migration_preserves_pairing_and_removes_plaintext(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy = root / "legacy.json"
            protected = root / "private" / "devices.json"
            legacy.write_text(json.dumps({"devices": self.devices}), encoding="utf-8")
            with patch.multiple(server, DATA_DIR=root, SETTINGS_PATH=root / "settings.json",
                                TRUSTED_DEVICES_PATH=protected, LEGACY_TRUSTED_DEVICES_PATH=legacy):
                state = server.ServerState()
                state.load()
                self.assertEqual(state.trusted_devices, self.devices)
                self.assertFalse(legacy.exists())
                self.assertEqual(credential_store.decode_devices(json.loads(protected.read_text())), self.devices)
                state.load()
                self.assertEqual(state.trusted_devices, self.devices)

    def test_failed_migration_keeps_original(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            legacy = root / "legacy.json"
            legacy.write_text(json.dumps({"devices": self.devices}), encoding="utf-8")
            with patch.multiple(server, DATA_DIR=root, SETTINGS_PATH=root / "settings.json",
                                TRUSTED_DEVICES_PATH=root / "private" / "devices.json",
                                LEGACY_TRUSTED_DEVICES_PATH=legacy):
                with patch.object(credential_store, "ensure_private_directory", side_effect=OSError("test failure")):
                    with self.assertRaises(OSError):
                        server.ServerState().load()
                self.assertTrue(legacy.exists())

    def test_private_directory_has_only_user_and_system_access(self):
        with tempfile.TemporaryDirectory() as directory:
            private = Path(directory) / "private"
            credential_store.ensure_private_directory(private)
            command = (
                "$ErrorActionPreference='Stop'; $acl = [System.IO.DirectoryInfo]::new('" + str(private).replace("'", "''") + "').GetAccessControl(); "
                "$rules = @($acl.Access | ForEach-Object { "
                "@{sid=$_.IdentityReference.Translate([System.Security.Principal.SecurityIdentifier]).Value; "
                "inherited=$_.IsInherited; type=$_.AccessControlType.ToString(); rights=$_.FileSystemRights.ToString()} }); "
                "@{protected=$acl.AreAccessRulesProtected; rules=$rules} | ConvertTo-Json -Depth 4 -Compress"
            )
            output = subprocess.check_output(["powershell.exe", "-NoProfile", "-Command", command],
                                             creationflags=subprocess.CREATE_NO_WINDOW)
            acl = json.loads(output)
            self.assertTrue(acl["protected"])
            self.assertEqual({rule["sid"] for rule in acl["rules"]},
                             {credential_store.user_sid(), "S-1-5-18"})
            for rule in acl["rules"]:
                self.assertFalse(rule["inherited"])
                self.assertEqual(rule["type"], "Allow")
                self.assertIn("FullControl", rule["rights"])


if __name__ == "__main__":
    unittest.main()
