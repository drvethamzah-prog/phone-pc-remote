"""Persistent Windows-protected identity for the forthcoming pinned TLS transport."""

import base64
import ctypes
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import ssl
import tempfile

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID


class Blob(ctypes.Structure):
    _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]


def protect_key(data, decrypt=False):
    """DPAPI user scope, with UI forbidden; never use machine-wide protection."""
    if os.name != "nt":
        raise RuntimeError("Identity storage requires Windows DPAPI")
    buffer = ctypes.create_string_buffer(data)
    source = Blob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    target = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    function = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    function.restype = wintypes.BOOL
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    if not function(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(target)):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        return ctypes.string_at(target.data, target.size)
    finally:
        kernel.LocalFree(target.data)


def public_key_bytes(key):
    return key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


class LocalIdentity:
    def __init__(self, key, certificate):
        if public_key_bytes(key.public_key()) != public_key_bytes(certificate.public_key()):
            raise ValueError("Identity key and certificate do not match")
        certificate.verify_directly_issued_by(certificate)
        now = datetime.now(timezone.utc)
        if not certificate.not_valid_before_utc <= now < certificate.not_valid_after_utc:
            raise ValueError("Identity certificate is not valid; explicit recovery is required")
        self.key = key
        self.certificate = certificate
        self.server_id = hashlib.sha256(public_key_bytes(key.public_key())).hexdigest()
        self.fingerprint = certificate.fingerprint(hashes.SHA256()).hex()
        self.hostname = "qareeb-" + self.server_id[:32] + ".local"

    @property
    def certificate_pem(self):
        return self.certificate.public_bytes(serialization.Encoding.PEM)

    @classmethod
    def load(cls, path):
        record = json.loads(Path(path).read_text(encoding="utf-8"))
        if not isinstance(record, dict) or record.get("version") != 1:
            raise ValueError("Unsupported identity format")
        key = serialization.load_pem_private_key(
            protect_key(base64.b64decode(record["protectedKey"], validate=True), decrypt=True), None)
        return cls(key, x509.load_pem_x509_certificate(record["certificate"].encode("ascii")))

    @classmethod
    def load_or_create(cls, path):
        path = Path(path)
        if path.exists():
            return cls.load(path)

        key = ec.generate_private_key(ec.SECP256R1())
        server_id = hashlib.sha256(public_key_bytes(key.public_key())).hexdigest()
        hostname = "qareeb-" + server_id[:32] + ".local"
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, hostname)])
        now = datetime.now(timezone.utc)
        certificate = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                       .public_key(key.public_key()).serial_number(x509.random_serial_number())
                       .not_valid_before(now - timedelta(minutes=5))
                       .not_valid_after(now + timedelta(days=365))
                       .add_extension(x509.SubjectAlternativeName([x509.DNSName(hostname)]), critical=False)
                       .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
                       .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False,
                                      key_encipherment=False, data_encipherment=False, key_agreement=False,
                                      key_cert_sign=False, crl_sign=False, encipher_only=False,
                                      decipher_only=False), critical=True)
                       .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                       .sign(key, hashes.SHA256()))
        identity = cls(key, certificate)
        private_pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                        serialization.NoEncryption())
        record = {"version": 1, "protectedKey": base64.b64encode(protect_key(private_pem)).decode("ascii"),
                  "certificate": identity.certificate_pem.decode("ascii")}
        path.parent.mkdir(parents=True, exist_ok=True)
        # Publish a complete file without overwriting a concurrently created identity.
        with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            output.write(json.dumps(record).encode("utf-8"))
            output.flush()
            os.fsync(output.fileno())
        try:
            try:
                os.link(temporary, path)
            except FileExistsError:
                return cls.load_or_create(path)
        finally:
            temporary.unlink()
        return identity

    def server_context(self):
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.set_ciphers("ECDHE+AESGCM:ECDHE+CHACHA20")
        # OpenSSL loads key material into memory before temporary files are removed.
        with tempfile.TemporaryDirectory(prefix="qareeb-tls-") as folder:
            cert_path = Path(folder) / "certificate.pem"
            key_path = Path(folder) / "key.pem"
            password = secrets.token_bytes(32)
            cert_path.write_bytes(self.certificate_pem)
            key_path.write_bytes(self.key.private_bytes(serialization.Encoding.PEM,
                                 serialization.PrivateFormat.PKCS8, serialization.BestAvailableEncryption(password)))
            context.load_cert_chain(cert_path, key_path, password=password)
        return context

    def trusted_client_context(self):
        """Test/client helper: trust exactly this certificate, not any LAN peer."""
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_verify_locations(cadata=self.certificate_pem.decode("ascii"))
        return context
