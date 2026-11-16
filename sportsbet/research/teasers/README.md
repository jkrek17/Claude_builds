# NFL teasers: honest result

**Conclusion first: the classic 6-point teaser is a fair bet at about -131, so it is a small loser or
break-even at FanDuel's published -134 and a small winner only at -125 or better. The game model does
not help pick legs.** The price in the app decides everything; the system built here is mostly a price
gate.

Rules were written first (`RULEBOOK.md`, committed before the filter test). Every number below comes
from `backtest.py` (`backtest_output.txt`).

## The classic legs

Underdogs of +1.5 to +2.5 and favourites of -7.5 to -8.5 at the closing spread, teased 6 points,
2003 to week 10 of 2026 (6,381 games). The rule dates from 2001, so all of it is out of sample.

| Seasons | Legs | Won | Underdog legs | Favourite legs |
|---|---|---|---|---|
| 2003-2014 | 309-111 | 73.6% (±2.2) | 72.1% | 75.9% |
| 2015-2019 | 230-70 | 76.7% (±2.4) | 77.7% | 75.0% |
| 2020-2022 | 150-43 | 77.7% (±3.0) | 75.7% | 80.8% |
| 2023-2026 | 225-76 | 74.8% (±2.5) | 78.7% | 63.7% (51-29) |
| **All** | **914-300** | **75.3% (±1.2)** | 75.8% | 74.3% |

75.3% per leg breaks even at **-131**. One standard error either way is -121 to -141, so 24 seasons
cannot tell a -125 teaser from a -134 teaser with confidence. There is no sign the edge has faded: the
last four seasons (74.8%) sit inside the noise of the long-run number. Single seasons run from 67% to
85%.

## What a teaser returned

Every same-week pair of legs from different games (1,875 teasers; a pushed leg refunds):

| Seasons | Won | at -110 | at -120 | at -125 | at -130 | at -134 |
|---|---|---|---|---|---|---|
| 2003-2014 | 51.7% | -1.4% | -5.2% | -6.9% | -8.5% | -9.6% |
| 2015-2019 | 62.6% | +19.1% | +14.5% | +12.4% | +10.5% | +9.1% |
| 2020-2022 | 64.2% | +22.5% | +17.6% | +15.5% | +13.5% | +12.0% |
| 2023-2026 | 56.5% | +7.9% | +3.6% | +1.7% | 0.0% | -1.3% |
| **All** | **58.1%** | **+10.9% (±4.5)** | **+6.5% (±4.3)** | **+4.6% (±4.2)** | **+2.8% (±4.1)** | **+1.5% (±4.1)** |

Under the live rule (at most 2 teasers a week, 456 teasers in 24 seasons): +5.4% at -120, +3.5% at
-125, +0.4% at -134, each give or take about 4.4%.

In money: the live rule makes about 19 teasers a season. At $5 each that is $95 staked; a real 5%
edge is about $5 a season, and a single season swings by far more than that.

## The model filter: not adopted

`gap` is how much more the game model likes the teased side than the closing spread does. Chosen on
2010-2019, scored once on 2020-2026.

| Keep a leg if | 2010-2019 kept | dropped | Passed? | 2020-2026 kept | dropped |
|---|---|---|---|---|---|
| gap >= 0 | 75.4% | 75.7% | No | 76.6% | 74.5% |
| gap >= 1 | 76.5% | 74.7% | No | 75.3% | 76.5% |
| gap >= 2 | 78.7% | 74.0% | No (keeps 33%, lost in 2010-14) | 73.3% | 77.0% |
| gap > -2 | 75.5% | 76.0% | No | 75.9% | 75.9% |

No candidate passed the adoption test, and the one that looked best on the design seasons (gap >= 2)
was the worst on the judging seasons. The gap's correlation with how a leg did is +0.035 on 1,002
legs. This matches everything else known about the model against closing spreads. The weekly list
still shows the model's opinion and uses it to order the pairs, because some order is needed, but it
is not evidence.

## Half a point is the whole edge

Closing lines are not the lines you bet. If the line you get is half a point worse than the close,
legs win 74.3%; half a point better, 76.3%. The two-point swing is larger than the gap between a -120
and a -134 teaser. Check the app's line is inside the range when you bet, and prefer +2.5 and -7.5
over the far ends when there is a choice of timing.

## Things often said about teasers, for the record

All of these were looked at before the rulebook was written, so none is a rule.

- **Low totals are better:** not supported. Total 42 or less 75.3%, 42.5 to 47 77.4%, above 47 72.3%.
- **Home or away:** away legs 76.5%, home legs 74.3%; home favourites are the weakest group (72.5%).
- **Underdogs versus favourites:** favourite legs fell to 63.7% in 2023-2026, on 80 legs. That is
  about two standard errors below their long-run 74.3%, which is what one group in twenty does by
  luck. Not a reason to drop them yet; worth watching.
- **Bigger teasers:** 6.5 points 75.7% and 7 points 76.4% on slightly wider ranges, but those cost
  more. **Wider ranges** (+1 to +3, -7 to -9) at 6 points: 73.5%, worse.
- **Three-team teasers** need each leg to win 72.7% at +160, 73.7% at +150, 74.7% at +140. If either
  app pays +150 or better on three teams, that is a better deal than its two-team price. Not built
  until the real prices are known.

## The system

- Legs: the classic rule, nothing else.
- Price gate: bet only when the app's price needs each leg to win at least one point less than the
  rule's record in all earlier seasons. Today that is 75.2% on 1,167 legs, so **-123 or better**.
  FanDuel at -134: no bet. A book at -120: bet.
- $5 a teaser, at most 2 a week, one leg per game.
- `nflteasers --fanduel -134 --fanatics -120` prints the week's legs, the pairs and the verdict for
  each price.

## Not known

- The real prices. FanDuel's -134 is from an undated article; Fanatics' chart is not published.
  Whether FanDuel refunds a 2-team teaser when a leg pushes was assumed, not checked.
- Each book's own spread at the time of the bet. There is no saved game-line history for either book.
- Whether either book prices teasers leg by leg (worse prices on exactly these legs). Several books
  now do.
