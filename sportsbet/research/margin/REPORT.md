# NFL margin model: honest backtest

**Conclusion first: this model does not beat the closing line.** Out of sample 2010-2025 it is 0.24 points of MAE worse than the market and loses money against the spread at every threshold with a meaningful sample. Use it as a market-free prior, not as a betting signal against closing numbers.

## Method (`margin_model.py`)
One chronological pass, batched by calendar date: every game on a date is predicted from the state before that date, then that date's played games update the state.
- **Elo** on margin (K=28, autocorrelation-corrected MOV multiplier, 40% regression to the mean between seasons).
- **Opponent-adjusted EPA ratings**: pass and rush, offence and defence, updated online, 30% regression between seasons.
- **QB value**: opponent-adjusted team passing EPA in a QB's starts (decay 0.9 per start, shrunk to a running new-starter prior), relative to his team's pass rating.
- **Drifting home field**: slow EWMA of home residuals (2.6 early, 1.4 in 2021, 2.0 now); zero at neutral sites.
- **Rest differential**.
- **Blend**: no-intercept ridge; weights for season S are fit on seasons before S only.

Hyperparameters were tuned on 2003-2009 RMSE only. Exception: the HFA drift rate was set by judgement (2003-2009 prefers no drift, since HFA was stable then). The closing line is never read.

## Accuracy, 2010-2025 (n=4,363, playoffs included)
| | MAE | RMSE | corr with result |
|---|---|---|---|
| Model | 10.30 | 13.26 | 0.398 |
| Closing spread | 10.05 | 13.04 | 0.431 |
| Predict 0 | 11.37 | 14.60 | |

Correlation of model with closing spread: 0.896. The model is closer than the line in 45.4% of games and beats the line's MAE in 1 season of 16 (2015). Regressing the result on both gives the line 0.92 and the model 0.14; the slope of (result - line) on (model - line) is +0.107 (SE 0.073), not distinguishable from zero.

## Against the spread, 2010-2025 (pushes excluded)
| abs(model - line) > | Bets | W-L | Win % | SE | Seasons above 52.4% | Clears 52.4%? |
|---|---|---|---|---|---|---|
| 0 | 4,254 | 2100-2154 | 49.4 | 0.8 | 4/16 | No |
| 1 | 2,923 | 1449-1474 | 49.6 | 0.9 | 3/16 | No |
| 2 | 1,850 | 922-928 | 49.8 | 1.2 | 4/16 | No |
| 3 | 1,035 | 528-507 | 51.0 | 1.6 | 6/16 | No |
| 4 | 537 | 281-256 | 52.3 | 2.2 | 10/16 | No |
| 5 | 271 | 144-127 | 53.1 | 3.0 | | Within noise |

Nothing is more than about one standard error above 50%. The upward drift with threshold is noise-level, and 2023-2025 were losing seasons at most thresholds. Per-season records: `backtest_output.txt`.

## Residual SD and win probability
Residual SD is **13.26** (constant shipped: `RESID_SD = 13.3`); the line's is 13.04. Residuals are close to normal (70.2% within 1 SD, 94.8% within 2, excess kurtosis +0.34).

| Home-win forecast (n=4,349) | Brier | Log loss |
|---|---|---|
| Model, Normal(pred, 13.3) | 0.2174 | 0.6236 |
| Model, SD from prior seasons only | 0.2175 | 0.6238 |
| De-vigged closing moneyline | 0.2111 | 0.6096 |
| Base rate | 0.2467 | 0.6866 |

Reliability is good: decile calibration error is 0.015, and nine of ten deciles are within 1.5 SE. The top decile is under-confident (predicted 0.82, actual 0.86, z=2.2). Calibrated, but less sharp than the market.

**Cover probabilities are not usable.** Normal(pred - line, 13.3) implies 36% to 64% cover chances, but actual cover rates are flat at 47% to 52% in every bin.

## What helped (RMSE 2010-2025 when removed)
- Home field +0.16, Elo +0.12, QB +0.11: the three real contributors.
- Pass EPA +0.03, rush EPA +0.02: small.
- Rest: 0.00. It helped slightly in 2003-2009, so it stayed under the selection rule; it is dead weight now.
- Drifting versus static HFA: 0.008 better. Autocorrelation correction: no measurable effect.
- Elo + HFA alone: 13.39, so everything else buys 0.13.

## Leakage checks
- Line MAE 10.05, in the expected 10 to 10.5 range; the model is worse, as it should be.
- Truncation at six cut dates (2012 to 2025), three ways each: later rows removed, later results blanked, later results and stats scrambled. Predictions up to and including the cut date were identical (max difference 0.0) in all 18 runs.
- Shuffling all market columns changes nothing.

## Caveats
- Starting QB ids are the actual starters, as instructed; late scratches are assumed known.
- nflverse EPA comes from a model fit on all seasons, a mild built-in look-ahead in the inputs.
- Thursday results inform Sunday predictions in the same week.
- For 109 unplayed 2026 games with no QB listed, the team's last starter is assumed (`qb_assumed`).
- No non-QB injuries, weather or travel.
- 2003 weights are fit on only three seasons.
