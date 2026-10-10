from concurrent.futures import ThreadPoolExecutor
import unittest
import server


class ConnectionAdmissionTests(unittest.TestCase):
    def test_peer_limit_does_not_block_other_peers(self):
        budget = server.ConnectionAdmission(maximum=3, per_peer=1)
        self.assertTrue(budget.acquire("phone"))
        self.assertFalse(budget.acquire("phone"))
        self.assertTrue(budget.acquire("other"))
        budget.release("phone")
        self.assertTrue(budget.acquire("phone"))

    def test_global_limit_and_cleanup(self):
        budget = server.ConnectionAdmission(maximum=2, per_peer=2)
        self.assertTrue(budget.acquire("one"))
        self.assertTrue(budget.acquire("two"))
        self.assertFalse(budget.acquire("three"))
        budget.release("one")
        budget.release("two")
        self.assertEqual(budget.active, 0)
        self.assertEqual(budget.peers, {})

    def test_concurrent_acquire_is_bounded(self):
        budget = server.ConnectionAdmission(maximum=4, per_peer=4)
        with ThreadPoolExecutor(max_workers=16) as workers:
            results = list(workers.map(lambda _: budget.acquire("same"), range(64)))
        self.assertEqual(sum(results), 4)
        for _ in range(4):
            budget.release("same")
        self.assertEqual(budget.peers, {})


if __name__ == "__main__":
    unittest.main()
