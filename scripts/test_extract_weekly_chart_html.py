import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("extract_weekly_chart_html.py")
SPEC = importlib.util.spec_from_file_location("weekly_extractor", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(MODULE)


class WeeklyExtractorNineMarketTests(unittest.TestCase):
    def test_all_nine_market_ids_are_accepted(self):
        cases = {
            "SRIDEVI": 7, "SRIDEVI_NIGHT": 7, "KALYAN": 6,
            "MAIN_BAZAR": 5, "TIME_BAZAR": 7, "MILAN_DAY": 7,
            "MILAN_NIGHT": 6, "RAJDHANI_DAY": 6, "RAJDHANI_NIGHT": 5,
        }
        for market, days in cases.items():
            with self.subTest(market=market):
                end_day = days
                date_end = f"{end_day}/1/24"
                html = "<table><tr><td>1/1/24 to " + date_end + "</td>" + "".join(
                    "<td>123</td><td>65</td><td>456</td>" for _ in range(days)
                ) + "</tr></table>"
                rows, issues = MODULE.extract_rows(
                    html, market=market, session_id="main", source_id="canonical",
                    retrieved_at="2026-11-19T10:00:00+05:30", source_url="https://example.test/chart"
                )
                self.assertEqual(days, len(rows))
                self.assertEqual([], issues)
                self.assertTrue(all(row["status"] == "PUBLISHED" for row in rows))
                self.assertTrue(all(row["result"] == "123-65-456" for row in rows))


    def test_historical_week_length_is_not_rejected_by_current_schedule(self):
        # Weekly schedules can change over the archive's lifetime. A valid
        # Monday-to-Saturday row must remain importable for a market whose
        # current page also contains Sunday columns.
        html = "<table><tr><td>1/1/24 to 6/1/24</td>" + "".join(
            "<td>123</td><td>65</td><td>456</td>" for _ in range(6)
        ) + "</tr></table>"
        rows, issues = MODULE.extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="canonical",
            retrieved_at="2026-11-19T10:00:00+05:30"
        )
        self.assertEqual(6, len(rows))
        self.assertEqual([], issues)

    def test_cell_groups_define_historical_week_length_when_range_end_drifts(self):
        # Kalyan has six result columns even when the publisher labels the week
        # through Sunday. Keep six observations and flag the source discrepancy.
        html = "<table><tr><td>1/1/24 to 7/1/24</td>" + "".join(
            "<td>123</td><td>65</td><td>456</td>" for _ in range(6)
        ) + "</tr></table>"
        rows, issues = MODULE.extract_rows(
            html, market="KALYAN", session_id="main", source_id="canonical",
            retrieved_at="2026-11-19T10:00:00+05:30"
        )
        self.assertEqual(6, len(rows))
        self.assertEqual("2024-01-06", rows[-1]["date"])
        self.assertTrue(any("spans 7 days but has 6 result groups" in issue for issue in issues))

    def test_shorter_date_range_than_result_columns_is_skipped(self):
        html = "<table><tr><td>1/1/24 to 6/1/24</td>" + "".join(
            "<td>123</td><td>65</td><td>456</td>" for _ in range(7)
        ) + "</tr></table>"
        rows, issues = MODULE.extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="canonical",
            retrieved_at="2026-11-19T10:00:00+05:30"
        )
        self.assertEqual([], rows)
        self.assertTrue(any("row skipped to avoid shifting dates" in issue for issue in issues))

    def test_invalid_jodi_is_retained_as_malformed_not_corrected(self):
        html = "<table><tr><td>1/1/24 to 7/1/24</td>" + "".join(
            "<td>123</td><td>00</td><td>456</td>" for _ in range(7)
        ) + "</tr></table>"
        rows, _ = MODULE.extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="canonical",
            retrieved_at="2026-11-19T10:00:00+05:30"
        )
        self.assertEqual(7, len(rows))
        self.assertTrue(all(row["status"] == "MALFORMED" for row in rows))
        self.assertTrue(all(row["result"] == "" for row in rows))

    def test_future_week_slots_are_excluded_at_capture_date(self):
        html = "<table><tr><td>16/11/26 to 22/11/26</td>" + "".join(
            "<td>123</td><td>65</td><td>456</td>" for _ in range(7)
        ) + "</tr></table>"
        rows, issues = MODULE.extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="canonical",
            retrieved_at="2026-11-20T18:30:00+05:30"
        )
        self.assertEqual(
            ["2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20"],
            [row["date"] for row in rows]
        )
        self.assertEqual(5, len(rows))
        self.assertTrue(any("future chart dates excluded" in issue for issue in issues))

    def test_retrieval_timestamp_requires_timezone(self):
        with self.assertRaises(ValueError):
            MODULE.extract_rows(
                "<table></table>", market="TIME_BAZAR", session_id="main",
                source_id="canonical", retrieved_at="2026-11-19T10:00:00"
            )


if __name__ == "__main__":
    unittest.main()