from pathlib import Path
import subprocess
import tempfile
import unittest


class LocalTransportTests(unittest.TestCase):
    def test_java_transport_matrix(self):
        jdk = Path("C:/Program Files/Eclipse Adoptium/jdk-17.0.19.10-hotspot/bin")
        if not (jdk / "javac.exe").exists():
            self.skipTest("JDK unavailable")
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory() as folder:
            subprocess.run([str(jdk / "javac.exe"), "-d", folder,
                            str(root / "mobile-app/app/src/main/java/com/phonepcremote/LocalTransportPolicy.java"),
                            str(root / "mobile-app/tests/com/phonepcremote/LocalTransportPolicyTest.java")],
                           check=True, capture_output=True, timeout=30)
            result = subprocess.run([str(jdk / "java.exe"), "-cp", folder,
                                     "com.phonepcremote.LocalTransportPolicyTest"],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS 96", result.stdout)
