from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
import tempfile
import time
import unittest

from qr_pairing import Enrollment, write_qr


class QrPairingTests(unittest.TestCase):
    def test_expiry_and_single_use(self):
        now = [0]
        enrollment = Enrollment(clock=lambda: now[0])
        self.assertFalse(enrollment.accept("wrong", "phone", "secret"))
        self.assertFalse(enrollment.accept(enrollment.code, "phone", ""))
        self.assertTrue(enrollment.accept(enrollment.code, "phone", "secret"))
        self.assertFalse(enrollment.accept(enrollment.code, "phone", "secret"))
        self.assertFalse(enrollment.accept(enrollment.code, "other-phone", "secret"))
        self.assertFalse(enrollment.accept(enrollment.code, "phone", "other-secret"))
        now[0] = 300
        self.assertFalse(enrollment.accept(enrollment.code, "phone", "secret"))

    def test_concurrent_claims_have_one_winner(self):
        enrollment = Enrollment()
        with ThreadPoolExecutor(max_workers=16) as pool:
            results = list(pool.map(lambda i: enrollment.accept(enrollment.code, str(i), "secret"), range(32)))
        self.assertEqual(sum(results), 1)

    def test_nonlocal_endpoint_rejected(self):
        for host in ("8.8.8.8", "127.0.0.1", "0.0.0.0", "224.0.0.1", "example.com", "2001:db8::1", "fe80::1%13"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                Enrollment().uri(host, 8765, "a" * 64)

    def test_image_and_java_parser_compatibility(self):
        jdk = Path("C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin")
        if not (jdk / "javac.exe").exists():
            self.skipTest("JDK not available")
        enrollment = Enrollment()
        uri = enrollment.uri("10.10.10.124", 8765, "a" * 64)
        root = Path(__file__).resolve().parents[2]
        sources = root / "mobile-app/app/src/main/java/com/phonepcremote"
        tests = root / "mobile-app/tests/com/phonepcremote"
        with tempfile.TemporaryDirectory() as folder:
            image = Path(folder) / "qr.png"
            write_qr(uri, image)
            from PIL import Image
            with Image.open(image) as bitmap:
                self.assertGreater(bitmap.width, 200)
                self.assertEqual(len(bitmap.getcolors()), 2)
            subprocess.run([str(jdk / "javac.exe"), "-d", folder,
                            *[str(sources / name) for name in ("QrEnrollment.java", "PinnedTls.java",
                                                              "LinkLocalEndpoint.java", "LocalSubnet.java")],
                            str(tests / "QrEnrollmentTest.java")], check=True, capture_output=True, timeout=30)
            result = subprocess.run([str(jdk / "java.exe"), "-cp", folder,
                                     "com.phonepcremote.QrEnrollmentTest", uri, str(int(time.time()))],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
