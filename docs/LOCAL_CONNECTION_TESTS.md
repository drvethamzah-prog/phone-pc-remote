# Local Connection Verification

## 0.34 Local IPv6 Follow-Up

Date: 2026-10-07; Android 0.30.0-local-ipv6.

- Physical phone link-local ICMP check from Windows: two replies, no loss, average 26 ms. This is not application-level phone verification.
- Python: 19 tests passed, including coexisting IPv4/IPv6 listeners, IPv6 hello, IPv6-only socket mode, exclusion of global/unscoped addresses, interface removal and endpoint advertisement cleanup.
- Java: endpoint normalization accepts only link-local IPv6 literals and removes foreign interface zones; public, loopback, mapped IPv4 and malformed fallback endpoints are rejected.
- Android API 35: 67 assertions passed in Arabic and English. Added checks for filtered IPv6 caching and replacing a foreign desktop scope with the Android interface scope. The emulator's unreachable cached IPv6 endpoint falls back to IPv4 without breaking existing recovery/IME tests.
- Gradle build and lint passed; existing warnings remain.
- Updated production server: scoped link-local IPv6 identity/hello passed from Windows itself; IPv4 remains listening and the prior control-enabled state was retained. No global IPv6 wildcard listener, route modification or firewall change was added.
- Physical Samsung verification completed: logs show Android 0.30 hello/status and mouse clicks over the phone's link-local IPv6 address. User confirmed control after closing/reopening the app.
- Removed the previously approved IPv4 /32 workaround from both ActiveStore and PersistentStore and verified its absence. User confirmed control still worked afterward, then confirmed it again after closing the app, disabling/re-enabling phone Wi-Fi and reopening the app. Subsequent logs still show IPv6 control traffic.
- An actual Android IPv4 address change remains unverified: the neighbor cache still contains the previous IPv4 address as Stale, which is not proof of the current phone address. This test confirms Wi-Fi recovery, not DHCP address reassignment. Desktop reboot and IPv6-disabled networks remain additional acceptance tests.
- Initial connection must succeed once to cache the desktop endpoints. This is a remembered-endpoint fallback, not initial IPv6 multicast/mDNS discovery. IPv6-disabled or isolated networks still require an available local path.

IPv6 interface scoping reference: https://developer.android.com/reference/java/net/Inet6Address

## 0.33 Baseline

Date: 2026-10-07. Project 0.33.0-local-recovery; Android 0.29.0-local-recovery.

## Implemented

- Foreground network observation and a five-second non-input identity health probe.
- Bounded handshake recovery with jitter; cancellation on backgrounding, network changes and local unpairing.
- No replay of uncertain input. Stale-generation input failures cannot clear the new session's queue.
- Local Wi-Fi/Ethernet socket binding, independent of Internet validation. Cellular-only and default VPN connections fail closed. Public control addresses are rejected.
- IPv4 discovery uses actual subnet broadcast addresses and the response packet's source address. TCP scanning is bounded to the current /24 window intersected with the subnet.
- Recovery discovery uses a fresh nonce and HMAC-SHA256 with the existing pairing secret, without broadcasting that secret. TCP identify/hello verifies possession before reconnect credentials are sent on that socket.

## Verification

- Java local subnet tests: /0, /16, /20, /23, /26, /31, /32; scan bounds and local/public address checks.
- Java recovery tests: retry bounds, jitter bounds, budget reset.
- Java discovery tests: valid proof; wrong key, modified nonce, modified port and malformed proof rejected. Shared Python/Java test vector.
- Existing Java transport and pairing tests: ordering, cancellation, ambiguous-response no-replay and retained pairing credentials.
- Python: 16 transport/Win32 keyboard regression tests passed using isolated data stores and dry-run controls.
- Android API 35 emulator: 65 assertions passed in Arabic and English, including native Arabic/English IME, media controls, authenticated UDP discovery, wrong-secret rejection, stale saved host recovery, lost input reply without replay, foreground lifecycle, and actual Wi-Fi off/on recovery. Cellular was enabled for the English run; it was not accepted as a control transport after Wi-Fi loss.
- Gradle assembleDebug, assembleDebugAndroidTest and lintDebug passed. Existing lint warnings remain; this is a debug APK, not a commercial signed release.
- Updated production server: loopback identity/hello with the existing phone credential passed, as did a legacy Android-style hello. No keyboard or mouse input was injected by these checks. Physical Samsung validation of this new version is pending.

## Limits

This is not a claim of universal network compatibility or commercial security readiness.

- The user's existing IPv4 asymmetric-routing problem is still a network configuration issue. The approved persistent /32 workaround remains unchanged; it will not follow a new phone IP automatically.
- Discovery cannot overcome client isolation, broken return routes, or a blocked service port. No administrator route changes or firewall disabling are performed by recovery.
- New Android automatic recovery requires the updated desktop server. Older phones remain compatible with that server. An old desktop server is not silently trusted when it cannot produce an identity proof.
- HMAC discovery is not TLS, secure initial pairing, or protection from an active forwarding/MITM attack. Transport encryption and secure credential storage remain release blockers.
- IPv6 discovery, mDNS/NSD, multi-computer selection, USB and Bluetooth are not implemented by this change. IPv6 link-local discovery is worth evaluating for same-link IPv4 subnet mismatch without privileged route changes.
- No cloud relay, public remote-control endpoint, Internet fallback or router port forwarding was added.

## Design References

- Android network state and callbacks: https://developer.android.com/develop/connectivity/network-ops/reading-network-state
- Android standard local service discovery, for the subsequent mDNS/NSD stage: https://developer.android.com/develop/connectivity/wifi/use-nsd
- KDE Connect local discovery and firewall limits: https://userbase.kde.org/KDEConnect/en
- Unified Remote local Wi-Fi/Bluetooth orientation, with Internet connectivity deliberately excluded here: https://help.unifiedremote.com/article/31-enabling-external-connections
