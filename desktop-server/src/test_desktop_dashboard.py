import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from desktop_dashboard import probe_server, trusted_device_rows, show_dashboard


class DashboardTests(unittest.TestCase):
    def test_hidden_window_builds_and_closes_without_changing_startup(self):
        import tkinter as tk
        try:
            root = tk.Tk()
        except tk.TclError as error:
            self.skipTest(str(error))
        root.withdraw()
        root.after(350, root.destroy)
        values = {"computerName": "Test", "localIp": "192.168.1.2", "port": 8765,
                  "transport": "TLS", "latencyMs": 1, "controlEnabled": True,
                  "controlsDryRun": False, "trustedDeviceCount": 1}
        with patch("tkinter.Tk", return_value=root), \
                patch("desktop_dashboard.current_command", return_value="same"), \
                patch("desktop_dashboard.startup_command", return_value="same"), \
                patch("desktop_dashboard.set_enabled") as change, \
                patch("desktop_dashboard.probe_server", return_value=values) as probe, \
                patch("desktop_dashboard.trusted_device_rows", return_value=[("Test phone", "today")]):
            show_dashboard()
        change.assert_not_called()
        probe.assert_called_once_with(tls=False)

    def test_missing_device_store_is_empty(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(trusted_device_rows(Path(folder) / "missing.json"), [])

    def test_device_list_excludes_tokens_and_bounds_names(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "devices.json"
            path.write_text(json.dumps({"devices": {"phone": {
                "deviceName": "x" * 1000, "token": "never-display", "lastSeenAt": "today"}}}))
            rows = trusted_device_rows(path)
            self.assertEqual(len(rows[0][0]), 120)
            self.assertNotIn("never-display", str(rows))

    def test_bad_device_store_is_not_reported_as_empty(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "devices.json"
            path.write_text('{"devices": []}')
            with self.assertRaises(ValueError):
                trusted_device_rows(path)

    def test_reply_is_bounded_and_socket_closes(self):
        for reply in (b"x" * 65537, b'{"type":"hello_ok"}\n', b'[]\n', b'{"type":"status"}'):
            with self.subTest(reply=reply[:40]):
                connection = MagicMock()
                connection.makefile.return_value.__enter__.return_value.readline.return_value = reply
                with patch("desktop_dashboard.socket.create_connection", return_value=connection) as connect:
                    with self.assertRaises(ValueError):
                        probe_server()
                connect.assert_called_once_with(("127.0.0.1", 8765), timeout=2)
                connection.sendall.assert_called_once_with(b'{"type":"status"}\n')
                connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
