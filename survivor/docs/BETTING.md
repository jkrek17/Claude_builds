# Betting

Everything below is implemented in `survivor/engine` (`Betting.kt`, `BettingEngine`) and is pure and
deterministic: the same season, board, settings, and line history always produce the same result. This
module is separate from the survivor-pool model - it prices bets on the same games your survivor pick
might come from, using the market data the app already fetches (plus, optionally, a multi-book odds
board and the app's recorded line history).

## The graded board (`BettingEngine.board`)

`BettingEngine.board(season, user, nowEpochMs, lineHistory)` is the Bets screen's primary data source (a
`BetBoard`). Unlike `evaluate` (kept for compatibility - see below), it doesn't filter to sides that clear
an edge threshold: **every** SCHEDULED, not-yet-kicked-off game of the current week appears, with
MONEYLINE and SPREAD always attempted (falling back to the ESPN/DraftKings line when there's no odds
board) and TOTAL attempted only when `settings.includeTotals` and the board prices a total for that game.
Both sides of every market get a `SideAssessment`: a 0-100 **Bet Score**, a letter grade, a `Tier`, and a
rank against every other game's same market this week (`MarketAssessment.rank`, 1 = best `best.score`).
`MarketAssessment.best` is simply the higher-scoring of the two sides. Most sides of most markets are
below break-even by construction (the book's vig), so a low score and an `F`/`AVOID` tier are the normal,
correct outcome for most rows on the board - not a sign anything is broken.

`BetBoard.topPicks(n)` returns the `n` best-scoring sides across every game and market this week.
`BetBoard.suggestedStake(side, settings)` runs the same fractional-Kelly staking as below, using `side`'s
`fairProbability` (falling back to `modelProbability`), and is zero unless the side's blended EV (see the
Bet Score formula) is positive.

### Bet Score formula

For each side of each market:

```
blendedEv = lineShopEv (0 if null) + modelWeight × modelEv (0 if null)   // modelWeight: ModelSettings, default 0.25
base      = 50 + 12.5 × blendedEv × 100                                  // +4% EV -> 100, -4% EV -> 0
score     = base
          − bookConfidencePenalty   // 0 for >=6 books, 2 for 3-5, 5 for 2, 10 for a single source (no board)
          − dispersionPenalty       // 100 × lineDispersion × 0.5, capped at 6 - books disagreeing means a less reliable fair price
          + movementAdjustment      // clamp(lineMovePoints × 1.0, -4, +4) for spread/total; clamp(Δno-vig% × 0.5, -4, +4) for moneyline
          − staleBoardPenalty       // 5 past 6h, 10 past 24h since the board was fetched, 0 if fresh or there's no board
score     = score.coerceIn(0, 100)
```

`BetScoreComponents` carries every term above plus `lineShopEvPct` and `modelEvPct` - the two *already
model-weighted* percentage-point contributions that sum into `base`, so the model's actual, usually small,
share of the score stays visible in the UI even though the score itself is a single number. Worked
example: a fresh, 9-book board with no dispersion or line movement and a +1.5% line-shopping EV (model
weight zeroed out) scores `50 + 12.5 × 1.5 = 68.75` → grade `B-`, tier `ACCEPTABLE`.

**`fairProbability`** is the multi-book no-vig mean at the market's consensus point (≥2 books quoting both
sides at the same point, same grouping rule as line shopping below) when a board exists; without one, it
falls back to the ESPN/DraftKings line itself - the real no-vig probability of `Game.line`'s two moneylines
for MONEYLINE, or an assumed 50% at standard -110 juice for SPREAD (a fairly-set spread is, by
construction, close to a coinflip at standard vig). TOTAL has no ESPN fallback at all (the scoreboard
payload never carries one) and is simply skipped without a board.

**`lineDispersion`** is the standard deviation of the consensus books' own no-vig probabilities for that
side (0.0 for a single paired book, `null` with no board to disperse at all) - a proxy for how much the
market disagrees with itself about the true number.

**`lineMovePoints`** compares the side's current number to the earliest point recorded in the app's
`LineHistory` (positive = the number moved in this side's favor): for SPREAD, the side's own point,
current minus earliest; for MONEYLINE, the change in no-vig probability (percentage points) from the
DraftKings/ESPN line's own history. `null` with no recorded history. TOTAL has no history support (ESPN
never carries a total) and is always `null` here.

**Grades**: `A+ ≥92, A ≥85, A- ≥80, B+ ≥76, B ≥72, B- ≥68, C ≥60, D ≥50, F <50` - stricter than the
survivor Safety Score's scale, on purpose, since most sides here are meant to read as unattractive.
**Tiers** reuse `Tier`: `STRONG ≥76, ACCEPTABLE 68-76, RISKY 60-68, AVOID <60`.

