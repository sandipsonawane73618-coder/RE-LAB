# Source observation CSV import

The importer loads **source observations**, not final chart rows. It keeps source identity, session identity, retrieval/publication timestamps, and the raw text. Conflicting sources remain separate observations and must pass through source reconciliation before a chart row is analyzed.

## Required headers

`market,session_id,date,source_id,retrieved_at,raw_text,status`

Optional headers:

`source_record_id,source_url,published_at,result`

- `market`: `SRIDEVI` or `KALYAN`
- `session_id`: stable identity such as `main` or `day`; never merge distinct sessions
- `date`: ISO date, `YYYY-MM-DD`
- `retrieved_at` and optional `published_at`: ISO-8601 instants, preferably with UTC offset/Z
- `status`: `PUBLISHED`, `PENDING`, `HOLIDAY_CLOSED`, `MISSING_FROM_SOURCE`, or `MALFORMED`
- `raw_text`: exact source text as captured; quoted CSV fields support commas, quotes, and embedded newlines
- `result`: optional normalized `OPN-JJ-CLN` form, e.g. `277-65-249`

A published result is accepted only if its three-digit Pannas and two-digit Jodi are structurally valid and the Jodi matches both Panna digit sums. Invalid published rows are retained as `MALFORMED` observations with their raw text; they are not silently corrected. Non-published statuses never become chart results.

Rows that cannot be parsed (for example, invalid dates or timestamps) are reported as row-numbered import issues. Valid rows in the same file are still returned.

## Example

```csv
market,session_id,date,source_id,retrieved_at,raw_text,status,source_record_id,source_url,published_at,result
SRIDEVI,main,2026-11-09,archive-a,2026-11-09T08:00:00Z,"277-65-249",PUBLISHED,row-1,https://example.test/archive,2026-11-09T08:01:00Z,277-65-249
SRIDEVI,main,2026-11-10,archive-a,2026-11-10T08:00:00Z,"Result not posted",PENDING,row-2,https://example.test/archive,,
```

This schema does not imply that any historical dataset has already been imported. Source collection and reconciliation remain separate, auditable steps.
