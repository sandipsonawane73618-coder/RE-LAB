#!/usr/bin/env python3
"""Chronological, per-market walk-forward evaluation of the RE-LAB ranking weights.

This is a descriptive historical test, not a probability model or betting advice.
Each target uses only earlier eligible rows from the same market/session.
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import Counter
from datetime import date
from pathlib import Path

MARKETS = (
    "SRIDEVI", "SRIDEVI_NIGHT", "KALYAN", "MAIN_BAZAR", "TIME_BAZAR",
    "MILAN_DAY", "MILAN_NIGHT", "RAJDHANI_DAY", "RAJDHANI_NIGHT",
)
SIDES = ("open", "close")


def family_sequence(panna: str) -> str:
    if len(panna) != 3 or not panna.isascii() or not panna.isdigit():
        raise ValueError(f"Invalid Panna: {panna}")
    return "".join(str(int(digit) % 5) for digit in panna)


def read_eligible(path: Path) -> dict[str, list[dict]]:
    grouped: dict[str, list[dict]] = {market: [] for market in MARKETS}
    with path.open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            market = row["market"].strip().upper()
            if market not in grouped:
                raise ValueError(f"Unknown market in eligible CSV: {market}")
            if row["status"].upper() != "PUBLISHED":
                raise ValueError("Eligible CSV contains a non-published row")
            result = row["result"].split("-")
            if len(result) != 3:
                raise ValueError(f"Invalid result for {market} {row['date']}")
            day = date.fromisoformat(row["date"])
            grouped[market].append({
                "date": day,
                "session_id": row["session_id"],
                "open": result[0],
                "close": result[2],
            })
    for market, rows in grouped.items():
        rows.sort(key=lambda row: (row["session_id"], row["date"]))
        seen = set()
        for row in rows:
            key = (row["session_id"], row["date"])
            if key in seen:
                raise ValueError(f"Duplicate eligible date: {market} {key}")
            seen.add(key)
    return grouped


def rank_pannas(history: list[dict], target_date: date, side: str, limit: int = 1000) -> list[tuple[str, float]]:
    values = [row[side] for row in history]
    families = [family_sequence(value) for value in values]
    exact_counts = Counter(values)
    family_counts = Counter(families)
    weekday_counts = Counter(
        families[index] for index, row in enumerate(history)
        if row["date"].weekday() == target_date.weekday()
    )
    transitions = Counter(zip(families, families[1:]))
    previous_state = families[-1]
    max_exact = max(exact_counts.values(), default=1)
    max_family = max(family_counts.values(), default=1)
    max_weekday = max(weekday_counts.values(), default=1)
    max_transition = max(transitions.values(), default=1)

    ranked = []
    for value in range(1000):
        panna = f"{value:03d}"
        family = family_sequence(panna)
        exact = exact_counts[panna]
        family_count = family_counts[family]
        weekday_count = weekday_counts[family]
        transition_count = transitions[(previous_state, family)]
        score = (
            0.30 * exact / max_exact
            + 0.35 * family_count / max_family
            + 0.20 * weekday_count / max_weekday
            + 0.15 * transition_count / max_transition
        ) * 100.0
        ranked.append((panna, score, family_count, exact))
    ranked.sort(key=lambda item: (-item[1], -item[2], -item[3], item[0]))
    return [(item[0], item[1]) for item in ranked[:limit]]


def rank_exact_frequency(history: list[dict], side: str, limit: int = 1000) -> list[str]:
    counts = Counter(row[side] for row in history)
    return sorted((f"{value:03d}" for value in range(1000)),
                  key=lambda panna: (-counts[panna], panna))[:limit]


def _metrics(hits: list[int], ranks: list[int], baseline_hits: list[int], baseline_ranks: list[int]) -> dict:
    total = len(hits)
    def hit_rate(k: int) -> float:
        return round(sum(rank > 0 and rank <= k for rank in ranks) / total, 6) if total else 0.0
    return {
        "tested_targets": total,
        "top1_hit_rate": hit_rate(1),
        "top3_hit_rate": hit_rate(3),
        "top5_hit_rate": hit_rate(5),
        "top10_hit_rate": hit_rate(10),
        "mrr_top10": round(sum(1.0 / rank for rank in ranks if 1 <= rank <= 10) / total, 6) if total else 0.0,
        "baseline_top1_hit_rate": round(sum(1 for rank in baseline_ranks if rank == 1) / total, 6) if total else 0.0,
        "baseline_top3_hit_rate": round(sum(1 for rank in baseline_ranks if 1 <= rank <= 3) / total, 6) if total else 0.0,
        "baseline_top5_hit_rate": round(sum(1 for rank in baseline_ranks if 1 <= rank <= 5) / total, 6) if total else 0.0,
        "baseline_top10_hit_rate": round(sum(1 for rank in baseline_ranks if 1 <= rank <= 10) / total, 6) if total else 0.0,
        "baseline_mrr_top10": round(sum(1.0 / rank for rank in baseline_ranks if 1 <= rank <= 10) / total, 6) if total else 0.0,
        "top10_delta_vs_exact_frequency": round(hit_rate(10) - (sum(1 for rank in baseline_ranks if 1 <= rank <= 10) / total if total else 0.0), 6),
    }


def evaluate_market(rows: list[dict], market: str, minimum_history: int = 30) -> dict:
    by_session: dict[str, list[dict]] = {}
    for row in rows:
        by_session.setdefault(row["session_id"], []).append(row)
    output = {}
    for session_id, session_rows in sorted(by_session.items()):
        session_rows.sort(key=lambda row: row["date"])
        for side in SIDES:
            history: list[dict] = []
            ranks: list[int] = []
            baseline_ranks: list[int] = []
            for target in session_rows:
                # Evaluate first, then append target: no same-day or future leakage.
                if len(history) >= minimum_history:
                    ranked = rank_pannas(history, target["date"], side, 1000)
                    baseline = rank_exact_frequency(history, side, 1000)
                    actual = target[side]
                    rank = next((index + 1 for index, item in enumerate(ranked) if item[0] == actual), 0)
                    baseline_rank = next((index + 1 for index, item in enumerate(baseline) if item == actual), 0)
                    ranks.append(rank)
                    baseline_ranks.append(baseline_rank)
                history.append(target)
            result = _metrics(ranks, ranks, [], baseline_ranks)
            result.update({
                "market": market,
                "session_id": session_id,
                "side": side,
                "minimum_history": minimum_history,
                "training_records_min": minimum_history if ranks else 0,
                "training_records_max": max(0, len(session_rows) - 1),
                "first_test_date": session_rows[minimum_history]["date"].isoformat() if len(session_rows) > minimum_history else None,
                "last_test_date": session_rows[-1]["date"].isoformat() if len(session_rows) > minimum_history else None,
            })
            output[f"{session_id}:{side}"] = result
    return output


def evaluate(path: Path, minimum_history: int = 30) -> dict:
    grouped = read_eligible(path)
    results = {}
    for market in MARKETS:
        results[market] = evaluate_market(grouped[market], market, minimum_history)
    combined = {}
    for side in SIDES:
        side_results = [
            value for market_results in results.values()
            for value in market_results.values() if value["side"] == side
        ]
        # Aggregate hits by target count, preserving per-market chronological evaluation.
        total = sum(item["tested_targets"] for item in side_results)
        def weighted(field: str) -> float:
            return round(sum(item[field] * item["tested_targets"] for item in side_results) / total, 6) if total else 0.0
        combined[side] = {
            "tested_targets": total,
            **{field: weighted(field) for field in (
                "top1_hit_rate", "top3_hit_rate", "top5_hit_rate", "top10_hit_rate",
                "mrr_top10", "baseline_top1_hit_rate", "baseline_top3_hit_rate",
                "baseline_top5_hit_rate", "baseline_top10_hit_rate", "baseline_mrr_top10",
                "top10_delta_vs_exact_frequency",
            )},
        }
    return {
        "input": str(path),
        "method": "chronological per-market/session walk-forward; prior rows only; RE-LAB weights 0.30 exact, 0.35 family, 0.20 same-weekday family, 0.15 previous-family transition",
        "minimum_history": minimum_history,
        "source_provenance": "single canonical publisher; not multi-source verified",
        "score_interpretation": "relative evidence index, not probability or guarantee",
        "baseline": "exact historical Panna frequency with deterministic lexical tie-break",
        "markets": results,
        "combined_by_side": combined,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("eligible_csv", type=Path)
    parser.add_argument("--minimum-history", type=int, default=30)
    parser.add_argument("--output", type=Path, default=Path("data/derived/walk_forward_report.json"))
    args = parser.parse_args()
    if args.minimum_history < 1:
        parser.error("--minimum-history must be at least 1")
    report = evaluate(args.eligible_csv, args.minimum_history)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["combined_by_side"], ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
