import tempfile
import unittest
from datetime import date
from pathlib import Path

import walk_forward_backtest as backtest


class WalkForwardBacktestTests(unittest.TestCase):
    def test_digit_families_match_locked_pairs(self):
        self.assertEqual("112", backtest.family_sequence("167"))
        self.assertEqual("001", backtest.family_sequence("056"))

    def test_walk_forward_uses_only_prior_rows(self):
        rows = [
            {"date": date(2026, 1, 5), "session_id": "main", "open": "111", "close": "222"},
            {"date": date(2026, 1, 6), "session_id": "main", "open": "333", "close": "444"},
        ]
        report = backtest.evaluate_market(rows, "SRIDEVI", minimum_history=1)
        result = report["main:open"]
        self.assertEqual(1, result["tested_targets"])
        self.assertEqual("2026-01-06", result["first_test_date"])
        self.assertEqual(1, result["training_records_min"])
        self.assertEqual(1, result["training_records_max"])

    def test_empty_market_abstains_without_fake_accuracy(self):
        result = backtest.evaluate_market([], "KALYAN", minimum_history=1)
        self.assertEqual(0, result["main:open"]["tested_targets"])
        self.assertEqual(0.0, result["main:open"]["top10_hit_rate"])


if __name__ == "__main__":
    unittest.main()
