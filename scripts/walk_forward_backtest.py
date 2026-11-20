#!/usr/bin/env python3
"""Chronological, per-market walk-forward evaluation of the RE-LAB ranking weights.

This is a descriptive historical test, not a probability model or betting advice.
Each target uses only earlier eligible rows from the same market/session.
"""
from __future__ import annotations

import argparse
import csv
import heapq
import json
import re
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
            if row["status"].strip().upper() != "PUBLISHED":
                raise ValueError("Eligible CSV contains a non-published row")
            session_id = row["session_id"].strip()
            if not session_id:
                raise ValueError(f"Missing session ID for {market} {row['date']}")
            result = [part.strip() for part in row["result"].strip().split("-")]
            if (
                len(result) != 3
                or re.fullmatch(r"[0-9]{3}", result[0]) is None
                or re.fullmatch(r"[0-9]{2}", result[1]) is None
                or re.fullmatch(r"[0-9]{3}", result[2]) is None
            ):
                raise ValueError(
                    f"Invalid result for {market} {row['date']}: expected NNN-NN-NNN"
                )
            day = date.fromisoformat(row["date"])
            grouped[market].append({
                "date": day,
                "session_id": session_id,
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


CANDIDATES = tuple(f"{value:03d}" for value in range(1000))
CANDIDATE_FAMILIES = {panna: family_sequence(panna) for panna in CANDIDATES}


def rank_pannas(
    exact_counts: Counter, family_counts: Counter, weekday_counts: Counter,
    transitions: Counter, previous_state: str, actual: str, limit: int = 10,
    weights: tuple[float, float, float, float] = (0.30, 0.35, 0.20, 0.15),
    lexical_tiebreak: bool = False
) -> tuple[list[str], int]:
    max_exact = max(exact_counts.values(), default=1)
    max_family = max(family_counts.values(), default=1)
    max_weekday = max(weekday_counts.values(), default=1)
    max_transition = max(transitions.values(), default=1)
    scored = []
    actual_item = None
    for panna in CANDIDATES:
        family = CANDIDATE_FAMILIES[panna]
        exact = exact_counts[panna]
        family_count = family_counts[family]
        score = (
            weights[0] * exact / max_exact
            + weights[1] * family_count / max_family
            + weights[2] * weekday_counts[family] / max_weekday
            + weights[3] * transitions[(previous_state, family)] / max_transition
        ) * 100.0
        item = (panna, score, family_count, exact)
        scored.append(item)
        if panna == actual:
            actual_item = item

    sort_key = (
        (lambda item: (-item[1], item[0]))
        if lexical_tiebreak
        else (lambda item: (-item[1], -item[2], -item[3], item[0]))
    )
    top = heapq.nsmallest(limit, scored, key=sort_key)
    actual_rank = 1 + sum(sort_key(item) < sort_key(actual_item) for item in scored)
    return [item[0] for item in top], actual_rank


def rank_exact_frequency(exact_counts: Counter, actual: str) -> int:
    actual_count = exact_counts[actual]
    return 1 + sum(
        exact_counts[panna] > actual_count
        or (exact_counts[panna] == actual_count and panna < actual)
        for panna in CANDIDATES
    )


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
            exact_counts = Counter()
            family_counts = Counter()
            weekday_family_counts = {weekday: Counter() for weekday in range(7)}
            transitions = Counter()
            family_history: list[str] = []
            history_count = 0
            ranks: list[int] = []
            baseline_ranks: list[int] = []
            ablation_ranks: dict[str, list[int]] = {
                "exact_only": [], "family_only": [], "no_exact": [],
                "no_weekday": [], "no_transition": [],
            }
            ablation_weights = {
                "exact_only": (1.0, 0.0, 0.0, 0.0),
                "family_only": (0.0, 1.0, 0.0, 0.0),
                "no_exact": (0.0, 0.50, 0.30, 0.20),
                "no_weekday": (0.375, 0.4375, 0.0, 0.1875),
                "no_transition": (0.352941, 0.411765, 0.235294, 0.0),
            }
            for target in session_rows:
                # Evaluate first, then append target: no same-day or future leakage.
                if history_count >= minimum_history:
                    actual = target[side]
                    weekday_counts = weekday_family_counts[target["date"].weekday()]
                    _, rank = rank_pannas(
                        exact_counts, family_counts, weekday_counts,
                        transitions, family_history[-1], actual, 10
                    )
                    baseline_rank = rank_exact_frequency(exact_counts, actual)
                    ranks.append(rank)
                    baseline_ranks.append(baseline_rank)
                    for variant, weights in ablation_weights.items():
                        _, variant_rank = rank_pannas(
                            exact_counts, family_counts, weekday_counts,
                            transitions, family_history[-1], actual, 10,
                            weights=weights, lexical_tiebreak=True
                        )
                        ablation_ranks[variant].append(variant_rank)

                panna = target[side]
                family = family_sequence(panna)
                if family_history:
                    transitions[(family_history[-1], family)] += 1
                family_history.append(family)
                exact_counts[panna] += 1
                family_counts[family] += 1
                weekday_family_counts[target["date"].weekday()][family] += 1
                history_count += 1
            result = _metrics(ranks, ranks, [], baseline_ranks)
            result["feature_ablation_top10"] = {
                variant: {
                    "top10_hit_rate": round(
                        sum(1 for rank in variant_ranks if rank <= 10) / len(variant_ranks), 6
                    ) if variant_ranks else 0.0,
                    "mrr_top10": round(
                        sum(1.0 / rank for rank in variant_ranks if rank <= 10) / len(variant_ranks), 6
                    ) if variant_ranks else 0.0,
                    "tested_targets": len(variant_ranks),
                }
                for variant, variant_ranks in ablation_ranks.items()
            }
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
