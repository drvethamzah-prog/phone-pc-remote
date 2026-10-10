"""Bounded, process-wide admission limits for unauthenticated pairing."""

import math
import threading
import time


class PairingLimits:
    def __init__(self, per_peer=5, total=100, window=60, clock=time.monotonic):
        self.per_peer = per_peer
        self.total = total
        self.window = window
        self.clock = clock
        self.lock = threading.Lock()
        self.peers = {}
        self.started = None
        self.count = 0

    def admit(self, peer):
        """Reserve an attempt atomically; return seconds to wait or zero."""
        with self.lock:
            now = self.clock()
            if self.started is None or now - self.started >= self.window:
                self.started = now
                self.count = 0
                self.peers.clear()
            count = self.peers.get(peer, 0)
            if self.count >= self.total or count >= self.per_peer:
                return max(1, math.ceil(self.window - (now - self.started)))
            # A global cap also bounds memory and attempts from changing addresses.
            self.count += 1
            self.peers[peer] = count + 1
            return 0
