import argparse
import ctypes
from ctypes import wintypes
import json
import hashlib
import hmac
import ipaddress
import platform
import secrets
import socket
import socketserver
import sys
import subprocess
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from pairing_limits import PairingLimits
import credential_store
from input_focus import MONITOR as INPUT_FOCUS


HOST = "0.0.0.0"
PORT = 8765
DEFAULT_PAIRING_CODE = "111111"
SERVER_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = SERVER_ROOT / "data"
LEGACY_TRUSTED_DEVICES_PATH = DATA_DIR / "trusted-devices.json"
TRUSTED_DEVICES_PATH = credential_store.default_path()
EVENT_LOG_PATH = DATA_DIR / "events.log"
SETTINGS_PATH = DATA_DIR / "settings.json"
DISCOVERY_MESSAGE_TYPE = "phone_pc_remote_discovery"
DISCOVERY_RESPONSE_TYPE = "discovery_response"
FILE_WRITE_LOCK = threading.Lock()
ACTIVITY_SAVE_INTERVAL_SECONDS = 30
IPV6_ENDPOINTS: set[str] = set()
IPV6_ENDPOINTS_LOCK = threading.Lock()
PAIRING_LIMITS = PairingLimits()
QR_ENROLLMENT = None


def confirm_account_link(account_email: str, device_name: str) -> bool:
    """Require a local desktop user decision before trusting a new phone."""
    try:
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)
        accepted = messagebox.askyesno(
            "Qareeb Control",
            f"Link this computer to:\n{account_email}\n\nPhone: {device_name}?",
            parent=root,
        )
        root.destroy()
        return bool(accepted)
    except Exception as exc:
        log_event("pairing_confirmation_error", reason=str(exc))
        return False


def advertised_local_ipv6() -> list[str]:
    with IPV6_ENDPOINTS_LOCK:
        return sorted(IPV6_ENDPOINTS)

MAX_MOUSE_DELTA = 200
MAX_SCROLL_AMOUNT = 10
MAX_TEXT_LENGTH = 160
MAX_KEY_REPEAT = 5
MAX_REQUEST_BYTES = 16 * 1024
WHEEL_DELTA = 120
SHUTDOWN_CONFIRMATION = "SHUTDOWN"
DEFAULT_SHUTDOWN_DELAY_SECONDS = 30
MIN_SHUTDOWN_DELAY_SECONDS = 10
MAX_SHUTDOWN_DELAY_SECONDS = 300

DEFAULT_SETTINGS = {
    "mouseSensitivity": 1.0,
    "scrollSensitivity": 1.0,
    "maxTextLength": 160,
}

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
INPUT_KEYBOARD = 1

BUTTON_FLAGS = {
    "left": (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    "right": (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
    "middle": (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
}

MODIFIER_KEYS = {
    "ctrl": 0x11,
    "control": 0x11,
    "shift": 0x10,
    "alt": 0x12,
    "win": 0x5B,
    "windows": 0x5B,
}

NAMED_KEYS = {
    "enter": 0x0D,
    "escape": 0x1B,
    "esc": 0x1B,
    "backspace": 0x08,
    "tab": 0x09,
    "space": 0x20,
    "delete": 0x2E,
    "del": 0x2E,
    "insert": 0x2D,
    "home": 0x24,
    "end": 0x23,
    "page_up": 0x21,
    "page_down": 0x22,
    "up": 0x26,
    "down": 0x28,
    "left": 0x25,
    "right": 0x27,
}

for function_index in range(1, 13):
    NAMED_KEYS[f"f{function_index}"] = 0x70 + function_index - 1

MEDIA_KEYS = {
    "volume_mute": 0xAD,
    "mute": 0xAD,
    "volume_down": 0xAE,
    "volume_up": 0xAF,
    "next": 0xB0,
    "previous": 0xB1,
    "prev": 0xB1,
    "stop": 0xB2,
    "play_pause": 0xB3,
    "play": 0xB3,
    "pause": 0xB3,
}

SHORTCUTS = {
    "copy": (["ctrl"], "c"),
    "paste": (["ctrl"], "v"),
    "cut": (["ctrl"], "x"),
    "select_all": (["ctrl"], "a"),
    "undo": (["ctrl"], "z"),
    "redo": (["ctrl"], "y"),
    "alt_tab": (["alt"], "tab"),
    "show_desktop": (["win"], "d"),
    "presentation_next": ([], "right"),
    "presentation_previous": ([], "left"),
    "presentation_prev": ([], "left"),
}

ULONG_PTR = ctypes.c_ulonglong if ctypes.sizeof(ctypes.c_void_p) == 8 else ctypes.c_ulong


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wintypes.LONG), ("dy", wintypes.LONG),
        ("mouseData", wintypes.DWORD), ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]


class INPUTUNION(ctypes.Union):
    # SendInput requires the complete Win32 union, even for keyboard-only events.
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wintypes.DWORD), ("u", INPUTUNION)]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def ensure_data_dir() -> None:
    DATA_DIR.mkdir(parents=True, exist_ok=True)


def get_computer_name() -> str:
    return platform.node() or socket.gethostname() or "Unknown-PC"


def get_local_ip() -> str:
    hostname = socket.gethostname()
    try:
        addresses = socket.gethostbyname_ex(hostname)[2]
    except OSError:
        addresses = []

    for address in addresses:
        if not address.startswith("127."):
            return address
    return "127.0.0.1"


def normalize_pairing_code(value: Any) -> str:
    pairing_code = str(value or "").strip().upper()
    if not pairing_code:
        return DEFAULT_PAIRING_CODE
    if not pairing_code.isalnum():
        raise argparse.ArgumentTypeError("Pairing code must contain letters and numbers only")
    if len(pairing_code) < 4 or len(pairing_code) > 32:
        raise argparse.ArgumentTypeError("Pairing code must be between 4 and 32 characters")
    return pairing_code


def normalize_requested_token(value: Any) -> str:
    token = str(value or "").strip()
    if len(token) < 16 or len(token) > 128:
        return ""
    if not all(char.isalnum() or char in "-_." for char in token):
        return ""
    return token


