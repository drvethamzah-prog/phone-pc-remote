import argparse
import json
import socket
import sys
from typing import Any


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8765


def send_message(host: str, port: int, payload: dict[str, Any]) -> dict[str, Any]:
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
    with socket.create_connection((host, port), timeout=5) as client:
        client.sendall(data)
        response = client.makefile("rb").readline()
    if not response:
        raise RuntimeError("Server closed the connection without a response")
    return json.loads(response.decode("utf-8"))


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qareeb Control test client")
    parser.add_argument(
        "command",
        choices=(
            "ping",
            "status",
            "hello",
            "mouse-move",
            "mouse-click",
            "scroll",
            "key-press",
            "type-text",
            "media-control",
            "shortcut",
            "get-settings",
            "update-settings",
            "trusted-devices",
            "forget-device",
            "rotate-pairing-code",
            "shutdown-computer",
            "cancel-shutdown",
            "control-start",
            "control-stop",
        ),
    )
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", default=DEFAULT_PORT, type=int)
    parser.add_argument("--device-id", default="test-device")
    parser.add_argument("--device-name", default="Test Client")
    parser.add_argument("--pairing-code", default="")
    parser.add_argument("--token", default="")
    parser.add_argument("--dx", default=0, type=int)
    parser.add_argument("--dy", default=0, type=int)
    parser.add_argument("--amount", default=0, type=int)
    parser.add_argument("--button", default="left", choices=("left", "right", "middle"))
    parser.add_argument("--count", default=1, type=int)
    parser.add_argument("--key", default="enter")
    parser.add_argument("--modifiers", default="")
    parser.add_argument("--text", default="")
    parser.add_argument("--control", default="play_pause")
    parser.add_argument("--shortcut", default="copy")
    parser.add_argument("--target-device-id", default="")
    parser.add_argument("--mouse-sensitivity", default=None, type=float)
    parser.add_argument("--scroll-sensitivity", default=None, type=float)
    parser.add_argument("--max-text-length", default=None, type=int)
    parser.add_argument("--shutdown-delay", default=30, type=int)
    parser.add_argument("--confirm-shutdown", action="store_true")
    return parser.parse_args()


def authenticated_payload(args: argparse.Namespace, message_type: str) -> dict[str, Any]:
    return {
        "type": message_type,
        "deviceId": args.device_id,
        "token": args.token,
    }


def build_payload(args: argparse.Namespace) -> dict[str, Any]:
    if args.command == "ping":
        return {"type": "ping"}
    if args.command == "status":
        return {"type": "status"}
    if args.command == "hello":
        return {
            "type": "hello",
            "deviceId": args.device_id,
            "deviceName": args.device_name,
            "pairingCode": args.pairing_code,
            "token": args.token,
        }
    if args.command == "mouse-move":
        payload = authenticated_payload(args, "mouse_move")
        payload.update({"dx": args.dx, "dy": args.dy})
        return payload
    if args.command == "mouse-click":
        payload = authenticated_payload(args, "mouse_click")
        payload.update({"button": args.button, "count": args.count})
        return payload
    if args.command == "scroll":
        payload = authenticated_payload(args, "scroll")
        payload.update({"amount": args.amount})
        return payload
    if args.command == "key-press":
        payload = authenticated_payload(args, "key_press")
        payload.update({"key": args.key, "modifiers": args.modifiers, "count": args.count})
        return payload
    if args.command == "type-text":
        payload = authenticated_payload(args, "type_text")
        payload.update({"text": args.text})
        return payload
    if args.command == "media-control":
        payload = authenticated_payload(args, "media_control")
        payload.update({"control": args.control})
        return payload
    if args.command == "shortcut":
        payload = authenticated_payload(args, "shortcut")
        payload.update({"shortcut": args.shortcut})
        return payload
    if args.command == "get-settings":
        return authenticated_payload(args, "get_settings")
    if args.command == "update-settings":
        payload = authenticated_payload(args, "update_settings")
        if args.mouse_sensitivity is not None:
            payload.update({"mouseSensitivity": args.mouse_sensitivity})
        if args.scroll_sensitivity is not None:
            payload.update({"scrollSensitivity": args.scroll_sensitivity})
        if args.max_text_length is not None:
            payload.update({"maxTextLength": args.max_text_length})
        return payload
    if args.command == "trusted-devices":
        return authenticated_payload(args, "trusted_devices")
    if args.command == "forget-device":
        payload = authenticated_payload(args, "forget_device")
        if args.target_device_id:
            payload.update({"targetDeviceId": args.target_device_id})
        return payload
    if args.command == "rotate-pairing-code":
        return authenticated_payload(args, "rotate_pairing_code")
    if args.command == "shutdown-computer":
        payload = authenticated_payload(args, "shutdown_computer")
        payload.update({"delaySeconds": args.shutdown_delay})
        if args.confirm_shutdown:
            payload.update({"confirm": "SHUTDOWN"})
        return payload
    if args.command == "cancel-shutdown":
        return authenticated_payload(args, "cancel_shutdown")
    if args.command == "control-start":
        return authenticated_payload(args, "control_start")
    return authenticated_payload(args, "control_stop")


def main() -> int:
    args = parse_args()
    try:
        response = send_message(args.host, args.port, build_payload(args))
    except Exception as exc:
        print(f"Connection failed: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(response, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


