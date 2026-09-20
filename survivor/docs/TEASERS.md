# Teasers

A Wong teaser finder, entirely separate from the Bets screen's straight-bet board (`docs/BETTING.md`).
Implemented in `engine/.../Teasers.kt` (`Teasers`) and shown on its own screen, reached from a "Teasers"
button on Bets.

## What a Wong teaser is

A 6-point two-team teaser lets you move both legs' spreads by 6 points in your favor, at a worse price than
a straight bet (typically -110 to -130 instead of -110 per side). Wong's original insight (Stanford Wong,
*Sharp Sports Betting*) is that two particular 6-point moves are unusually valuable because they cross the
NFL's biggest key number, 3, *twice*:

- A **favorite** closing **-7.5 to -8.5**, teased down to **-1.5 to -2.5** - this crosses both 7 (a common
  final-margin number) and 3.
- An **underdog** closing **+1.5 to +2.5**, teased up to **+7.5 to +8.5** - the same two crossings from the
  other side.

## Why the empirical window rate, not the margin model

This is the one place in the app that deliberately does **not** use the `KeyNumbers` margin model
(`docs/BETTING.md`) to price a leg. Historically (2010-2025, nflverse closing lines, 895 legs at these exact
windows):

| Window | Record | Win rate |
|---|---:|---:|
| Favorite -7.5..-8.5 -> -1.5..-2.5 | 220-81 | 73.1% |
| Underdog +1.5..+2.5 -> +7.5..+8.5 | 450-139 | 76.4% |
| Combined | 670-220 | 75.3% |
| By era | | 73.8% / 76.4% / 75.2% |

`KeyNumbers`'s symmetric margin model, applied to the same legs, predicts only **71.5%** - noticeably lower
than what actually happened. That gap is exactly why this feature exists as its own module: the edge here
is a *specific, well-documented anomaly in these two windows*, not something the general margin model
(fitted to explain the whole distribution, not this one corner of it) reproduces. Pricing these legs off the
model instead of the historical rate would understate the edge and could pass on genuinely good teasers, so
`Teasers` values every qualifying leg with its own empirical window rate (`FAV_WINDOW_RATE` /
`DOG_WINDOW_RATE` in `Teasers.kt`), not `KeyNumbers`.

Both rates are still stable, not a one-era fluke (73.8% / 76.4% / 75.2% across three roughly-equal eras of
the sample) - see the by-era row above.

## The haircut

Because a fixed historical rate is still an estimate, not a guarantee, every leg's rate is reduced by
`ModelSettings.teaserLegHaircut` (default **0.015**, i.e. 1.5 percentage points) before pricing:

```
favWindowRate = 0.731 - teaserLegHaircut
dogWindowRate = 0.764 - teaserLegHaircut
```

This is a deliberately conservative, single fixed haircut rather than a fitted confidence interval - simple
enough to reason about, and cheap insurance against the historical rate drifting or the sample being
slightly optimistic.

## Two-leg pricing and independence

A two-team teaser's win probability is the product of both legs' rates:

```
winProbability = legRateA x legRateB
```

This assumes the two legs are **independent** - true when they're different games (as `Teasers.find` always
requires; it only pairs legs from distinct `gameId`s), but not perfectly true in general (e.g. correlated
weather across a slate, or a Thursday/Sunday pair sharing playoff-seeding stakes). Treated as an
approximation here, not exact.

## Break-even and price sensitivity

For a two-leg teaser at American price `P`, both legs pay off together only when *both* cover, so the
break-even **per leg** is the square root of the break-even for the pair:

```
breakEvenLegRate(P) = sqrt(1 / decimal(P))
```

| Your book's price | Break-even per leg |
|---:|---:|
| -110 | 72.4% |
| -120 | 73.9% |
| -130 | 75.2% |

Worked example at -120 (`decimal(-120) = 1.8333`): a favorite leg (haircut 0.015) prices at 0.731 - 0.015 =
**71.6%**, an underdog leg at 0.764 - 0.015 = **74.9%**. Paired, `winProbability = 0.716 x 0.749 = 0.5363`,
`ev = 0.5363 x 1.8333 - 1 ≈ -1.68%` - slightly negative, since the geometric mean of the two legs (73.2%)
sits just under the -120 break-even (73.9%). A dog+dog pairing at the same price (`0.749^2 = 0.561`,
`ev ≈ +2.85%`) is the stronger combination in practice - `Teasers.find` prices and ranks every pairing (both
favorites, both dogs, or one of each) by EV rather than assuming any one shape is always best.

## Settings

| Setting | Default | Meaning |
|---|---:|---|
| `includeTeasers` | true | Whether the Teasers tab looks for legs at all. |
| `teaserPrice` | -120 | American price your book charges on a 6-point two-team teaser. |
| `teaserPoints` | 6.0 | Points bought on a leg. Only 6 is supported today. |
| `teaserLegHaircut` | 0.015 | Subtracted from both empirical window rates before pricing. |
| `teaserMaxTotal` | 49.0 | Wong's shootout filter - a leg whose game total is above this is flagged "high total" but still shown. |

## Pushes

A teased leg landing exactly on the teased number pushes. Most books reduce a two-team teaser with one
pushing leg to a one-team teaser at the single (worse) price rather than voiding the whole ticket; this app
models that simply as a **refund** - `Teasers.grade` returns `PUSH` (not `LOSS`) whenever no leg lost and at
least one pushed, and `Teasers.profit` pays 0 on a `PUSH`, same as a straight bet. It does not attempt to
re-price the reduced single-team leg at its own odds.

## Grading

`Teasers.grade(bet, season)`: `WIN` if every leg covers its `teasedPoint`, `PUSH` if any leg lands exactly on
it and none lose, `LOSS` if any leg fails to cover, `PENDING` until every leg's game is final.
`Teasers.ledger(season, user)` grades every recorded `TeaserBet` in `UserState.teaserBets` with running
W-L-P, staked, profit and ROI, the same shape as `BettingEngine.ledger` for straight bets.
