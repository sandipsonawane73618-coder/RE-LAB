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
