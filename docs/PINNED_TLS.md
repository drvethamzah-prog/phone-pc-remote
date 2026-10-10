# Pinned Local TLS: Opt-In Migration

This developer stage is not a commercial security release. Existing connections
and Windows sign-in startup remain in legacy mode. The new Android APK does not
enable TLS until a fingerprint is explicitly saved.

## Controlled Setup

1. Install `desktop-server/requirements.txt` using the server's Python runtime.
2. Schedule a brief disconnect, stop the old server, and start the same server
   with `--tls` (and the existing pairing-code setting). Do not run both modes on
   the same port. Nothing in this update stops the production server for you.
3. The desktop console shows `TLS SHA-256 fingerprint`. On Android 0.31 open
   Devices, select the certificate fingerprint row, and enter those 64 hex
   characters directly from the trusted computer. Never copy a fingerprint from
   an unauthenticated LAN discovery response.
4. Reconnect using the existing saved phone token. All TCP paths (control,
   handshake, health checks, diagnostics and discovery probes) now use pinned TLS.
5. Verify actual input on the phone and reconnect after Wi-Fi changes. Then open
   the desktop dashboard > Startup transport, select TLS and explicitly confirm.
   Existing identity validation must succeed before the preference is saved.
   This changes the NEXT launch only; it never restarts the current server.
   Ensure Start with Windows is enabled separately. Secure startup passes
   `--tls --require-existing-identity`, with no plaintext or new-identity fallback.

No accept-any-certificate mode is provided. Empty/invalid fingerprints are not
accepted by the dialog, and a saved pin cannot silently downgrade to plaintext.
The application currently has one saved computer profile, so the pin follows that
profile across IP changes. Multiple computer profiles and an explicit reset/
re-enrollment workflow are still needed. Certificate expiration requires explicit
recovery; automatic renewal is not implemented.

## Evidence and Limits

34 Python tests passed, including a compiled Java client using the Android
transport classes against the real Python TLS handler. This verifies trusted
hello and Arabic text (dry run, no actual Windows input), rejection of a different
fingerprint, and rejection of plaintext by the TLS-only listener. TLS setup occurs
inside each connection worker, not on the accept loop. Wrapped sockets close at
handler completion.

Android `assembleDebug` and `lintDebug` passed. This is not yet physical-phone TLS
verification or native UI visual verification. Existing plaintext mode remains
available only for migration; do not market the default configuration as encrypted.

Next: native/physical tests, local desktop approval, secure token storage,
certificate renewal and explicit identity reset. QR enrollment is implemented in
Android 0.32+ but still needs physical scanning verification. Startup-mode integration
is implemented in desktop 0.40; actual secure sign-in/reboot verification is pending.