**Rationale** is one sentence, market-appropriate and never crossing vocabularies: MONEYLINE states the
best price against the fair price and books (e.g. *"FanDuel +350 vs fair +343 from 9 books (no-vig
22.6%); EV +1.5%."*); SPREAD and TOTAL state the price, the consensus fair number and books, and - SPREAD
only - the model's **cover** probability and its break-even (e.g. *"LAC -9.5 at -105 (LowVig.ag);
consensus -9.5 at fair -108 from 7 books; model has LAC covering 56% (break-even 51.2%)."*) - a spread's
model number is a cover probability, never described as a moneyline win probability.

## `evaluate` and the two-signal `BettingBoard` (kept for compatibility)

`BettingEngine.evaluate` and the `BettingBoard`/`BetPick` types it returns still work exactly as before,
for anything still built against them; the Bets screen itself now uses `board` above. `evaluate` only
surfaces sides that clear an edge threshold, kept apart as two signals:

`BetPick.signal` is either `LINE_SHOP` or `MODEL`. They are **never merged** - the same side of the same
game can appear once under each, with very different confidence:

- **Line shopping (`LINE_SHOP`) is the primary, reliable signal.** It never asks "who wins" - it only
  asks "is one book's price better than what the rest of the market says it should be?" The no-vig
  consensus across several books is a good estimate of the true probability precisely because it doesn't
  depend on this engine's model being right about the game at all. A price that beats that consensus by a
  real margin is a genuine, structural edge (the book made a pricing or timing mistake), and it is small
  and rare by nature - most books agree most of the time.