def load_json_file(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return default


def save_json_file(path: Path, payload: Any) -> None:
    with FILE_WRITE_LOCK:
        ensure_data_dir()
        temp_path = path.with_suffix(path.suffix + ".tmp")
        temp_path.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        temp_path.replace(path)


def log_event(event_type: str, **fields: Any) -> None:
    ensure_data_dir()
    safe_fields = {key: value for key, value in fields.items() if key not in ("token", "text")}
    event = {"time": utc_now(), "type": event_type, **safe_fields}
    with EVENT_LOG_PATH.open("a", encoding="utf-8") as log_file:
        log_file.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")


def bounded_int(value: Any, minimum: int, maximum: int, reason: str) -> int:
    if isinstance(value, bool):
        raise ValueError(reason)
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise ValueError(reason) from None
    return max(minimum, min(maximum, number))


def bounded_float(value: Any, minimum: float, maximum: float, reason: str) -> float:
    if isinstance(value, bool):
        raise ValueError(reason)
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise ValueError(reason) from None
    return max(minimum, min(maximum, number))


def normalize_name(value: Any) -> str:
    return str(value or "").strip().lower().replace("-", "_").replace(" ", "_")


def key_to_vk(key: Any) -> int | None:
    normalized = normalize_name(key)
    if normalized in NAMED_KEYS:
        return NAMED_KEYS[normalized]
    if len(normalized) == 1 and normalized.isascii():
        char = normalized.upper()
        if "A" <= char <= "Z" or "0" <= char <= "9":
            return ord(char)
    return None


def modifiers_to_vk(modifiers: Any) -> list[int]:
    if modifiers in (None, ""):
        return []
    if isinstance(modifiers, str):
        raw_modifiers = [item for item in modifiers.split("+") if item]
    elif isinstance(modifiers, list):
        raw_modifiers = modifiers
    else:
        raise ValueError("invalid_modifiers")

    resolved: list[int] = []
    for modifier in raw_modifiers:
        normalized = normalize_name(modifier)
        vk = MODIFIER_KEYS.get(normalized)
        if vk is None:
            raise ValueError("unsupported_modifier")
        if vk not in resolved:
            resolved.append(vk)
    return resolved


class MouseController:
    def __init__(self) -> None:
        self.dry_run = False
        self.lock = threading.Lock()

    def is_available(self) -> bool:
        return self.dry_run or platform.system() == "Windows"

    def _user32(self) -> Any:
        if self.dry_run:
            return None
        if platform.system() != "Windows":
            raise RuntimeError("mouse_control_unavailable")
        return ctypes.windll.user32

    def move(self, dx: int, dy: int) -> None:
        user32 = self._user32()
        if self.dry_run:
            return
        with self.lock:
            user32.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0)

    def click(self, button: str, count: int) -> None:
        down_flag, up_flag = BUTTON_FLAGS[button]
        user32 = self._user32()
        if self.dry_run:
            return
        with self.lock:
            for _ in range(count):
                user32.mouse_event(down_flag, 0, 0, 0, 0)
                user32.mouse_event(up_flag, 0, 0, 0, 0)

    def scroll(self, amount: int) -> None:
        user32 = self._user32()
        if self.dry_run:
            return
        with self.lock:
            user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, amount * WHEEL_DELTA, 0)


class KeyboardController:
    def __init__(self) -> None:
        self.dry_run = False
        self.lock = threading.RLock()

    def is_available(self) -> bool:
        return self.dry_run or platform.system() == "Windows"

    def _user32(self) -> Any:
        if self.dry_run:
            return None
        if platform.system() != "Windows":
            raise RuntimeError("keyboard_control_unavailable")
        return ctypes.windll.user32

    def _key_event(self, user32: Any, vk: int, key_up: bool = False) -> None:
        user32.keybd_event(vk, 0, KEYEVENTF_KEYUP if key_up else 0, 0)

    def press_key(self, vk: int, modifiers: list[int] | None = None, count: int = 1) -> None:
        if self.dry_run:
            return
        user32 = self._user32()
        modifiers = modifiers or []
        with self.lock:
            for modifier_vk in modifiers:
                self._key_event(user32, modifier_vk)
            try:
                for _ in range(count):
                    self._key_event(user32, vk)
                    self._key_event(user32, vk, key_up=True)
            finally:
                for modifier_vk in reversed(modifiers):
                    self._key_event(user32, modifier_vk, key_up=True)

    def _send_unicode_character(self, user32: Any, char: str) -> None:
        code = ord(char)
        if code < 32:
            raise RuntimeError("unsupported_text_character")
        user32.SendInput.argtypes = [ctypes.c_uint, ctypes.POINTER(INPUT), ctypes.c_int]
        user32.SendInput.restype = ctypes.c_uint
        encoded = char.encode("utf-16-le")
        events = []
        for offset in range(0, len(encoded), 2):
            unit = int.from_bytes(encoded[offset:offset + 2], "little")
            events.extend([
                INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(0, unit, KEYEVENTF_UNICODE, 0, 0)),
                INPUT(type=INPUT_KEYBOARD, ki=KEYBDINPUT(0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, 0)),
            ])
        inputs = (INPUT * len(events))(*events)
        sent = user32.SendInput(len(events), inputs, ctypes.sizeof(INPUT))
        if sent != len(events):
            raise RuntimeError("text_input_failed")

    def _press_character_key(self, user32: Any, char: str) -> bool:
        user32.VkKeyScanW.argtypes = [ctypes.c_wchar]
        user32.VkKeyScanW.restype = ctypes.c_short
        mapped = user32.VkKeyScanW(char)
        if mapped in (-1, 0xFFFF):
            return False
        vk = mapped & 0xFF
        shift_state = (mapped >> 8) & 0xFF
        modifiers = []
        if shift_state & 1:
            modifiers.append(MODIFIER_KEYS["shift"])
        if shift_state & 2:
            modifiers.append(MODIFIER_KEYS["ctrl"])
        if shift_state & 4:
            modifiers.append(MODIFIER_KEYS["alt"])
        for modifier_vk in modifiers:
            self._key_event(user32, modifier_vk)
        try:
            self._key_event(user32, vk)
            self._key_event(user32, vk, key_up=True)
        finally:
            for modifier_vk in reversed(modifiers):
                self._key_event(user32, modifier_vk, key_up=True)
        return True

    def type_text(self, text: str) -> None:
        if self.dry_run:
            return
        user32 = self._user32()
        with self.lock:
            for char in text:
                if char == "\n":
                    self.press_key(NAMED_KEYS["enter"])
                    continue
                if char == "\t":
                    self.press_key(NAMED_KEYS["tab"])
                    continue
                if ord(char) < 128 and self._press_character_key(user32, char):
                    continue
                self._send_unicode_character(user32, char)


