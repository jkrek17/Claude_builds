# De-vig method and the props price test

Question: does a vig-removal method other than the proportional one in `research/props/prices/evaluate.py`
change what we measure against FanDuel prices? **No. Every difference is far inside the noise. Keep
the proportional de-vig.**

Run: `python research/props/devig/devig.py ODDS_DIR/` (reads `scored_30.csv.gz`, the output of
`evaluate.py`; the model is not refitted). Full tables: `devig_output.txt`. Methods are the seven in
`penaltyblog.implied` 1.13.1 (installed with `pip install --no-deps penaltyblog` plus the modules its
import chain needs, because one of its declared dependencies, `socks`, does not build).

## Data

FanDuel, 30 minutes before kickoff, 2023 to week 9 of 2026: 20,611 two-sided main lines in 982 games
(rec_yds, receptions, rush_yds, pass_yds, pass_tds). Median hold 5.8%. The 7,774 FanDuel `any_td` lines
are all one-sided (Yes only), so no method can remove the vig from them; they are left out of everything
here and their bets are unchanged.

## Findings

1. **With two outcomes there are only four distinct methods.** Additive, Shin and differential margin
   weighting give identical numbers; so do odds ratio and logarithmic. So: multiplicative, additive,
   power, odds ratio.
2. **The methods barely disagree on these lines.** 64% of lines are within 2 points of 50/50, where all
   methods agree. Any method differs from multiplicative by more than 1 point on 5.6% of lines (power;
   1.0% for additive/Shin, 1.4% for odds ratio); the largest difference anywhere is 2.4 points.
3. **Accuracy of the fair chance is the same.** Brier 0.24765 for all (power 0.24766). Differences from
   multiplicative are +0.01 to +0.09 in units of 0.0001, with a 95% interval of about ±0.4 to ±0.6. On
   the 1,152 lines where methods disagree by over 1 point, multiplicative is nominally best but the
   interval is five times the difference. By season the sign flips: alternatives better in 2023 and 2025,
   worse in 2024 and 2026.
4. **No favourite-longshot bias to fix.** Calibration slope of the multiplicative fair chance is
   1.007 ±0.146; the other methods push favourites up and give 0.91 to 0.95 (all consistent with 1).
   Favourites priced at a raw 65% or more win 64.0% (±3.0): multiplicative says 64.1%, additive 65.0%,
   power 65.4%. The visible miscalibration is the lean to unders (over rate 48.1%), which is the same
   under every method.
5. **The model's blend weight does not move.** 0.305 ±0.09 against multiplicative, 0.306 to 0.308 against
   the others; by season the spread across methods is at most 0.01 against intervals of ±0.17 to ±0.23.
   The model's weight does not shrink under any alternative.
6. **Bet ROI in the current code does not depend on the de-vig at all.** `evaluate.bets` uses
   `p_model * dec_over - 1` and `(1 - p_model) * dec_under - 1`; `p_book` is used only in the Brier and
   blend-weight table. Checked by reading the code.
7. **A bet rule that does use the fair chance** (bet on the edge of a blend of model and fair chance,
   intercept and weight fitted on earlier seasons only, so 2024 to 2026) gives the same result under
   every method:

   | blend edge | multiplicative | additive / Shin / DMW | power | odds ratio / log | units vs multiplicative |
   |---|---|---|---|---|---|
   | > 0% | 4,921 bets, +3.1% ±3.2% | 4,873, +3.4% ±3.2% | 4,866, +3.3% ±3.2% | 4,864, +3.3% ±3.2% | +5 to +12, ±30 to ±37 |
   | > 5% | 1,291, +6.1% ±6.1% | 1,255, +6.1% ±6.3% | 1,255, +6.4% ±6.3% | 1,256, +6.0% ±6.3% | -4 to +1, ±16 to ±20 |
   | > 10% | 334, +10.3% ±12.1% | 317, +10.8% ±12.3% | 314, +10.4% ±12.4% | 317, +10.8% ±12.3% | -2 to 0, ±10 to ±11 |

   (± is 1.96 game-clustered standard errors. "Units vs multiplicative" is the paired profit difference
   over all lines.) Only 23 to 345 of the bets change with the method. Season by season the ROI gap to
   multiplicative is at most 0.7 points at edge > 0% and > 5% (intervals ±5 to ±15) and changes sign
   across seasons and thresholds.

## Not tested here

- Whether the blend rule itself beats the current model-only rule. Its pooled ROI is higher on fewer
  bets (+3.1% on 4,921 against +0.6% on 10,525 at edge > 0, 2024 to 2026) but the intervals overlap and
  it was not the question; it needs its own test.
- Other books, and other snapshot times.
- Markets with long prices on both ends (alternate lines, any_td if a No price were available), where
  the methods would differ much more.
