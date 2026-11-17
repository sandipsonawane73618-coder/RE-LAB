import csv
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_weekly_chart_html import _cell_observation, extract_rows, write_csv


class WeeklyChartHtmlExtractorTest(unittest.TestCase):
    def test_extracts_sridevi_seven_day_week_and_marker_without_faking_result(self):
        html = """
        <table><tr><th>Date</th><th>Mon</th><th>Tue</th><th>Wed</th>
        <th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th></tr>
        <tr><td>29/12/25TO04/01/26</td><td>4 6 0 09 2 8 9</td>
        <td>4 5 0 92 2 4 6</td><td>1 8 9 83 6 8 9</td>
        <td>1 5 5 14 6 8 0</td><td>2 9 9 00 4 7 9</td>
        <td>**</td><td>1 3 4 89 4 5 0</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="SRIDEVI", session_id="main", source_id="hira",
            retrieved_at="2026-11-17T12:00:00+05:30",
            source_url="https://example.test/sridevi"
        )
        self.assertEqual([], issues)
        self.assertEqual(7, len(rows))
        self.assertEqual("2025-12-29", rows[0]["date"])
        self.assertEqual("460-09-289", rows[0]["result"])
        self.assertEqual("PUBLISHED", rows[0]["status"])
        self.assertEqual("**", rows[5]["raw_text"])
        self.assertEqual("MISSING_FROM_SOURCE", rows[5]["status"])
        self.assertEqual("", rows[5]["result"])
        self.assertEqual("2026-01-04", rows[6]["date"])

    def test_accepts_all_five_supported_market_names(self):
        weekly = (
            "<table><tr><td>16/11/2026 to 22/11/2026</td>"
            + "".join(f"<td>{cell}</td>" for cell in ["668 07 449"] * 7)
            + "</tr></table>"
        )
        for market in ("SRIDEVI", "SRIDEVI_NIGHT", "TIME_BAZAR", "MAIN_BAZAR"):
            with self.subTest(market=market):
                rows, issues = extract_rows(
                    weekly, market=market, session_id="main", source_id="archive",
                    retrieved_at="2026-11-17T12:00:00Z"
                )
                self.assertEqual([], issues)
                self.assertEqual(7, len(rows))
                self.assertTrue(all(row["market"] == market for row in rows))

    def test_extracts_kalyan_six_day_week_and_flags_bad_math(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668 07 449</td><td>470 17 179</td><td>247 33 490</td>
        <td>568 92 345</td><td>247 32 480</td><td>123 45 678</td>
        </tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="fixresult",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual(6, len(rows))
        self.assertEqual("668-07-449", rows[0]["result"])
        self.assertEqual("MALFORMED", rows[5]["status"])
        self.assertEqual("", rows[5]["result"])

    def test_explicit_holiday_and_pending_markers_remain_distinct(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668 07 449</td><td>470 17 179</td><td>247 33 490</td>
        <td>568 92 345</td><td>HOLIDAY</td><td>Pending</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual("HOLIDAY_CLOSED", rows[4]["status"])
        self.assertEqual("PENDING", rows[5]["status"])
        self.assertEqual("", rows[4]["result"])
        self.assertEqual("", rows[5]["result"])

    def test_year_and_date_digits_do_not_turn_markers_into_results(self):
        html = """
        <table><tr><td>24-08-2026 to 29-08-2026</td>
        <td>668 07 449</td><td>470 17 179</td><td>247 33 490</td>
        <td>568 92 345</td><td>Holiday 2026</td>
        <td>Market closed 24-08-2026</td></tr></table>
        """
        rows, issues = extract_rows(
            html, market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        self.assertEqual([], issues)
        self.assertEqual("HOLIDAY_CLOSED", rows[4]["status"])
        self.assertEqual("HOLIDAY_CLOSED", rows[5]["status"])
        self.assertEqual("", rows[4]["result"])
        self.assertEqual("", rows[5]["result"])

    def test_contradictory_marker_and_result_is_malformed(self):
        status, result = _cell_observation("Holiday 668-07-449")
        self.assertEqual("MALFORMED", status)
        self.assertEqual("", result)

    def test_source_record_ids_are_date_stable_when_irrelevant_rows_are_inserted(self):
        weekly_row = (
            "<tr><td>24-08-2026 to 29-08-2026</td>"
            + "".join(f"<td>{cell}</td>" for cell in ["668 07 449"] * 6)
            + "</tr>"
        )
        common = dict(
            market="KALYAN", session_id="main", source_id="archive",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        first, _ = extract_rows(f"<table>{weekly_row}</table>", **common)
        shifted, _ = extract_rows(
            f"<table><tr><td>Archive heading</td></tr>{weekly_row}</table>",
            **common
        )
        self.assertEqual(
            [row["source_record_id"] for row in first],
            [row["source_record_id"] for row in shifted]
        )
        self.assertEqual("2026-08-24", first[0]["source_record_id"])

    def test_retrieval_timestamp_requires_timezone_offset(self):
        with self.assertRaisesRegex(ValueError, "UTC offset"):
            extract_rows(
                "<table></table>", market="KALYAN", session_id="main",
                source_id="x", retrieved_at="2026-11-17T12:00:00"
            )

    def test_csv_output_round_trips_commas_and_quotes_in_raw_text(self):
        rows, _ = extract_rows(
            "<table><tr><td>24-08-2026 to 29-08-2026</td>"
            + "".join(f"<td>{cell}</td>" for cell in ["668 07 449"] * 6)
            + "</tr></table>",
            market="KALYAN", session_id="main", source_id="fix",
            retrieved_at="2026-11-17T12:00:00Z"
        )
        rows[0]["raw_text"] = '668 07 449, board says "published"'
        output = io.StringIO()
        write_csv(rows, output)
        parsed = list(csv.DictReader(io.StringIO(output.getvalue())))
        self.assertEqual(rows[0]["raw_text"], parsed[0]["raw_text"])

    def test_rejects_bad_retrieval_timestamp_and_market(self):
        with self.assertRaises(ValueError):
            extract_rows("<table></table>", market="UNKNOWN", session_id="main",
                         source_id="x", retrieved_at="2026-11-17T12:00:00Z")
        with self.assertRaises(ValueError):
            extract_rows("<table></table>", market="KALYAN", session_id="main",
                         source_id="x", retrieved_at="not-a-time")


if __name__ == "__main__":
    unittest.main()
