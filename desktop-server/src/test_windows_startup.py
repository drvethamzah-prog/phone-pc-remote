import subprocess
import unittest
from unittest.mock import patch

import startup_launcher
import windows_startup


class StartupTests(unittest.TestCase):
    def test_command_uses_windowless_runtime_and_absolute_launcher(self):
        with patch.object(windows_startup.Path, "is_file", return_value=True):
            command = windows_startup.startup_command()
        self.assertIn("pythonw.exe", command)
        self.assertIn(str(windows_startup.Path(windows_startup.__file__).resolve().parent), command)
        self.assertIn("startup_launcher.py", command)

    def test_missing_runtime_is_rejected(self):
        with patch.object(windows_startup.Path, "is_file", return_value=False):
            with self.assertRaises(RuntimeError):
                windows_startup.startup_command()

    def test_launcher_redirects_output_and_hides_window(self):
        with patch.object(startup_launcher, "server_arguments", return_value=[]), \
                patch.object(startup_launcher.subprocess, "call", return_value=0) as call:
            self.assertEqual(startup_launcher.launch(), 0)
        args, kwargs = call.call_args
        self.assertTrue(args[0][-1].endswith("server.py"))
        self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)
        self.assertEqual(kwargs["creationflags"], subprocess.CREATE_NO_WINDOW)
        self.assertIs(kwargs["stderr"], subprocess.STDOUT)

    def test_launcher_preserves_tls_mode(self):
        with patch.object(startup_launcher, "server_arguments", return_value=["--tls", "--require-existing-identity"]), \
                patch("startup_settings.validate_tls"), \
                patch.object(startup_launcher.subprocess, "call", return_value=0) as call:
            self.assertEqual(startup_launcher.launch(), 0)
        self.assertEqual(call.call_args.args[0][-2:], ["--tls", "--require-existing-identity"])
        call.assert_called_once()

    def test_invalid_config_never_launches_plaintext(self):
        with patch.object(startup_launcher, "server_arguments", side_effect=ValueError("bad config")), \
                patch.object(startup_launcher.subprocess, "call") as call:
            self.assertEqual(startup_launcher.launch(), 1)
        call.assert_not_called()

    def test_invalid_tls_identity_never_downgrades(self):
        with patch.object(startup_launcher, "server_arguments", return_value=["--tls"]), \
                patch("startup_settings.validate_tls", side_effect=ValueError("broken identity")), \
                patch.object(startup_launcher.subprocess, "call") as call:
            self.assertEqual(startup_launcher.launch(), 1)
        call.assert_not_called()


if __name__ == "__main__":
    unittest.main()