MOUSE = MouseController()
KEYBOARD = KeyboardController()


@dataclass
class ServerState:
    pairing_code: str = field(default_factory=lambda: DEFAULT_PAIRING_CODE)
    configured_pairing_code: str = field(default_factory=lambda: DEFAULT_PAIRING_CODE)
    trusted_devices: dict[str, dict[str, Any]] = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)
    save_lock: threading.Lock = field(default_factory=threading.Lock)
    last_saved_at: float = field(default_factory=time.monotonic)
    computer_name: str = field(default_factory=get_computer_name)
    local_ip: str = field(default_factory=get_local_ip)
    started_at: str = field(default_factory=utc_now)
    control_enabled: bool = True
    settings: dict[str, Any] = field(default_factory=lambda: dict(DEFAULT_SETTINGS))

    def load(self) -> None:
        ensure_data_dir()
        migrate = False
        if TRUSTED_DEVICES_PATH.exists():
            devices = credential_store.decode_devices(json.loads(TRUSTED_DEVICES_PATH.read_text(encoding="utf-8")))
        elif LEGACY_TRUSTED_DEVICES_PATH.exists():
            devices = credential_store.read_legacy(LEGACY_TRUSTED_DEVICES_PATH)
            migrate = True
        else:
            devices = {}
        settings = load_json_file(SETTINGS_PATH, DEFAULT_SETTINGS)
        if not isinstance(settings, dict):
            settings = DEFAULT_SETTINGS
        with self.lock:
            self.trusted_devices = devices
            self.settings = self.normalized_settings(settings)
        if migrate:
            self.save()
        if TRUSTED_DEVICES_PATH.exists() and LEGACY_TRUSTED_DEVICES_PATH.exists():
            # Only remove the old plaintext store after the encrypted copy verifies.
            credential_store.decode_devices(json.loads(TRUSTED_DEVICES_PATH.read_text(encoding="utf-8")))
            LEGACY_TRUSTED_DEVICES_PATH.unlink()

    def save(self, activity_only: bool = False) -> None:
        # Serialize snapshots as well as writes so an older snapshot cannot win.
        with self.save_lock:
            if activity_only and time.monotonic() - self.last_saved_at < ACTIVITY_SAVE_INTERVAL_SECONDS:
                return
            with self.lock:
                devices = {key: dict(value) for key, value in self.trusted_devices.items()}
            credential_store.ensure_private_directory(TRUSTED_DEVICES_PATH.parent)
            save_json_file(TRUSTED_DEVICES_PATH, credential_store.encode_devices(devices))
            self.last_saved_at = time.monotonic()

    def normalized_settings(self, settings: dict[str, Any]) -> dict[str, Any]:
        normalized = dict(DEFAULT_SETTINGS)
        try:
            normalized["mouseSensitivity"] = bounded_float(settings.get("mouseSensitivity", 1.0), 0.2, 3.0, "invalid_mouse_sensitivity")
            normalized["scrollSensitivity"] = bounded_float(settings.get("scrollSensitivity", 1.0), 0.2, 3.0, "invalid_scroll_sensitivity")
            normalized["maxTextLength"] = bounded_int(settings.get("maxTextLength", MAX_TEXT_LENGTH), 20, 500, "invalid_max_text_length")
        except ValueError:
            return dict(DEFAULT_SETTINGS)
        return normalized

    def save_settings(self) -> None:
        with self.lock:
            settings = dict(self.settings)
        save_json_file(SETTINGS_PATH, settings)

    def update_settings(self, updates: dict[str, Any]) -> dict[str, Any]:
        with self.lock:
            merged = dict(self.settings)
            merged.update({key: value for key, value in updates.items() if key in DEFAULT_SETTINGS})
            self.settings = self.normalized_settings(merged)
            settings = dict(self.settings)
        save_json_file(SETTINGS_PATH, settings)
        return settings

    def settings_payload(self) -> dict[str, Any]:
        with self.lock:
            return dict(self.settings)

    def mouse_sensitivity(self) -> float:
        with self.lock:
            return float(self.settings.get("mouseSensitivity", 1.0))

    def scroll_sensitivity(self) -> float:
        with self.lock:
            return float(self.settings.get("scrollSensitivity", 1.0))

    def max_text_length(self) -> int:
        with self.lock:
            return int(self.settings.get("maxTextLength", MAX_TEXT_LENGTH))

    def trust_device(self, device_id: str, device_name: str, requested_token: str = "") -> str:
        token = requested_token or secrets.token_urlsafe(24)
        now = utc_now()
        with self.lock:
            self.trusted_devices[device_id] = {
                "deviceName": device_name,
                "token": token,
                "trustedAt": now,
                "lastSeenAt": now,
            }
        self.save()
        log_event("device_paired", deviceId=device_id, deviceName=device_name)
        return token

    def is_trusted(self, device_id: str, token: str) -> bool:
        with self.lock:
            device = self.trusted_devices.get(device_id)
            if not device:
                return False
            trusted_token = str(device.get("token", ""))
            is_match = bool(trusted_token and secrets.compare_digest(trusted_token, token))
            if is_match:
                device["lastSeenAt"] = utc_now()
        if is_match:
            self.save(activity_only=True)
        return is_match

    def disable_control(self) -> None:
        with self.lock:
            self.control_enabled = False

    def enable_control(self) -> None:
        with self.lock:
            self.control_enabled = True

    def set_pairing_code(self, pairing_code: str) -> None:
        with self.lock:
            self.configured_pairing_code = pairing_code
            self.pairing_code = pairing_code

    def rotate_pairing_code(self) -> str:
        with self.lock:
            self.pairing_code = self.configured_pairing_code
            return self.pairing_code

    def list_trusted_devices(self) -> list[dict[str, Any]]:
        with self.lock:
            return [
                {
                    "deviceId": device_id,
                    "deviceName": str(device.get("deviceName", "Unknown Device")),
                    "trustedAt": str(device.get("trustedAt", "")),
                    "lastSeenAt": str(device.get("lastSeenAt", "")),
                }
                for device_id, device in sorted(self.trusted_devices.items())
            ]

    def forget_device(self, device_id: str) -> bool:
        with self.lock:
            existed = device_id in self.trusted_devices
            if existed:
                del self.trusted_devices[device_id]
        if existed:
            self.save()
        return existed

    def status_payload(self) -> dict[str, Any]:
        with self.lock:
            trusted_count = len(self.trusted_devices)
            control_enabled = self.control_enabled
        return {
            "type": "status",
            "computerName": self.computer_name,
            "host": HOST,
            "port": PORT,
            "localIp": self.local_ip,
            "localIpv6": advertised_local_ipv6(),
            "pairingRequired": True,
            "persistentConnection": True,
            "trustedDeviceCount": trusted_count,
            "controlEnabled": control_enabled,
            "mouseControlAvailable": MOUSE.is_available(),
            "keyboardControlAvailable": KEYBOARD.is_available(),
            "controlsDryRun": MOUSE.dry_run and KEYBOARD.dry_run,
            "powerControlAvailable": platform.system() == "Windows" or (MOUSE.dry_run and KEYBOARD.dry_run),
            "shutdownDelaySeconds": DEFAULT_SHUTDOWN_DELAY_SECONDS,
            "maxTextLength": self.max_text_length(),
            "settings": self.settings_payload(),
            "startedAt": self.started_at,
            "serverTime": utc_now(),
        }


