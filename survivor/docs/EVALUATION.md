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

<!-- SECTION5 -->

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
