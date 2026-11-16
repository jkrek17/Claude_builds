# Backup and injured quarterbacks

Asked for on 2026-11-16 after week 10 of 2026: the Cardinals played their third-string quarterback and
scored 6 while the model projected 19, and the Patriots lost by 25 in a game their starter left early.

Run `python3 research/backup_qb/backtest.py ~/.cache/nflmodel` (output in `backtest_output.txt`) and
`python3 research/backup_qb/explore.py ~/.cache/nflmodel`. Everything is walk-forward on 2010 to 2025,
4,363 games, the same window as `research/backtest_v2.py`. d(MSE) is the fall in squared error against
the model before the change; positive is better.

## What was wrong

1. **The model had the wrong quarterback.** nflverse lists a starter for upcoming games, and it is
   sometimes a player who cannot play. In week 10 it listed Gardner Minshew for Arizona (on injured
   reserve; Carson Beck, a rookie, played) and Sam Darnold for Seattle (ruled out; Drew Lock played).
   The listing is not always corrected afterwards either: in 55 team-games, 50 of them in 2024 to
   2026, the listed starter never took a dropback, so the game was credited to the wrong player.
2. **A game the starter left early was credited to him.** The margin model gave the whole passing
   result to the listed starter even when the backup played most of it.
3. **New and inexperienced backups were rated too kindly.** Over 2010 to 2025 a backup making the
   first start of a stint scored 1.3 points fewer than the model expected (standard error 0.4), and a
   quarterback making his first career start 1.7 fewer (0.8). The betting line was off by 0.4 and
   0.3 in the same games. After the change the model's shortfalls are 0.9 and 1.3
   (`explore_output.txt`, which is the model as it now stands), so the gap is smaller, not gone.

Teams on their third starter of a season, taken as a group, were not rated too kindly (0.1 points
below the model, 386 team-games), and a "third starter" flag made the model slightly worse. What
matters is that the starter is new and inexperienced, not his number in the queue.

## What was tested

| Change | Margin d(MSE) | Total d(MSE) | Verdict |
|---|---|---|---|
| Start goes to the quarterback who played | +0.15 (10 of 16 seasons) | +0.06 | adopted: it is a data correction |
| Flag: backup making the first start of a stint | +0.16 (9 of 16) | +0.20 (11 of 16) | adopted for the margin |
| Flag: starter has under 8 career starts | -0.05 | +0.18 | not adopted |
| Flag: inexperienced backup | -0.06 | +0.27 (12 of 16) | adopted for the total |
| Flag: third starter of the season | -0.09 | +0.01 | not adopted |
| The two starters' form, for the total | | +0.39 (12 of 16) | adopted |
| Early exit: credit the game to who played | +0.17 (10 of 16) | +0.05 | adopted |
| Early exit: count the game half | -0.06 | 0.00 | not adopted |
| Early exit: ignore the game | -0.33 | -0.05 | not adopted, worse |

None of these is large and several are within one or two standard errors on their own. Together, on
the full model: margin miss 10.273 to 10.263 (RMSE 13.223 to 13.206), total miss 10.590 to 10.572
(RMSE 13.423 to 13.403), each better in 14 of 16 seasons. Against the closing line the model is still
at 50.3% on spreads and 51.6% on totals, short of the 52.4% break-even.

Ignoring a game the starter left early made things worse. Those games still say something about the
team (its defense, its running game, and the backup who may start next week), so the model keeps them
and moves the blame to the right quarterback.

## The check before a week is predicted

`qb_depth.expected_starters` replaces a listed starter who is off the active roster or listed Out or
Doubtful with the available quarterback who most recently took dropbacks for the team this season,
or failing that the one longest on its roster. Replayed on 2019 to 2026 with each team's previous
starter as the listing, it stepped in 311 times and named the quarterback who started 254 times (82%).
It never replaced a quarterback who went on to start. Game-day inactive lists were left out of that
replay because they are only known after the fact.

## Week 10 of 2026, replayed before kickoff

| Game | Line | Before | After | Result |
|---|---|---|---|---|
| Rams at Cardinals, margin | Rams by 10 | Rams by 9.4 | Rams by 12.2 | Rams by 28 |
| Rams at Cardinals, total | 38.5 | 48.1 | 47.0 | 40 |
| Seahawks at Raiders, total | 40.5 | 44.7 | 41.8 | 34 |
| Dolphins at Colts, total | 37.5 | 46.2 | 44.9 | 46 |

The fix moves these games one to three points toward the line. It does not close a nine-point gap:
history says an inexperienced backup is worth a few points, and the model has no way to know that a
particular team has collapsed more than that.