STATE = ServerState()


class RemoteControlHandler(socketserver.StreamRequestHandler):
    disable_nagle_algorithm = True
    timeout = 60

    def setup(self) -> None:
        context = getattr(self.server, "tls_context", None)
        if context is not None:
            self.request.settimeout(5)
            self.request = context.wrap_socket(self.request, server_side=True)
        super().setup()

    def finish(self) -> None:
        try:
            super().finish()
        finally:
            if getattr(self.server, "tls_context", None) is not None:
                self.request.close()

    def send_json(self, payload: dict[str, Any]) -> None:
        request_id = getattr(self, "request_id", "")
        if request_id:
            payload = {**payload, "requestId": request_id}
        data = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
        self.wfile.write(data)
        self.wfile.flush()
        if getattr(self, "message_type", "") == "hello":
            log_event("handshake_response_sent", peer=f"{self.client_address[0]}:{self.client_address[1]}",
                      responseType=payload.get("type"), requestId=request_id,
                      elapsedMs=round((time.monotonic() - self.request_started_at) * 1000, 2))

    def handle(self) -> None:
        peer = f"{self.client_address[0]}:{self.client_address[1]}"
        print(f"[{utc_now()}] Connected: {peer}")
        log_event("client_connected", peer=peer)

        received_message = False
        try:
            while True:
                raw_line = self.rfile.readline(MAX_REQUEST_BYTES + 1)
                if not raw_line:
                    break
                received_message = True
                if len(raw_line) > MAX_REQUEST_BYTES:
                    self.send_json({"type": "error", "reason": "request_too_large"})
                    break
                if not raw_line.endswith(b"\n"):
                    self.send_json({"type": "error", "reason": "incomplete_request"})
                    break
                try:
                    message = json.loads(raw_line.decode("utf-8"))
                except (json.JSONDecodeError, UnicodeDecodeError):
                    self.send_json({"type": "error", "reason": "invalid_json"})
                    log_event("invalid_json", peer=peer)
                    break
                if not isinstance(message, dict):
                    self.send_json({"type": "error", "reason": "invalid_message"})
                    break
                message_type = str(message.get("type", "")).strip()
                self.message_type = message_type
                self.request_id = str(message.get("requestId", ""))[:80]
                self.request_started_at = time.monotonic()
                if message_type not in ("ping", "mouse_move", "scroll", "key_press", "type_text"):
                    log_event("message_received", peer=peer, messageType=message_type,
                              keepAlive=message.get("keepAlive") is True,
                              clientVersion=str(message.get("clientVersion", ""))[:80],
                              requestId=self.request_id)
                self.handle_message(message)
                # Old clients retain one-request-per-connection behavior.
                if message.get("keepAlive") is not True:
                    break
        except (ConnectionError, socket.timeout):
            pass
        except Exception as exc:
            log_event("server_error", peer=peer, error=str(exc))
            try:
                self.send_json({"type": "error", "reason": "server_error"})
            except OSError:
                pass

        if not received_message:
            log_event("empty_request", peer=peer)
        print(f"[{utc_now()}] Disconnected: {peer}")
        log_event("client_disconnected", peer=peer)

    def handle_message(self, message: dict[str, Any]) -> None:
        message_type = message.get("type")

        if message_type == "ping":
            self.send_json({"type": "pong", "time": utc_now(), "persistentConnection": True})
            return

        if message_type == "status":
            self.send_json(STATE.status_payload())
            return

        if message_type == "identify":
            self.send_json(discovery_response_payload(message))
            return

        if message_type == "input_focus":
            if self.require_trusted_device(message, "input_focus") and self.ensure_control_enabled():
                self.send_json({"type": "input_focus", **INPUT_FOCUS.snapshot()})
            return

        if message_type == "hello":
            self.handle_hello(message)
            return

        if message_type == "mouse_move":
            self.handle_mouse_move(message)
            return

        if message_type == "mouse_click":
            self.handle_mouse_click(message)
            return

        if message_type == "scroll":
            self.handle_scroll(message)
            return

        if message_type == "key_press":
            self.handle_key_press(message)
            return

        if message_type == "type_text":
            self.handle_type_text(message)
            return

        if message_type == "media_control":
            self.handle_media_control(message)
            return

        if message_type == "shortcut":
            self.handle_shortcut(message)
            return

        if message_type == "get_settings":
            self.handle_get_settings(message)
            return

        if message_type == "update_settings":
            self.handle_update_settings(message)
            return

        if message_type == "trusted_devices":
            self.handle_trusted_devices(message)
            return

        if message_type == "forget_device":
            self.handle_forget_device(message)
            return

        if message_type == "rotate_pairing_code":
            self.handle_rotate_pairing_code(message)
            return

        if message_type == "shutdown_computer":
            self.handle_shutdown_computer(message)
            return

        if message_type == "cancel_shutdown":
            self.handle_cancel_shutdown(message)
            return

        if message_type == "control_start":
            self.handle_control_start(message)
            return

        if message_type == "control_stop":
            self.handle_control_stop(message)
            return

        self.send_json({
            "type": "error",
            "reason": "unsupported_message",
            "messageType": message_type,
        })

    def handle_hello(self, message: dict[str, Any]) -> None:
        device_id = str(message.get("deviceId", "")).strip()
        device_name = str(message.get("deviceName", "Unknown Phone")).strip() or "Unknown Phone"
        pairing_code = str(message.get("pairingCode", "")).strip().upper()
        token = str(message.get("token", "")).strip()
        requested_token = normalize_requested_token(message.get("requestedToken", ""))
        account_email = str(message.get("accountEmail", "")).strip().lower()
        account_link_confirmed = bool(message.get("confirmAccountLink", False))

        if not device_id:
            self.send_json({"type": "error", "reason": "missing_device_id"})
            return

        if token and STATE.is_trusted(device_id, token):
            log_event("device_reconnected", deviceId=device_id, deviceName=device_name)
            self.send_json({
                "type": "hello_ok",
                "localIpv6": advertised_local_ipv6(),
                "trusted": True,
                "deviceId": device_id,
                "computerName": STATE.computer_name,
                "serverTime": utc_now(),
            })
            return

        retry_after = PAIRING_LIMITS.admit(self.client_address[0].split("%", 1)[0])
        if retry_after:
            # Preserve the existing client's unauthorized handling, adding detail.
            self.send_json({"type": "error", "reason": "unauthorized",
                            "errorCode": "pairing_rate_limited", "retryAfterSeconds": retry_after})
            return

        if QR_ENROLLMENT is not None and "@" not in account_email:
            log_event("pairing_confirmation_required", deviceId=device_id, accountEmail=account_email)
            self.send_json({
                "type": "pairing_confirmation_required",
                "deviceId": device_id,
                "accountEmail": account_email,
                "reason": "computer_confirmation_required",
            })
            return

        if QR_ENROLLMENT is not None and not account_link_confirmed:
            if not confirm_account_link(account_email, device_name):
                log_event("pairing_rejected_by_user", deviceId=device_id, accountEmail=account_email)
                self.send_json({"type": "error", "reason": "computer_confirmation_denied"})
                return
            account_link_confirmed = True

        accepted_code = (QR_ENROLLMENT.accept(pairing_code, device_id, requested_token)
                         if QR_ENROLLMENT is not None else
                         hmac.compare_digest(pairing_code.encode("utf-8"), STATE.pairing_code.encode("utf-8")))
        if accepted_code:
            new_token = STATE.trust_device(device_id, device_name, requested_token)
            self.send_json({
                "type": "paired",
                "localIpv6": advertised_local_ipv6(),
                "deviceId": device_id,
                "token": new_token,
                "computerName": STATE.computer_name,
                "serverTime": utc_now(),
            })
            return

        log_event("unauthorized_hello", deviceId=device_id, deviceName=device_name)
        self.send_json({"type": "error", "reason": "unauthorized"})

    def require_trusted_device(self, message: dict[str, Any], action: str) -> str | None:
        device_id = str(message.get("deviceId", "")).strip()
        token = str(message.get("token", "")).strip()

        if not device_id:
            self.send_json({"type": "error", "reason": "missing_device_id"})
            return None

        if not token:
            log_event("unauthorized_control", deviceId=device_id, action=action, reason="missing_token")
            self.send_json({"type": "error", "reason": "missing_token"})
            return None

        if not STATE.is_trusted(device_id, token):
            log_event("unauthorized_control", deviceId=device_id, action=action, reason="unauthorized")
            self.send_json({"type": "error", "reason": "unauthorized"})
            return None

        return device_id

    def ensure_control_enabled(self) -> bool:
        if STATE.control_enabled:
            return True
        self.send_json({"type": "error", "reason": "control_disabled"})
        return False

    def handle_mouse_move(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "mouse_move")
        if not device_id or not self.ensure_control_enabled():
            return

        try:
            raw_dx = bounded_int(message.get("dx", 0), -MAX_MOUSE_DELTA, MAX_MOUSE_DELTA, "invalid_mouse_delta")
            raw_dy = bounded_int(message.get("dy", 0), -MAX_MOUSE_DELTA, MAX_MOUSE_DELTA, "invalid_mouse_delta")
            sensitivity = STATE.mouse_sensitivity()
            dx = bounded_int(round(raw_dx * sensitivity), -MAX_MOUSE_DELTA, MAX_MOUSE_DELTA, "invalid_mouse_delta")
            dy = bounded_int(round(raw_dy * sensitivity), -MAX_MOUSE_DELTA, MAX_MOUSE_DELTA, "invalid_mouse_delta")
            MOUSE.move(dx, dy)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        except RuntimeError as exc:
            log_event("mouse_control_error", deviceId=device_id, action="mouse_move", reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "mouse_ok", "action": "move", "dx": dx, "dy": dy, "dryRun": MOUSE.dry_run})

    def handle_mouse_click(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "mouse_click")
        if not device_id or not self.ensure_control_enabled():
            return

        button = normalize_name(message.get("button", "left"))
        if button not in BUTTON_FLAGS:
            self.send_json({"type": "error", "reason": "unsupported_mouse_button"})
            return

        try:
            count = bounded_int(message.get("count", 1), 1, 2, "invalid_click_count")
            MOUSE.click(button, count)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        except RuntimeError as exc:
            log_event("mouse_control_error", deviceId=device_id, action="mouse_click", reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "mouse_ok", "action": "click", "button": button, "count": count, "dryRun": MOUSE.dry_run})

    def handle_scroll(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "scroll")
        if not device_id or not self.ensure_control_enabled():
            return

        try:
            raw_amount = bounded_int(message.get("amount", message.get("dy", 0)), -MAX_SCROLL_AMOUNT, MAX_SCROLL_AMOUNT, "invalid_scroll_amount")
            amount = bounded_int(round(raw_amount * STATE.scroll_sensitivity()), -MAX_SCROLL_AMOUNT, MAX_SCROLL_AMOUNT, "invalid_scroll_amount")
            MOUSE.scroll(amount)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        except RuntimeError as exc:
            log_event("mouse_control_error", deviceId=device_id, action="scroll", reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "mouse_ok", "action": "scroll", "amount": amount, "dryRun": MOUSE.dry_run})

    def handle_key_press(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "key_press")
        if not device_id or not self.ensure_control_enabled():
            return

        key = normalize_name(message.get("key", ""))
        vk = key_to_vk(key)
        if vk is None:
            self.send_json({"type": "error", "reason": "unsupported_key"})
            return

        try:
            modifiers = modifiers_to_vk(message.get("modifiers", []))
            count = bounded_int(message.get("count", 1), 1, MAX_KEY_REPEAT, "invalid_key_count")
            KEYBOARD.press_key(vk, modifiers, count)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        except RuntimeError as exc:
            log_event("keyboard_control_error", deviceId=device_id, action="key_press", key=key, reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "keyboard_ok", "action": "key_press", "key": key, "count": count, "dryRun": KEYBOARD.dry_run})

    def handle_type_text(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "type_text")
        if not device_id or not self.ensure_control_enabled():
            return

        text = message.get("text", "")
        if not isinstance(text, str):
            self.send_json({"type": "error", "reason": "invalid_text"})
            return
        max_text_length = STATE.max_text_length()
        if len(text) > max_text_length:
            self.send_json({"type": "error", "reason": "text_too_long", "maxLength": max_text_length})
            return
        if any(ord(char) < 32 and char not in "\n\t" for char in text):
            self.send_json({"type": "error", "reason": "unsupported_text_character"})
            return

        try:
            KEYBOARD.type_text(text)
        except RuntimeError as exc:
            log_event("keyboard_control_error", deviceId=device_id, action="type_text", length=len(text), reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "keyboard_ok", "action": "type_text", "length": len(text), "dryRun": KEYBOARD.dry_run})

    def handle_media_control(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "media_control")
        if not device_id or not self.ensure_control_enabled():
            return

        control = normalize_name(message.get("control", message.get("key", "")))
        vk = MEDIA_KEYS.get(control)
        if vk is None:
            self.send_json({"type": "error", "reason": "unsupported_media_control"})
            return

        try:
            KEYBOARD.press_key(vk)
        except RuntimeError as exc:
            log_event("keyboard_control_error", deviceId=device_id, action="media_control", control=control, reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "keyboard_ok", "action": "media_control", "control": control, "dryRun": KEYBOARD.dry_run})

    def handle_shortcut(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "shortcut")
        if not device_id or not self.ensure_control_enabled():
            return

        shortcut = normalize_name(message.get("shortcut", message.get("name", "")))
        shortcut_definition = SHORTCUTS.get(shortcut)
        if shortcut_definition is None:
            self.send_json({"type": "error", "reason": "unsupported_shortcut"})
            return

        modifier_names, key = shortcut_definition
        vk = key_to_vk(key)
        if vk is None:
            self.send_json({"type": "error", "reason": "unsupported_shortcut"})
            return

        try:
            modifiers = modifiers_to_vk(modifier_names)
            KEYBOARD.press_key(vk, modifiers)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        except RuntimeError as exc:
            log_event("keyboard_control_error", deviceId=device_id, action="shortcut", shortcut=shortcut, reason=str(exc))
            self.send_json({"type": "error", "reason": str(exc)})
            return

        self.send_json({"type": "keyboard_ok", "action": "shortcut", "shortcut": shortcut, "dryRun": KEYBOARD.dry_run})

    def handle_get_settings(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "get_settings")
        if not device_id:
            return
        self.send_json({"type": "settings", "settings": STATE.settings_payload()})

    def handle_update_settings(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "update_settings")
        if not device_id:
            return
        try:
            updates = {
                "mouseSensitivity": message["mouseSensitivity"] if "mouseSensitivity" in message else STATE.settings_payload().get("mouseSensitivity", 1.0),
                "scrollSensitivity": message["scrollSensitivity"] if "scrollSensitivity" in message else STATE.settings_payload().get("scrollSensitivity", 1.0),
                "maxTextLength": message["maxTextLength"] if "maxTextLength" in message else STATE.settings_payload().get("maxTextLength", MAX_TEXT_LENGTH),
            }
            settings = STATE.update_settings(updates)
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return
        log_event("settings_updated", deviceId=device_id, settings=settings)
        self.send_json({"type": "settings_updated", "settings": settings})

    def handle_trusted_devices(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "trusted_devices")
        if not device_id:
            return
        self.send_json({
            "type": "trusted_devices",
            "devices": STATE.list_trusted_devices(),
        })

    def handle_forget_device(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "forget_device")
        if not device_id:
            return
        target_device_id = str(message.get("targetDeviceId", device_id)).strip()
        if not target_device_id:
            self.send_json({"type": "error", "reason": "missing_target_device_id"})
            return
        removed = STATE.forget_device(target_device_id)
        log_event("device_forgotten", deviceId=device_id, targetDeviceId=target_device_id, removed=removed)
        self.send_json({
            "type": "device_forgotten",
            "targetDeviceId": target_device_id,
            "removed": removed,
        })

    def handle_rotate_pairing_code(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "rotate_pairing_code")
        if not device_id:
            return
        new_code = STATE.rotate_pairing_code()
        log_event("pairing_code_rotated", deviceId=device_id)
        self.send_json({"type": "pairing_code_rotated", "pairingCode": new_code})

    def handle_control_start(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "control_start")
        if not device_id:
            return
        STATE.enable_control()
        log_event("control_started", deviceId=device_id)
        self.send_json({"type": "control_started", "controlEnabled": True})

    def handle_shutdown_computer(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "shutdown_computer")
        if not device_id or not self.ensure_control_enabled():
            return

        confirmation = str(message.get("confirm", "")).strip().upper()
        if confirmation != SHUTDOWN_CONFIRMATION:
            self.send_json({"type": "error", "reason": "shutdown_confirmation_required"})
            log_event("shutdown_rejected", deviceId=device_id, reason="missing_confirmation")
            return

        try:
            delay_seconds = bounded_int(
                message.get("delaySeconds", DEFAULT_SHUTDOWN_DELAY_SECONDS),
                MIN_SHUTDOWN_DELAY_SECONDS,
                MAX_SHUTDOWN_DELAY_SECONDS,
                "invalid_shutdown_delay",
            )
        except ValueError as exc:
            self.send_json({"type": "error", "reason": str(exc)})
            return

        if platform.system() != "Windows" and not (MOUSE.dry_run and KEYBOARD.dry_run):
            self.send_json({"type": "error", "reason": "shutdown_unavailable"})
            log_event("shutdown_rejected", deviceId=device_id, reason="unsupported_platform")
            return

        if MOUSE.dry_run and KEYBOARD.dry_run:
            log_event("shutdown_scheduled_dry_run", deviceId=device_id, delaySeconds=delay_seconds)
            self.send_json({"type": "shutdown_scheduled", "delaySeconds": delay_seconds, "dryRun": True})
            return

        try:
            subprocess.Popen([
                "shutdown",
                "/s",
                "/t",
                str(delay_seconds),
                "/c",
                "Qareeb Control requested shutdown",
            ])
        except OSError as exc:
            self.send_json({"type": "error", "reason": "shutdown_failed"})
            log_event("shutdown_failed", deviceId=device_id, error=str(exc))
            return

        log_event("shutdown_scheduled", deviceId=device_id, delaySeconds=delay_seconds)
        self.send_json({"type": "shutdown_scheduled", "delaySeconds": delay_seconds, "dryRun": False})

    def handle_cancel_shutdown(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "cancel_shutdown")
        if not device_id:
            return

        if MOUSE.dry_run and KEYBOARD.dry_run:
            log_event("shutdown_cancelled_dry_run", deviceId=device_id)
            self.send_json({"type": "shutdown_cancelled", "dryRun": True})
            return

        if platform.system() != "Windows":
            self.send_json({"type": "error", "reason": "shutdown_cancel_unavailable"})
            return

        try:
            subprocess.Popen(["shutdown", "/a"])
        except OSError as exc:
            self.send_json({"type": "error", "reason": "shutdown_cancel_failed"})
            log_event("shutdown_cancel_failed", deviceId=device_id, error=str(exc))
            return

        log_event("shutdown_cancelled", deviceId=device_id)
        self.send_json({"type": "shutdown_cancelled", "dryRun": False})

    def handle_control_stop(self, message: dict[str, Any]) -> None:
        device_id = self.require_trusted_device(message, "control_stop")
        if not device_id:
            return
        STATE.disable_control()
        log_event("control_stopped", deviceId=device_id)
        self.send_json({"type": "control_stopped", "controlEnabled": False})


