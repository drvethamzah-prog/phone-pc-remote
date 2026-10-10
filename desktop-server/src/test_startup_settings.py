import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from startup_settings import load_mode, save_mode, server_arguments, validate_tls


class StartupSettingsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "startup-settings.json"

    def tearDown(self):
        self.temp.cleanup()

    def test_existing_installation_defaults_to_legacy_without_writing(self):
        self.assertEqual(load_mode(self.path), "legacy")
        self.assertEqual(server_arguments(self.path), [])
        self.assertFalse(self.path.exists())

    def test_tls_roundtrip_and_explicit_legacy_reset(self):
        with patch("startup_settings.validate_tls") as validate:
            save_mode("tls", self.path)
        validate.assert_called_once()
        self.assertEqual(server_arguments(self.path), ["--tls", "--require-existing-identity"])
        save_mode("legacy", self.path)
        self.assertEqual(server_arguments(self.path), [])
        self.assertFalse(list(self.path.parent.glob("*.tmp")))

    def test_invalid_settings_fail_closed(self):
        for data in ("broken", "[]", '{"version":2,"transport":"tls"}', '{"version":1,"transport":"unknown"}'):
            self.path.write_text(data)
            with self.assertRaises(ValueError):
                server_arguments(self.path)

    def test_failed_tls_validation_preserves_existing_settings(self):
        save_mode("legacy", self.path)
        original = self.path.read_bytes()
        with patch("startup_settings.validate_tls", side_effect=ValueError("bad identity")):
            with self.assertRaises(ValueError):
                save_mode("tls", self.path)
        self.assertEqual(self.path.read_bytes(), original)

    def test_missing_identity_is_not_generated(self):
        identity = self.path.parent / "missing-identity.json"
        with self.assertRaises(FileNotFoundError):
            validate_tls(identity)
        self.assertFalse(identity.exists())

    def test_unknown_mode_is_rejected(self):
        with self.assertRaises(ValueError):
            save_mode("unknown", self.path)
        self.assertFalse(self.path.exists())
