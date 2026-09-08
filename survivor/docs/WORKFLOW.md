# Weekly workflow, refreshing data, recording picks, changing assumptions

## Refreshing data

| Action | What it fetches | When to use |
|---|---|---|
| Refresh icon (top bar) or **Survivor Tools → Refresh Odds** | 18 scoreboard pages: scores, game states, DraftKings spreads/moneylines for every week; consensus moneylines if a key is saved | before picking, after games finish |
| **Survivor Tools → Refresh NFL Data** | everything above plus FPI team ratings and ~270 per-game FPI projections (skips final games) | once a week, or after the first install |

Progress is shown under the top bar; a snackbar reports the outcome, including a partial failure of the optional
Odds API. Refreshes merge into the stored season: a game that comes back without a projection keeps its old one.

## Before each NFL week

1. Refresh.
2. Home: read the recommendation, the **Decision robustness** row, the "Why this pick" paragraphs, the
   future-value warning if any, and the alternatives with their one-line reasons.
3. Rankings tab for the full list (card view by default, or toggle to the full table); tap any row for the
   Safety Score breakdown.
4. If you have news the line has not absorbed (late QB scratch), open **More → Weekly Inputs**, tap the game,
   enter points (e.g. QB −4) or a full manual override. The rankings update immediately.
5. Record the pick: **Record … for Week N** on Home, or the Picks tab. The route re-optimizes with that team
   locked and the Planner shows it as *Locked*.

### Decision robustness

The Home screen also runs `RobustPlanner` in the background (150 line scenarios, cached after every
evaluation) and reports **"Chosen in N% of 150 line scenarios"** - how often the current recommendation
survives plausible lookahead-line movement. A share below 60% shows a **Toss-up** chip, and if a different
team scores better on average across those scenarios it is named as the more robust alternative. Treat a low
share as a signal to look harder at the alternatives rather than a formality.

## After the week

Refresh once games are final. The pick's result (win / loss / tie), the strike count, the Used Teams table,
and the availability of the team all update from the score. With a strike recorded, the model automatically
becomes more conservative (see MODEL.md §4) and the season survival switches to zero-loss only.

If your pool's week differs from the schedule (e.g. it counts a Thursday game differently), set a current-week
override in Model Settings.

## Changing model assumptions

**Model Settings** (More → Model Settings) is grouped into three sections:

- **Your pool** - number of entries (presets 10/25/50/100/250+ or an exact count), the payout-split
  assumption, the field's average weekly win probability, and a plain-language line computed from the
  current evaluation: "With N entries the model expects the last other entry to fall around Week X, so it
  optimizes for …". This directly drives `RouteObjective.POOL_WIN` (§6 of MODEL.md) and the "P(win the pool)"
  number on Home - a 10-entry pool usually resolves early and the model leans on near-term safety, while a
  250+-entry pool usually runs the full season and it leans on season-long survival.
- **Strategy** - the route objective as radio cards (Win the pool / Survive the season / Expected weeks alive
  / Blended, each with a one-line description; Win the pool is the default and marked recommended for most
  pools), the horizon weight (Blended only), the ownership strategy preset (sets the leverage weight:
  Conservative 0, Balanced 0.3, Contrarian 0.7, Max Pool Equity ranks by equity when shares exist), and the
  current-week override.
- **Advanced model parameters** (collapsed by default) - every remaining weight and penalty behind the Safety
  Score, each with a description and safe bounds, plus **Restore defaults**.

**Reset Model** (Survivor Tools) clears picks, adjustments and settings, and **Reset everything** also removes
downloaded data. Everything is saved instantly to the app's private storage and included in Android backups.

## The Odds API key (optional)

Sign up at the-odds-api.com, paste the key in Weekly Inputs, tap **Save key and refresh odds**. The current
week then uses the mean no-vig probability across US books instead of DraftKings alone. Each refresh uses one
request; the free tier allows 500 per month.
