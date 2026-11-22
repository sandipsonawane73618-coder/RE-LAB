#!/usr/bin/env python3
"""Extract the newest immutable capture from both publishers for every canonical market."""
from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

from extract_weekly_chart_html import CSV_FIELDS, extract_rows

MARKETS = (
    "SRIDEVI", "SRIDEVI_NIGHT", "KALYAN", "MAIN_BAZAR", "TIME_BAZAR",
    "MILAN_DAY", "MILAN_NIGHT", "RAJDHANI_DAY", "RAJDHANI_NIGHT",
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--capture-dir", type=Path, default=Path("data/captures"))
    parser.add_argument("--secondary-capture-dir", type=Path, default=Path("data/captures-secondary"))
    parser.add_argument("--output", type=Path, default=Path("data/derived/canonical_observations.csv"))
    parser.add_argument("--summary", type=Path, default=Path("data/derived/canonical_extraction_summary.json"))
    args = parser.parse_args()

    publishers = {
        "sattamatkajodi": args.capture_dir,
        "sattamatkame": args.secondary_capture_dir,
    }
    combined: list[dict[str, str]] = []
    report: dict[str, dict] = {
        market: {"capture": "OK", "sources": {}, "rows": 0, "published": 0,
                 "missing_or_closed": 0, "pending": 0, "malformed": 0, "issues": []}
        for market in MARKETS
    }
    failures: list[str] = []

    for source_id, capture_dir in publishers.items():
        for market in MARKETS:
            sidecars = sorted((capture_dir / market).glob("*.json"))
            if not sidecars:
                failures.append(f"{source_id}/{market}: no capture metadata found")
                report[market]["capture"] = "INCOMPLETE"
                report[market]["sources"][source_id] = {
                    "capture": "MISSING", "rows": 0, "published": 0,
                    "issues": ["no capture metadata"],
                }
                continue

            metadata_path = max(sidecars, key=lambda p: p.name)
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            if metadata.get("source_id") != source_id:
                failures.append(f"{source_id}/{market}: source_id mismatch in {metadata_path.name}")
                report[market]["capture"] = "INCOMPLETE"
                continue
            html_path = metadata_path.with_suffix(".html")
            if not html_path.is_file():
                failures.append(f"{source_id}/{market}: HTML file missing for {metadata_path.name}")
                report[market]["capture"] = "INCOMPLETE"
                report[market]["sources"][source_id] = {
                    "capture": "HTML_MISSING", "rows": 0, "published": 0,
                    "issues": ["HTML file missing"],
                }
                continue

            html = html_path.read_text(encoding="utf-8")
            rows, issues = extract_rows(
                html,
                market=market,
                session_id="main",
                source_id=source_id,
                retrieved_at=metadata["retrieved_at"],
                source_url=metadata["source_url"],
            )
            combined.extend(rows)
            published = sum(row["status"] == "PUBLISHED" for row in rows)
            source_report = {
                "capture": "OK",
                "retrieved_at": metadata["retrieved_at"],
                "html_bytes": metadata["bytes"],
                "sha256": metadata["sha256"],
                "rows": len(rows),
                "published": published,
                "missing_or_closed": sum(row["status"] in {"MISSING_FROM_SOURCE", "HOLIDAY_CLOSED"} for row in rows),
                "pending": sum(row["status"] == "PENDING" for row in rows),
                "malformed": sum(row["status"] == "MALFORMED" for row in rows),
                "issues": issues,
            }
            report[market]["sources"][source_id] = source_report
            for key in ("rows", "published", "missing_or_closed", "pending", "malformed"):
                report[market][key] += source_report[key]
            report[market]["issues"].extend(f"{source_id}: {issue}" for issue in issues)
            if not rows:
                failures.append(f"{source_id}/{market}: extractor recognized no weekly rows")
            if issues:
                print(f"WARNING {source_id}/{market}: " + "; ".join(issues), file=sys.stderr)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(combined)

    args.summary.parent.mkdir(parents=True, exist_ok=True)
    summary = {
        "publishers_expected": sorted(publishers),
        "markets_expected": len(MARKETS),
        "markets_with_rows": sum(bool(v.get("rows")) for v in report.values()),
        "markets_with_both_publishers": sum(
            all(v.get("sources", {}).get(source, {}).get("rows", 0) > 0 for source in publishers)
            for v in report.values()
        ),
        "total_observations": len(combined),
        "total_published": sum(row["status"] == "PUBLISHED" for row in combined),
        "total_malformed": sum(row["status"] == "MALFORMED" for row in combined),
        "publishers": {
            source: {
                "total_published": sum(
                    value.get("sources", {}).get(source, {}).get("published", 0)
                    for value in report.values()
                ),
                "markets_with_rows": sum(
                    value.get("sources", {}).get(source, {}).get("rows", 0) > 0
                    for value in report.values()
                ),
            }
            for source in publishers
        },
        "markets": report,
        "failures": failures,
    }
    args.summary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 2 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
