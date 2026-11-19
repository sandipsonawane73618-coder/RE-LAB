#!/usr/bin/env python3
"""Audit imported RE-LAB source-observation CSVs without guessing market calendars.

Reports per-market date coverage, duplicate source/date keys, missing observation
dates inside the observed span, malformed statuses and invalid Panna/Jodi math.
A calendar gap is only an unobserved date; it is NOT labelled a market-day miss.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path

MARKETS = (
    "SRIDEVI", "SRIDEVI_NIGHT", "KALYAN", "MAIN_BAZAR", "TIME_BAZAR",
    "MILAN_DAY", "MILAN_NIGHT", "RAJDHANI_DAY", "RAJDHANI_NIGHT",
)


def valid_result(value: str) -> bool:
    parts = value.split("-")
    if len(parts) != 3:
        return False
    opened, jodi, closed = parts
    if len(opened) != 3 or len(jodi) != 2 or len(closed) != 3:
        return False
    if not all(part.isascii() and part.isdigit() for part in parts):
        return False
    expected = f"{sum(map(int, opened)) % 10}{sum(map(int, closed)) % 10}"
    return jodi == expected


def audit(path: Path) -> dict:
    with path.open(newline="", encoding="utf-8-sig") as handle:
        rows = list(csv.DictReader(handle))
    by_market = defaultdict(list)
    for row in rows:
        by_market[row.get("market", "").strip().upper()].append(row)

    report = {"input": str(path), "total_rows": len(rows), "markets": {}}
    for market in MARKETS:
        subset = by_market.get(market, [])
        dates = []
        malformed = 0
        invalid_math = 0
        published = 0
        statuses = Counter()
        keys = Counter()
        for row in subset:
            try:
                day = date.fromisoformat(row["date"])
                dates.append(day)
            except (KeyError, ValueError):
                malformed += 1
            statuses[row.get("status", "").upper()] += 1
            key = (row.get("session_id", ""), row.get("date", ""), row.get("source_id", ""))
            keys[key] += 1
            if row.get("status", "").upper() == "MALFORMED":
                malformed += 1
            if row.get("status", "").upper() == "PUBLISHED":
                published += 1
                if not valid_result(row.get("result", "")):
                    invalid_math += 1
                    malformed += 1
        unique_dates = sorted(set(dates))
        gaps = []
        if unique_dates:
            observed = set(unique_dates)
            cursor = unique_dates[0]
            while cursor <= unique_dates[-1]:
                if cursor not in observed:
                    gaps.append(cursor.isoformat())
                cursor += timedelta(days=1)
        duplicate_keys = [list(key) for key, count in keys.items() if count > 1]
        report["markets"][market] = {
            "rows": len(subset),
            "date_start": unique_dates[0].isoformat() if unique_dates else None,
            "date_end": unique_dates[-1].isoformat() if unique_dates else None,
            "unique_dates": len(unique_dates),
            "calendar_gaps_unclassified": len(gaps),
            "calendar_gap_dates_sample": gaps[:30],
            "duplicate_source_date_keys": len(duplicate_keys),
            "duplicate_key_samples": duplicate_keys[:20],
            "published_rows": published,
            "malformed_rows_or_values": malformed,
            "published_invalid_panna_jodi_math": invalid_math,
            "statuses": dict(sorted(statuses.items())),
        }
    unknown = sorted(set(by_market) - set(MARKETS))
    report["unknown_market_ids"] = unknown
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv_file", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = audit(args.csv_file)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)
    return 0 if not result["unknown_market_ids"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
