# Windows Sign-In Startup

## Next-Launch Transport

Desktop 0.40 adds Startup transport > Legacy / TLS. Changes require explicit
confirmation and affect the next launch only, not the current server. TLS can be
saved only after its existing computer identity is loaded and validated; the
editor does not generate/replace an identity or enable sign-in registration.

Settings are atomically stored in `desktop-server/data/startup-settings.json`
with version 1 and transport `legacy` or `tls`. This runtime file is excluded
from release archives. A missing file preserves legacy behavior for existing
installations. A corrupt/unreadable file blocks launch rather than defaulting
to plaintext. TLS launch requires an existing identity, including inside the
server process, so a missing key cannot silently produce a different identity.

When explicitly enabled, the launcher passes `--tls --require-existing-identity`.
The launcher never falls back to plaintext after a TLS error. Diagnostic messages
go to `desktop-server/data/diagnostics/startup-server.log`. Repair/re-enroll locally
instead of changing the phone to accept an arbitrary new certificate.

No setting was activated on the user's remotely accessed computer. First verify
phone TLS pairing locally, then save the startup mode, then test sign-in/reboot.
60 Python tests passed; actual secure Windows reboot verification remains pending.

The settings window now opens the native desktop dashboard: server availability,
local address, transport, response time, control state and trusted-device last-seen
table. Status refresh runs in a background worker every five seconds. Closing the
window leaves the server and sign-in startup unchanged. No server control commands
are sent. Device trust/last-seen does not prove an active phone connection.

The "Probe using TLS" checkbox selects the diagnostic transport only; it neither
enables TLS on the server nor migrates Windows startup. It trusts the existing
local certificate without decrypting its private key. Failures clear stale status
and never silently fall back. The normal status probe is legacy until selected.

46 Python tests passed, including a hidden native window build/close smoke test
with mocked status. Full native visual verification and Arabic localization have
not yet been performed; no visible window was opened over the user's remote session.

Open the desktop startup settings:

```powershell
C:/Python314/pythonw.exe C:/developings/phone-pc-remote/desktop-server/src/windows_startup.py
```

The checkbox controls `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`,
value `QareebControl`. No administrator rights, firewall changes, or network
routes are required. It starts at this user's sign-in, not before sign-in.
Disabling startup does not stop the currently running server.

Command-line alternatives:

```powershell
C:/Python314/python.exe desktop-server/src/windows_startup.py enable
C:/Python314/python.exe desktop-server/src/windows_startup.py disable
C:/Python314/python.exe desktop-server/src/windows_startup.py status
```

The launcher uses absolute paths, the installed windowless Python runtime,
and the existing server defaults and data directory. Pairing credentials
are not copied into the registry. Startup output is appended to
`desktop-server/data/diagnostics/startup-server.log`.

Keep Python and this project at their registered paths; after moving either,
enable startup again from the new installation. Windows Startup Apps can
also disable this entry. This is a development installation, not a packaged
Windows service or signed commercial installer.

Verified: registry enable/disable/repeated-disable and re-enable; launcher
arguments and output redirection. Actual post-reboot startup remains to be
confirmed on the user's computer. Current server was left running.
