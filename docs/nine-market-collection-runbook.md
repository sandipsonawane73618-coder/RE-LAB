# Nine-market canonical chart collection runbook

The selected canonical panel source for each of the nine market IDs is registered in [source-register.md](source-register.md). All selected pages currently use publisher ID `sattamatkajodi`; page URLs and market IDs remain distinct. These are historical source captures, not independently corroborated feeds.

## 1. Capture immutable source snapshots

From the repository root:

```bash
python scripts/capture_canonical_charts.py --output-dir data/captures
```

The command writes one timestamped HTML file and one JSON sidecar per successful market. Sidecars include the exact URL, retrieval instant, HTTP status, content type, byte count and SHA-256. Existing snapshots are never overwritten. Failed fetches are printed per market and cause a non-zero exit code.

Capture one market at a time with `--market MARKET_ID` when diagnosing a source.

## 2. Extract observations

For each captured HTML file, pass its market ID, session ID, publisher ID, capture timestamp and exact source URL to `scripts/extract_weekly_chart_html.py`. Save outputs as separate market CSV files; do not concatenate markets before verifying the market/session keys.

The extractor preserves raw cell text and marks unavailable, starred, pending or malformed cells without manufacturing numeric values. Weekly ranges may contain different numbers of weekday columns across historical periods; do not impose today's market schedule on older rows.

## 3. Audit imported data

After combining only the nine explicitly keyed CSVs:

```bash
python scripts/audit_source_csv.py data/import/nine_markets.csv --output data/audit/nine_market_audit.json
```

The audit reports row counts, date coverage, duplicate source/date keys, malformed statuses, Panna/Jodi arithmetic errors and unobserved calendar dates. Calendar gaps are deliberately labelled unclassified: a missing date is not automatically a missed market session or a holiday.

## 4. Evidence required before backtesting

Do not run or report validated prediction metrics until:
- each market has an actual saved HTML capture and sidecar;
- every extracted record retains raw text, URL and retrieval timestamp;
- duplicate keys and malformed values have been reviewed;
- each market's observed date span and unobserved dates are reported;
- the evaluation uses chronological expanding/rolling windows and never trains on the target date or later rows;
- Top-1/3/5/10, MRR, sample size and a simple historical-frequency baseline are reported per market and separately for open/close sides.

A parser test or an archive's advertised record count is not evidence that data was captured or validated.
