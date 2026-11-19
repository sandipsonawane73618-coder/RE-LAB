import csv
import tempfile
import unittest
from pathlib import Path

import audit_source_csv


class AuditSourceCsvTests(unittest.TestCase):
    def test_audits_market_counts_gaps_duplicates_and_math(self):
        rows = [
            ["SRIDEVI", "main", "2026-01-05", "sattamatkajodi", "PUBLISHED", "123-65-456"],
            ["SRIDEVI", "main", "2026-01-05", "sattamatkajodi", "PUBLISHED", "123-65-456"],
            ["SRIDEVI", "main", "2026-01-07", "sattamatkajodi", "PUBLISHED", "123-00-456"],
            ["KALYAN", "main", "2026-01-05", "sattamatkajodi", "MISSING_FROM_SOURCE", ""],
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "observations.csv"
            with path.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["market", "session_id", "date", "source_id", "status", "result"])
                writer.writerows(rows)
            report = audit_source_csv.audit(path)

        sridevi = report["markets"]["SRIDEVI"]
        self.assertEqual(3, sridevi["rows"])
        self.assertEqual(2, sridevi["unique_dates"])
        self.assertEqual(1, sridevi["calendar_gaps_unclassified"])
        self.assertEqual(1, sridevi["duplicate_source_date_keys"])
        self.assertEqual(1, sridevi["published_invalid_panna_jodi_math"])
        self.assertEqual(1, report["markets"]["KALYAN"]["rows"])
        self.assertEqual(0, report["markets"]["SRIDEVI_NIGHT"]["rows"])

    def test_panna_jodi_math_preserves_leading_zeroes(self):
        self.assertTrue(audit_source_csv.valid_result("001-12-009"))
        self.assertFalse(audit_source_csv.valid_result("001-00-009"))
        self.assertFalse(audit_source_csv.valid_result("1-12-009"))


if __name__ == "__main__":
    unittest.main()
