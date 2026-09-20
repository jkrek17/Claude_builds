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
`BetBoard.goodBets()` returns every side that clears all five "good bet" tests below (`SideAssessment.goodBet`),
sorted by `expectedGrowth` descending - the list the Bets screen actually leads with, since a positive score
alone isn't the bar for "worth betting". `BetBoard.suggestedStake(side, settings)` runs fractional Kelly on
`side.kellyFraction` (already built from `blendedProbability` - see below) and is zero whenever that's zero.

### Why EV alone is the wrong ranking signal

The old formula scored every side by raw EV alone: `score = 50 + 12.5 × blendedEv%`. That put a +1.5% edge
on a 22.6% underdog (e.g. Cleveland moneyline at +350) in the exact same place as a +1.5% edge on a 78-82%
favorite. That's wrong on two counts. First, **compounding**: at quarter-Kelly, the underdog's stake is
about 0.5% of bankroll and the favorite's is about 6.8% - roughly *12× more capital actually put to work* at
the same edge, so the favorite's contribution to long-run bankroll growth is proportionally larger even
before variance is considered. Second, **noise**: a 1.5-percentage-point edge on a ~23% probability is
usually smaller than the standard error of a 9-book fair-price estimate - it's as likely to be sampling
noise in the books' own prices as a real mispricing, while the same 1.5 points on an ~80% favorite (a much
narrower, better-agreed-upon number in practice) is more often real. The rewrite replaces raw EV with a
Kelly-scaled **expected growth rate** as the primary ranking signal, and adds an explicit uncertainty
penalty and a sharp-book check so a good score means "worth acting on", not just "positive EV".

### The five "good bet" tests (`GoodBetTests`)

A side is a `goodBet` when it passes all five below **and** scores ≥ 68. A nullable test (no sharp quote,
no line history) never fails a side by itself - only a confirmed *disagreement* does (`!= false`, not
`== true`):

| Test | Passes when |
|---|---|
| `valueVsConsensus` | `lineShopEv > 0` - a real edge vs. the no-vig consensus (or ESPN/DraftKings fallback) at all. |
| `edgeConfident` | `edgeZ >= 1` - the edge is at least one standard error of the fair-price estimate (see below), not noise. |
| `sharpAgrees` (nullable) | `null` when the sharp reference book (`ModelSettings.sharpBooks`, default Pinnacle) isn't quoting both sides here; otherwise its own no-vig price is also positive EV against `bestPrice`. |
| `lineNotAgainst` (nullable) | `null` with no `LineHistory`; otherwise `lineMovePoints >= -0.5` (spread/total, points) or `>= -1.0` (moneyline, no-vig percentage points). |
| `boardFresh` | The multi-book board is on file (`boardAgeMs` not null) and no more than 6 hours old. |

`GoodBetTests.failedReasons` gives one plain-language sentence per failing test, e.g. *"Edge is within
fair-price noise (z = 0.6)"*, *"Pinnacle prices this side at -EV (-0.8%)"*, *"Line moved 1.0 pt against this
side"* (SPREAD/TOTAL) or *"Price moved against this side (-3.1 pp win probability)"* (MONEYLINE), *"Odds
board is 9 h old"*, *"No edge vs the consensus"*. A side that isn't a good bet keeps the strongest (first) of
these appended to its `rationale`, and stays visible everywhere in the UI with the reason in its subtitle -
failing a test never hides a side, it just keeps it out of "Good bets".

### Confidence and the standard error of the fair price (`fairProbabilitySe`, `edgeZ`, `Confidence`)

```
fairProbabilitySe = max(lineDispersion, 0.004) / sqrt(booksQuoting)   // floor avoids a zero se from identical books; null with no board
edgeZ             = lineShopEv / (fairProbabilitySe × decimal(bestPrice))   // null when either input is null
confidence        = NONE  if edgeZ is null or <= 0
                     LOW   if edgeZ <  1
                     MEDIUM if 1 <= edgeZ < 2
                     HIGH  if edgeZ >= 2
```

`lineDispersion` is unchanged from before (the standard deviation of the consensus books' own no-vig
probabilities for that side). `fairProbabilitySe` is its implied standard error of the *mean*, and `edgeZ`
expresses the edge in units of that standard error - the same logic as a z-test: an edge under 1 standard
error is statistically indistinguishable from noise in a handful of books' prices.

### Growth ranking (`blendedProbability`, `kellyFraction`, `expectedGrowth`)

