# Historical source register

This register lists candidate public archives for source-observation collection. These are independent third-party chart pages, not official market feeds. A page's own archive count or "verified" label is not independent proof of correctness.

## Sridevi — main session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `hpofficial-sridevi-panel` | https://hpofficial.in/sridevi-panel-chart/ | Weekly Open Panna / Jodi / Close Panna table. The current-year page and cached page text can show different end dates; record the exact retrieved snapshot. | Preserve the full panel row and source capture time. Do not infer blank cells as holidays. |
| `hpofficial-sridevi-jodi` | https://hpofficial.in/sridevi-jodi-chart/ | Weekly Jodi table; useful as a second view of the same publisher's archive, not an independent publisher. | Compare Jodi against the panel's Panna digit sums. |
| `livesattamatka-sridevi-panel` | https://livesattamatka.org/panel-record/sridevi | Full panel chart, Monday–Sunday; recent rows are visible in the page text. | Keep as an independent source observation; reconcile against other publishers. |
| `matkaflash-sridevi` | https://matkaflash.com/sridevi?tab=history | Archive states 2,123 dated records from 4 Jan 2021. Its 17 Nov 2026 board snapshot displayed 577-99-667 while another current-year source displayed 115-73-229. | Preserve the dated board value and capture timestamp as an observation; do not choose a winner from page recency alone. |

## Kalyan — main evening session

| Source ID | URL | Coverage / notes | Collection rule |
|---|---|---|---|
| `fixresult-kalyan` | https://www.fixresult.in/kalyan-matka-chart.html | Weekly Mon–Sat table; archive overview states 1,221 records from 31 Oct 2022 through 24 Sep 2026. | Useful historical baseline; preserve missing cells and anomalous rows. |
| `matkaflash-kalyan` | https://matkaflash.com/kalyan | Archive states 1,240 dated records from 17 Oct 2022. A 16 Nov 2026 board snapshot displayed 390-24-257, which conflicts with other recent chart observations. | Keep the 16 Nov value as source-specific evidence; do not merge it into another date or session without reconciliation. |
| `kalyanchart-history` | https://kalyanchart.cyou/history/ | Separate history pages for Kalyan and other day/night sessions. Some archive views have shown stale repeated values. | Verify date and session identity row-by-row; repeated rows are not automatically daily results. |

## Session identity rules

- Sridevi main, Sridevi Day, Sridevi Morning, and Sridevi Night are separate sessions.
- Kalyan evening and Kalyan Night are separate sessions.
- A publisher's weekly table, Jodi table, and panel table are different views from the same publisher, not three independent sources.
- Preserve original page URL, capture/retrieval time, published time when displayed, exact raw row text, and parsed result separately.
- A missing cell, `—`, `*`, or `**` is not a numeric result. Do not convert it to 00 or a holiday without an explicit source statement.

## Known reconciliation examples to keep visible

- Sridevi 17 Nov 2026: Hira Panna's current-year result view reported `115-73-229`; MatkaFlash's board snapshot showed `577-99-667`. Treat these as conflicting source observations until exact date/session snapshots are verified. A cached/lagging archive may have stale date labels.
- Kalyan 24 Aug 2026: FixResult records `668-07-449`; this is mathematically valid because open sum is 20 and close sum is 17.
- Kalyan 16 Nov 2026: MatkaFlash displayed `390-24-257`; other archive observations have reported a different value. Keep both raw observations and check session/date before analysis.

## Data ingestion status

This register is a collection plan and reconciliation aid, **not proof that these archives have been downloaded into the app**. Only observations present in an imported source CSV are counted by the app's coverage audit or pattern analysis. Record the actual retrieval instant and do not backdate a current scrape to the chart date.
