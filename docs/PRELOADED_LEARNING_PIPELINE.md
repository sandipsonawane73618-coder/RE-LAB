# RE-LAB historical preload and incremental update

## Build a preloaded APK

The audited source-observation ledger is supplied as
`canonical_observations.csv` by the two-publisher capture workflow. It uses the
RE-LAB source-observation schema (`market`, `session_id`, `date`, publisher
identity, status and result). The build validates the required columns and
packages the exact CSV with a SHA-256 manifest.

Build a preloaded debug APK with:

```bash
./gradlew :app:assembleDebug -PrelabPreloadedCsv=/absolute/path/canonical_observations.csv
```

A release build refuses to proceed without a preload file:

```bash
./gradlew :app:assembleRelease -PrelabPreloadedCsv=/absolute/path/canonical_observations.csv
```

The app verifies the manifest, parses every row, checks published coverage for
all nine markets and at least 30 published observations per market/session,
then merges the dataset into SQLite on first launch. No network request is
needed for the initial seed.

## Preservation and live updates

- Preload installation is checksum-idempotent.
- It appends/merges; it never replaces existing observations or prediction history.
- The seed checksum is stored transactionally in SQLite.
- Later CSV/chart imports remain append-only incremental updates.
- Frozen prediction snapshots and actual outcomes remain separate, first-write records.
- Explicitly clearing the dataset disables automatic preload reseeding.
- Live updates must process only newly appended observations; do not retrain or
  replay the full historical dataset on every update.

## What this pipeline does not claim

The current RE-LAB models are deterministic evidence/ranking and walk-forward
evaluators. PredictionSelfLearningEngine audits frozen outcomes;
ControlledSelfLearningEngine creates review-only challenger proposals and does
not silently alter production weights.

The historical metrics below are ranking hit-rate diagnostics, not calibrated
probabilities, guaranteed outcomes, or proof of a profitable strategy. A
challenger rollout still requires separate out-of-sample evidence and explicit
review.

## Two-publisher source audit (2026-11-22)

The capture workflow verified both independent publisher IDs:
`sattamatkajodi` and `sattamatkame`. Both publishers had rows for all nine
configured markets.

- Total source observations: 52,597
- Published observations: 49,302 (24,532 from sattamatkajodi; 24,770 from sattamatkame)
- Malformed observations: 571
- Missing/closed observations: 2,724
- Independent two-publisher consensus groups: 24,357
- Conflicting groups excluded: 87
- Single-source-only groups excluded: 258
- Future-dated rows: 0
- Invalid rows / missing required columns: 0

Only groups where both publishers supplied one identical published result were
used for historical evaluation. Conflict groups were excluded without selecting
a winner; single-source groups were also excluded. The capture quality gate
checked a minimum of 30 published rows per publisher/market/session and at
least 30 consensus dates per configured market.

## Historical walk-forward results

The successful PR #6 workflow run #1397 (commit `ed60c82`) generated the
`relab-historical-learning-results` artifact from the exact two-publisher
capture artifact.

- Source records used: 24,357 consensus records.
- Panna/Half-Sangam/Sangam report: 180 rows (nine markets × 20 metric families),
  with 3,285 scored targets per metric family.
- Multi-model report: 108 rows (nine markets × three channels × four model
  outputs), evaluating Open Panna, Close Panna and Jodi over the
  2025-11-23 through 2026-11-22 target window.
- The walk-forward evaluators use only earlier same-market/session records for
  each target; the target outcome is scored before it can enter later history.

Selected aggregate diagnostics across the nine markets:

| Channel / metric | Scored targets | Top-k hits | Rate |
|---|---:|---:|---:|
| Open Panna, top 10 | 3,285 | 197 | 6.00% |
| Close Panna, top 10 | 3,285 | 199 | 6.06% |
| Open Panna, top 100 | 3,285 | 1,364 | 41.52% |
| Close Panna, top 100 | 3,285 | 1,340 | 40.79% |
| Half-Sangam Open Panna → Close Ank, top 100 hybrid | 3,285 | 197 | 6.00% |
| Half-Sangam Open Ank → Close Panna, top 100 hybrid | 3,285 | 199 | 6.06% |
| Sangam 100×100 hybrid, top 100 | 3,285 | 15 | 0.46% |

For the independent multi-model evaluator, aggregate Top-5 rates were:
Open Panna: exact-frequency 2.59%, previous-result-transition 3.18%,
recency-weighted-frequency 1.97%, smart-consensus 1.65%;
Close Panna: 3.02%, 3.06%, 2.13%, 1.77%, respectively;
Jodi: 5.49%, 4.56%, 5.65%, 3.88%, respectively.
These rates are diagnostics over the scored targets; they are not probabilities.

## Verification status at commit ed60c82

- GitHub Actions runs #1396 (push) and #1397 (pull request): successful.
- Core + app unit-test XML artifact: 379 tests, 0 failures, 0 errors, 0 skipped.
- Historical backtest job: successful; both metrics CSVs and both source audit
  files were uploaded.
- Android Debug APK job: successful, but that generic CI APK was built without
  the historical CSV property. It is not the preloaded APK.
- Android instrumentation job: skipped by workflow conditions; SQLite/preload
  instrumentation tests are not yet verified on an emulator.

The next required step is to package the exact audited CSV into a preloaded APK,
then run the SQLite preload instrumentation tests and verify the packaged
checksum/count on-device. Do not treat the generic Debug APK as completion of
that step, and do not merge this Draft PR without explicit approval.
