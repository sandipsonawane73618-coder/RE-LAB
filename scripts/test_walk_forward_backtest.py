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

    def test_vectorized_rank_matches_reference_tie_breaks(self):
        exact = backtest.Counter({"123": 4, "456": 2, "007": 1})
        families = backtest.Counter({"123": 7, "401": 3, "002": 2})
        weekdays = backtest.Counter({"123": 2, "401": 1})
        transitions = backtest.Counter({("000", "123"): 3, ("000", "401"): 1})
        candidates = backtest.CANDIDATES
        family = backtest.CANDIDATE_FAMILIES
        cases = (
            ((0.30, 0.35, 0.20, 0.15), False, False),
            ((1.0, 0.0, 0.0, 0.0), True, False),
            ((0.0, 0.50, 0.30, 0.20), False, True),
        )
        for weights, lexical, exact_primary in cases:
            with self.subTest(weights=weights, lexical=lexical, exact_primary=exact_primary):
                max_exact = max(exact.values(), default=1)
                max_family = max(families.values(), default=1)
                max_weekday = max(weekdays.values(), default=1)
                max_transition = max(transitions.values(), default=1)
                scored = []
                for panna in candidates:
                    fam = family[panna]
                    score = (
                        weights[0] * exact[panna] / max_exact
                        + weights[1] * families[fam] / max_family
                        + weights[2] * weekdays[fam] / max_weekday
                        + weights[3] * transitions[("000", fam)] / max_transition
                    ) * 100.0
                    secondary = (
                        weights[1] * families[fam] / max_family
                        + weights[2] * weekdays[fam] / max_weekday
                        + weights[3] * transitions[("000", fam)] / max_transition
                    ) * 100.0
                    scored.append((panna, score, families[fam], exact[panna], secondary))
                if exact_primary:
                    key = lambda item: (-item[3], -item[4], item[0])
                elif lexical:
                    key = lambda item: (-item[1], item[0])
                else:
                    key = lambda item: (-item[1], -item[2], -item[3], item[0])
                ordered = sorted(scored, key=key)
                expected = 1 + next(i for i, item in enumerate(ordered) if item[0] == "123")
                actual_top, actual_rank = backtest.rank_pannas(
                    exact, families, weekdays, transitions, "000", "123",
                    weights=weights, lexical_tiebreak=lexical,
                    exact_primary_tiebreak=exact_primary,
                )
                self.assertEqual([item[0] for item in ordered[:10]], actual_top)
                self.assertEqual(expected, actual_rank)

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


    def test_weight_tuning_uses_disjoint_chronological_validation_and_test_windows(self):
        rows = [
            {
                "date": date.fromordinal(date(2026, 1, 1).toordinal() + index),
                "session_id": "main",
                "open": f"{index % 10}{(index + 1) % 10}{(index + 2) % 10}",
                "close": f"{(index + 3) % 10}{(index + 4) % 10}{(index + 5) % 10}",
            }
            for index in range(100)
        ]
        result = backtest.evaluate_market(rows, "SRIDEVI", minimum_history=5)["main:open"]
        validation = result["tuning_validation"]["balanced_default"]
        held_out = result["tuning_test"]["balanced_default"]
        self.assertEqual(15, validation["tested_targets"])
        self.assertEqual(15, held_out["tested_targets"])
        self.assertIn("top100_hits", held_out)
        self.assertIn("top100_hit_rate", held_out)
        self.assertGreaterEqual(held_out["top100_hits"], held_out["top10_hits"])
        self.assertEqual(15, result["tuning_test_baseline"]["tested_targets"])
        self.assertEqual(10, len(result["feature_ablation_top10"]))
        self.assertIn("balanced_default", result["feature_ablation_top10"])
        self.assertIn("exact_primary_combined", result["feature_ablation_top10"])

    def test_weight_selection_is_isolated_per_market_session_and_side(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "eligible.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=("market", "status", "date", "session_id", "result"),
                )
                writer.writeheader()
                for market in ("SRIDEVI", "KALYAN"):
                    for index in range(200):
                        opened = f"{index % 1000:03d}"
                        closed = f"{(index * 7 + 3) % 1000:03d}"
                        jodi = f"{sum(map(int, opened)) % 10}{sum(map(int, closed)) % 10}"
                        writer.writerow({
                            "market": market,
                            "status": "PUBLISHED",
                            "date": date.fromordinal(date(2020, 1, 1).toordinal() + index).isoformat(),
                            "session_id": "main",
                            "result": f"{opened}-{jodi}-{closed}",
                        })
            report = backtest.evaluate(path, minimum_history=5)

        tuning = report["chronological_weight_tuning"]
        self.assertEqual("independent_per_market_session_side", tuning["selection_scope"])
        cohorts = tuning["selected_variant_by_market_session"]
        self.assertEqual(
            {"SRIDEVI:main:open", "SRIDEVI:main:close", "KALYAN:main:open", "KALYAN:main:close"},
            set(cohorts),
        )
        self.assertTrue(all(
            item["selection_status"] == "SELECTED_ON_THIS_COHORT_VALIDATION_ONLY"
            and item["validation_targets"] == 30
            and item["untouched_test"]["tested_targets"] == 30
            for item in cohorts.values()
        ))
        self.assertEqual(120, tuning["aggregate_untouched_test"]["tested_targets"])

    def test_exact_primary_tiebreak_preserves_frequency_priority_and_uses_secondary_evidence(self):
        exact = backtest.Counter()
        families = backtest.Counter({"111": 5})
        weekdays = backtest.Counter()
        transitions = backtest.Counter()
        _, lexical_rank = backtest.rank_pannas(
            exact, families, weekdays, transitions, "000", "111",
            weights=(0.0, 0.0, 0.0, 0.0), lexical_tiebreak=True,
        )
        _, secondary_rank = backtest.rank_pannas(
            exact, families, weekdays, transitions, "000", "111",
            weights=(0.0, 1.0, 0.0, 0.0), exact_primary_tiebreak=True,
        )
        self.assertEqual(backtest.rank_exact_frequency(exact, "111"), lexical_rank)
        self.assertLess(secondary_rank, lexical_rank)

    def test_ablation_lexical_tiebreak_does_not_reintroduce_excluded_features(self):
        exact = backtest.Counter()
        families = backtest.Counter({"111": 10})
        weekdays = backtest.Counter()
        transitions = backtest.Counter()
        _, contaminated_rank = backtest.rank_pannas(
            exact, families, weekdays, transitions, "000", "000",
            limit=1, weights=(1.0, 0.0, 0.0, 0.0),
        )
        top, isolated_rank = backtest.rank_pannas(
            exact, families, weekdays, transitions, "000", "000",
            limit=1, weights=(1.0, 0.0, 0.0, 0.0), lexical_tiebreak=True,
        )
        self.assertEqual(["000"], top)
        self.assertEqual(1, isolated_rank)
        self.assertGreater(contaminated_rank, isolated_rank)



if __name__ == "__main__":
    unittest.main()
