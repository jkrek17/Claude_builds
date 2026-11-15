# Betting system: what the backtest found

The rules were written first ([RULEBOOK.md](RULEBOOK.md), committed before any of this was run), chosen
on 2023 and 2024, and scored once on 2025 and 2026 (through week 10). Full output:
[backtest_output.txt](backtest_output.txt). To rerun:

```
python research/props/prices/evaluate.py ODDS_DIR      # writes scored_30.csv.gz (paid data, not in the repo)
python research/system/backtest.py ODDS_DIR
```

## The system

1. FanDuel main line, receiving yards and receptions, **the under only**.
2. The model's edge at the posted price is at least 10%.
3. The edge after mixing the model with the book's own chance (weight 0.34 on the model) is at least 3.1%.
4. At most one bet per player and two per game.
5. Bet in kickoff order, **$5 flat**, until 10 are placed in the week ($50).
6. Bet in the half hour before kickoff, after the inactive list is out. That is the only timing tested.

`nflbets` (see the main README) prints the bets that pass these rules from live FanDuel prices.

## Result

| | Bets | Win rate | Return per $1 | 95% range |
|---|---|---|---|---|
| Design seasons 2023 to 2024 | 377 | 55.7% | +8.7% | -0.8% to +18.2% |
| **Judging seasons 2025 to 2026** | 272 | 59.9% | **+16.9%** | +4.6% to +29.2% |
| All four | 649 | 57.5% | +12.1% | +4.5% to +19.7% |

At $5 a bet that is $3,245 staked and $393 won over three and a half seasons, with 39% of weeks
losing and a deepest drop of $51. The system passed both tests set in advance: a positive return on
the judging seasons, and better than every one of 2,000 draws of ten blind unders a week.

## How much of this is luck

Most of the size, probably not all of the sign.

- **Do not expect +17%.** The same pool of bets without the weekly cap returned +6.7% on the judging
  seasons (1,452 bets) and +4.0% on the design seasons. Ten random bets a week from that pool landed
  between -1% and +14.5% in 90% of draws. The capped system drew a good hand. A sensible planning
  figure is **+3% to +7%**.
- **The judging seasons were not fully fresh.** The plain 5% rule was already known to be positive in
  every season, and the model's design was chosen on overlapping seasons.
- **One adopted change did not hold up.** Raising the edge bar to 10% won on both design seasons and
  then did slightly worse than the 5% rule on the judging seasons (+6.7% against +7.8%). It stays,
  because the rules were fixed in advance. The one-per-player, two-per-game cap held up (+14.0%).
- **The score cut is not a dial.** Other cuts give +11% to +18% on the judging seasons, and the
  strictest gives -1% on 96 bets. Nothing here separates them.
- **A season is too short to tell.** 180 bets pin the return down only to about plus or minus 15%.

What a season of ten $5 bets a week ($900 staked) looks like by luck alone:

| True return | Bad season (1 in 20) | Typical | Good season (1 in 20) | Chance of losing money |
|---|---|---|---|---|
| -4.5% (no skill, paying the vig) | -$149 | -$41 | +$67 | 73% |
| 0% | -$108 | $0 | +$108 | 50% |
| +3% | -$80 | +$29 | +$136 | 33% |
| +6% | -$55 | +$54 | +$162 | 21% |

So even if the edge is real, one season in three or four ends down.

## Rules that were tested and dropped

| Rule | Design seasons | Verdict |
|---|---|---|
| Bet overs as well | worse in 2023 | Rejected. Overs add nothing (and +3.1% against +7.8% on the judging seasons). |
| Drop bets where the model disagrees by more than 25% | worse in both | Rejected. The biggest disagreements were not the worst bets. |
| Skip a player's first 8 games with a new team | worse in 2024 | Rejected. Those bets returned +6.9% (301 bets, wide range). |
| Receptions only, or receiving yards only | each worse in one season | Rejected. Both props carry it. |

## Line shopping

Fanatics lists only about 55% of the players the system bets. Taking Fanatics whenever it showed a
higher line or a better price did **not** help (+6.3% against +6.7% for FanDuel alone on 1,452 bets):
a higher line at Fanatics usually comes with a worse price. Not shown to be worth the effort; the
system stays FanDuel only. Key numbers on spreads could not be tested, because the price history
holds props only.

## Staking and stop rules

- Flat $5 and Kelly-shaped stakes returned about the same (+12.1% against +13.2%) and the Kelly
  version had the deeper drop. Flat stays.
- $5 a bet needs a bankroll of roughly $250 to ride out a bad season. On a $50 to $100 bankroll, $1 to
  $2 a bet is the size that fits.
- The season pause ($150 down from the high) fires in 12% of seasons with no skill and 1% with a real
  edge. The retire rule (below -10% after 200 bets) fires 22% of the time with no skill and 3% with a
  +3% edge. Both protect the bankroll; neither can prove the edge is gone, and a bettor with no skill
  passes the retire check about three times in four.

## Not tested, and what could break it

- Betting earlier than 30 minutes before kickoff (needs about 40,000 Odds API credits).
- Prices moving between the scan and the bet. `nflbets` prints the worst price still worth taking.
- FanDuel limiting an account that keeps winning on props.
- The model was fitted with the real active list, which is known about 90 minutes before kickoff.
