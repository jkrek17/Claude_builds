# NFL totals model: market-free, walk-forward

## Method
Games are processed one calendar day at a time; a game's features come only from earlier days.

1. **Ratings.** Three online, opponent-adjusted systems: points, offensive EPA/play and plays (pace). Expected value = league mean + team offence + starting-QB term + opponent defence; each term moves by a learning rate times the game's residual. Team ratings regress to the mean each off-season; the league mean is an exponentially weighted average of past games. The QB term travels with the QB, so starter changes are priced automatically.
2. **Linear layer.** Ridge regression on 9 features (league level, offence and defence sums for points and EPA, pace, indoor roof, stadium-by-month wind climatology from earlier seasons, divisional game), refit each season on seasons 2000..S-1 only.

Rating hyper-parameters were tuned on seasons <= 2009 (`backtest.py --tune`). Results: 2010-2025, 4,363 games.

## Headline
| | MAE | RMSE | bias | residual SD |
|---|---|---|---|---|
| Model | 10.59 | 13.43 | +0.49 | 13.42 |
| Closing total | 10.47 | 13.24 | +0.52 | 13.23 |
| Ratings sum, no regression | 10.78 | 13.58 | -0.67 | |
| League average only | 10.99 | 13.89 | +0.13 | |

MAE gap to the line: +0.117 (paired SE 0.036). Correlation with the closing total 0.83; with the actual total 0.26 (line: 0.31). Regressing actual on both: line 0.91 (SE 0.08), model 0.08 (SE 0.09).

## Over/under at the closing total (pushes excluded; break-even 52.4%)
| abs(model - line) | W-L | win % | SE | z vs 52.4% |
|---|---|---|---|---|
| all | 2208-2110 | 51.1 | 0.8 | -1.64 |
| > 1 | 1523-1423 | 51.7 | 0.9 | -0.74 |
| > 2 | 918-851 | 51.9 | 1.2 | -0.41 |
| > 3 | 491-428 | 53.4 | 1.6 | +0.63 |
| > 4 | 215-213 | 50.2 | 2.4 | -0.89 |
| > 5 | 100-92 | 52.1 | 3.6 | -0.08 |

No threshold clears break-even beyond noise; the >3 row sits between two losing rows. Per season, the all-games win rate ranges from 45.1% to 55.1%. At >2 it was 53-61% in each of 2010-2015 and 46-52% in each of 2018-2025: any early edge is gone. Details: `backtest_output.txt`, section 2.

## Calibration
- **Distribution shape is fine.** P(total > T) from a normal with SD 13.4 around the prediction matches observed frequencies within 2 points in every decile (thresholds 30.5 to 60.5). Residual skew is +0.30 (95th percentile at +1.74 SD, 5th at -1.55 SD), so very high totals are slightly under-weighted.
- **Probabilities against the market line are not usable.** When the model says P(over) is 0.60-0.65, overs hit 52.2%; observed frequency stays within 44-54% in every bin. Do not size bets from `over_probability(pred, closing_line)`.

## What helped and what did not (walk-forward RMSE)
- Points ratings only 13.57; + EPA/play 13.50; + pace 13.49; + indoor 13.45; + wind climatology 13.44; + divisional 13.43.
- Removing the QB term costs 0.075. Points ratings are redundant given EPA (dropping them: 13.41).
- No help (within 0.003): late-season outdoor flag, playoff flag, week number, ridge versus OLS.
- Observed game-day wind and cold, an **upper bound that is not knowable in advance and is not in the model**: 13.39.

## Checks
- Truncation: deleting all rows after 2012 wk 5, 2019 wk 12, 2023 wk 17 and 2026 wk 10 leaves every output column for that week and all earlier games identical (max abs diff 0.0).
- Scrambling scores, lines, weather and stats on or after a game day leaves that day's predictions identical (three dates).

## Caveats
- Pre-2010 validation (1,869 games) could not separate feature sets (all 13.61-13.64). Indoor, wind and divisional terms were kept on prior knowledge and the 2010-2025 ablation: about 0.02 RMSE of post-hoc selection.
- The league-average learning rate (0.002) was set by judgement; pre-2010 tuning preferred slower. Out of sample it makes no difference.
- QB ids are actual starters, so a late scratch is known to the backtest. Unplayed games without a listed QB use the last starter (`qb_known = 0`).
- No non-QB injuries, forecast weather or coaching changes. Bets are graded at the closing number at -110; opening lines were not tested.

## Conclusion
**The model does not beat the closing total.** It is 0.12 points worse in MAE, wins 51.1% of bets against a 52.4% break-even, and carries no weight once the line is known. It is a market-free prior, not a betting signal.
