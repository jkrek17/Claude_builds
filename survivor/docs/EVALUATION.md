# Evaluation of the season-long selection process

Run on the recorded 2026 DraftKings lines and ESPN FPI projections from 2026-09-08 (all 272 games), with the
env-gated `SelectionExperiment` test (`SURVIVOR_REAL_DATA_DIR`). Numbers below are from that run.

## 1. Elite spots are scarce

| | Count |
|---|---:|
| Team-games above 80% win probability, whole season | 8 |
| Teams that own at least one of them | 7 (BUF, NE, KC, GB, LAR, SF, SEA) |
| Picks required | 18 |

The best available team in most weeks sits between 74% and 80%. Whatever the ordering, the season is a
grind: along the optimized route P(zero losses) is about 1% and P(one loss or fewer) about 6%.

## 2. The Week 1 decision is a coin flip, and the knobs barely matter

Across 200 perturbed-line scenarios the unconstrained optimum starts with LAC in 51% and JAX in 49%.
Changing the future discount from 0 to 10% per week, or the future market/FPI blend from 0 to 100% market,
moves modeled season survival by less than 0.7 percentage points. Two consequences:

- the app should present a toss-up as a toss-up (now done: the Decision robustness indicator);
- tuning the discount and blend by hand is not worth the effort; a closed-loop simulator is the right tool.

## 3. Planning beats greedy on the goal it was given and loses on another

Closed-loop simulation, 600 seasons, common random numbers. Each week the policy re-decides with lines that
carry logit-space noise `tau(k) = min(0.5, 0.08 + 0.03k)` for a game `k` weeks ahead, converging to the truth
as kickoff approaches.

| Policy | Survive season | Reach Week 10 | Expected weeks alive |
|---|---:|---:|---:|
| Greedy, highest win % each week | 6.5% | 45.3% | 8.54 |
| Optimizer as originally shipped (survive-season objective) | 9.3% | 42.3% | 8.34 |

The optimizer raises full-season survival by 43% relative, and by saving elite teams it dies earlier more
often. In a small pool that usually ends before Week 12, greedy is the better policy; in a large
double-elimination pool that runs to Week 18, the optimizer is right. The objective must depend on pool
size.

## 4. Pool-size-aware objective (implemented)

`RouteObjective.POOL_WIN` (now the default) maximizes an explicit P(win the pool): the field is modeled as
`poolEntries − 1` independent entries whose picks win with `fieldAverageWinProbability` each week under the
same double-elimination rule. See MODEL.md "Pool-win objective" for the formula. Closed-loop comparison with
the engine simulator (800 seasons, common random numbers):

| Policy | Survive season | Reach Wk 10 | Reach Wk 14 | Expected weeks alive |
|---|---:|---:|---:|---:|
| Greedy (highest win % now) | 6.4% | 42.9% | 21.8% | 9.19 |
| Optimized, survive-season objective, 3%/wk discount | 8.5% | 41.0% | 21.8% | 9.09 |
| Zero-loss path (Hungarian only) | 8.5% | 39.0% | 20.5% | 8.93 |
| Threshold guard (take the safest team if the plan's pick is under 70%) | 8.8% | 41.4% | 21.9% | 9.13 |
| Pool win, 10 entries | 8.6% | 42.0% | 21.1% | 9.09 |
| Pool win, 50 entries | 8.5% | 41.0% | 21.8% | 9.09 |
| Pool win, 500 entries | 8.5% | 41.0% | 21.8% | 9.09 |

Reading: the pool-win objective interpolates as intended (a 10-entry pool pulls the early weeks toward greedy;
50 or more entries reproduce the survive-season plan), but the effect is small because the same handful of
elite spots dominate every objective. The threshold guard is marginally best on every metric, so it is worth
adopting as the default decision rule. Differences of under one percentage point are within simulation noise
at 800 seasons; use 3000+ seasons in the Simulation screen before acting on small gaps.

## 5. What changed as a result

| Finding | Change |
|---|---|
| Objective ignores pool size | `POOL_WIN` objective with `poolEntries` setting; `EXPECTED_WEEKS_ALIVE` and `BLENDED` also available |
| One-answer presentation of a toss-up | `RobustPlanner`: share of scenarios choosing each team, route stability, robust pick; shown on the Dashboard |
| Open-loop Monte Carlo only | `PolicySimulator`: closed-loop, re-deciding weekly with converging noisy lines; Simulation screen |
| Noise model is a guess | `LineHistory`: every refresh logs lines; movement by weeks-ahead and a fitted `tauBase`/`tauPerWeek` |
| Local search could stall on non-multiplicative objectives | dual-start local search (Hungarian and greedy starts) |

## 6. Still open

- Calibrate `tau(k)` from the logged line history after a few weeks of data and feed it to the planner and simulator.
- Test whether the Safety Score's matchup penalties add anything over the plain optimizer pick, using the
  closed-loop simulator with a custom policy; drop them if not.
- Correlated risk: a team-level strength shock (QB injury) correlates that team's future games; the
  simulators treat games as independent.
- Field model: other entries are assumed to start each remaining week with a fresh strike allowance and to pick at a
  flat 76%; real fields concentrate on the same favorites, which correlates their survival with yours.

## 7. Early-season variance and what past seasons can add

Source: nflverse `games.csv`, 4,175 regular-season games with closing spreads, 2010–2025.

**Early lines are less confident, not less accurate.** Favourites win 61–64% of games in Weeks 1–6 versus
68–69% later, but that is because the lines themselves are tighter: mean |spread| is 4.4 in Week 1 and 5.8 in
Weeks 13–18, and only 6% of Week 1 games carry a 75%+ favourite versus 19% late in the season. Measured by
Brier score the early lines are only slightly worse (0.220–0.226 vs 0.203–0.208). The week's safest team
actually won 81% of the time in Week 1 against a 76% implied probability; in every bucket the safest picks won at
least as often as the line said.

**The spread curve was miscalibrated for favourites.** Fitting σ in `Φ(spread / σ)` by maximum likelihood gives
11.0 (stable across leave-one-season-out folds), not the ~13.5 margin standard deviation used before.

| Implied bucket at σ = 13.45 | Actual | Implied bucket at σ = 11.0 | Actual |
|---|---:|---|---:|
| 75–80% (77.4% implied) | 82.5% | 75–80% (76.4% implied) | 75.5% |
| 80–85% (82.7%) | 87.6% | 80–85% (82.2%) | 82.5% |
| 85–90% (86.5%) | 92.3% | 85–90% (88.2%) | 88.1% |

The default is now 11.0. Practical effect: a 7-point favourite is 74% rather than 70%, a 10-point favourite 82%
rather than 77%, so the safety of the top picks was being understated.

**Last season helps a little in Week 1 and not after.** Adding β × (last season's net points per game gap) to the
closing spread and choosing β on the other seasons improves Week 1 out of sample by 0.9% Brier (β ≈ +0.2, i.e.
the market underweights last year by about a fifth of a point per point of net margin), and does nothing from
Week 2 on (−0.25%, −0.07%, 0.00%). Among Weeks 1–3 favourites at 70%+, those with a +7 net-points edge from the
prior year won 83% versus 67% for those with a negative gap, but the sample is 6 games. Decision: not adopted
for now; the Week 1 gain is real but small, applies to one pick per season, and would add a data source.
Recorded here so it can be revisited with more seasons.
