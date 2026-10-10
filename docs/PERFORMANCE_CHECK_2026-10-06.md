# Connection Performance Check

Date: 2026-10-06. Server: 0.27.0-low-latency. Android: 0.23.0-low-latency.

## Findings

- ICMP to the phone's observed address, 10.10.11.9: 10/10 replies, 4 ms minimum,
  36 ms average, 106 ms maximum. This measures network RTT, not input-to-display latency.
- The old Android client opened a fresh socket for every input and ran four requests
  concurrently. The server rewrote trusted-devices.json on every authenticated command.
- Real phone logs contained concurrent temporary-file write/rename failures (Windows
  errors 13 and 32). They were not evidence of an incorrect pairing code.
- Quiet commands could succeed while an earlier timeout remained visible in the UI.

## Changes

- Ordered, persistent input connection with TCP_NODELAY; 2-second connection/read
  deadlines, no automatic replay of commands after ambiguous failures.
- Adjacent pending mouse moves are combined within the server's limits. Motion older
  than 250 ms is dropped. The queue is capped at 64 and cleared on transport failure.
- Pairing/discovery retain their separate executor. Legacy clients retain one-shot
  connections; the updated Android input path requires the updated server.
- Trusted-device snapshots and writes are serialized; last-seen persistence is limited
  to once per 30 seconds. Pairing and revocation still save immediately.
- The app shows RTT and version, has a 10-sample speed measurement, and clears stale
  error status when a new control response succeeds.

## Measurements

| Probe | Samples | Median/Mean | P95/Maximum |
| --- | --- | --- | --- |
| Before: PC loopback, fresh socket | 40 | median 15.21 ms | P95 23.17 ms |
| After: PC loopback, fresh socket | 100 | median 7.885 ms | P95 22.939 ms |
| After: PC loopback, persistent | 100 | median 0.105 ms | P95 0.147 ms |
| Final APK, Android emulator to PC | 10 | mean 4.7 ms | max 15.4 ms |

Loopback and emulator results do not include the real phone's Wi-Fi path. Samples
are diagnostic snapshots, not a sustained frame-rate or display-latency benchmark.
Raw local results: `releases/latency-pc-2026-10-06.json`.
Final Android screenshot: `releases/latency-emulator-2026-10-06.png`.

## Verification

- Six Python integration tests passed, including 200 concurrent authenticated requests
  with one activity save and no collisions, 100 ordered inputs on one socket,
  concurrent pairing persistence, legacy EOF behavior, and revocation on a live socket.
- The integration suite also ran the existing 14/14 capability checks on an isolated
  dry-run server. No test input was sent to the real Windows desktop.
- Three Java transport scenarios passed: 100 ordered exchanges on one socket,
  read-timeout recovery without replay, and cancellation of an in-flight request.
- Final APK built successfully and ran on the API 35 emulator. UI mouse input reached
  the isolated server on a single connection and displayed connected state with RTT.
  Three UI key presses also reached the isolated server in sequence.
- One early emulator check overlapped the timed shutdown of its temporary server;
  the check was repeated successfully using a server stopped explicitly after testing.
- The final 10-sample UI measurement against the real server succeeded. The emulator
  was restored to port 8765 and shut down; temporary test servers were stopped.

The physical phone was not connected through ADB. Install the new APK as an update,
then use the speed measurement on the phone to obtain its application-level RTT.

## Reproduction

```powershell
python desktop-server/src/test_transport.py
python desktop-server/src/latency_check.py --samples 100
javac -d mobile-app/build/transport-tests mobile-app/app/src/main/java/com/phonepcremote/ControlConnection.java mobile-app/tests/com/phonepcremote/ControlConnectionTest.java
java -cp mobile-app/build/transport-tests com.phonepcremote.ControlConnectionTest
```