```
blendedProbability = fairProbability + modelWeight × (modelProbability − fairProbability)   // when both exist - a shrunk blend toward the model
                    = whichever of fairProbability / modelProbability exists, otherwise      // when only one exists
kellyFraction       = kelly(blendedProbability, decimal(bestPrice)).coerceAtLeast(0)         // never negative - a losing edge stakes nothing
blendedEv           = blendedProbability × decimal(bestPrice) − 1
expectedGrowth      = kellyFraction × blendedEv     // proportional to the expected log-growth rate at fractional Kelly (leading-order approximation)
expectedGrowthBps   = expectedGrowth × 10,000
```

This is what actually ranks bets now, not raw EV: `BetBoard.goodBets()` sorts by `expectedGrowth`, and
`BetBoard.suggestedStake` stakes `kellyFraction` directly.

**Worked example - the underdog that looks good but isn't:** Cleveland moneyline at +350 (fair ≈22.6%,
no-vig), priced at roughly +1.5% EV. `decimal(350) = 4.5`. `kellyFraction ≈ (4.5-1)×0.226 - 0.774) / 3.5 ≈
0.005` (about half a percent of bankroll at full Kelly). `expectedGrowth ≈ 0.005 × 0.015 ≈ 0.00008`, or
**≈0.8 basis points**. Even with a confidently-priced, real edge, this barely moves the score.

