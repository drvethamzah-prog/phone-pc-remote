# Product policy

- The app scans a QR initiated by the desktop server.
- The app must verify the QR expiry, signature/fingerprint, and local network reachability before enrollment.
- Email sign-in or registration is required before the computer is linked to the account.
- A registered phone sends its verified account email during local QR enrollment; the computer must confirm the link before enrollment completes.
- The app must stop and show the user that confirmation is required if the computer rejects or does not receive confirmation.
- A user may link multiple phones to one computer when each local enrollment is approved by the computer.
- Control is direct to the desktop server over the local network and does not require internet access.
- Cloud data is limited to status, permissions, subscription tier, and settings-only backup.
