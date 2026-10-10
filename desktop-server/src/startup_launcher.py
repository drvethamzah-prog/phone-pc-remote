"""Windowless sign-in launcher; preserves the server's existing data store."""

from pathlib import Path
import subprocess
import sys
from startup_settings import server_arguments


def launch():
    source = Path(__file__).resolve().parent
    diagnostics = source.parent / "data" / "diagnostics"
    diagnostics.mkdir(parents=True, exist_ok=True)
    with (diagnostics / "startup-server.log").open("a", encoding="utf-8") as output:
        try:
            arguments = server_arguments()
        except (OSError, ValueError):
            output.write("Startup blocked: invalid or unreadable transport settings.\n")
            return 1
        if "--tls" in arguments:
            try:
                from startup_settings import validate_tls
                validate_tls()
            except Exception:
                output.write("Startup blocked: TLS identity is missing, invalid or inaccessible.\n")
                return 1
        return subprocess.call(
            [sys.executable, "-u", str(source / "server.py"), *arguments],
            cwd=str(source.parent),
            stdin=subprocess.DEVNULL,
            stdout=output,
            stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW,
        )


if __name__ == "__main__":
    sys.exit(launch())
