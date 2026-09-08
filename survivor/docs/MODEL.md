# Model

See also [EVALUATION.md](EVALUATION.md) for the data-science evaluation of the selection process on the real 2026 lines
and the changes it motivated.

Everything below is implemented in `survivor/engine` and is deterministic: the same data, picks and settings
always produce the same output. Every number on screen carries its **source** label.

## 1. Win probability

### Source priority

For a team in a game, the estimate comes from the first available source:

| Priority | Source | When |
|---|---|---|
| 0 | Manual override (Weekly Inputs) | always wins |
| 1 | Consensus moneyline, no-vig, from The Odds API (mean implied probability across US books) | current week, only if a key is configured |
| 2 | DraftKings moneyline from ESPN, no-vig | current week |
| 3 | Lookahead spread + FPI blend | future weeks (default 60% market / 40% FPI) |
| 4 | Market spread only | if FPI is missing |
| 5 | ESPN FPI game projection | if no line exists |
| 6 | FPI rating gap + home field | if even the projection is missing |

Injury / QB / weather adjustments (points of spread) are applied as a shift in spread space on top of whichever
automated source won: `p' = Φ((Φ⁻¹(p)·σ + points) / σ)`.

### No-vig moneyline

American odds → implied probability: negative odds `o` give `|o| / (|o| + 100)`, positive give `100 / (o + 100)`.
The two sides are normalised so they sum to 100%. Example: −200 / +170 → 66.7% / 37.0% raw → **64.3% / 35.7%**.

### Spread → win probability

A team favoured by `s` points wins with probability `Φ(s / σ)` with **σ = 11.0**. σ is the maximum-likelihood
fit to 4,162 regular-season closing lines from 2010–2025 (nflverse), chosen in every leave-one-season-out
fold, and it is calibrated within about one point in every implied-probability bucket (60–65% implied → 60.7%
actual; 80–85% → 82.5%; 90–95% → 93.2%). The raw standard deviation of the margin around the spread is
about 13.5 points, but using that value underprices big favourites by roughly five points, which matters
for survivor picks. σ is editable in Model Settings. See EVALUATION.md §7.

| Favorite by | Win probability |
|---:|---:|
| -0 | 50.0% |
| -1 | 53.6% |
| -1.5 | 55.4% |
| -2 | 57.2% |
| -2.5 | 59.0% |
| -3 | 60.7% |
| -3.5 | 62.5% |
| -4 | 64.2% |
| -4.5 | 65.9% |
| -5 | 67.5% |
| -5.5 | 69.1% |
| -6 | 70.7% |
| -6.5 | 72.3% |
| -7 | 73.8% |
| -7.5 | 75.2% |
| -8 | 76.6% |
| -8.5 | 78.0% |
| -9 | 79.3% |
| -9.5 | 80.6% |
| -10 | 81.8% |
| -10.5 | 83.0% |
| -11 | 84.1% |
| -12 | 86.2% |
| -13 | 88.1% |
| -13.5 | 89.0% |
| -14 | 89.8% |
| -15 | 91.4% |
| -16 | 92.7% |
| -17 | 93.9% |
| -18 | 94.9% |
| -20 | 96.5% |

Ties are ignored (P(tie) < 0.5%); an underdog's probability is 1 − favourite.

### Future weeks

Lookahead lines exist on ESPN for every game in all 18 weeks but are thinner and move more than current-week
lines, so future weeks use `0.6 · market + 0.4 · FPI` (weights editable). Inside the **optimizer only**,
future probabilities are additionally shrunk toward 50% by 3% per week ahead:
`p_d = 0.5 + (p − 0.5) · 0.97^(weeks ahead)`. Displayed win probabilities are never discounted.

## 2. Situational factors

Computed from the schedule and applied as small Safety Score penalties (the line already prices most of this;
these represent extra variance):

- **Rest**: days since the team's previous game vs. the opponent's; each day of disadvantage costs 0.6 points
- **Travel**: time zones crossed by the road team, 0.4 points each
- **Road**: 1.0 point; **divisional**: 1.5 points
- **QB flag**: 4.0 points when any QB adjustment is entered for the game
- **Market confidence**: +2.0 when a real moneyline backs the number; −3.0 when only a projection does