- **Model vs. market (`MODEL`) is a secondary, clearly weaker signal.** It compares this engine's own
  win-probability estimate (the same FPI/market blend `ProbabilityResolver` uses for future survivor
  weeks) to the best price available. This is a bet that *the model knows something the market doesn't*,
  which is a strong claim against a market as efficient as NFL betting - it is included because it can
  surface real value (an FPI signal the market hasn't caught up to), but it is speculative, shown with a
  higher edge threshold, and always labeled so it's never mistaken for the line-shop signal.

If `Season.board` is empty (no Odds API key saved), only the `MODEL` signal works, priced against the
DraftKings line ESPN already carries (`Game.line`) - line shopping needs several books to build a
consensus from, so it needs the board.

## Formulas

### No-vig probability

Same as the survivor model (see MODEL.md): American odds → implied probability, normalised so both sides
of a market sum to 100%. `Probability.noVig(oddsA, oddsB)`.

### Building the fair price (line shopping)

For each game and market (`MONEYLINE`, `SPREAD`, `TOTAL`), quotes are grouped by point (moneyline has one
group; a spread's point is normalised to the home team's number so a home quote and its matching away
quote land in the same group). Within the group that has at least two books quoting **both** sides (the
"consensus group"), each such book's own no-vig probability for each side is computed and averaged →
`fairProbability`. The **best price** for a side is the maximum American price offered by *any* book for
that side and point (not only the books that fed the average) - that's the whole point of line shopping.

`fairPrice` is `fairProbability` converted back to American odds (`OddsApiParser.americanFromProbability`).

**Better number.** For `SPREAD` and `TOTAL`, when a book hangs a different (and better) point than the
consensus group, its price is evaluated against the consensus fair probability shifted by the point
difference using the same spread curve as the survivor model:
`Probability.shiftByPoints(fairProbability, pointDifference, sigma)` - `marginSigma` (11.0) for spreads,
`totalSigma` (10.0) for totals. A half point at a pick'em spread is worth about 1.8% of win probability at
sigma 11 - small, but often enough to turn a break-even price into a real edge. These picks carry
`note = "better number"`.

### EV

`decimal(american)` = `1 + 100/|a|` for a negative price, `1 + a/100` for a positive one.
`ev = fairProbability × decimal(bestPrice) − 1`. A pick is shown when `ev ≥ settings.minLineShopEdge`
(line shopping, default 1%) or `ev ≥ settings.minModelEdge` (model, default 3% - higher because the
model signal is noisier and needs more margin to be worth acting on).

**Worked example:** two books both at −110/−110 → fair 50%. A third book offers −105 on the same side:
`ev = 0.5 × 1.952 − 1 ≈ −2.4%` - below the 1% threshold, so no pick, even though −105 is objectively the
best price on the board; the vig at −110 is simply too large to clear with half a point of price
improvement. A book offering +150 against a 42% fair probability: `ev = 0.42 × 2.5 − 1 = +5%` - shown.

### Model probability

`MONEYLINE`: the same FPI/market blend the survivor model uses for a future week -
`ProbabilityResolver.resolve(game, team, isCurrentWeek = false, ...)`, which blends the market win
probability (whichever source is available: consensus, book moneyline, or spread) with the ESPN FPI
projection at `settings.futureMarketWeight` (default 60% market / 40% FPI). `SPREAD`: that same win
probability is converted to a model margin (`Probability.spreadFromWinProbability`), and the probability
the team covers the specific point on offer is `Φ((modelMargin + teamPoint) / marginSigma)` (the team's
spread point is negative when favored). `TOTAL` has no model counterpart and is skipped for this signal.

### Kelly staking

`kellyFraction = (b·p − q) / b`, with `b = decimal − 1`, `p` the relevant probability (`fairProbability`
for line shopping, the model probability for the model signal), `q = 1 − p`. Stake is
`bankroll × kellyFraction × kellyMultiplier`, capped at `bankroll × maxStakePct`, rounded to whole
dollars, and zero whenever `kellyFraction ≤ 0`. The default `kellyMultiplier` of 0.25 (quarter-Kelly) and
`maxStakePct` of 2% are deliberately conservative - full Kelly is well known to be too aggressive for
noisy probability estimates, and the model signal in particular is exactly that.

### Grading and profit

`BettingEngine.grade(bet, game)` settles a bet once its game is `FINAL`: `MONEYLINE` by winner (a tie is a
`PUSH`); `SPREAD` by `(sideScore + point) − otherScore` (`> 0` win, `= 0` push, `< 0` loss); `TOTAL` by
`homeScore + awayScore` against the point, the same way. `BettingEngine.profit` pays `stake × (decimal −
1)` on a win, `−stake` on a loss, and `0` on a push or while still pending.

### Closing line value (CLV)

`BettingEngine.ledger` reports, per graded bet, how much the line moved in the bettor's favor after the
bet was placed - the standard long-run skill signal in sports betting, independent of any single result.
It uses the last known DraftKings line on the game (`Game.line`) as a simple proxy for the closing line:

- `SPREAD` / `TOTAL`: closing point minus the bet's own point, from the side's perspective.
- `MONEYLINE`: the closing no-vig probability of the side minus the raw (vig-included) implied probability
  of the price actually bet - a logged `Bet` keeps only its own price, not the opposing side's, so it
  can't be no-vig'd on its own; comparing it to the closing no-vig number is the simplest apples-to-apples
  check available.
- Always `null` for `TOTAL`, since ESPN's scoreboard payload (the only source `Game.line` draws from)
  never carries a total.

## Settings (`ModelSettings`)

| Setting | Default | Meaning |
|---|---:|---|
| `bankroll` | $1000 | Assumed bankroll for stake sizing. |
| `kellyMultiplier` | 0.25 | Fraction of full Kelly staked. |
| `maxStakePct` | 2% | Hard cap on any single stake, as a fraction of bankroll. |
| `minLineShopEdge` | 1% | Minimum EV to show a line-shopping pick. |
| `minModelEdge` | 3% | Minimum EV to show a model-vs-market pick. |
| `totalSigma` | 10.0 | Scale of the total → probability curve (for pricing a "better number" total). |
| `includeTotals` | true | Whether the totals market is considered at all. |
| `modelWeight` | 0.25 | Weight on the model-vs-market edge inside the Bet Score's blended EV. 0 ignores it; 0.5 weights it the same as the line-shopping edge. |

## The odds board and API quota

`OddsApiParser.parseBoard` reads The Odds API's `regions=us&markets=h2h,spreads,totals` endpoint into one
`GameBoard` per game, keeping every book's quote for every market (unlike `parse`/`attach`, which collapse
straight to a single consensus moneyline for the survivor model). `OddsApiParser.attachBoard` matches
boards to ESPN games the same way `attach` does (same teams, kickoff within 3 days) and returns them keyed
by the ESPN game id for `Season.board`.

**Quota note:** The Odds API bills by market, not by request - `markets=h2h,spreads,totals` counts as
**3 requests** against the free tier's 500/month, not 1. Refreshing the board every 3 hours during an
18-week season (roughly 12 refreshes/week × 18 weeks × 3 = ~650) already exceeds the free tier by itself,
before the existing consensus-moneyline fetch (`OddsApiParser.parse`, 1 request) is counted - so the board
should be fetched deliberately (e.g. from a dedicated "Refresh Betting Board" action), not folded into
every automatic refresh, and no more often than every 3 hours.
