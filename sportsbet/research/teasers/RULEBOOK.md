# Teaser rulebook

Written on 2026-11-16 **before** the model-filter backtest in this folder was run. The commit history
shows the order. Results are in `README.md`; this file is changed only by adding to "Verdicts" at the
end.

## What the system is for

Two-team, 6-point NFL teasers at FanDuel and Fanatics, $5 each, placed from a phone. A teaser moves
both spreads 6 points your way and both legs must win. It costs more than a normal bet (about -120 to
-135 instead of -110), so each leg has to win about three times in four:

| Price of a 2-team teaser | Each leg must win |
|---|---|
| -110 | 72.4% |
| -120 | 73.9% |
| -125 | 74.5% |
| -130 | 75.2% |
| -134 | 75.7% |
| -140 | 76.4% |

The only published FanDuel price found is a fixed -134 (Action Network, undated). Fanatics does not
publish its chart; its house rules say the price is fixed and that a 2-team teaser with a pushed leg
is refunded. Both prices need to be read off the apps.

## Data

- nflverse `games.csv`: final scores and the market's closing spread, 2003 to week 10 of 2026,
  playoffs included. These are consensus closing lines, not FanDuel's or Fanatics' own numbers, and a
  bettor has to place a teaser before its first leg kicks off, so the line bet can differ by half a
  point. Half a point matters here; the backtest shows by how much (see "Half-point check").
- Game model: `nflmodel` walk-forward predicted margins, which never read a betting line. Its settings
  were tuned on 2003 to 2009, so its predictions are honest from 2010.

## Base rule (B): the classic legs

A leg qualifies when the closing spread is

- an underdog of +1.5 to +2.5 (teased to +7.5 to +8.5), or
- a favourite of -7.5 to -8.5 (teased to -1.5 to -2.5).

Both moves carry the bet across 3 and 7, the two most common winning margins. This rule was published
by Stanford Wong in 2001 from 1990s data, so every season here is out of sample for it.

**Already seen, said plainly.** Before this file was written, the base rule was run on 2003 to 2026:
legs won 75.3% (914-300), by era 73.6% (2003-14), 76.7% (2015-19), 77.7% (2020-22), 74.8% (2023-26).
Splits by underdog or favourite, home or away, game total and division game were also looked at on
all seasons. Those splits are therefore **not** candidates: any of them picked now would be picked
with the answer in hand. They are printed in the report for the record and nothing else.

## The model filter (the new, clean test)

For each qualifying leg, `gap` = the model's predicted margin for that team minus the margin the
closing spread implies (model margin + the team's spread). A positive gap means the model likes the
teased side more than the market does.

- **Design seasons: 2010 to 2019.** The filter is chosen here alone.
- **Judging seasons: 2020 to 2026.** The chosen filter is scored there once. Nothing about the gap on
  any season had been looked at when this was written.

Candidates, each one switch on top of the base rule:

| # | Keep a leg only if | Reason |
|---|---|---|
| M1 | gap >= 0 | The model agrees with the side being teased. |
| M2 | gap >= 1 | Stronger agreement. |
| M3 | gap >= 2 | Stronger still, fewer legs. |
| M4 | gap > -2 | Only drop legs the model strongly dislikes. |

**Adoption test (design seasons only).** A candidate is adopted only if all three hold:

1. its kept legs win more often than its dropped legs in 2010-2014 **and** in 2015-2019, each taken
   separately;
2. it keeps at least 40% of qualifying legs (fewer leaves too few teasers to matter);
3. over 2010-2019 the kept-minus-dropped difference is at least 1.5 standard errors.

If more than one passes, the one with the highest kept win rate on the design seasons is adopted. If
none passes, the system is the base rule with no model filter, and the report says so.

**Judging.** The adopted filter (or each candidate, for the record, if none is adopted) is scored on
2020-2026. It is confirmed only if kept legs again beat dropped legs there. A filter that fails is
dropped, not adjusted.

Expectation, written in advance: the model has shown no edge against closing spreads (49.9%), so the
likely result is that no filter passes.

## From legs to teasers

- **Backtest, all pairs:** every pair of qualifying legs from different games in the same week. This
  is the average teaser the rule produces and the headline number.
- **Backtest, live rule:** rank the week's qualifying legs by gap (highest first), one leg per game,
  pair 1st with 2nd and 3rd with 4th. At most 2 teasers a week.
- A teaser with a pushed leg is a refund (Fanatics' rule; assumed for FanDuel until checked).
- Returns are shown at -110, -120, -125, -130 and -134.
- Margin of error: legs in one week are treated as a group (bootstrap over weeks), because pairs share
  legs.

## Price gate (when to actually bet)

A teaser is bet only when the app's price needs each leg to win at least **1 percentage point less**
than the rule's own past record, measured on every season before the current one. With the base
rule's 75.3%, that means a price of about -123 or better. At -134 the system says "no bet" every week
and that is the intended behaviour: the price, not the picks, decides whether there is anything here.

## Stakes

$5 per teaser, at most 2 a week ($10), each game in at most one teaser. This sits beside the props
system's $50 a week, not inside it.

## Reported, not chosen

- Half-point check: the base rule's legs if the line bet had been half a point worse or better.
- 6.5 and 7 point teasers and whole-number lines next to the range (+1, +3, -7, -9).
- Three-team teasers: needs each leg at 72.7% at +160, 73.7% at +150, 74.7% at +140.
- Game totals, home and away, underdog and favourite, by era (already seen; see above).

## Verdicts

Added 2026-11-16 after the backtest (`backtest_output.txt`).

- **Model filter: none adopted.** M1 to M4 all failed the adoption test on 2010-2019. On 2020-2026 no
  candidate separated kept from dropped legs (differences +2.0, -1.2, -3.7 and 0.0 points). The model
  is used only to order the week's pairs.
- **Base rule:** 914-300, 75.3% (±1.2), fair price -131. All pairs returned +1.5% (±4.1) at -134 and
  +6.5% (±4.3) at -120.
- **Price gate:** for 2026 the past record is 75.2%, so the gate allows -123 or better.
- A fresh look at any rule here belongs on seasons after 2026, not on these again.