def discovery_response_payload(request: dict[str, Any] | None = None) -> dict[str, Any]:
    payload = STATE.status_payload()
    payload.update({
        "type": DISCOVERY_RESPONSE_TYPE,
        "app": "Qareeb Control",
        "protocol": "phone_pc_remote",
        "discoveryVersion": 1,
    })
    if request:
        nonce = request.get("nonce", "")
        device_id = request.get("deviceId", "")
        if isinstance(nonce, str) and 32 <= len(nonce) <= 128 and isinstance(device_id, str):
            with STATE.lock:
                token = STATE.trusted_devices.get(device_id, {}).get("token", "")
            if token:
                message = f"qareeb-discovery-v1\n{nonce}\n{PORT}".encode("utf-8")
                payload["discoveryProof"] = hmac.new(token.encode("utf-8"), message, hashlib.sha256).hexdigest()
    return payload


def udp_discovery_loop(stop_event: threading.Event) -> None:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        bind_host = "" if HOST in ("0.0.0.0", "") else HOST
        sock.bind((bind_host, PORT))
        sock.settimeout(1.0)
        log_event("discovery_started", host=HOST, port=PORT)
        while not stop_event.is_set():
            try:
                data, address = sock.recvfrom(2048)
            except socket.timeout:
                continue
            except OSError:
                break

            try:
                message = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                if data.strip() == b"PHONE_PC_REMOTE_DISCOVERY":
                    message = {"type": DISCOVERY_MESSAGE_TYPE}
                else:
                    continue

            if not isinstance(message, dict) or message.get("type") != DISCOVERY_MESSAGE_TYPE:
                continue

            response = json.dumps(discovery_response_payload(message), separators=(",", ":")).encode("utf-8")
            sock.sendto(response, address)
            log_event("discovery_response_sent", peer=f"{address[0]}:{address[1]}")
    except OSError as exc:
        log_event("discovery_error", host=HOST, port=PORT, error=str(exc))
        print(f"Discovery disabled: {exc}")
    finally:
        sock.close()
        log_event("discovery_stopped", host=HOST, port=PORT)


