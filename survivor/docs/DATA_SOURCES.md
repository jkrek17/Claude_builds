# Data sources

| Data | Source | Endpoint | Key needed | Refreshed by |
|---|---|---|---|---|
| Schedule, kickoff times, neutral sites, scores, game state | ESPN public site API | `site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?seasontype=2&week=N&dates=YYYY` | no | Refresh Odds / Refresh NFL Data |
| Spreads and moneylines (DraftKings), all 18 weeks | same scoreboard payload, `competitions[0].odds[0]` | as above | no | Refresh Odds / Refresh NFL Data |
| FPI game projections (home win %) | ESPN core API | `sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/{id}/competitions/{id}/predictor` | no | Refresh NFL Data |
| FPI team ratings and ranks | ESPN power index | `site.web.api.espn.com/apis/fitt/v3/sports/football/nfl/powerindex` | no | Refresh NFL Data |
| Consensus moneylines across US books | The Odds API v4 | `api.the-odds-api.com/v4/sports/americanfootball_nfl/odds?regions=us&markets=h2h` | yes (free tier: 500 requests/month) | any refresh when a key is saved |
| Injuries, starting QBs, weather | none automated | — | — | Weekly Inputs (points of spread) |
| Survivor pick popularity | none automated | — | — | Weekly Inputs (pick share %) |

## Freshness and staleness

Every refresh stamps the season with `scheduleFetchedAt`, `oddsFetchedAt`, `fpiFetchedAt` and, if used,
`consensusFetchedAt`. The Dashboard shows the age of each; when lines are more than 24 hours old the line turns
red with "Odds are stale". Each `MarketLine` and `FpiProjection` also carries its own fetch timestamp, and the
Weekly Inputs screen shows the same ages. Stale odds are never silently used: the timestamps are always visible.

## Raw data vs. model

Raw imports are stored as-is in the `Season` object (games with `line`, `consensus`, `fpi`; `ratings`), separate
from user state (`UserState`: picks, adjustments, settings) and from model output (`Evaluation`, which is
recomputed and never stored). The whole app state is one JSON file (`files/survivor_state.json`, ~100 KB).

## Proxies and limitations

- **Home field, rest, travel, divisional** effects are largely already in the market line. The extra Safety
  penalties are deliberately small and represent variance, not a second pricing.
- **FPI rating gap** is used only when neither a line nor a game projection exists (has not happened in
  practice for the 2026 season: all 272 games carry both).
- **Injuries / QB / weather** have no reliable free feed. They are manual point adjustments and are clearly
  labelled as such in the rankings.
- **Pick popularity** must be entered by hand from your pool's site or a public survivor-ownership page.
- ESPN's endpoints are public but undocumented. The parsers ignore unknown fields and fall back through
  `spread` → `pointSpread.home.close.line` → the `details` string, and `moneyline.*.close.odds` →
  `homeTeamOdds.moneyLine`, to be tolerant of small shape changes. Recorded fixtures from 2026-09-08 are in the
  test resources.
