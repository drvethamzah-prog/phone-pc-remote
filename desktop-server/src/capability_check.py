import argparse
import json
import socket
import time
import uuid
from dataclasses import dataclass
from typing import Any, Callable


DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 18765
DEFAULT_PAIRING_CODE = "111111"


@dataclass
class CheckResult:
    name: str
    passed: bool
    detail: str


def send_message(host: str, port: int, payload: dict[str, Any], timeout: float = 5.0) -> dict[str, Any]:
    data = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
    with socket.create_connection((host, port), timeout=timeout) as client:
        client.settimeout(timeout)
        client.sendall(data)
        response = client.makefile("rb").readline()
    if not response:
        raise RuntimeError("server_closed_without_response")
    return json.loads(response.decode("utf-8"))


def assert_type(response: dict[str, Any], expected_type: str) -> str:
    response_type = response.get("type")
    if response_type != expected_type:
        raise AssertionError(f"expected {expected_type}, got {response_type}: {response}")
    return expected_type


def run_check(name: str, fn: Callable[[], str]) -> CheckResult:
    try:
        return CheckResult(name, True, fn())
    except Exception as exc:
        return CheckResult(name, False, f"{exc.__class__.__name__}: {exc}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Qareeb Control capability smoke check")
    parser.add_argument("--host", default=DEFAULT_HOST)
    parser.add_argument("--port", default=DEFAULT_PORT, type=int)
    parser.add_argument("--pairing-code", default=DEFAULT_PAIRING_CODE)
    parser.add_argument("--device-id", default=f"capability-check-{int(time.time())}")
    parser.add_argument("--device-name", default="Capability Check")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    token_holder = {"token": ""}

    def status() -> str:
        response = send_message(args.host, args.port, {"type": "status"})
        assert_type(response, "status")
        if not response.get("pairingRequired"):
            raise AssertionError("pairingRequired should be true")
        return f"{response.get('computerName')} dryRun={response.get('controlsDryRun')}"

    def bad_pairing_rejected() -> str:
        response = send_message(
            args.host,
            args.port,
            {
                "type": "hello",
                "deviceId": args.device_id + "-bad",
                "deviceName": args.device_name,
                "pairingCode": "000000",
            },
        )
        assert_type(response, "error")
        if response.get("reason") != "unauthorized":
            raise AssertionError(f"expected unauthorized, got {response}")
        return "unauthorized"

    def pairing() -> str:
        requested_token = f"check-{uuid.uuid4()}-{uuid.uuid4()}"
        response = send_message(
            args.host,
            args.port,
            {
                "type": "hello",
                "deviceId": args.device_id,
                "deviceName": args.device_name,
                "pairingCode": args.pairing_code,
                "requestedToken": requested_token,
            },
        )
        assert_type(response, "paired")
        token = str(response.get("token", ""))
        if not token:
            raise AssertionError("missing token")
        if token != requested_token:
            raise AssertionError("server did not preserve requested token")
        token_holder["token"] = token
        return "client token accepted"

    def reconnect() -> str:
        response = send_message(args.host, args.port, auth_payload(args, token_holder["token"], "hello"))
        assert_type(response, "hello_ok")
        return "trusted"

    def control_command(message_type: str, expected_type: str, **fields: Any) -> Callable[[], str]:
        def check() -> str:
            payload = auth_payload(args, token_holder["token"], message_type)
            payload.update(fields)
            response = send_message(args.host, args.port, payload)
            assert_type(response, expected_type)
            if response.get("dryRun") is not True and expected_type in ("mouse_ok", "keyboard_ok", "shutdown_scheduled", "shutdown_cancelled"):
                raise AssertionError(f"expected dryRun response, got {response}")
            return str(response.get("action", response.get("type", "ok")))

        return check

    def settings_update() -> str:
        payload = auth_payload(args, token_holder["token"], "update_settings")
        payload.update({"mouseSensitivity": 1.4, "scrollSensitivity": 1.3, "maxTextLength": 240})
        response = send_message(args.host, args.port, payload)
        assert_type(response, "settings_updated")
        settings = response.get("settings", {})
        if settings.get("maxTextLength") != 240:
            raise AssertionError(f"settings did not update: {response}")
        return "settings saved"

    def control_stop_blocks_mouse() -> str:
        response = send_message(args.host, args.port, auth_payload(args, token_holder["token"], "control_stop"))
        assert_type(response, "control_stopped")
        payload = auth_payload(args, token_holder["token"], "mouse_move")
        payload.update({"dx": 1, "dy": 1})
        blocked = send_message(args.host, args.port, payload)
        assert_type(blocked, "error")
        if blocked.get("reason") != "control_disabled":
            raise AssertionError(f"expected control_disabled, got {blocked}")
        resumed = send_message(args.host, args.port, auth_payload(args, token_holder["token"], "control_start"))
        assert_type(resumed, "control_started")
        return "blocked then resumed"

    def rotate_keeps_fixed_code() -> str:
        response = send_message(args.host, args.port, auth_payload(args, token_holder["token"], "rotate_pairing_code"))
        assert_type(response, "pairing_code_rotated")
        if response.get("pairingCode") != args.pairing_code:
            raise AssertionError(f"expected fixed code {args.pairing_code}, got {response}")
        return str(response.get("pairingCode"))

    checks = [
        ("status", status),
        ("bad pairing rejected", bad_pairing_rejected),
        ("pair with code", pairing),
        ("reconnect with token", reconnect),
        ("mouse move", control_command("mouse_move", "mouse_ok", dx=5, dy=-3)),
        ("mouse click", control_command("mouse_click", "mouse_ok", button="left", count=1)),
        ("scroll", control_command("scroll", "mouse_ok", amount=2)),
        ("key press", control_command("key_press", "keyboard_ok", key="a", count=1)),
        ("type text", control_command("type_text", "keyboard_ok", text="abc123")),
        ("media", control_command("media_control", "keyboard_ok", control="play_pause")),
        ("shortcut", control_command("shortcut", "keyboard_ok", shortcut="copy")),
        ("settings update", settings_update),
        ("control stop/start", control_stop_blocks_mouse),
        ("fixed pairing code command", rotate_keeps_fixed_code),
    ]

    results = [run_check(name, check) for name, check in checks]
    for result in results:
        status_text = "PASS" if result.passed else "FAIL"
        print(f"{status_text} {result.name}: {result.detail}")
    failed = [result for result in results if not result.passed]
    print(f"SUMMARY passed={len(results) - len(failed)} failed={len(failed)} total={len(results)}")
    return 1 if failed else 0


def auth_payload(args: argparse.Namespace, token: str, message_type: str) -> dict[str, Any]:
    return {
        "type": message_type,
        "deviceId": args.device_id,
        "deviceName": args.device_name,
        "token": token,
    }


if __name__ == "__main__":
    raise SystemExit(main())
