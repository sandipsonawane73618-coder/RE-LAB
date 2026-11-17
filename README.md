# RE-LAB

Offline-first Android research workspace for Sridevi and Kalyan chart history.

## Project boundaries

RE-LAB is an independent project. It must not read, modify, or depend on the user's Jarvis or Euronics repositories, apps, Firebase projects, databases, or configuration.

## Product goals

- Import and reconcile historical chart records with source provenance.
- Preserve raw source observations, including conflicting values, missing markers, holidays, and publication/update timestamps.
- Keep market/session identity explicit; never merge similarly named sessions without evidence.
- Derive and validate Panna, Jodi, digit-family, and descriptive pattern features.
- Run chronological walk-forward analysis without future-data leakage.
- Store data locally and support backup, restore, and export.
- Produce a complete Android APK and verify it with automated tests and on-device installation.

## Data integrity rules

- Never invent or silently repair chart results.
- Never treat `*`, `**`, or blank values as numeric results.
- Keep every source observation and its provenance; resolve conflicts only through explicit, auditable reconciliation.
- Scheduled session times and actual publication/update times are separate fields.
- A missing entry in one source is not proof that no result was published.
- Analysis is descriptive and experimental; no result is guaranteed or represented as a winning prediction.

## Digit-family mapping

| Family | Digits |
|---|---|
| F0 | 0, 5 |
| F1 | 1, 6 |
| F2 | 2, 7 |
| F3 | 3, 8 |
| F4 | 4, 9 |

The family code is `digit % 5`. Original digits and raw records must always be preserved separately from derived family features.

## Chart formats

- Sridevi: Monday–Sunday.
- Kalyan: Monday–Saturday.
- Record format: Open Panna (3 digits), Jodi (2 digits), Close Panna (3 digits).
- Preserve leading zeroes in Jodi values (for example, `07` and `00`).
- Panna categories: SP (all distinct), DP (exactly one pair), TP (all three equal).
- Reverse Panna is a separate descriptive feature (for example, `278` ↔ `872`).

## Mathematical validation

For a record with open Panna digits (a,b,c) and close Panna digits (x,y,z):

- `open_ank = (a+b+c) % 10`
- `close_ank = (x+y+z) % 10`
- `jodi = open_ank followed by close_ank` (two-character string)
- `open_jodi_family = (family(a)+family(b)+family(c)) % 5`
- `close_jodi_family = (family(x)+family(y)+family(z)) % 5`

Jodi family is mathematically derived from the Panna family sums; it must not be counted as an independent signal.

## Development principles

1. Evidence before claims: report only builds, tests, and device checks that actually ran.
2. Historical validation must be chronological and leakage-free.
3. Keep source observations immutable; derived features are reproducible.
4. Prefer a small, testable architecture over speculative features.
5. Do not publish or merge changes without explicit approval.
6. Do not add sample/demo chart results to production history.

## Planned structure

- `app/` — Android application.
- `core/` — domain models, parsing, validation, feature derivation, analysis.
- `data/` — local persistence, import/export, provenance and reconciliation.
- `docs/` — architecture, source register, data dictionary, research notes.
- `scripts/` — reproducible data validation utilities.
- `.github/workflows/` — CI and APK build.

The initial repository setup will be followed by a source and data audit before historical records are imported.
