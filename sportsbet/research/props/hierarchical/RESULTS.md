# Hierarchical shrinkage for the props model: review

**Verdict: no. Nothing here clearly beats the shipped model, overall or for players with little history. Do not adopt.**

Question: does a proper hierarchical (partial-pooling) model of a player's level do better than the
hand-built shrinkage in `props.py` (SHRINK = 3 pseudo-games, decays 0.75 / 0.94, memory cuts at a new
season and team, ridge on top)? Props: receiving yards, receptions, rushing yards. Walk-forward, all 12
test seasons 2015 to 2026 (2026 partial), same rows as `P.eligible`, same spread code, same scoring as
`research/props/backtest.py` (`s0_baseline.py` reproduces its MAE and CRPS to four digits).

## Why the answer was likely to be no

The shipped features are `sum / (n + 3)` and `3 / (n + 3)`. A ridge on those two is already a conjugate
normal-normal posterior mean `(k*mu + sum) / (k + n)` with k = 3 and the prior mean `mu` learned per
position by the regression. So "hierarchical" can only change (1) k, (2) how k varies with history, or
(3) the dynamics (decay and breaks). All three were tested.

## What was run

| Tag | Candidate | How |
|---|---|---|
| a | **PyMC k per stat and position** | PyMC 6.4, NUTS (2 chains x 1000 draws), refitted for each test season on earlier seasons only. Hierarchical normal: game ~ N(player-season level, sigma_pos), level ~ N(mu_pos, tau_pos); the levels are integrated out exactly, so NUTS samples mu, tau, sigma. k = sigma^2 / tau^2 replaces 3. No divergences. (`s2_pymc.py`, `pymc_hyper.csv`) |
| b | Ridge + PyMC posterior mean and sd | same hyperparameters, added as extra features |
| a | **Kalman level** (dynamic hierarchical model) | local level per player with position-level prior, drift per game, pull toward the position mean plus extra variance at a new season and a new team, noise growing with the level; 9 hyperparameters per stat and position by maximum marginal likelihood on earlier seasons. This is empirical Bayes (scipy + numba), not PyMC. Three uses: alone, replacing the player's own averages in the ridge, and added to the ridge with its posterior sd (b). |
| c1 | Fixed k = 1, 2, 4.5, 7, 10, and k chosen on the last three training seasons | no PyMC |
| c2 | Weight on own average varies with career games (8/(games+8) interactions), and small-sample / rookie terms | no PyMC |

Learned k (median over folds): targets RB 3.8, WR 1.6, TE 2.2; receptions 4.4 / 1.9 / 2.8; receiving yards
5.5 / 2.6 / 3.4; carries RB 1.5; rushing yards RB 2.6 (QB 5 to 68, unstable with few QBs). So a learned
strength lands at 1.5 to 5.5: the hand-set 3 is in the middle of it.

The Kalman fit (2026 fold, `kalman_hyper.csv`) keeps 0.71 to 0.89 of a player's distance from the position
mean across a season break and 0.27 to 0.69 across a team change (non-QB), against the hand-set memory
cuts of 0.7 and 0.5. The two are not the same quantity, but they say the same thing.

## Results (CRPS change against the shipped model, %; seasons better of 12)

| | rec_yds all | rec_yds <8 games | receptions all | receptions <8 | rush_yds all | rush_yds <8 |
|---|---|---|---|---|---|---|
| rows | 26,257 | 1,146 | 26,257 | 1,146 | 9,278 | 346 |
| PyMC k (a) | +0.01, 4 | +0.00, 7 | +0.01, 2 | +0.12, 4 | **-0.06, 10** | +0.08, 4 |
| Ridge + PyMC mean, sd (b) | +0.01, 3 | -0.02, 6 | -0.01, 8 | -0.16, 9 | +0.05, 4 | +0.22, 4 |
| Kalman level alone (a) | +1.74, 1 | +2.42, 2 | +1.63, 1 | +2.90, 1 | +3.05, 1 | +2.08, 4 |
| Kalman replaces own averages (a) | +0.06, 5 | +0.58, 5 | +0.15, 2 | +0.31, 6 | -0.03, 4 | -1.16, 3 |
| Ridge + Kalman mean, sd (b) | +0.09, 4 | +0.61, 4 | +0.06, 3 | +0.29, 4 | +0.09, 3 | +0.70, 6 |
| k = 1 | -0.00, 7 | +0.08, 7 | +0.03, 3 | +0.31, 4 | +0.02, 6 | +0.56, 3 |
| k = 10 | +0.02, 5 | +0.14, 4 | -0.02, 8 | -0.15, 6 | -0.06, 7 | -0.10, 9 |
| k chosen on train (c1) | +0.01, 4 | +0.23, 4 | -0.01, 5 | -0.15, 4 | -0.00, 3 | -0.18, 7 |
| Weight varies with career (c2) | +0.03, 5 | +0.22, 5 | +0.03, 3 | -0.04, 7 | +0.22, 6 | -0.28, 7 |
| Small / rookie terms (c2) | +0.07, 5 | +0.56, 4 | +0.03, 3 | -0.18, 9 | +0.11, 6 | -0.35, 6 |