## 3. Future value and opportunity cost

For every team, over strictly future weeks: best and second-best remaining win probability, average, counts
above 70 / 75 / 80%, and "premium spots" (>80%).

Two costs are computed by actually re-running the season optimizer:

- **Opportunity cost** = relative loss in the survival probability of the best path over *next week → Week 18*
  when this team is removed from the pool: `100 · (1 − V_without / V_with)`. It is low when other teams can
  cover the team's good future spots just as well, high when they cannot. Labelled Low (<5%), Medium (5–12%),
  High (≥12%).
- **Season path loss** = relative loss in survival of the best *full* path (this week → Week 18) when this team
  is locked in this week vs. the unconstrained optimum. The team on the unconstrained optimum has 0.

## 4. Survivor Safety Score (0–100)

```
Safety = 100 · p
       − (road + divisional + rest + travel + QB)             matchup risk
       + market confidence adjustment
       − 0.55 · opportunity cost · m                          future value
       − 1.5 · min(premium spots, 3) · m                      scarcity
       − 0.5 · season path loss · m                           path alignment
       − max(0, minimum − p) · 100 · (strikes = 1 ? 1.5 : 0.5) threshold
       + leverage weight · leverage score                     ownership (0 unless Balanced/Contrarian)
```

where `m` = 1 with no strike and **0.4 after a strike** (the pool is now single-elimination for you, so
this week's safety dominates). All weights are in Model Settings with descriptions.

Grades: A+ ≥88, A ≥82, A− ≥78, B+ ≥74, B ≥70, B− ≥66, C ≥60, D ≥52, else F.
Tiers/colours: Strong ≥76 (green), Acceptable 68–76 (yellow), Risky 60–68 (orange), Avoid <60 (red).

The ranking key is the Safety Score, except under **Max Pool Equity** where teams with an ownership estimate
rank by expected pool equity.

## 5. Double elimination

For weekly probabilities `p₁…pₙ`:

- `P(0 losses) = Π pᵢ`
- `P(exactly 1 loss) = Σⱼ (1 − pⱼ) · Π_{i≠j} pᵢ`
- Survive = P(0) + P(1) with no strike used, P(0) only after a strike

The Dashboard reports both along the optimized route using undiscounted probabilities.

## 6. Season optimization

One team per week, each team at most once, over the remaining weeks. This is an assignment problem.

1. **Hungarian assignment** on cost `−ln(p_d)` (weeks × teams, unavailable = +∞) finds the path that exactly
   maximises `P(0 losses)`. This is a good starting point for `SURVIVE_SEASON`, since a zero-loss path is never
   a bad path to start improving from.
2. **Local search** then climbs the *selected* objective (see below) with two move types until no improvement:
   replace a week's team with an unused team; swap the teams of two weeks. It runs from the Hungarian
   assignment AND from the plain greedy assignment (highest probability per week, no reuse) and keeps whichever
   finishes higher — see "Two starting points for the local search" below.
3. Recorded picks for the current or future weeks are **locked**; used teams are removed.

The displayed route always starts with the recommended (or recorded) pick so the planner, season survival and
Monte Carlo describe the plan you would actually follow; the unconstrained optimum is shown alongside when it
differs, with the objective delta, and the difference feeds the "season path loss" term.

The greedy "highest win % every week" route is kept as a baseline for the Monte Carlo comparison (and, since
recently, as a second starting point the local search itself climbs from).

### Route objective

A real pool rarely requires the entry to survive all 18 weeks — pools usually end once everyone else is
eliminated — so being alive for **more weeks** has value even for an entry that eventually takes a fatal loss.
`ModelSettings.routeObjective` (default `POOL_WIN`) selects what the local search in step 2 climbs:

| Objective | Formula | Notes |
|---|---|---|
| `POOL_WIN` | See "Pool-win objective" below | **Default.** Maximizes P(win the pool), which accounts for pool size — the field's elimination rate matters as much as your own survival. |
| `SURVIVE_SEASON` | `P(0 losses) + P(1 loss)` (or `P(0)` after a strike) over the whole route | Maximizes P(alive after Week 18) alone, ignoring pool size. |
| `EXPECTED_WEEKS_ALIVE` | `Σₖ P(at most strikesAllowed losses among the first k games)`, k = 1..n | Maximizes the expected number of remaining weeks the entry gets through, rewarding routes that stay safe *early* even at some cost to the full-season number. |
| `BLENDED` | `(1 − horizonWeight) · P(survive season) + horizonWeight · expectedWeeksAlive / n` | `horizonWeight` (default 0.5, range 0..1) is a `ModelSettings` field; `n` = number of weeks in the route. |

`expectedWeeksAlive` and its building block `aliveAfterEachWeek` (the survival probability after each
week-`k` prefix) live in `Survival`, computed incrementally from the same loss-distribution DP as
`lossDistribution`, O(n²). Because these are *prefix* probabilities, unlike `P(0)+P(1)` they depend on which
week each team is assigned to, not just which teams are used — so `EXPECTED_WEEKS_ALIVE` can prefer spending a
very safe team **now** instead of saving it for a marginally-better spot later, where `SURVIVE_SEASON` would
save it. `Route.objectiveValue(objective, horizonWeight, poolEntries, fieldWinProbability)` reports whichever
value is configured; `Route.survival` and `Route.expectedWeeksAlive` are always available for reporting
regardless of what the optimizer is climbing.

Opportunity cost and season path loss (§3) are relative losses of this same objective value, not always of raw
survival probability.

### Two starting points for the local search

Step 2's local search (replace-with-unused and pairwise swap moves) is a hill-climb, so where it starts
matters. The Hungarian assignment (maximizing `P(0 losses)`) is an excellent seed for `SURVIVE_SEASON`, but it
can strand `EXPECTED_WEEKS_ALIVE`, `BLENDED`, or `POOL_WIN` in a local optimum a different starting point would
have avoided — for example, a "triangle" of three weeks and three teams where each team is available in only
two of the three weeks can have exactly two complete assignments, reachable from each other only by rotating
all three at once, which neither a single replace nor a single swap move can do. So the optimizer runs the
local search from **both** the Hungarian assignment and the plain greedy assignment (highest probability per
week, in week order, never reusing a team) and returns whichever finished assignment scores higher on the
selected objective. This never returns a worse objective value than either starting point alone, and is fully
deterministic given the same inputs.

### Pool-win objective

`SURVIVE_SEASON` answers "will I still be alive at the end of Week 18?" — but most pools never get that far:
they end the moment only one entry (or none) is left standing. A 10-entry pool typically resolves by
mid-season; a 500-entry pool usually runs the full year. `POOL_WIN` answers the question that actually
determines whether you take home the pot: **P(you win the pool)**, given how many entries are in it.

**Field model.** The rest of the pool is modeled as `m = poolEntries - 1` other entries, each independent,
each starting the remaining season fresh (zero strikes already used — a full double-elimination allowance)
and picking at a flat weekly win probability `ModelSettings.fieldAverageWinProbability` (default 76%) every
week. This is a deliberate simplification: real entries differ in skill, enter the remaining season with a mix
of strikes already used, and don't all have the identical schedule access — but it keeps the field a single
tunable number instead of requiring a model of every other entrant's picks (§8 describes how the *current*
week's flat `f` is sharpened using real Yahoo pick shares, when known). One other entry's alive-after-week-`k`
curve, `a_k` (`a_0 = 1`), is `Survival.fieldAliveCurve(n, f)` — the same `aliveAfterEachWeek` math applied to
`n` copies of `f`.

**All others eliminated by week `k`.** With `m` independent identical entries, `D_k = (1 - a_k)^m` for
`k = 0..n` (`Survival.otherEntriesEliminatedCdf`); `D_0 = 0` for `m > 0` (nobody's played yet) and `D_0 = 1`
when `m = 0` (no other entries), which the `(1-a_0)^m = 0^m` formula already gives by the standard `0^0 = 1`
convention.

**Your win probability**, for `you_k` = `aliveAfterEachWeek(ps, strikesAllowed)[k]` (`you_0 = 1`):

- **Outright**, you're the sole remaining entry the moment the field is fully eliminated:
  `Σ_{k=1..n} you_k · (D_k − D_{k−1})`. `you_k` already allows you to have taken a loss in week `k` itself and
  still be within your strike allowance, so a week where you *and* the field's last survivor both go out is
  correctly excluded (`you_k` is false for you there).
- **End of season**, you're alive after week `n` sharing the pot with `K ~ Binomial(m, a_n)` other survivors,
  worth `1/(1+K)`: `E[1/(1+K)] = (1 − (1 − a_n)^(m+1)) / ((m+1)·a_n)` (1 when `a_n = 0`). `E[1/(1+K)]` counts
  `K = 0` (probability `D_n`, i.e. sole survivor at week `n`), which the outright sum already counted at
  `k = n`, so the end term subtracts it: `you_n · (E[1/(1+K)] − D_n)`.

`poolWinProbability(ps, strikesAllowed, poolEntries, f) = outright + endOfSeason`. `ModelSettings.poolPayoutSplit`
does not change this formula — an even split of the pot and a tiebreaker you'd win with probability `1/(1+K)`
give you the same *expected* share — the setting exists only so Settings can state the assumption; see
`Survival.poolWinProbability`'s KDoc. Verified against brute-force enumeration of every win/loss sequence for
tiny pools in `SurvivalTest`.

`Evaluation` reports `poolWinProbability` (this formula along the displayed route, undiscounted),
`expectedFieldSurvivors` (`week → m · a_k`, non-increasing), and `expectedPoolEndWeek` (the expectation of the
week the field's last other entry is eliminated, from `D_k`'s increments; a route where the field can still be
alive past the last modeled week is folded in as "last week + 1").

## 7. Monte Carlo

Each simulated season walks the route: every week is an independent Bernoulli trial at that game's undiscounted
win probability; a second strike eliminates. Reported: P(survive season), P(zero-loss finish), P(reach Week
10 / 14 / 18), expected strikes, expected elimination week, expected weeks alive (mean number of weeks
survived in the simulation, comparable to `Survival.expectedWeeksAlive`), and `poolWinProbability` — not
simulated, computed analytically from the route's raw probabilities via `Survival.poolWinProbability` using
the configured `poolEntries` and `fieldAverageWinProbability`, the same way `Route.survival` is. Strategies
compared:

| Strategy | Route |
|---|---|
| Highest win % each week | greedy, no reuse |
| Future-value optimized | Hungarian + local search on discounted probabilities, using the configured route objective (the app's recommendation) |
| Zero-loss path (undiscounted) | Hungarian only, no discount |
| Contrarian (ownership-nudged) | optimizer on probabilities nudged by `0.25 · (equity − 1)` where pick shares exist, using the configured route objective |
| Expected-weeks-alive optimized | Hungarian + local search on discounted probabilities, always with `EXPECTED_WEEKS_ALIVE` regardless of settings, so it can be compared against the configured objective |
| Pool-win optimized | Hungarian + local search on discounted probabilities, always with `POOL_WIN` at the configured pool size, regardless of settings |

## 8. Ownership and pool equity

**Pick share, automatically.** Every refresh pulls Yahoo Survival Football's public pick-distribution page -
the real share of ALL Yahoo Survival Football entries picking each team, for the current week, no login
required (see `docs/DATA_SOURCES.md` "Survivor pick popularity (Yahoo)" and `YahooPickDistributionParser`).
Fetched shares land on `Season.pickShares` (week → team → share). `Evaluator.effectivePickShare(season, user,
week, team)` is the pick share used everywhere in the model: a manual `Adjustment.estimatedPickShare` for that
team-week always wins when set, else Yahoo's fetched share, else null (no estimate at all, leverage disabled
for that team). A Yahoo fetch failure is non-fatal - the app keeps whatever shares it already had.

**Leverage.** With effective pick share `o` and the field's average win probability `f` (default 76%):

- surviving share of the pool if you advance ≈ `o + (1 − o) · f`
- **expected pool equity** = `p / (o + (1 − o) · f)` (1.0 = average)
- **leverage score** = `(equity − 1) · 100`

`Safety.leverage` computes this from `effectivePickShare`; `TeamWeekEvaluation.leverage` (and its
`leverageAdjustment` Safety term) reflect it for every team playing the current week, not just ones with a
manual estimate. Strategy presets set the leverage weight: Conservative 0, Balanced 0.3, Contrarian 0.7, Max
Pool Equity ranks by equity outright. This is a proxy - it does not know which teams *your specific* pool's
entries hold, only Yahoo's much larger public pool - so a manual pick share remains available to override it
per team-week; a pool-size-aware equity model is listed under future improvements.

**Field win probability, refined for the current week.** `RouteObjective.POOL_WIN`'s field model (§6 "Pool-win
objective") normally uses one flat `f` for every remaining week. When Yahoo (or manual) pick shares are known
for the *current* week, `Evaluator` instead computes `fieldWinProbabilityThisWeek = Σ share_t · p_t / Σ share_t`
over the teams playing this week with a known effective share, using their resolved current-week win
probabilities `p_t` - i.e. the field's actual pick-weighted win rate this week, rather than the flat average.
`Evaluation.fieldWinProbabilityThisWeek` exposes this (null when no shares are known for the current week).
`Survival.poolWinProbability`, `Survival.objectiveValue`, `Route.objectiveValue` and `Optimizer.optimize` all
accept an optional per-week field-win-probability override (`fieldWinProbabilities`, sized to the route/sequence
in week order) that replaces the flat `f` for the corresponding weeks and falls back to it everywhere else;
`Evaluator` passes `[fieldWinProbabilityThisWeek] + flat f for every later week` wherever it builds or scores a
route, so `Evaluation.poolWinProbability` and the season optimizer both use the sharper current-week number
while every existing caller that omits the override keeps the exact flat-`f` behavior it always had.

## Formula reference

| Quantity | Formula | Where |
|---|---|---|
| implied probability | `o<0: −o/(−o+100)`, `o>0: 100/(o+100)` | `Probability.impliedFromAmerican` |
| no-vig | `a / (a + b)` | `Probability.noVig` |
| spread → p | `Φ(s / 11.0)` | `Probability.winProbabilityFromSpread` |
| p → spread | `Φ⁻¹(p) · 11.0` (Acklam) | `Probability.spreadFromWinProbability` |
| discount | `0.5 + (p − 0.5)(1 − d)^k` | `Probability.discount` |
| rest days | days between kickoffs, 7 for Week 1, clamped 3–21 | `ScheduleAnalysis.restDays` |
| P(0), P(1) | product / sum of single-loss products | `Survival` |
| alive after week k | `P(at most strikesAllowed losses among first k games)` | `Survival.aliveAfterEachWeek` |
| expected weeks alive | `Σₖ aliveAfterEachWeek[k]`, k = 1..n | `Survival.expectedWeeksAlive` |
| field alive curve | `aliveAfterEachWeek(n copies of f, strikesAllowedForField)` | `Survival.fieldAliveCurve` |
| field fully eliminated by week k | `D_k = (1 − a_k)^m`, m = poolEntries − 1 | `Survival.otherEntriesEliminatedCdf` |
| pool-win probability | `Σₖ you_k·(D_k−D_{k−1}) + you_n·(E[1/(1+K)]−D_n)`, K ~ Binomial(m, a_n) | `Survival.poolWinProbability` |
| route objective | `POOL_WIN: poolWinProbability`; `SURVIVE_SEASON: P(0)+P(1)`; `EXPECTED_WEEKS_ALIVE: expectedWeeksAlive`; `BLENDED: (1−horizonWeight)·survive + horizonWeight·expectedWeeksAlive/n` | `Survival.objectiveValue`, `Route.objectiveValue` |
| opportunity cost | `100(1 − V_without/V_with)`, path from next week, `V` = the configured route objective | `Evaluator` |
| season path loss | `100(1 − V_locked/V_unconstrained)`, `V` = the configured route objective | `Evaluator` |
| safety | see §4 | `Safety.components` |
| equity | `p / (o + (1 − o) f)` | `Safety.leverage` |
