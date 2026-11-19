#!/usr/bin/env python3
"""Capture the nine selected canonical panel-chart pages as immutable HTML snapshots.

Run from the repository root:
  python scripts/capture_canonical_charts.py --output-dir data/captures

Existing files are never overwritten. Each snapshot gets a JSON sidecar containing
its exact URL, retrieval instant, HTTP metadata and SHA-256 digest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

SOURCES = {
    "SRIDEVI": "https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php",
    "SRIDEVI_NIGHT": "https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php",
    "KALYAN": "https://sattamatkajodi.com/record/kalyan-penal-chart.php",
    "MAIN_BAZAR": "https://sattamatkajodi.com/main-bazar-panel-chart.php",
    "TIME_BAZAR": "https://sattamatkajodi.com/time-bazar-panel-chart.php",
    "MILAN_DAY": "https://sattamatkajodi.com/record/milan-day-penal-chart.php",
    "MILAN_NIGHT": "https://sattamatkajodi.com/record/milan-night-penal-chart.php",
    "RAJDHANI_DAY": "https://sattamatkajodi.com/record/rajdhani-day-penal-chart.php",
    "RAJDHANI_NIGHT": "https://sattamatkajodi.com/record/rajdhani-night-penal-chart.php",
}


def capture(market: str, url: str, output_dir: Path, timeout: int) -> dict:
    request = Request(url, headers={"User-Agent": "RE-LAB historical research snapshot/1.0"})
    retrieved_at = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read()
            status = response.status
            final_url = response.geturl()
            content_type = response.headers.get("Content-Type", "")
    except (HTTPError, URLError, TimeoutError) as exc:
        return {"market": market, "url": url, "status": "FETCH_FAILED", "error": str(exc)}

    if status != 200 or not body:
        return {"market": market, "url": url, "status": "INVALID_HTTP_RESPONSE", "http_status": status}

    folder = output_dir / market
    folder.mkdir(parents=True, exist_ok=True)
    stamp = datetime.fromisoformat(retrieved_at).strftime("%Y%m%dT%H%M%S%z")
    html_path = folder / f"{market}_{stamp}.html"
    meta_path = html_path.with_suffix(".json")
    # Exclusive create: never silently replace an earlier evidence snapshot.
    try:
        with html_path.open("xb") as handle:
            handle.write(body)
    except FileExistsError:
        return {"market": market, "url": url, "status": "COLLISION_NOT_OVERWRITTEN"}

    metadata = {
        "market": market,
        "source_id": "sattamatkajodi",
        "source_url": url,
        "final_url": final_url,
        "retrieved_at": retrieved_at,
        "http_status": status,
        "content_type": content_type,
        "bytes": len(body),
        "sha256": hashlib.sha256(body).hexdigest(),
        "html_file": str(html_path),
    }
    with meta_path.open("x", encoding="utf-8") as handle:
        json.dump(metadata, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return {"market": market, "status": "CAPTURED", **metadata}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("data/captures"))
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--market", choices=sorted(SOURCES), action="append")
    args = parser.parse_args()
    selected = args.market or list(SOURCES)
    results = [capture(market, SOURCES[market], args.output_dir, args.timeout) for market in selected]
    for result in results:
        print(json.dumps(result, ensure_ascii=False))
    return 0 if all(item["status"] == "CAPTURED" for item in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
