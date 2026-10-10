# Optional adaptive connection

The Android settings switch defaults to off and persists on this installation.
Existing health checks and reconnect behavior remain enabled independently.

When enabled, successful control response times feed an exponentially weighted
latency and jitter estimate (weight 0.2). Six sustained slow observations
(average above 80 ms or jitter above 35 ms) increase the mouse-only coalescing
window by 10 ms, up to 40 ms. Twelve healthy observations (average below 45 ms
and jitter below 15 ms) reduce it by 10 ms. Session cancellation resets history.

Only a lone queued mouse movement can wait. New non-mouse commands wake the
worker immediately. Existing adjacent movement merging and stale movement
discarding still apply. Text, clicks and other commands are never retried or
reordered by this policy. It does not change routes, firewall rules, endpoints,
socket timeouts or authentication. It is active during use, not an Android
background service. This measures command RTT, not bandwidth, and cannot fix
radio interference. There is no added idle probing.

Validation: standalone policy test covers opt-out, sustained degradation,
bounded adjustment, recovery, invalid samples and session reset.

## Isolated validation (2026-10-08)

AdaptiveSocketTest uses a loopback-only TCP server on an ephemeral port and the
real ControlConnection. All 150 ordered exchanges passed: the first 30 delayed
responses (120 ms) raised the window to 40 ms; subsequent 5 ms responses restored
it to zero. A parallel disabled policy remained at zero. No production server,
route or firewall settings were changed. This validates transport-fed policy,
not the Android queue integration or end-to-end phone performance.

AdaptiveQueueSimulationTest models 10 seconds of movement every 16 ms and a
keyboard command every 503 ms. Its simplified adjacent-move merging omits the
production displacement cap and is not an Android runtime benchmark.

| RTT | Enabled | Total sends | Mean key queue wait | Max key queue wait |
| --- | --- | --- | --- | --- |
| 10 ms | No | 645 | 3.7 ms | 10 ms |
| 10 ms | Yes | 645 | 3.7 ms | 10 ms |
| 120 ms | No | 85 | 171.5 ms | 232 ms |
| 120 ms | Yes | 74 | 164.2 ms | 234 ms |

All 20 keyboard commands survived each simulation without duplication. At slow
RTT the send count fell about 13%, but the worst keyboard wait did not improve.
This is a bounded load-reduction tradeoff, not a bandwidth/speed guarantee.
Physical-phone, Android queue wakeup, displacement-cap and jitter/loss testing
are still required before release. Keep the option off by default.
