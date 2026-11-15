# Props model research

`backtest.py` is the walk-forward test of the shipped model (`src/nflmodel/props.py`); its output is
in `backtest_output.txt` and its summary in `src/nflmodel/props_backtest.json`.

The three folders hold the expert reviews that chose the model's design. Each tested ideas against
the first version of the model (commit `06bcc62`), so their scripts reproduce their numbers only with
`props.py` from that commit. Downloads and caches are not kept.

| Review | Adopted | Tested and rejected |
|---|---|---|
| `usage/` (roles and volume) | Vacated volume from missing teammates, last-game and long-run snap share, memory cut at a new season (x0.7) and a new team (x0.5), absence flags and years of experience for receiving props, a wider gate for receivers whose role just grew | Other decay rates, a third memory horizon for receivers, quarterback-form features for receivers, draft position prior, air yards and target depth for the expected value |
| `environment/` (game and opponent) | Implied team points x the player's level, completion rate allowed, team rushing EPA for passing props, starting quarterback level x receiver level; home field dropped | Defence versus position, non-linear spread and total terms, pace, pass rate, pass rush, rest, division, surface, cold, opponent-adjusted history. Wind helps passing yards by about 0.25% with a perfect forecast and is not in yet |
| `distribution/` (spread and calibration) | Binomial(9) for passing TDs, 2 to 6 expected-value bins for yardage spreads, a direct logistic model for anytime TD, receptions dispersion that shrinks as the mean grows, randomised PITs for scoring counts | Log-link or boosted-tree expected values, gamma, lognormal and skew-normal yardage families, attempts and completions as counts, early-season dummies, rolling training windows, any recalibration layer |

Tested, real, and not yet built: a spread modifier for rushing and receiving yards (target depth, the
player's own volatility, weeks 1 to 4), worth 0.1 to 0.4% CRPS and better calibration for deep
threats (`distribution/q2b_scaled.py`).

Known faults left in the shipped model (from `distribution/final.out`): passing yards run a little
high for the best quarterbacks (300+ yards forecast 23.5%, happened 21.6%), rushing overs run high
in weeks 1 to 4, and quarterback rushing bands are slightly narrow.

How much the game line matters (`environment/exp7_line.py`): without any spread or total, passing
yards lose about a third of their skill over the player's own average and passing TDs over half;
receiving and rushing props barely change. The nflmodel prediction recovers a quarter to a half of that.
