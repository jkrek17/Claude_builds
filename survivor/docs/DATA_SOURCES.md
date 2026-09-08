# Data sources

| Data | Source | Endpoint | Key needed | Refreshed by |
|---|---|---|---|---|
| Schedule, kickoff times, neutral sites, scores, game state | ESPN public site API | `site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard?seasontype=2&week=N&dates=YYYY` | no | Refresh Odds / Refresh NFL Data |
| Spreads and moneylines (DraftKings), all 18 weeks | same scoreboard payload, `competitions[0].odds[0]` | as above | no | Refresh Odds / Refresh NFL Data |
| FPI game projections (home win %) | ESPN core API | `sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/{id}/competitions/{id}/predictor` | no | Refresh NFL Data |
| FPI team ratings and ranks | ESPN power index | `site.web.api.espn.com/apis/fitt/v3/sports/football/nfl/powerindex` | no | Refresh NFL Data |
| Consensus moneylines across US books | The Odds API v4 | `api.the-odds-api.com/v4/sports/americanfootball_nfl/odds?regions=us&markets=h2h` | yes (free tier: 500 requests/month) | any refresh when a key is saved |
| Injuries, starting QBs, weather | none automated | — | — | Weekly Inputs (points of spread) |
| Survivor pick popularity (Yahoo) | Yahoo Survival Football pick-distribution page (public, server-rendered HTML) | `football.fantasysports.yahoo.com/survival/pickdistribution/` | no | Refresh Odds / Refresh NFL Data |
| Multi-book odds board (moneyline, spread, total) for the Betting tab | The Odds API v4 | `api.the-odds-api.com/v4/sports/americanfootball_nfl/odds?regions=us&markets=h2h,spreads,totals` | yes (counts as 3 of the 500 free monthly requests per refresh - see BETTING.md) | a dedicated betting-board refresh, recommended at most every 3 hours |

## Freshness and staleness

Every refresh stamps the season with `scheduleFetchedAt`, `oddsFetchedAt`, `fpiFetchedAt` and, if used,
`consensusFetchedAt`. The Dashboard shows the age of each; when lines are more than 24 hours old the line turns
red with "Odds are stale". Each `MarketLine` and `FpiProjection` also carries its own fetch timestamp, and the
Weekly Inputs screen shows the same ages. Stale odds are never silently used: the timestamps are always visible.

## Raw data vs. model

Raw imports are stored as-is in the `Season` object (games with `line`, `consensus`, `fpi`; `ratings`), separate
from user state (`UserState`: picks, adjustments, settings) and from model output (`Evaluation`, which is
recomputed and never stored). The whole app state is one JSON file (`files/survivor_state.json`, ~100 KB).

## Line history and calibration

Every successful refresh (`refreshNflData` or `refreshOdds`) appends a snapshot of every non-final game's
DraftKings spread, moneylines, and FPI projection to `LineHistory` (`engine/.../LineHistory.kt`), tagged with
the inferred (or overridden) current week. Snapshots are throttled to at most one per 6 hours unless a
recorded spread actually changed, and only the newest 60 are kept. `LineHistory` is part of `SavedState`, so
it persists across restarts and, like the rest of the state, is cleared only by "Reset app data" (a settings
reset that keeps downloaded data keeps line history too).

The Weekly Inputs screen's "Line movement" card shows the biggest spread moves since the previous snapshot,
and a calibration table: games are bucketed by how many weeks ahead of their own week a line was recorded,
and each bucket reports its sample count, the mean absolute spread change (points), and the standard
deviation of the win-probability change in logit space, versus that game's closing (in-week) line. Once
several buckets have at least 5 samples, a least-squares line `logitSd(k) ≈ tauBase + tauPerWeek * k` is
fit across them — the measured counterpart to the assumed future-week noise model `tau(k) = min(0.5, 0.08 +
0.03k)` used elsewhere in the engine to widen uncertainty on lookahead lines. Comparing the two once enough
weeks of data has accumulated tells you whether that assumed noise model over- or under-states real line
movement.

## Survivor pick popularity (Yahoo)

Every refresh also pulls Yahoo Survival Football's public pick-distribution page for the current week - the
real share of ALL Yahoo Survival Football entries (not just this app's user) that picked each team, no login
required. The page is server-rendered HTML (`<table class="ysf-pick-distribution-table">`); `YahooPickDistributionParser`
(`engine/.../data/YahooPickDistributionParser.kt`) scans it with a regex tag scan (no HTML library) for each
row's team name and percentage, and separately reads the page's embedded `"current_week":"N"` field to confirm
which week the shares are for. Yahoo names teams with its own "city-style" display names (mostly matching
`Team.city` exactly, e.g. "Jacksonville", "Green Bay"; the two-team cities are abbreviated "LA Chargers", "LA
Rams", "NY Giants", "NY Jets") - `teamFromYahooName` maps all 32. The page also embeds a `pickDistribution` JSON
blob keyed by week (1-18 plus four playoff rounds) covering the whole season in one payload, but at recording
time every week past the currently-active one was an empty array - Yahoo simply hasn't published a distribution
for weeks the field hasn't started picking - so the app only fetches the plain (no `?week=`) URL, which always
lands on the active week, rather than iterating all 18.

Fetched shares are stored on `Season.pickShares` (week → team → share, 0..1), keyed by week so past weeks'
shares are kept even after the current week moves on, alongside `pickSharesFetchedAtEpochMs` and
`pickSharesSource`. The fetch is non-fatal: on failure the app keeps whatever shares it already had and appends
"Yahoo pick shares unavailable" to the refresh status message rather than failing the whole refresh. A manual
pick share entered in Weekly Inputs (`Adjustment.estimatedPickShare`) always overrides Yahoo's for that
team-week - see `Evaluator.effectivePickShare`.

## Proxies and limitations

- **Home field, rest, travel, divisional** effects are largely already in the market line. The extra Safety
  penalties are deliberately small and represent variance, not a second pricing.
- **FPI rating gap** is used only when neither a line nor a game projection exists (has not happened in
  practice for the 2026 season: all 272 games carry both).
- **Injuries / QB / weather** have no reliable free feed. They are manual point adjustments and are clearly
  labelled as such in the rankings.
- **Pick popularity** is fetched automatically from Yahoo Survival Football's public pool - a good proxy for
  the field in most pools, but not necessarily identical to *your specific* pool's picks. A manual pick share
  in Weekly Inputs overrides it for a team-week when you know your own pool's ownership better.
- ESPN's endpoints are public but undocumented. The parsers ignore unknown fields and fall back through
  `spread` → `pointSpread.home.close.line` → the `details` string, and `moneyline.*.close.odds` →
  `homeTeamOdds.moneyLine`, to be tolerant of small shape changes. Recorded fixtures from 2026-09-08 are in the
  test resources.
