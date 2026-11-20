# Independent publisher discovery — 2026-11-20

## Scope

All nine RE-LAB market streams are in scope. This is a discovery manifest, not an assertion that every domain is an independent publisher or that any row has already been imported. Search-result snapshots are leads only; capture the actual HTML, timestamp, final URL, and SHA-256 before parsing.

## Candidate coverage matrix

| Market ID | Candidate archive domains surfaced | Evidence / caution |
|---|---|---|
| `SRIDEVI` | `sattamatka.global`, `sattamatkaopen.com`, `sattamatkahere.com`, `simransattamatka.com`, `sattamatka.email`, `kalyanmatka.site` | Search-indexed pages show Sridevi results/charts; exact dated historical rows and ownership still need verification. |
| `SRIDEVI_NIGHT` | same six domains | Several pages list Sridevi Night separately; do not merge with Sridevi main. |
| `KALYAN` | same six domains | Kalyan appears as a distinct day/evening market; keep separate from Kalyan Night and Kalyan Morning. |
| `MAIN_BAZAR` | same six domains | Main Bazar appears on all-market indexes; confirm whether a page is day or night before importing. |
| `TIME_BAZAR` | same six domains | Time Bazar is listed separately; verify the exact session label/date on each archive. |
| `MILAN_DAY` | same six domains | Milan Day is separately listed on the surfaced indexes. |
| `MILAN_NIGHT` | same six domains | Milan Night is separately listed; never combine with Milan Day. |
| `RAJDHANI_DAY` | same six domains | Rajdhani Day is separately listed on the surfaced indexes. |
| `RAJDHANI_NIGHT` | same six domains | Rajdhani Night is separately listed; never combine with Rajdhani Day. |

Candidate pages:
- https://sattamatka.global/
- https://sattamatkaopen.com/
- https://sattamatkahere.com/
- https://www.simransattamatka.com/
- https://sattamatka.email/sridevi-matka-chart
- https://www.kalyanmatka.site/

## Publisher independence is NOT yet established

Different hostnames do not prove different editorial/data origins. The surfaced 20 Nov 2026 snippets repeat several identical result strings across domains (for example Sridevi 590-47-566, Time Bazar 240-62-390, Milan Night 479-02-246, Rajdhani Night 466-62-679 and Main Bazar 159-50-370). That can indicate shared upstream feeds, syndication, or copying. Until ownership/editorial provenance is checked, assign provisional source IDs such as `candidate:sattamatka.global`; do not count these as independent corroborators in the published consensus.

## Import gate for each candidate × market × session

1. Capture the exact page response as immutable HTML with retrieval timestamp, final URL, HTTP status, content type, byte count, and SHA-256. Never overwrite earlier captures.
2. Preserve the publisher's displayed draw date and session label independently from retrieval time. Reject undated current-result cards from historical imports.
3. Keep exact raw row text and parsed `OPN-JJ-CLN` separately. Preserve pending, starred, missing, holiday, and malformed values as non-results; never coerce them to zero.
4. Verify Panna/Jodi arithmetic and date/session alignment. Do not merge Morning, Day, main/evening, or Night variants.
5. Compare candidate captures on matching market + session + draw date. Record agreement, conflict, ambiguity, and missing coverage.
6. Promote a candidate to an independent publisher only after evidence of distinct editorial/operator ownership or independently maintained archive provenance. Same upstream feed, mirror, syndicated copy, or common operator means one publisher identity.
7. Only after this gate, add verified publisher IDs to the canonical source register and include their observations in consensus and backtests.

## Current status

- Canonical baseline: nine streams from `sattamatkajodi` only.
- Candidate discovery: six domains surfaced for all nine streams; archive depth and row-level date coverage are not yet verified.
- Independent publisher count: **not yet verified** for every candidate. Do not report six independent sources.
- No candidate observations are promoted into the eligible research dataset by this discovery note alone.
- Next engineering step: add a manifest-driven multi-publisher capture/import path that stores candidates in a separate raw observation set, then run the existing source reconciliation and independence audit without modifying the canonical baseline.
