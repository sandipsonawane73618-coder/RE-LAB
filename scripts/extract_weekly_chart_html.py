#!/usr/bin/env python3
"""Extract weekly Sridevi/Kalyan HTML chart tables into RE-LAB source-observation CSV.

This parser works on saved HTML snapshots. It never fetches a website and never
turns blank/marker cells into numeric results. Capture time must be supplied by
the caller so archive date and retrieval date cannot be confused.
"""
from __future__ import annotations

import argparse
import csv
import re
import sys
from datetime import date, datetime, timedelta
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable

CSV_FIELDS = [
    "market", "session_id", "date", "source_id", "retrieved_at",
    "raw_text", "status", "source_record_id", "source_url",
    "published_at", "result",
]


class TableRows(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[list[str]] = []
        self._row: list[str] | None = None
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        if tag == "tr":
            self._row = []
        elif tag in {"td", "th"} and self._row is not None:
            self._cell = []

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in {"td", "th"} and self._cell is not None and self._row is not None:
            self._row.append(" ".join("".join(self._cell).split()))
            self._cell = None
        elif tag == "tr" and self._row is not None:
            if self._row:
                self.rows.append(self._row)
            self._row = None
            self._cell = None


def _parse_date(value: str) -> date:
    for fmt in ("%d/%m/%y", "%d/%m/%Y", "%d-%m-%Y", "%d-%m-%y"):
        try:
            return datetime.strptime(value, fmt).date()
        except ValueError:
            pass
    raise ValueError(f"Unsupported date in weekly range: {value}")


def _week_range(value: str) -> tuple[date, date] | None:
    matches = re.findall(r"\d{1,2}[/-]\d{1,2}[/-]\d{2,4}", value)
    if len(matches) != 2:
        return None
    try:
        start, end = map(_parse_date, matches)
    except ValueError:
        return None
    if end < start:
        return None
    return start, end


def _cell_observation(cell: str) -> tuple[str, str]:
    """Return (status, normalized result); raw cell text is stored separately."""
    lowered = cell.casefold()
    digits = "".join(re.findall(r"\d", cell))
    if not digits:
        if any(marker in lowered for marker in ("holiday", "closed", "market off")):
            return "HOLIDAY_CLOSED", ""
        if any(marker in lowered for marker in ("pending", "awaiting", "not declared")):
            return "PENDING", ""
        return "MISSING_FROM_SOURCE", ""
    if len(digits) != 8:
        return "MALFORMED", ""
    open_panna, jodi, close_panna = digits[:3], digits[3:5], digits[5:]
    expected = f"{sum(map(int, open_panna)) % 10}{sum(map(int, close_panna)) % 10}"
    if jodi != expected:
        return "MALFORMED", ""
    return "PUBLISHED", f"{open_panna}-{jodi}-{close_panna}"


def extract_rows(
    html: str,
    *,
    market: str,
    session_id: str,
    source_id: str,
    retrieved_at: str,
    source_url: str = "",
) -> tuple[list[dict[str, str]], list[str]]:
    market = market.upper()
    if market not in {"SRIDEVI", "KALYAN"}:
        raise ValueError("market must be SRIDEVI or KALYAN")
    if not session_id.strip() or not source_id.strip():
        raise ValueError("session_id and source_id are required")
    # Validate the caller-provided capture instant; preserve its exact text in CSV.
    datetime.fromisoformat(retrieved_at.replace("Z", "+00:00"))

    parser = TableRows()
    parser.feed(html)
    output: list[dict[str, str]] = []
    issues: list[str] = []
    active_days = 7 if market == "SRIDEVI" else 6

    for row_number, cells in enumerate(parser.rows, start=1):
        if len(cells) < active_days + 1:
            continue
        week = _week_range(cells[0])
        if week is None:
            continue
        start, end = week
        if start.weekday() != 0 or (end - start).days != active_days - 1:
            issues.append(f"row {row_number}: week range does not match {market} trading days: {cells[0]}")
            continue
        if len(cells) != active_days + 1:
            issues.append(f"row {row_number}: expected {active_days} day cells, found {len(cells) - 1}")
            continue

        for offset, raw_cell in enumerate(cells[1:]):
            chart_date = start + timedelta(days=offset)
            status, result = _cell_observation(raw_cell)
            output.append({
                "market": market,
                "session_id": session_id,
                "date": chart_date.isoformat(),
                "source_id": source_id,
                "retrieved_at": retrieved_at,
                "raw_text": raw_cell,
                "status": status,
                "source_record_id": f"table-row-{row_number}-day-{offset + 1}",
                "source_url": source_url,
                "published_at": "",
                "result": result,
            })

    if not output:
        issues.append("no weekly chart rows were recognized; verify the saved HTML and table format")
    return output, issues


def write_csv(rows: Iterable[dict[str, str]], output) -> None:
    writer = csv.DictWriter(output, fieldnames=CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    writer.writerows(rows)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("html_file", type=Path)
    parser.add_argument("--market", required=True, choices=("SRIDEVI", "KALYAN"))
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--source-id", required=True)
    parser.add_argument("--retrieved-at", required=True, help="ISO-8601 instant, e.g. 2026-11-17T12:00:00+05:30")
    parser.add_argument("--source-url", default="")
    args = parser.parse_args()

    html = args.html_file.read_text(encoding="utf-8")
    rows, issues = extract_rows(
        html,
        market=args.market,
        session_id=args.session_id,
        source_id=args.source_id,
        retrieved_at=args.retrieved_at,
        source_url=args.source_url,
    )
    write_csv(rows, sys.stdout)
    for issue in issues:
        print(f"WARNING: {issue}", file=sys.stderr)
    return 0 if rows else 2


if __name__ == "__main__":
    raise SystemExit(main())
