import csv
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
        self.assertEqual({}, result)


    def test_eligible_csv_rejects_malformed_published_chart_results(self):
        cases = (
            ("12-34-567", "main", "Invalid result"),
            ("123-4-567", "main", "Invalid result"),
            ("123-45-67", "main", "Invalid result"),
            ("123-45-567", "   ", "Missing session ID"),
        )
        for result, session_id, message in cases:
            with self.subTest(result=result, session_id=session_id):
                with tempfile.TemporaryDirectory() as directory:
                    path = Path(directory) / "eligible.csv"
                    with path.open("w", newline="", encoding="utf-8") as handle:
                        writer = csv.DictWriter(
                            handle,
                            fieldnames=("market", "status", "date", "session_id", "result"),
                        )
                        writer.writeheader()
                        writer.writerow({
                            "market": "SRIDEVI",
                            "status": "PUBLISHED",
                            "date": "2026-01-01",
                            "session_id": session_id,
                            "result": result,
                        })
                    with self.assertRaisesRegex(ValueError, message):
                        backtest.read_eligible(path)

    def test_eligible_csv_accepts_zero_padded_chart_values_and_trimmed_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "eligible.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=("market", "status", "date", "session_id", "result"),
                )
                writer.writeheader()
                writer.writerow({
                    "market": " sridevi ",
                    "status": " published ",
                    "date": "2026-01-01",
                    "session_id": " main ",
                    "result": " 007-05-009 ",
                })
            rows = backtest.read_eligible(path)
        self.assertEqual("007", rows["SRIDEVI"][0]["open"])
        self.assertEqual("009", rows["SRIDEVI"][0]["close"])
        self.assertEqual("main", rows["SRIDEVI"][0]["session_id"])


    def test_feature_ablation_uses_same_held_out_targets_as_full_model(self):
        rows = [
            {
                "date": date.fromordinal(date(2026, 1, 1).toordinal() + index),
                "session_id": "main",
                "open": f"{index % 10}{(index + 1) % 10}{(index + 2) % 10}",
                "close": f"{(index + 3) % 10}{(index + 4) % 10}{(index + 5) % 10}",
            }
            for index in range(12)
        ]
        result = backtest.evaluate_market(rows, "SRIDEVI", minimum_history=5)["main:open"]
        self.assertEqual(result["tested_targets"], result["feature_ablation_top10"]["exact_only"]["tested_targets"])
        self.assertEqual(result["tested_targets"], result["feature_ablation_top10"]["family_only"]["tested_targets"])
        self.assertEqual(result["tested_targets"], result["feature_ablation_top10"]["no_exact"]["tested_targets"])
        self.assertTrue(all(
            0.0 <= item["top10_hit_rate"] <= 1.0
            for item in result["feature_ablation_top10"].values()
        ))


if __name__ == "__main__":
    unittest.main()