**Worked example - the favorite at roughly the same EV:** the same ≈+1.5-3% edge on a ~78% favorite (e.g.
a book at -330 against a 9-book fair price of ~78% from -420/+340 quotes). `kellyFraction ≈ 0.12` (about
12% of bankroll at full Kelly - vastly larger, because a favorite's Kelly fraction scales with how far its
probability sits above the market's implied breakeven, not with the raw edge alone). `expectedGrowth ≈
0.12 × 0.017 ≈ 0.002`, or **≈20-50 basis points depending on the exact price** - one to two orders of
magnitude larger than the underdog's, at a similar percentage EV. This is the fix: the same-looking EV
produces wildly different growth depending on how far from a coinflip the side is.

### Bet Score formula

```
growthPoints        = clamp(expectedGrowthBps / 2, -50, +50)   // a favorite at +1.5% EV -> ~+2 to +25; the 22.6% dog at +1.5% -> ~+0.4
uncertaintyPenalty   = 0 if edgeZ >= 2, 4 if 1 <= edgeZ < 2, 10 if edgeZ < 1 or null
sharpPenalty         = 8 if sharpAgrees == false, 0 otherwise (including "no sharp quote")
score = 50
      + growthPoints
      − uncertaintyPenalty
      − sharpPenalty
      + movementAdjustment      // unchanged: clamp(lineMovePoints × 1.0, -4, +4) for spread/total; clamp(Δno-vig% × 0.5, -4, +4) for moneyline
      − staleBoardPenalty       // unchanged: 5 past 6h, 10 past 24h since the board was fetched, 0 if fresh or there's no board
      − bookConfidencePenalty   // unchanged: 0 for >=6 books, 2 for 3-5, 5 for 2, 10 for a single source (no board)
score = score.coerceIn(0, 100)
```

`BetScoreComponents` carries every term above (`base = 50`, `growthPoints`, `uncertaintyPenalty`,
`sharpPenalty`, `movementAdjustment`, `staleBoardPenalty`, `bookConfidencePenalty`), plus `lineShopEvPct`
and `modelEvPct` kept for the UI's informational display (they no longer feed the score directly - `growthPoints`
does). A side with `expectedGrowth <= 0` gets `growthPoints <= 0` and, since a non-positive edge also means
`edgeZ <= 0` and therefore the full 10-point `uncertaintyPenalty`, reliably lands in `AVOID` - exactly the
old bug's mirror image fixed: no side scores well just because its EV happens to be positive.

**Worked example, updated:** the same fresh, 9-book, zero-dispersion, zero-movement board with a +1.5%
line-shopping EV that used to score `68.75` (grade `B-`) now depends entirely on *whose* +1.5% it is -
see the two worked examples above. A coinflip-ish side (fair ≈50%) at that EV now scores **≈51** (grade
`D`, tier `AVOID`) even though every test but the score threshold passes; a confident ~78% favorite at a
comparable EV can clear 68+ and register as a real `goodBet`.

**`fairProbability`** is the multi-book no-vig mean at the market's consensus point (≥2 books quoting both
sides at the same point, same grouping rule as line shopping below) when a board exists; without one, it
falls back to the ESPN/DraftKings line itself - the real no-vig probability of `Game.line`'s two moneylines
for MONEYLINE, or an assumed 50% at standard -110 juice for SPREAD (a fairly-set spread is, by
construction, close to a coinflip at standard vig). TOTAL has no ESPN fallback at all (the scoreboard
payload never carries one) and is simply skipped without a board.

**`lineDispersion`** is the standard deviation of the consensus books' own no-vig probabilities for that
side (0.0 for a single paired book, `null` with no board to disperse at all) - a proxy for how much the
market disagrees with itself about the true number, which now feeds `fairProbabilitySe`/`edgeZ` above.

**`lineMovePoints`** compares the side's current number to the earliest point recorded in the app's
`LineHistory` (positive = the number moved in this side's favor): for SPREAD, the side's own point,
current minus earliest; for MONEYLINE, the change in no-vig probability (percentage points) from the
DraftKings/ESPN line's own history. `null` with no recorded history. TOTAL has no history support (ESPN
never carries a total) and is always `null` here.

**Grades**: `A+ ≥92, A ≥85, A- ≥80, B+ ≥76, B ≥72, B- ≥68, C ≥60, D ≥50, F <50` - stricter than the
survivor Safety Score's scale, on purpose, since most sides here are meant to read as unattractive.
**Tiers** reuse `Tier`: `STRONG ≥76, ACCEPTABLE 68-76, RISKY 60-68, AVOID <60`.

## Sharp reference (Pinnacle)

`ModelSettings.sharpBooks` (default `["pinnacle"]`, lowercase Odds API bookmaker keys) names the book(s)
treated as a sharp reference. `Quote.bookKey` carries the Odds API's own bookmaker key (e.g. `"pinnacle"`)
alongside its display `book` title, defaulting to `""` so a `Quote` saved before this field existed still
decodes. For each side, `sharpProbability` is the sharp book's *own* no-vig probability from its own
two-sided quote at the market's consensus point (independent of which book actually has `bestPrice` -
`bestBookIsSharp` flags that separately, purely informationally). `sharpEv = sharpProbability ×
decimal(bestPrice) − 1`, and `sharpAgrees = sharpEv > 0`. Both are `null` when the sharp book isn't quoting
both sides of this market/point (including always, with no odds board).

Pinnacle is a European/offshore sharp book, not licensed in the `us` region The Odds API serves by default,
so reaching it requires adding the `eu` region: `ModelSettings.includeSharpRegion` (default `true`) controls
this, and `OddsApiParser.boardUrl(apiKey, includeSharpRegion)` switches between `regions=us,eu` (6 requests
per fetch - 3 markets × 2 regions) and `regions=us` (3 requests, no sharp reference) accordingly. A
Settings toggle ("Use Pinnacle as sharp reference (doubles odds board cost)") exposes this trade-off
directly, since it doubles the odds board's cost against The Odds API's monthly quota.

**Rationale** is one sentence, market-appropriate and never crossing vocabularies: MONEYLINE states the
best price against the fair price and books (e.g. *"FanDuel +350 vs fair +343 from 9 books (no-vig
22.6%); EV +1.5%."*); SPREAD and TOTAL state the price, the consensus fair number and books, and - SPREAD
only - the model's **cover** probability and its break-even (e.g. *"LAC -9.5 at -105 (LowVig.ag);
consensus -9.5 at fair -108 from 7 books; model has LAC covering 56% (break-even 51.2%)."*) - a spread's
model number is a cover probability, never described as a moneyline win probability.

## Book roles

Adding the `eu` region for Pinnacle (see above) also pulls in books a US bettor cannot use at all: betting
exchanges (Matchbook, Betfair, Smarkets - pre-commission, thin, and easily the "best" price on the board for
the wrong reason) and books not licensed in the US (e.g. Unibet's European entity). Left unfiltered, these
inflate EV/growth and can push a longshot exchange price to the top of the board, and folding an exchange's
paired quote into the no-vig consensus overstates how many independent books actually agree (a smaller
standard error than is really there).

`BookRole` (from `ModelSettings.roleOf(bookKey)`) fixes this by giving every book on the board exactly one
of four roles:

| Role | Comes from | Can be `bestBook`/`bestPrice`? | Feeds the consensus/fair price? |
|---|---|:-:|:-:|
| `BETTABLE` | `ModelSettings.bettableBooks` (The Odds API's US-region bookmaker keys - DraftKings, FanDuel, BetMGM, Caesars, etc.) | Yes | Yes |
| `SHARP` | `ModelSettings.sharpBooks` (default `["pinnacle"]`) | No | Yes |
| `REFERENCE` | Any other book (e.g. a legitimate non-US book like Unibet) | No | Yes |
| `EXCLUDED` | `ModelSettings.excludedBooks` (betting exchanges: Matchbook, Betfair, Smarkets) | No | No - dropped entirely |

`roleOf` checks `sharpBooks`, then `excludedBooks`, then `bettableBooks`, in that order (so a key can't be
both sharp and excluded), and a blank `bookKey` (a `Quote` saved before that field existed, or the
ESPN/DraftKings fallback's synthetic quote) is always treated as `BETTABLE` - it's always safe to show as a
price a bettor can act on.

In `BettingEngine.board`, **best price** (`SideAssessment.bestBook`/`bestPrice`/`bestBookIsSharp`) is chosen
only among `BETTABLE` quotes for that side; if none exists, the side is skipped for that market rather than
falling back to a sharp, reference or excluded price (the ESPN/DraftKings fallback, which has no roles at
all, is unaffected). **Consensus/fair price/dispersion/`booksQuoting`** are built from `BETTABLE` + `SHARP` +
`REFERENCE` quotes together - an excluded book is dropped before the group is even formed, so it can't widen
`booksQuoting` or narrow the fair-price standard error either. `SideAssessment.bettableBooks` reports how
many of `booksQuoting` are actually `BETTABLE`. `BetBoard.booksSeen` is the distinct count of `BETTABLE`
books across the week's board; `BetBoard.referenceBooksSeen` is the same for `REFERENCE` books. The sharp
book's own two-sided quote (`sharpProbability`/`sharpEv`/`sharpAgrees`) is unchanged - it was never chosen as
a best price to begin with.

`ModelSettings.bettableBooks` and `ModelSettings.excludedBooks` are both plain, editable lists (Settings →
Betting → Advanced: book roles) so a user can add a book The Odds API has since added, or exclude one that
turns out to be unusable in their market.

## `evaluate` and the two-signal `BettingBoard` (kept for compatibility)

`BettingEngine.evaluate` and the `BettingBoard`/`BetPick` types it returns still work exactly as before,
for anything still built against them; the Bets screen itself now uses `board` above. `evaluate` only
surfaces sides that clear an edge threshold, kept apart as two signals. **`evaluate` does not apply book
roles** - its own line-shopping consensus (`lineShopMarket`) picks the best price across every quoted book
regardless of role, unlike `board`'s fixed selection above; it is unused by the app UI, kept only for
anything still built directly against it.

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
| `sharpBooks` | `["pinnacle"]` | The Odds API bookmaker keys treated as a sharp reference price for `sharpAgrees` and the good-bet checks. |
| `includeSharpRegion` | true | Whether the odds board adds the `eu` region to reach Pinnacle. Doubles the board's request cost (6 instead of 3). |
| `bettableBooks` | The Odds API's US-region bookmaker keys | Books an ordinary US bettor can actually place a bet at - the only ones a `bestBook`/`bestPrice` can come from. See "Book roles" above. |
| `excludedBooks` | `betfair_ex_eu, betfair_ex_uk, betfair_ex_au, matchbook, smarkets, betfair_sb_uk` | Betting exchanges and other books dropped from the board entirely - never a best price, never part of the consensus. |

## The odds board and API quota

`OddsApiParser.parseBoard` reads The Odds API's `regions={us|us,eu}&markets=h2h,spreads,totals` endpoint
(`OddsApiParser.boardUrl(apiKey, includeSharpRegion)`) into one `GameBoard` per game, keeping every book's
quote for every market (unlike `parse`/`attach`, which collapse straight to a single consensus moneyline
for the survivor model). `OddsApiParser.attachBoard` matches boards to ESPN games the same way `attach`
does (same teams, kickoff within 3 days) and returns them keyed by the ESPN game id for `Season.board`.

**Quota note:** The Odds API bills by market *per region*, not by request - `markets=h2h,spreads,totals`
counts as **3 requests** against the free tier's 500/month with `regions=us`, or **6 requests** with
`regions=us,eu` (`includeSharpRegion = true`, the default, needed to reach Pinnacle - a European/offshore
book not licensed `us`). Refreshing the board every 3 hours during an 18-week season (roughly 12
refreshes/week × 18 weeks × 3-6 = ~650-1300) already exceeds the free tier by itself, before the existing
consensus-moneyline fetch (`OddsApiParser.parse`, 1 request) is counted - so the board should be fetched
deliberately (e.g. from a dedicated "Refresh Betting Board" action), not folded into every automatic
refresh, no more often than every 3 hours, and with `includeSharpRegion` turned off in Settings for anyone
tight on quota who doesn't need the sharp-book checks.
