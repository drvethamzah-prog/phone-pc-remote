"""Versioned startup transport preference; never restart a running server."""

import json
import os
from pathlib import Path
import tempfile

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
SETTINGS_PATH = DATA_DIR / "startup-settings.json"


def load_mode(path=None):
    path = Path(path or SETTINGS_PATH)
    try:
        text = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return "legacy"
    record = json.loads(text)
    if (not isinstance(record, dict) or record.get("version") != 1
            or record.get("transport") not in ("legacy", "tls")):
        raise ValueError("Invalid startup transport settings; startup is blocked")
    return record["transport"]


def validate_tls(identity_path=None):
    from local_identity import LocalIdentity
    # An explicit setup must already have created the identity; never replace it.
    identity = LocalIdentity.load(identity_path or DATA_DIR / "local-identity.json")
    identity.server_context()


def save_mode(mode, path=None, identity_path=None):
    if mode not in ("legacy", "tls"):
        raise ValueError("Unsupported startup transport")
    if mode == "tls":
        validate_tls(identity_path)
    path = Path(path or SETTINGS_PATH)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, suffix=".tmp", delete=False) as output:
        temporary = Path(output.name)
        output.write(json.dumps({"version": 1, "transport": mode}).encode("utf-8"))
        output.flush()
        os.fsync(output.fileno())
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def server_arguments(path=None):
    return ["--tls", "--require-existing-identity"] if load_mode(path) == "tls" else []
