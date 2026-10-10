# Local QR Pairing - Development Stage

## Current trial update (2026-10-08)

The user explicitly started a TLS/QR trial on the existing desktop server.
Android 0.37 crashed on Scan: reproduced on API 35 with NoClassDefFoundError for
androidx.core.content.ContextCompat in CaptureManager.openCameraWithPermission.
Android 0.38 explicitly includes AndroidX Core 1.13.1 and enables AndroidX.
Build/lint passed. Native launch tests cover denied and granted camera access;
physical scanning and TLS enrollment remain pending. Expired QR tickets need
deliberate regeneration. No phone data reset or startup-mode change is required.

The older notes below describe the state before this explicitly requested trial.

Do not restart the working server while relying on it remotely. This feature was
built and tested in isolation; it has not been activated on the user's computer.
Physical camera and native UI verification are deferred until the user is local.

## Planned Local Trial

Install desktop requirements and Android 0.32, then deliberately stop the old
server when a brief disconnect is acceptable. Start the server with:

```powershell
C:/Python314/python.exe desktop-server/src/server.py --tls --pairing-qr
```

The desktop displays a QR window. Android Devices > Scan pairing QR requests
camera permission, scans it, then asks for confirmation before changing settings.
Only scan the trusted computer's screen; a substituted QR can substitute trust.
Scanning is not remote control through the Internet and cannot start an offline
computer or stopped server. Camera denial/cancellation does not import settings.

The QR includes a local literal IP, TCP port, SHA-256 certificate fingerprint,
128-bit random temporary code, version and expiration. It contains no existing
trusted-device token or private key. The default endpoint prefers a scoped-local
IPv6 address with its scope removed; Android applies its own interface scope.
Use `--qr-host 192.168.1.20` when choosing a particular local interface is needed.

The server enforces a monotonic five-minute deadline independently of the phone
clock. The phone also checks wall-clock expiry before confirmation, so incorrect
clocks can require correction. A ticket can bind to one phone/requested-token
tuple; retrying that same enrollment is allowed while valid. Multiple phones must
not share a ticket. In QR mode the static code (including 111111) does not enroll
new phones, even after expiry; existing trusted tokens continue to reconnect.

The QR image is a temporary enrollment secret stored under excluded diagnostics;
do not share it. The viewer closes after five minutes and the image is removed on
normal server exit. An expired image cannot enroll a phone. Regeneration currently
requires a deliberate restart with the option; a live desktop approval/regeneration
UI and explicit profile reset remain future work. Desktop 0.40 now supports an
explicit saved TLS startup mode; it does not automatically generate new QR tickets.

## Verification

39 Python tests passed: ticket expiry, retry binding, atomic concurrent claims,
server integration, valid QR image generation and Java URI parsing compatibility.
Java rejects duplicate fields, public addresses, DNS destinations, loopback,
invalid versions/ports, expired tickets, future clock skew and unexpected schemes.
This is not yet proof of scanning the generated image with a physical camera.
Android assembleDebug/lintDebug passed with core-library desugaring for API 23.

Scanner library: [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded).
