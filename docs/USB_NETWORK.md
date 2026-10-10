# USB Network Support: Experimental Platform Policy

The previous recommendation to try USB tethering first was a proposed direction,
not proof that ordinary tethering works with the current socket binding design.
Android's USB Network transport and its downstream tethering interface are different
roles; a downstream interface may not be exposed as an app-usable Network.

## Implemented

Android 0.34 adds Devices > Connection mode:

- Automatic local network: accepts OS-exposed Wi-Fi, Ethernet or USB networks.
- USB network only (experimental): requires Android 12/API 31+ and an OS-exposed
  USB Network. Refuses other bearers instead of silently reverting to Wi-Fi.

The selection survives app restarts. Changing mode cancels the old session,
queued input and pending handshakes, then starts the existing bounded recovery.
The setting is independent of UI language, pairing credentials and TLS trust.
An active default VPN remains a blocking condition. Cellular capabilities are
rejected even if another transport is also reported on that candidate.

All sockets and discovery use the selected Android Network through existing
binding APIs. No arbitrary cellular/default socket or USB interface-name guessing
was introduced. When no supported USB Network exists, the app reports unavailable.
It does not request privileged tethering permissions, enable tethering for the
user, change Windows routes/firewall, use ADB, or require root.

## Physical Acceptance Still Required

No hardware cable test has been performed. First inspect what Android exposes on
Samsung/Windows after connecting a data-capable cable and enabling tethering.
If the downstream is not app-visible, this feature does not solve that device's
USB tethering case; evaluate a separately secured direct USB accessory transport
or another supported architecture. Do not bypass the local-only constraint using
an unbound default socket to make a test appear successful.

Test Wi-Fi disabled, cellular-only refusal, VPN refusal, USB removal/reinsert,
address changes, trusted identity, Unicode typing, and no duplicated input. Confirm
Windows driver support and any explicit local firewall permission separately.
Tethering can also share the phone's Internet with Windows at OS level: the app
does not need that Internet path, but does not control Windows background traffic
or disable OS sharing. Do not promise that enabling tethering never uses mobile data.

## Evidence

51 Python tests passed, including 96 compiled Java policy combinations. Android
debug compilation and lint are checked separately. This tests selection logic,
not USB hardware, native selector appearance, or downstream tethering routing.
The working user's server and startup registration were not restarted/changed.

References: [Android NetworkCapabilities](https://developer.android.com/reference/android/net/NetworkCapabilities),
[Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state),
[Android tethering architecture](https://source.android.com/docs/core/ota/modular-system/tethering).