def start_discovery_responder(stop_event: threading.Event) -> threading.Thread:
    thread = threading.Thread(target=udp_discovery_loop, args=(stop_event,), daemon=True)
    thread.start()
    return thread
class ConnectionAdmission:
    """A shared worker budget across IPv4 and all IPv6 listeners."""

    def __init__(self, maximum=64, per_peer=16):
        self.maximum = maximum
        self.per_peer = per_peer
        self.lock = threading.Lock()
        self.peers = {}
        self.active = 0

    def acquire(self, peer):
        with self.lock:
            count = self.peers.get(peer, 0)
            if self.active >= self.maximum or count >= self.per_peer:
                return False
            self.peers[peer] = count + 1
            self.active += 1
            return True

    def release(self, peer):
        with self.lock:
            count = self.peers[peer]
            if count == 1:
                del self.peers[peer]
            else:
                self.peers[peer] = count - 1
            self.active -= 1


CONNECTION_ADMISSION = ConnectionAdmission()


class ThreadedTcpServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 128

    def __init__(self, *args, **kwargs):
        self.connection_admission = CONNECTION_ADMISSION
        super().__init__(*args, **kwargs)

    def process_request(self, request, client_address):
        peer = client_address[0]
        if not self.connection_admission.acquire(peer):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.connection_admission.release(peer)
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.connection_admission.release(client_address[0])


