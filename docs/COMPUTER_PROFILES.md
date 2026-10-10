# Saved Computers (Android 0.36)

The Devices tab contains My Computers, separate from the PC's trusted-phone list.
Each entry has a name supplied by a successful handshake, its last verified endpoint,
port, certificate pin, IPv6 discovery cache and last successful connection time.
There is at most one active computer; switching is explicit, never an automatic
fallback to another saved computer. The current limit is 20 saved profiles.

Connected means the selected session completed its authenticated handshake and
has not been marked disconnected by the existing health checks. It is not a
continuous reachability guarantee. All other profiles say Saved; no speculative
online badge or background scanning of every computer is performed.

Profile metadata is versioned JSON in app-private preferences. Tokens are kept
separately as `computer_token:<UUID>`, protected with Android Keystore AES-GCM and
authenticated with that preference name. Active token/context updates are committed
with profile selection or confirmed enrollment. Corrupt stores are reported, not
silently reset. The previous single-computer pairing migrates without rotating its
token. Pending enrollment credentials are scoped to the selected profile.

Add Computer clears only the active context, preserving saved profiles. Connect
loads the chosen profile and starts an authenticated local handshake. Switching
cancels scheduled recovery, disposable pairing sockets and queued input; generation
checks discard stale callbacks. Already transmitted input cannot be undone.
Queued control, direct authenticated and pairing requests capture their TLS pin;
they do not read another profile's pin after a switch. A stale control session is
closed before the next command. This preserves existing per-profile TLS choices,
not a claim that legacy plaintext connections are secure.
The last verified address updates after successful discovery/reconnection, without
creating another profile for the selected computer.

Forget Computer is a confirmed local action that removes that profile and its
saved secret. Forgetting an inactive computer does not interrupt the active one.
Local deletion does not revoke this phone on the PC; use the existing server-side
trusted-device removal action for revocation.

No Internet transport or relay is added. Existing local-only network selection
remains in force. Legacy plaintext transport is still supported by the existing
protocol; this feature does not make TLS mandatory or fix the known test pairing
code. Never treat a stored IP address or computer name as an authenticated identity.

Physical two-PC usage, actual phone upgrade and full commercial release signing
remain unverified. New builds are debug/test artifacts.

## Validation

Android debug/test builds and lint passed. API-35 emulator tests use isolated
preferences and a loopback recording server, never the production server or real
Windows input. Coverage includes profile migration, independent tokens/pins/ports,
selected-address updates, restart persistence, ciphertext swapping, deletion,
corrupt-store refusal, queued-pin capture and Activity add/switch/reconnect/forget.
English and Arabic screenshots were inspected for layout and direction.
The final instrumentation runs passed 108 checks in English and 108 in Arabic.
All 75 Python tests were rerun in separate test-file processes and passed.
The test harness backlog now matches the server's 128-entry backlog; the initial
Arabic run had timed out on a test snapshot before this harness adjustment.
