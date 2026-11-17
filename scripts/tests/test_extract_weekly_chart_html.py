import csv
import io
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from extract_weekly_chart_html import extract_rows, write_csv


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