class LocalIpv6TcpServer(ThreadedTcpServer):
    address_family = socket.AF_INET6

    def server_bind(self) -> None:
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


class LocalIpv6Service:
    """Bind only scoped link-local addresses, never a wildcard/global IPv6 listener."""

    def __init__(self, port: int, tls_context=None):
        self.port = port
        self.tls_context = tls_context
        self.listeners: dict[tuple[str, int], tuple[LocalIpv6TcpServer, threading.Thread]] = {}
        self.stop_event = threading.Event()
        self.worker: threading.Thread | None = None
        self.retry_after: dict[tuple[str, int], float] = {}

    @staticmethod
    def addresses() -> set[tuple[str, int]]:
        results = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET6, socket.SOCK_STREAM)
        return {(item[4][0].split("%")[0], item[4][3]) for item in results
                if ipaddress.ip_address(item[4][0].split("%")[0]).is_link_local and item[4][3] > 0}

    def refresh(self) -> None:
        try:
            addresses = self.addresses()
        except OSError:
            return
        for endpoint in set(self.listeners) - addresses:
            listener, worker = self.listeners.pop(endpoint)
            listener.shutdown()
            listener.server_close()
            worker.join(2)
        for endpoint in addresses - set(self.listeners):
            if time.monotonic() < self.retry_after.get(endpoint, 0):
                continue
            try:
                host, scope = endpoint
                listener = LocalIpv6TcpServer((host, self.port, 0, scope), RemoteControlHandler)
                listener.tls_context = self.tls_context
                worker = threading.Thread(target=listener.serve_forever, daemon=True)
                worker.start()
                self.listeners[endpoint] = (listener, worker)
                log_event("local_ipv6_started", address=host, interface=scope, port=self.port)
            except OSError as exc:
                self.retry_after[endpoint] = time.monotonic() + 30
                log_event("local_ipv6_unavailable", address=endpoint[0], error=str(exc))
        with IPV6_ENDPOINTS_LOCK:
            IPV6_ENDPOINTS.clear()
            IPV6_ENDPOINTS.update(host for host, _ in self.listeners)

    def start(self) -> None:
        self.refresh()

        def monitor() -> None:
            while not self.stop_event.wait(5):
                self.refresh()

        self.worker = threading.Thread(target=monitor, daemon=True)
        self.worker.start()

    def stop(self) -> None:
        self.stop_event.set()
        if self.worker:
            self.worker.join()
        for listener, worker in self.listeners.values():
            listener.shutdown()
            listener.server_close()
            worker.join(2)
        self.listeners.clear()
        with IPV6_ENDPOINTS_LOCK:
            IPV6_ENDPOINTS.clear()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qareeb Control desktop server")
    parser.add_argument("--host", default=HOST, help="Host/IP to bind. Default: 0.0.0.0")
    parser.add_argument("--port", default=PORT, type=int, help="TCP port. Default: 8765")
    parser.add_argument("--pairing-code", default=DEFAULT_PAIRING_CODE, type=normalize_pairing_code, help="Pairing code shown on startup. Default: 111111")
    parser.add_argument("--controls-disabled", action="store_true", help="Start with mouse/keyboard control disabled")
    parser.add_argument("--dry-run-controls", action="store_true", help="Accept control commands without moving the mouse or typing")
    parser.add_argument("--no-discovery", action="store_true", help="Disable UDP discovery responses")
    parser.add_argument("--tls", action="store_true", help="Require pinned TLS for all TCP connections; no plaintext fallback")
    parser.add_argument("--require-existing-identity", action="store_true", help="Fail if the TLS identity is missing; never generate a replacement")
    parser.add_argument("--pairing-qr", action="store_true", help="Show a five-minute local QR ticket; requires --tls")
    parser.add_argument("--qr-host", help="Local IP address encoded in the pairing QR")
    return parser.parse_args()


