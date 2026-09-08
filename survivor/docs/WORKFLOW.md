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
2. Dashboard: read the recommendation, the three explanation paragraphs, the future-value warning if any, and the
   alternatives with their reasons.
3. Rankings tab for the full table; tap any row for the Safety Score breakdown.
4. If you have news the line has not absorbed (late QB scratch), open **Weekly Inputs**, tap the game, enter points
   (e.g. QB −4) or a full manual override. The rankings update immediately.
5. Record the pick: **Record … as Week N pick** on the Dashboard, or the Picks screen. The route re-optimizes with
   that team locked and the Planner shows it as *Locked*.

## After the week

Refresh once games are final. The pick's result (win / loss / tie), the strike count, the Used Teams table,
and the availability of the team all update from the score. With a strike recorded, the model automatically
becomes more conservative (see MODEL.md §4) and the season survival switches to zero-loss only.

If your pool's week differs from the schedule (e.g. it counts a Thursday game differently), set a current-week
override in Model Settings.

## Changing model assumptions

**Model Settings** lists every parameter with a description and safe bounds:

- *Strategy* presets set the ownership leverage weight (Conservative 0, Balanced 0.3, Contrarian 0.7, Max Pool
  Equity ranks by equity when shares exist).
- *Future value weight* and *Season path loss weight* control how much elite future spots are protected.
- *Future discount per week* controls how much Weeks 12–18 count inside the optimizer.
- *Future market weight* sets the lookahead-spread vs. FPI blend for future weeks.
- *Margin sigma* changes the spread → win probability curve.
- *Minimum acceptable win probability* flags picks below it.
- *Strike future weight multiplier* sets how much future value is ignored after a strike.

**Restore defaults** resets the parameters; **Reset Model** (Survivor Tools) clears picks, adjustments and
settings, and **Reset everything** also removes downloaded data. Everything is saved instantly to the app's
private storage and included in Android backups.

## The Odds API key (optional)

Sign up at the-odds-api.com, paste the key in Weekly Inputs, tap **Save key and refresh odds**. The current
week then uses the mean no-vig probability across US books instead of DraftKings alone. Each refresh uses one
request; the free tier allows 500 per month.
