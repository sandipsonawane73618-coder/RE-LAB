# RE-LAB historical preload and incremental update

## Build a preloaded APK

Export the canonical source-observation ledger from RE-LAB as CSV. The file must use
the exact SourceCsvExporter format and contain all nine enabled markets, with at
least 30 valid PUBLISHED observations for every market/session pair. Conflicting,
malformed, or incomplete rows must be reconciled before packaging.

Build the APK with:

```bash
./gradlew :app:assembleDebug -PrelabPreloadedCsv=/absolute/path/source-observations.csv
```

A release build refuses to proceed without a preload file:

```bash
./gradlew :app:assembleRelease -PrelabPreloadedCsv=/absolute/path/source-observations.csv
```

The build task copies the CSV into APK assets and writes a SHA-256 manifest.
The app validates the manifest, parses every row, checks all nine markets and
minimum per-session history, then merges the data into SQLite on first launch.
No network request is needed for this initial seed.

## Preservation and live updates

- Preload installation is checksum-idempotent.
- It appends/merges; it never replaces existing observations or prediction history.
- The seed checksum is stored transactionally in SQLite.
- Later CSV/chart imports remain append-only incremental updates.
- Frozen prediction snapshots and actual outcomes remain separate, first-write records.
- The dashboard analysis cache is keyed by the full observation dataset and target date,
  so new observations invalidate only the affected analysis generation.
- Explicitly clearing the dataset disables automatic preload reseeding.

## What this pipeline does not claim

The current RE-LAB models are deterministic evidence/ranking and walk-forward evaluators.
PredictionSelfLearningEngine currently audits frozen outcomes; ControlledSelfLearningEngine
creates review-only challenger proposals and does not silently alter production weights.
The preload package seeds historical chart knowledge and makes historical evaluations
available immediately; it does not turn unverified hit rates into calibrated probabilities
or automatically approve new model weights. Any challenger rollout still requires
out-of-sample evidence and explicit review.

## Latest public capture audit (2026-11-22)

The one-time nine-market capture completed successfully and produced 26,199 raw
source observations: 24,532 PUBLISHED, 1,357 MISSING_FROM_SOURCE, and 310
MALFORMED. All nine markets have substantially more than the 30-row minimum.
The dataset spans 2012–2026, contains no future-dated observations relative to
capture time, and passes the Panna/Jodi checksum validation.

This capture currently has one publisher (`sattamatkajodi`), so published rows
are **single-source unverified**, not independent consensus. The audit found 81
market/session/date conflict groups caused by multiple distinct published
values for the same date. These conflicts are preserved as raw evidence and
must be excluded from historical training/backtests; they must never be
resolved by choosing the newest row or by majority vote from the same source.
The conflict-free dates may be used only as exploratory single-source history
until an independent source confirms them. Missing and malformed rows are
retained for audit but are not training outcomes.

The capture artifact includes immutable HTML snapshots, per-page URL/retrieval
metadata and SHA-256 hashes, the extracted CSV, and a quality summary. CI keeps
this artifact for 90 days. A release APK must package the exact audited CSV
using the documented Gradle property; a debug APK without that property is
not evidence that the historical dataset was included.