def main() -> None:
    global HOST, PORT, QR_ENROLLMENT
    args = parse_args()
    if args.pairing_qr and not args.tls:
        raise SystemExit("--pairing-qr requires --tls")
    if args.require_existing_identity and not args.tls:
        raise SystemExit("--require-existing-identity requires --tls")
    tls_context = None
    if args.tls:
        from local_identity import LocalIdentity
        load_identity = LocalIdentity.load if args.require_existing_identity else LocalIdentity.load_or_create
        identity = load_identity(DATA_DIR / "local-identity.json")
        tls_context = identity.server_context()
        print(f"TLS SHA-256 fingerprint: {identity.fingerprint}")
    HOST = args.host
    PORT = args.port
    STATE.control_enabled = not args.controls_disabled
    MOUSE.dry_run = args.dry_run_controls
    KEYBOARD.dry_run = args.dry_run_controls

    STATE.load()
    STATE.set_pairing_code(args.pairing_code)
    log_event(
        "server_started",
        host=HOST,
        port=PORT,
        computerName=STATE.computer_name,
        controlEnabled=STATE.control_enabled,
        controlsDryRun=args.dry_run_controls,
    )

    print("Qareeb Control desktop server")
    print(f"Computer: {STATE.computer_name}")
    print(f"Local IP: {STATE.local_ip}")
    print(f"Listening on {HOST}:{PORT}")
    print("Pairing: temporary QR ticket only" if args.pairing_qr else f"Pairing code: {STATE.pairing_code}")
    print(f"Trusted devices: {len(STATE.trusted_devices)}")
    print(f"Control enabled: {STATE.control_enabled}")
    print(f"Mouse available: {MOUSE.is_available()}")
    print(f"Keyboard available: {KEYBOARD.is_available()}")
    print(f"Dry run controls: {args.dry_run_controls}")
    print(f"UDP discovery: {'disabled' if args.no_discovery else f'enabled on {PORT}'}")
    print(f"Data folder: {DATA_DIR}")
    print("Press Ctrl+C to stop.")

    discovery_stop = threading.Event()
    discovery_thread = None
    with ThreadedTcpServer((HOST, PORT), RemoteControlHandler) as server:
        server.tls_context = tls_context
        ipv6_service = LocalIpv6Service(PORT, tls_context) if HOST in ("0.0.0.0", "") and socket.has_ipv6 else None
        if ipv6_service:
            ipv6_service.start()
        qr_path = None
        if args.pairing_qr:
            from qr_pairing import Enrollment, write_qr
            enrollment = Enrollment()
            host = args.qr_host or next(iter(advertised_local_ipv6()), STATE.local_ip)
            qr_path = DATA_DIR / "diagnostics" / "pairing-qr.png"
            write_qr(enrollment.uri(host, PORT, identity.fingerprint), qr_path)
            QR_ENROLLMENT = enrollment
            subprocess.Popen([str(Path(sys.executable).with_name("pythonw.exe")),
                              str(Path(__file__).with_name("qr_pairing.py")), str(qr_path)])
        if not args.no_discovery:
            discovery_thread = start_discovery_responder(discovery_stop)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("\nStopping server.")
        finally:
            if ipv6_service:
                ipv6_service.stop()
            STATE.save()
            if qr_path is not None:
                qr_path.unlink(missing_ok=True)
            discovery_stop.set()
            if discovery_thread is not None:
                discovery_thread.join(timeout=2)
            log_event("server_stopped", host=HOST, port=PORT)


if __name__ == "__main__":
    main()


