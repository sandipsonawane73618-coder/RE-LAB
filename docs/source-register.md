# Historical source register

This register lists candidate public archives for source-observation collection. These are independent third-party chart pages, not official market feeds. A page's own archive count or "verified" label is not independent proof of correctness.

## Sridevi — main session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `hpofficial` | https://hpofficial.in/sridevi-panel-chart/ | Weekly Open Panna / Jodi / Close Panna table. The current-year page and cached page text can show different end dates; record the exact retrieved snapshot. | Preserve the full panel row and source capture time. Do not infer blank cells as holidays. |
| `hpofficial` | https://hpofficial.in/sridevi-jodi-chart/ | Weekly Jodi table; a second view of the same publisher's archive, not an independent publisher. | Reuse source ID `hpofficial`; compare Jodi against the panel's Panna digit sums. |
| `livesattamatka` | https://livesattamatka.org/panel-record/sridevi | Full panel chart, Monday–Sunday; recent rows are visible in the page text. | Keep as an independent source observation; reconcile against other publishers. |
| `matkaflash` | https://matkaflash.com/sridevi?tab=history | Archive states 2,123 dated records from 4 Jan 2021. Its 17 Nov 2026 board snapshot displayed 577-99-667 while another current-year source displayed 115-73-229. | Preserve the dated board value and capture timestamp as an observation; do not choose a winner from page recency alone. |

## Kalyan — main evening session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `fixresult` | https://sattamatkajodi.com/record/kalyan-penal-chart.php | Weekly Mon–Sat table; archive overview states 1,221 records from 31 Oct 2022 through 24 Sep 2026. | Useful historical baseline; preserve missing cells and anomalous rows. |
| `matkaflash` | https://matkaflash.com/kalyan | Archive states 1,240 dated records from 17 Oct 2022. A 16 Nov 2026 board snapshot displayed 390-24-257, which conflicts with other recent chart observations. | Keep the 16 Nov value as source-specific evidence; do not merge it into another date or session without reconciliation. |
| `kalyanchart` | https://kalyanchart.cyou/history/ | Separate history pages for Kalyan and other day/night sessions. Some archive views have shown stale repeated values. | Verify date and session identity row-by-row; repeated rows are not automatically daily results. |

## Session identity rules

- Sridevi main, Sridevi Day, Sridevi Morning, and Sridevi Night are separate sessions.
- Kalyan evening and Kalyan Night are separate sessions.
- A publisher's weekly table, Jodi table, and panel table are different views from the same publisher, not three independent sources.
- `source_id` is the canonical publisher identity, not a page identity. Reuse one `source_id` for every page/view owned by the same publisher; distinguish the exact page with `source_url`. Independent-source consensus counts distinct publisher IDs only.
- In particular, Hira Panna Official's Sridevi panel and Jodi pages both use `source_id=hpofficial`; they must never count as two independent publishers.
- MatkaFlash Sridevi and Kalyan pages both use `source_id=matkaflash`; market/session fields keep their records separate.
- The same canonical rule applies to `livesattamatka`, `fixresult`, and `kalyanchart`; do not add market or page suffixes to a publisher ID.
- Preserve original page URL, capture/retrieval time, published time when displayed, exact raw row text, and parsed result separately.
- A missing cell, `—`, `*`, or `**` is not a numeric result. Do not convert it to 00 or a holiday without an explicit source statement.

## Known reconciliation examples to keep visible

- Sridevi 17 Nov 2026: Hira Panna's current-year result view reported `115-73-229`; MatkaFlash's board snapshot showed `577-99-667`. Treat these as conflicting source observations until exact date/session snapshots are verified. A cached/lagging archive may have stale date labels.
- Kalyan 24 Aug 2026: FixResult records `668-07-449`; this is mathematically valid because open sum is 20 and close sum is 17.
- Kalyan 16 Nov 2026: MatkaFlash displayed `390-24-257`; other archive observations have reported a different value. Keep both raw observations and check session/date before analysis.

## Data ingestion status

This register is a collection plan and reconciliation aid, **not proof that these archives have been downloaded into the app**. Only observations present in an imported source CSV are counted by the app's coverage audit or pattern analysis. Record the actual retrieval instant and do not backdate a current scrape to the chart date.


## Nine-market canonical collection scope

The application scope is nine independent chart streams. This section records the selected canonical panel page for each stream; it is a collection plan, not proof of a completed download. Do not combine values across these markets or day/night sessions.

| Market ID | Canonical panel page | Publisher ID | Weekly layout |
|---|---|---|---|
| `SRIDEVI` | https://sattamatkajodi.com/record/sridevi-satta-penal-chart.php | `matkaflash` | Daily/session-specific; confirm actual table layout before import |
| `SRIDEVI_NIGHT` | https://sattamatkajodi.com/record/sridevi-night-satta-penal-chart.php | `sattamatkajodi` | Confirm page layout and active days from captured rows |
| `KALYAN` | https://www.fixresult.in/kalyan-matka-chart.html | `fixresult` | Monday–Saturday |
| `MAIN_BAZAR` | https://sattamatkajodi.com/main-bazar-panel-chart.php | `sattamatkajodi` | Monday–Friday |
| `TIME_BAZAR` | https://sattamatkajodi.com/time-bazar-panel-chart.php | `sattamatkajodi` | Daily |
| `MILAN_DAY` | https://sattamatkajodi.com/record/milan-day-penal-chart.php | `sattamatkajodi` | Monday–Sunday |
| `MILAN_NIGHT` | https://sattamatkajodi.com/record/milan-night-penal-chart.php | `sattamatkajodi` | Monday–Saturday |
| `RAJDHANI_DAY` | https://sattamatkajodi.com/satta-matka-chart.php (Rajdhani Day Panel Chart index entry) | `sattamatkajodi` | Monday–Saturday |
| `RAJDHANI_NIGHT` | https://sattamatkajodi.com/record/rajdhani-night-penal-chart.php | `sattamatkajodi` | Monday–Friday |

All page views from the same publisher reuse the same `source_id`; a publisher is not counted as independent corroboration merely because it exposes multiple market pages. For each market, capture the exact final page URL, timestamp, raw row text, and source revision before importing. The weekly extractor's layout checks must be confirmed against real saved HTML for every market; a passing parser run alone does not establish chart correctness.

The chart publisher's all-market index lists Sridevi Day/Night, Milan Day/Night, Rajdhani Day/Night, Main Bazar, and Time Bazar as separate panel-chart entries. See https://sattamatkajodi.com/satta-matka-chart.php. The market's advertised weekly schedule is only a parser expectation: missing cells remain missing unless the source explicitly marks a holiday.
