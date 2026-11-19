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

## 4. Build the eligible research set

Keep the full extracted observations CSV as the immutable raw-derived record. Generate a separate eligible set:

```bash
python scripts/audit_source_csv.py data/derived/canonical_observations.csv \
  --output data/derived/canonical_audit.json \
  --eligible-output data/derived/canonical_eligible_observations.csv
```

The eligible CSV contains only PUBLISHED rows with valid Panna/Jodi arithmetic and a unique market/session/date/source key. Every row under a duplicate key is excluded from the eligible set; no conflicting result is silently selected. The raw observations CSV remains unchanged for later reconciliation.

## 5. Chronological walk-forward evaluation

```bash
python scripts/walk_forward_backtest.py \
  data/derived/canonical_eligible_observations.csv \
  --minimum-history 30 \
  --output data/derived/walk_forward_report.json
```

The evaluator runs separately for every market, session and OPEN/CLOSE side. Each target is scored only against earlier rows. It reproduces the ranking weights (0.30 exact Panna, 0.35 family sequence, 0.20 same-weekday family, 0.15 previous-family transition) and compares them with exact historical-frequency ranking. Report Top-1/3/5/10, MRR, sample size and baseline deltas; scores are relative evidence indices, not probabilities or guarantees.

## 6. Evidence and interpretation rules

- Each market must have a real saved HTML capture and JSON sidecar.
- Every extracted row retains raw text, URL and retrieval timestamp.
- Duplicate keys, malformed values and source date-range mismatches stay visible in the audit.
- Calendar gaps remain unclassified unless a market-specific historical schedule is independently established.
- No market/session may borrow rows from another market/session.
- A parser test or an archive's advertised record count is not evidence that data was captured or validated.
- Do not describe descriptive walk-forward hit rates as a guaranteed prediction edge; compare them against the baseline and report the complete sample size.
