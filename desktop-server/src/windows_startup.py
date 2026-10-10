"""Per-user, reversible Windows sign-in startup settings."""

import argparse
from pathlib import Path
import subprocess
import sys

VALUE_NAME = "QareebControl"
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"


def startup_command():
    interpreter = Path(sys.executable).with_name("pythonw.exe")
    launcher = Path(__file__).resolve().with_name("startup_launcher.py")
    if not interpreter.is_file() or not launcher.is_file():
        raise RuntimeError("Python windowless runtime or launcher is missing")
    return subprocess.list2cmdline([str(interpreter), str(launcher)])


def current_command():
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            return winreg.QueryValueEx(key, VALUE_NAME)[0]
    except FileNotFoundError:
        return None


def set_enabled(enabled):
    import winreg

    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, startup_command())
        else:
            try:
                winreg.DeleteValue(key, VALUE_NAME)
            except FileNotFoundError:
                pass


def show_settings():
    from desktop_dashboard import show_dashboard
    show_dashboard()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["enable", "disable", "status", "settings"], default="settings", nargs="?")
    args = parser.parse_args()
    if args.action in ("enable", "disable"):
        set_enabled(args.action == "enable")
    if args.action == "settings":
        show_settings()
    else:
        print("enabled" if current_command() == startup_command() else "disabled")
