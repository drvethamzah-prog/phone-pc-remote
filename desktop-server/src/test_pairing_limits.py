import unittest
from concurrent.futures import ThreadPoolExecutor

from pairing_limits import PairingLimits


class PairingLimitsTests(unittest.TestCase):
    def test_peer_limit_and_expiry(self):
        now = [0]
        limits = PairingLimits(clock=lambda: now[0])
        self.assertEqual([limits.admit("a") for _ in range(5)], [0] * 5)
        self.assertEqual(limits.admit("a"), 60)
        self.assertEqual(limits.admit("b"), 0)
        now[0] = 59.5
        self.assertEqual(limits.admit("a"), 1)
        now[0] = 60
        self.assertEqual(limits.admit("a"), 0)

    def test_changing_addresses_cannot_bypass_global_limit(self):
        limits = PairingLimits(total=10, clock=lambda: 0)
        self.assertEqual([limits.admit(str(i)) for i in range(10)], [0] * 10)
        self.assertEqual(limits.admit("another"), 60)
        self.assertEqual(len(limits.peers), 10)

    def test_concurrent_reservations(self):
        limits = PairingLimits(clock=lambda: 0)
        with ThreadPoolExecutor(max_workers=20) as pool:
            results = list(pool.map(lambda _: limits.admit("same-peer"), range(100)))
        self.assertEqual(results.count(0), 5)


if __name__ == "__main__":
    unittest.main()
