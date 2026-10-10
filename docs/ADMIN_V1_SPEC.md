# Qareeb Control Admin V1

## Product boundary

The admin panel is an identity, status, subscription, settings-backup, and audit surface. It is not a remote-control surface. Mouse, keyboard, power, screen, and file commands must never be routed through the cloud.

## Required states

- `cloud_status`: last known account/device status only.
- `local_status`: direct reachability from the browser to the private server on the LAN.
- `control_available`: true only after a verified local transport handshake.
- `pairing_state`: idle, awaiting_phone, awaiting_computer_confirmation, paired, expired, rejected, consumed.

The UI must never label a cloud heartbeat as an active control session.

## V1 navigation

1. Overview: cloud health, private-server health, local-control availability, alerts.
2. Computers: Windows, macOS, and Linux servers; version, certificate, last cloud heartbeat, local reachability.
3. Phones: multiple registered phones per computer; email, device name, last pairing, revoke one/all.
4. Sessions: local sessions only; cloud can show history but cannot terminate a local control session.
5. Access and security: roles, MFA, pairing policy, LAN-only policy, certificate rotation.
6. Backups: settings-only snapshots and restore history.
7. Audit: immutable events for authentication, pairing, revocation, policy, and backup actions.
8. Subscription: plan, limits, expiry, grace period, and entitlement state.

## Pairing contract

The computer starts pairing and displays a one-time QR. A registered phone scans it. The computer displays the verified account email and asks for explicit confirmation. Only approval completes pairing. QR reuse, expiry, email mismatch, missing LAN reachability, and denial must all fail closed.

## Backup boundary

Back up only account settings, device names, phone associations, permissions, plan metadata, and pairing rules. Never back up passwords, private TLS keys, control tokens, screen content, files, or input events.

## Acceptance checks

- A cloud-visible computer cannot be controlled when the phone is outside the LAN.
- A computer can have more than one phone.
- A QR ticket can be consumed only once.
- Every pairing attempt has an audit event.
- Removing a phone immediately blocks its next local handshake.
- Settings restore never restores private credentials.