Full tables with MAE, bias, 50% and 80% coverage, and the established, rookie and new-team groups are in
`s4_report.out`. Coverage and bias do not move for any candidate (changes of 0.000 to 0.003 overall).

Reading it by the stated bar (pooled change of at least 0.1% and about 9 of 12 seasons):

- Overall, every candidate is within +-0.1% except the ones that are clearly worse. The best is PyMC k on
  rushing yards, -0.06% in 10 of 12 seasons: consistent but below the bar, and the same idea is +0.01% on
  the two receiving props.
- Small-sample rows: the largest gains are -0.16% to -0.18% on receptions (9 of 12) with row-level
  standard errors of 0.1 to 0.3 points, and they do not repeat on receiving yards (same players, +0.6% for
  the same small/rookie terms). Rushing has 346 small-sample rows; -1.16% for the Kalman swap wins only 3
  of 12 seasons. None of this is a result.
- The chosen k jumps between 1 and 10 from season to season (`k_chosen.csv`): the fit is flat in k.
- The Kalman level alone beats the plain slow average (rec_yds 17.00 against 17.06) but is far behind the
  ridge, which gets its edge from the fast average, shares, snaps and game context, not from the shrinkage.

## Is the shipped model biased for small-sample players? (`s1_diagnose.out`)

| | n | bias (pred - actual) | se | share above median | in 50% | in 80% |
|---|---|---|---|---|---|---|
| rec_yds, <8 career games | 1,146 | +0.76 | 0.91 | 0.487 | 0.510 | 0.793 |
| rec_yds, rookies | 2,217 | -0.16 | 0.67 | 0.501 | 0.509 | 0.797 |
| receptions, <8 games | 1,146 | -0.02 | 0.06 | 0.492 | 0.492 | 0.784 |
| receptions, rookies | 2,217 | -0.06 | 0.04 | 0.520 | 0.502 | 0.800 |
| rush_yds, <8 games | 346 | -3.57 | 2.06 | 0.514 | 0.454 | 0.751 |
| rush_yds, rookies | 843 | -1.70 | 1.24 | 0.516 | 0.499 | 0.791 |
| new team, first 8 games: rec_yds / receptions / rush_yds | 2,824 / 2,824 / 1,114 | +0.07 / +0.04 / -1.07 | 0.57 / 0.04 / 0.96 | 0.488 / 0.483 / 0.504 | 0.481 / 0.485 / 0.456 | 0.778 / 0.783 / 0.763 |

No mean bias to fix for the receiving props. Two things do show, neither of which shrinkage addresses:

1. **Bands are a little narrow for players on a new team** (80% band holds 76% to 78% on all three props,
   2 to 3 standard errors low) and for rushers with under 8 games (75%, n = 346).
2. **First game with a new team**: receptions forecast 0.24 high (se 0.10, high in 10 of 12 seasons), rushing
   yards 7.2 low (se 2.8, n = 155). Small groups; treat as leads, not findings.

Note the gates mean a player never gets a line in his first career game, and only 4% of scored rows have
under 8 career games. Even a large gain there would barely move the pooled score.

## No look-ahead

- Shrink and PyMC-mean columns are functions of the panel's own pre-game decayed sums (`stat_slow * (n_slow + 3)`).
- Kalman columns are the filter's state before each game's update, run in date order per player.
- All hyperparameters (PyMC, Kalman, chosen k, ridge, spreads) use seasons before the test season only.
- `python s3_candidates.py leak` scrambles every outcome from 2022 on and confirms the Kalman and shrink
  columns of all 74,283 rows up to and including each player's first scrambled game are unchanged, and that
  later rows do change (`leak_check.out`).
- The gate columns are never modified, so every candidate is scored on exactly the shipped model's rows,
  with the same random draws for tied PITs.

## Limits

- PyMC was used for the static hierarchical model; the dynamic one is maximum marginal likelihood. Full
  NUTS over a state-space model was not attempted; with hyperparameters this well determined (tens of
  thousands of games) it would not change the point forecasts.
- The normal likelihood is a rough fit for yardage; it is used only to set shrinkage weights.
- Not tested: role-specific prior means beyond position (the ridge already has snap share and target share,
  which is the same information), and anything on the rejected list.
- Standard errors treat rows as independent; the seasons-better count is the safer guide.

## Reproduce

```
python s0_baseline.py      # shipped model, per-row scores (checks against backtest_output.txt)
python s1_diagnose.py      # small-sample bias and calibration
python s2_pymc.py          # PyMC hyperparameters per fold (about 3 minutes)
python s3_candidates.py    # all candidates, walk-forward (about 5 minutes); add "leak" for the check alone
python s4_report.py        # tables
```
Per-row scores are cached outside the repo (path in `common.py`, override with `HIER_CACHE`).
