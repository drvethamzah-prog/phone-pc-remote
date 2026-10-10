# Product policy

- The desktop server starts pairing and displays the one-time QR ticket.
- The ticket is valid only for one successful enrollment and only on a local LAN endpoint.
- When a registered phone scans the ticket, the phone presents its verified account email over the local enrollment channel.
- The computer must show the account email and ask the user to confirm linking before completing enrollment.
- Confirmation is mandatory; canceling or closing the prompt aborts enrollment and does not trust the phone.
- Multiple phones may be trusted by one computer; each phone receives its own local credential.
- Control remains local and works without internet.
- Cloud connectivity may publish status, permissions, subscription tier, and settings backup only.
- The server must never use cloud connectivity as a control path.
- The implementation must remain portable across Windows, macOS, and Linux.
