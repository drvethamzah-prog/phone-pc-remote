"""User-profile credential storage with DPAPI and restricted Windows ACLs."""

import base64
import csv
from functools import lru_cache
import io
import json
import os
from pathlib import Path
import re
import subprocess
import threading

from local_identity import protect_key

DIRECTORY_LOCK = threading.Lock()
SECURED_DIRECTORIES = set()


def default_path():
    if os.name != "nt" or not os.environ.get("LOCALAPPDATA"):
        raise RuntimeError("Protected credentials currently require Windows user-profile storage")
    return Path(os.environ["LOCALAPPDATA"]) / "QareebControl" / "credentials" / "trusted-devices.json"


@lru_cache(maxsize=1)
def user_sid():
    executable = Path(os.environ["SystemRoot"]) / "System32" / "whoami.exe"
    output = subprocess.check_output([str(executable), "/user", "/fo", "csv", "/nh"],
                                     creationflags=subprocess.CREATE_NO_WINDOW)
    row = next(csv.reader(io.StringIO(output.decode(errors="replace").strip())))
    sid = row[1]
    if not re.fullmatch(r"S-1-\d+(?:-\d+)+", sid):
        raise ValueError("Could not identify the current Windows user")
    return sid


def ensure_private_directory(path):
    path = Path(path).resolve()
    with DIRECTORY_LOCK:
        if path in SECURED_DIRECTORIES:
            return
        path.mkdir(parents=True, exist_ok=True)
        icacls = str(Path(os.environ["SystemRoot"]) / "System32" / "icacls.exe")
        for options in (["/reset"], ["/inheritance:r", "/grant:r", f"*{user_sid()}:(OI)(CI)F", "*S-1-5-18:(OI)(CI)F"]):
            subprocess.run([icacls, str(path), *options, "/Q"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                           creationflags=subprocess.CREATE_NO_WINDOW)
        SECURED_DIRECTORIES.add(path)


def encode_devices(devices):
    result = {}
    for device_id, device in devices.items():
        metadata = {key: value for key, value in device.items() if key not in ("token", "protectedToken")}
        secret = json.dumps({"deviceId": device_id, "token": device["token"]}).encode("utf-8")
        metadata["protectedToken"] = base64.b64encode(protect_key(secret)).decode("ascii")
        result[device_id] = metadata
    return {"version": 2, "devices": result}


def decode_devices(record):
    if not isinstance(record, dict) or record.get("version") != 2 or not isinstance(record.get("devices"), dict):
        raise ValueError("Invalid protected credential store")
    result = {}
    for device_id, device in record["devices"].items():
        if not isinstance(device, dict) or "token" in device:
            raise ValueError("Plaintext credential in protected store")
        secret = json.loads(protect_key(base64.b64decode(device["protectedToken"], validate=True), decrypt=True))
        if not isinstance(secret, dict) or secret.get("deviceId") != device_id or not isinstance(secret.get("token"), str) or not secret["token"]:
            raise ValueError("Credential identity mismatch")
        result[device_id] = {key: value for key, value in device.items() if key != "protectedToken"}
        result[device_id]["token"] = secret["token"]
    return result


def read_legacy(path):
    record = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(record, dict) or record.get("version", 1) != 1 or not isinstance(record.get("devices"), dict):
        raise ValueError("Invalid legacy credential store")
    devices = record["devices"]
    if any(not isinstance(device, dict) or not isinstance(device.get("token"), str) or not device["token"]
           for device in devices.values()):
        raise ValueError("Invalid legacy credential")
    return devices
