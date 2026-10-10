import ctypes
import platform
import unittest
from unittest.mock import Mock

import server


class KeyboardTests(unittest.TestCase):
    @unittest.skipUnless(platform.system() == "Windows", "Windows ABI")
    def test_input_abi_size(self):
        is_64 = ctypes.sizeof(ctypes.c_void_p) == 8
        self.assertEqual(ctypes.sizeof(server.INPUT), 40 if is_64 else 28)
        self.assertEqual(server.INPUT.u.offset, 8 if is_64 else 4)

    def check_character(self, character, units):
        user32 = Mock()
        captured = []

        def send(count, inputs, size):
            self.assertEqual(size, ctypes.sizeof(server.INPUT))
            captured.extend((inputs[i].ki.wScan, inputs[i].ki.dwFlags) for i in range(count))
            return count

        user32.SendInput.side_effect = send
        server.KeyboardController()._send_unicode_character(user32, character)
        self.assertEqual(captured, [(unit, flag) for unit in units for flag in (4, 6)])

    def test_arabic(self):
        self.check_character("\u0633", [0x0633])

    def test_supplementary_character(self):
        self.check_character("\U0001f600", [0xD83D, 0xDE00])

    def test_failed_injection_is_reported(self):
        user32 = Mock()
        user32.SendInput.return_value = 0
        with self.assertRaisesRegex(RuntimeError, "text_input_failed"):
            server.KeyboardController()._send_unicode_character(user32, "\u0633")


if __name__ == "__main__":
    unittest.main()
