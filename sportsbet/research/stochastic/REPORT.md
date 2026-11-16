# Stochastic game model: does simulating games beat the current model?

**Short answer: no.** The simulation is as accurate as the current model, not more, and neither has
an edge against closing lines. It prices win, spread, total and key-number chances consistently from
one picture of each game, which the current model already does about as well with a simpler method.

`python3 research/stochastic/backtest.py ~/.cache/nflmodel` reproduces every number below; the output
is saved in `backtest_output.txt`.

## What was built

1. **Bayesian ratings** (`nflmodel/bayes_ratings.py`). A Kalman filter follows each team's offence
   and defence for points, passing EPA and rushing EPA. Every rating has a mean and an uncertainty.
   Bands start wide each season, narrow with games, and widen when the starting quarterback changes,
   more so for a team's third starter of the season or later.
2. **Score model** (`nflmodel/gamesim.py`). Each team's expected points from its rating matchups plus
   the existing Elo, quarterback, rest, pace, roof and wind features, and whether the starter is the
   team's second or third quarterback of the season.
3. **Monte Carlo.** Each game is played 10,000 times. A run draws both teams' strength from their
   bands, then builds each score from touchdowns, extra points, field goals and safeties over a
   fixed number of drives, and settles ties in overtime.
4. **Calibration.** The simulated chance of each exact margin and total is corrected by how often
   that number came up in earlier seasons.

`nflsim` prints a week's simulated odds. It is a research tool and is not on the web page.

## How it was tested

- The filter's settings were tuned on 2003 to 2017 (`tune.py`). Nothing was tuned on 2018 onward.
- Scored once on 2018 to 2026 (2,376 games), walk-forward: every forecast uses earlier games only.
  A look-ahead check confirms that removing later games changes no earlier forecast.
- Prices are the closing consensus spread, total and moneyline in nflverse, not FanDuel or Fanatics
  specifically. The saved odds history holds player props only, so there are no past alternate-line
  prices to bet against.

## Results, 2018 to 2026

| | Closing line | Current model | Simulation |
|---|---|---|---|
| Margin, average miss (points) | 9.81 | 10.12 | 10.06 |
| Total, average miss (points) | 10.42 | 10.62 | 10.62 |
| Picks the winner | 66.5% | 65.2% | 65.4% |
| Win chance log loss (lower is better) | 0.609 | 0.624 | 0.624 |
| Against the spread, every game | | 48.3% | 48.8% |
| Against the spread, bets that beat the vig | | 48.2%, return -6.6% | 49.4%, return -4.4% |
| Against the total, bets that beat the vig | | 50.1%, return -2.8% | 50.2%, return -2.7% |
| Moneyline, bets that beat the vig | | return -7.0% | return -9.1% |

Each return has a standard error of 2 to 3 points, so the two models cannot be told apart and both
lose about the vig. Asking for a bigger edge before betting (3% or 6%) does not help either model.

**Margin forecast.** The simulation's margin is slightly better than the current model's (squared
error lower in 7 of 9 seasons, t = 2.0), but the two forecasts are 98% correlated and the gain is
0.06 points against a 0.25 point gap to the closing line. Next to the closing line the simulation
adds nothing (weight 0.04, standard error 0.10).

**Calibration.** Win chances are honest for both models: favourites given 57% won 57%, those given
75% won 75%.

**Key numbers.** A game ended on 3 in 14.9% of cases and on 7 in 8.5%.

| Chance given to | Actual | Current model | Simulation, raw | Simulation, calibrated |
|---|---|---|---|---|
| Margin of 3 | 14.9% | 14.9% | 8.6% | 13.5% |
| Margin of 7 | 8.5% | 9.1% | 6.3% | 8.3% |
| Push when the spread is 3 (339 games) | 9.4% | 8.2% | 4.8% | 7.6% |
| Push when the spread is 7 (130 games) | 6.9% | 5.5% | 3.9% | 5.1% |

The raw simulation gets the shape right (3 and 7 stand out) but undercounts both, because real teams
play to the score late in a game: they kick to tie or win, which independent drives cannot know.
After calibration the simulation matches the current model's key-number method, which stays the
better tool for pushes and half points. On the whole distribution the two are level (average log
chance of the exact margin: current -3.854, simulation -3.857, market line -3.832).

**Uncertainty bands.** The bands are honest: 69% of team scores fall inside the 68% band. But they
barely differ between games (rating uncertainty 3.5 points in weeks 1 and 2, 3.1 from week 9), next
to a 9.4 point game-to-game spread in one team's score. Games the simulation called most uncertain
were not missed by more (correlation with the size of the miss -0.01). Giving every game the same
band scores the same. This repeats the earlier ensemble finding: almost all of the noise is the game
itself, not doubt about the teams.

**Quarterback depth.** Team points, actual minus forecast, by which starter of the season played:

| | Team-games | Closing line | Current model | Simulation |
|---|---|---|---|---|
| First starter | 3,755 | +0.4 | +0.4 | +0.3 |
| Second starter | 783 | -0.4 | -0.9 | -0.9 |
| Third or later | 214 | +1.1 | +0.5 | +1.0 |

Teams on their second quarterback scored about a point less than either model expected, which is a
real miss (standard error 0.3), and the closing line had most of it. Teams on a third quarterback
did not score less than forecast on average in this window (standard error 0.7), so there is no
general "third string" penalty left to add, although single games can miss badly. The weight the
score model learned for a third starter is -0.9 points, and it is already included.

**2025 and 2026 only (434 games).** Same picture: margin miss 9.91 against 9.94 for the current
model and 9.64 for the line; 49.5% against the spread on bets that beat the vig.

## What this means

- Keep the current model on the page. The simulation would not change its picks or its record.
- Simulating does not make NFL games less noisy, and it does not find bets against a closing line.
- Not tested, because there is no price history for it: alternate spreads and totals at FanDuel and
  Fanatics. That is the one place a full distribution could matter, and it needs saved prices from
  the weekly loop or a paid pull.
