# Model

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

Final margin is modelled as Normal(spread, σ) with **σ = 13.45 points**, so a team favoured by `s` points wins
with probability `Φ(s / σ)`. This is the standard NFL closing-line fit (2000–2024 favourites: −3 ≈ 59%,
−7 ≈ 70%, −10 ≈ 77%, −14 ≈ 85%). σ is editable in Model Settings.

| Favorite by | Win probability |
|---:|---:|
| -0 | 50.0% |
| -1 | 53.0% |
| -1.5 | 54.4% |
| -2 | 55.9% |
| -2.5 | 57.4% |
| -3 | 58.8% |
| -3.5 | 60.3% |
| -4 | 61.7% |
| -4.5 | 63.1% |
| -5 | 64.5% |
| -5.5 | 65.9% |
| -6 | 67.2% |
| -6.5 | 68.6% |
| -7 | 69.9% |
| -7.5 | 71.1% |
| -8 | 72.4% |
| -8.5 | 73.6% |
| -9 | 74.8% |
| -9.5 | 76.0% |
| -10 | 77.1% |
| -10.5 | 78.3% |
| -11 | 79.3% |
| -12 | 81.4% |
| -13 | 83.3% |
| -13.5 | 84.2% |
| -14 | 85.1% |
| -15 | 86.8% |
| -16 | 88.3% |
| -17 | 89.7% |
| -18 | 91.0% |
| -20 | 93.1% |

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
   maximises `P(0 losses)`. This is a good starting point for every objective below, since a zero-loss path is
   never a bad path to start improving from.
2. **Local search** then climbs the *selected* objective (see below) with two move types until no improvement:
   replace a week's team with an unused team; swap the teams of two weeks.
3. Recorded picks for the current or future weeks are **locked**; used teams are removed.

The displayed route always starts with the recommended (or recorded) pick so the planner, season survival and
Monte Carlo describe the plan you would actually follow; the unconstrained optimum is shown alongside when it
differs, with the objective delta, and the difference feeds the "season path loss" term.

The greedy "highest win % every week" route is kept as a baseline for the Monte Carlo comparison.

### Route objective

A real pool rarely requires the entry to survive all 18 weeks — pools usually end once everyone else is
eliminated — so being alive for **more weeks** has value even for an entry that eventually takes a fatal loss.
`ModelSettings.routeObjective` (default `SURVIVE_SEASON`) selects what the local search in step 2 climbs:

| Objective | Formula | Notes |
|---|---|---|
| `SURVIVE_SEASON` | `P(0 losses) + P(1 loss)` (or `P(0)` after a strike) over the whole route | Current/default behavior — maximizes P(alive after Week 18). |
| `EXPECTED_WEEKS_ALIVE` | `Σₖ P(at most strikesAllowed losses among the first k games)`, k = 1..n | Maximizes the expected number of remaining weeks the entry gets through, rewarding routes that stay safe *early* even at some cost to the full-season number. |
| `BLENDED` | `(1 − horizonWeight) · P(survive season) + horizonWeight · expectedWeeksAlive / n` | `horizonWeight` (default 0.5, range 0..1) is a `ModelSettings` field; `n` = number of weeks in the route. |

`expectedWeeksAlive` and its building block `aliveAfterEachWeek` (the survival probability after each
week-`k` prefix) live in `Survival`, computed incrementally from the same loss-distribution DP as
`lossDistribution`, O(n²). Because these are *prefix* probabilities, unlike `P(0)+P(1)` they depend on which
week each team is assigned to, not just which teams are used — so `EXPECTED_WEEKS_ALIVE` can prefer spending a
very safe team **now** instead of saving it for a marginally-better spot later, where `SURVIVE_SEASON` would
save it. `Route.objectiveValue(objective, horizonWeight)` reports whichever value is configured;
`Route.survival` and `Route.expectedWeeksAlive` are always available for reporting regardless of what the
optimizer is climbing.

Opportunity cost and season path loss (§3) are relative losses of this same objective value, not always of raw
survival probability.

## 7. Monte Carlo

Each simulated season walks the route: every week is an independent Bernoulli trial at that game's undiscounted
win probability; a second strike eliminates. Reported: P(survive season), P(zero-loss finish), P(reach Week
10 / 14 / 18), expected strikes, expected elimination week, expected weeks alive (mean number of weeks
survived in the simulation, comparable to `Survival.expectedWeeksAlive`). Strategies compared:

| Strategy | Route |
|---|---|
| Highest win % each week | greedy, no reuse |
| Future-value optimized | Hungarian + local search on discounted probabilities, using the configured route objective (the app's recommendation) |
| Zero-loss path (undiscounted) | Hungarian only, no discount |
| Contrarian (ownership-nudged) | optimizer on probabilities nudged by `0.25 · (equity − 1)` where pick shares exist, using the configured route objective |
| Expected-weeks-alive optimized | Hungarian + local search on discounted probabilities, always with `EXPECTED_WEEKS_ALIVE` regardless of settings, so it can be compared against the configured objective |

## 8. Ownership and pool equity

With an estimated pick share `o` and the field's average win probability `f` (default 76%):

- surviving share of the pool if you advance ≈ `o + (1 − o) · f`
- **expected pool equity** = `p / (o + (1 − o) · f)` (1.0 = average)
- **leverage score** = `(equity − 1) · 100`

Strategy presets set the leverage weight: Conservative 0, Balanced 0.3, Contrarian 0.7, Max Pool Equity ranks
by equity outright. This is a proxy; a pool-size-aware equity model is listed under future improvements.

## Formula reference

| Quantity | Formula | Where |
|---|---|---|
| implied probability | `o<0: −o/(−o+100)`, `o>0: 100/(o+100)` | `Probability.impliedFromAmerican` |
| no-vig | `a / (a + b)` | `Probability.noVig` |
| spread → p | `Φ(s / 13.45)` | `Probability.winProbabilityFromSpread` |
| p → spread | `Φ⁻¹(p) · 13.45` (Acklam) | `Probability.spreadFromWinProbability` |
| discount | `0.5 + (p − 0.5)(1 − d)^k` | `Probability.discount` |
| rest days | days between kickoffs, 7 for Week 1, clamped 3–21 | `ScheduleAnalysis.restDays` |
| P(0), P(1) | product / sum of single-loss products | `Survival` |
| alive after week k | `P(at most strikesAllowed losses among first k games)` | `Survival.aliveAfterEachWeek` |
| expected weeks alive | `Σₖ aliveAfterEachWeek[k]`, k = 1..n | `Survival.expectedWeeksAlive` |
| route objective | `SURVIVE_SEASON: P(0)+P(1)`; `EXPECTED_WEEKS_ALIVE: expectedWeeksAlive`; `BLENDED: (1−horizonWeight)·survive + horizonWeight·expectedWeeksAlive/n` | `Survival.objectiveValue`, `Route.objectiveValue` |
| opportunity cost | `100(1 − V_without/V_with)`, path from next week, `V` = the configured route objective | `Evaluator` |
| season path loss | `100(1 − V_locked/V_unconstrained)`, `V` = the configured route objective | `Evaluator` |
| safety | see §4 | `Safety.components` |
| equity | `p / (o + (1 − o) f)` | `Safety.leverage` |
