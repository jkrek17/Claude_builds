# Betting system rulebook

Written on 2026-11-15 **before** the backtest in this folder was run. The commit history shows the
order. Results are in `README.md`; this file is changed only by adding to "Verdicts" at the end.

## What the system is for

Small flat bets ($5, at most $50 a week) at FanDuel and Fanatics on NFL player props, placed from a
phone in the half hour before kickoff. It uses the one place the project has seen a sign of an edge:
the props model against real FanDuel prices (about +2.4%, give or take 2.5%, on 11,457 bets). Game
sides and totals are not in the system because the game model never beat the closing line.

## Data and split

- Prices: one snapshot 30 minutes before kickoff, 992 games, 2023 to week 9 of 2026
  (`scored_30.csv.gz`, made by `research/props/prices/evaluate.py`). The model was fitted on earlier
  seasons only and knew the real active list.
- **Design seasons: 2023 and 2024.** Every choice below is made on these alone.
- **Judging seasons: 2025 and 2026.** The chosen system is scored there once. A rule that fails there
  is dropped, not adjusted.
- Not clean, and said so: the plain 5% rule's result in each season was already known when this was
  written (positive in all four), and the model's design was chosen on seasons that overlap. Only the
  new rules below (ranking, caps, skips, staking) are untouched by earlier looks.

## Base rule (B)

FanDuel main line (the most evenly priced two-sided line per player). Props: receiving yards and
receptions. Side: under. Model edge at the posted price (model chance x decimal price - 1) of at
least 5%.

## Candidate changes

Each is one switch on top of the base rule, with the reason an expert would give for it.

| # | Change | Reason |
|---|---|---|
| C1 | Both sides instead of unders only | Check that "unders only" is doing work. Expect worse. |
| C2 | Edge at least 10% instead of 5% | Bigger claimed edge should pay more if the model has skill. |
| C3 | Drop bets with model edge above 25% | A huge disagreement usually means the model is missing news. |
| C4 | Score bets by blended edge (model and book mixed) instead of model edge | The book is the better forecaster, so shrink toward it. The mixing weight is fitted on the design seasons only. |
| C5 | Skip a player in their first 8 games with their current team | The model's ranges are known to be too narrow there. |
| C6 | At most 1 bet per player and 2 per game each week | Bets in one game rise and fall together. |
| C7 | Receptions only, or receiving yards only | See whether one prop carries the result. |

**Adoption test (design seasons only):** a change is adopted only if it gives a higher return than
the base rule in 2023 **and** in 2024, each taken separately. Adopted changes are stacked.

## Weekly cap: 10 bets

About 100 bets a week pass the base rule, so the system needs a way to pick 10. Two versions:

- **W-live (the system of record):** go through the week in kickoff order and bet every qualifying
  line whose score is at least T, until 10 bets are placed. T is the value that gives an average of 10
  to 12 qualifying bets a week in the design seasons. This is what can be done live, because Monday's
  prices are not known on Sunday.
- **W-best:** the 10 highest scores of the whole week. Not possible live; shown as an upper bound.
- **W-random:** 10 qualifying bets drawn at random each week, 2,000 draws. This is the luck yardstick:
  it shows how much a 10-a-week sample swings even when nothing about the picking changes.

The score is model edge, or blended edge if C4 is adopted.

## Line shopping (measured, not chosen)

Fanatics prices exist only from 2025, so this cannot be chosen on the design seasons. For the
system's bets in 2025 and 2026, report how often Fanatics has a better under (a higher line, or the
same line at a better price), and the return from always taking the better of the two.

Key numbers on spreads (3 and 7) cannot be tested: the price history holds props only, not game lines
from both books.

## Staking

Flat $5 is the rule. Compared against stakes in proportion to the Kelly fraction with the same
average stake and a $10 ceiling. Staking cannot create an edge; the comparison is about swings.

## Stop rules

Fixed here, simulated in the report:

- Weekly: never more than $50 staked. No chasing.
- Season: pause for the rest of the season after a $150 drop from the high point.
- Retire: after 200 bets, stop for good if the return is below -10%.

The report states how often each rule would fire by luck alone with a true edge of 0%, +3% and at the
book's vig.

## What counts as success

On 2025 and 2026 together, the W-live system should show a positive return, and should beat random
unders on the same lines (10 blind unders a week, 2,000 draws) more than 90% of the time. Anything
less is reported as "not shown".

Timing (betting the day before) is not tested: it needs about 40,000 Odds API credits and Jason has
not approved the spend.

## Verdicts

(added after the backtest)
