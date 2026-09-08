# Survivor Optimizer (Android)

A native Android app that works as an **NFL survivor-pool command center** for a double-elimination pool
(one team per week, each team once, second loss eliminates). Every week it answers:

> **Who should I pick, how safe is it, and what am I giving up by using this team now?**

Nothing has to be typed in. Schedule, scores, DraftKings spreads and moneylines for all 18 weeks, and ESPN's
FPI projections are pulled from ESPN's public JSON. Your pick's result and strike count are read from the
final score. Manual overrides exist for the rare case where you know something the line does not.

- [Install on your phone](#install-on-your-phone)
- [What each screen does](#what-each-screen-does)
- [Weekly routine](#weekly-routine)
- [How the model works](docs/MODEL.md) · [Formula reference](docs/MODEL.md#formula-reference)
- [Data sources and freshness](docs/DATA_SOURCES.md)
- [Refreshing data, recording picks, changing assumptions](docs/WORKFLOW.md)
- [Optimization methodology](docs/MODEL.md#season-optimization)
- [Recommended future improvements](docs/FUTURE.md)
- [Build from source](#build-from-source)
- [Project structure](#project-structure)
- [Validation](#validation)

---

## Install on your phone

Every push to GitHub that touches `survivor/` runs the tests, lint and both builds, and publishes two rolling
pre-releases on the repository's Releases page:

| Channel | Tag | What it is |
|---|---|---|
| `survivor-release-latest` (recommended) | https://github.com/jkrek17/Claude_builds/releases/tag/survivor-release-latest | R8-shrunk release build (~1.5 MB) |
| `survivor-debug-latest` | https://github.com/jkrek17/Claude_builds/releases/tag/survivor-debug-latest | Debuggable build (~18 MB) |

1. Open the link on the phone, tap `SurvivorOptimizer-release.apk` under **Assets**.
2. Open the downloaded file and allow installs from this source when Android asks. On the Play Protect
   prompt tap **More details → Install anyway**.
3. Open **Survivor Optimizer** and tap **Download NFL data**. The first download fetches 18 weeks of
   schedule and lines plus ~270 FPI projections (10–20 seconds on Wi-Fi).

Requirements: Android 8.0 (API 26) or newer and an internet connection for refreshes. Both channels are signed
with the same checked-in debug key until the `SV_RELEASE_*` secrets are configured (see the Composition Coach
[docs/RELEASE.md](../docs/RELEASE.md) for the same procedure with the `SV_` prefix), so they install as
updates over each other.

## What each screen does

| Screen | Purpose |
|---|---|
| **Dashboard** | Current week, strikes, teams used, data freshness. The recommended pick with win %, Safety Score, grade, spread, moneyline, future-cost label, best future spot, a plain-language explanation (why safe, why now, what we give up), 3 alternatives with why they rank lower, the ranked top-10 table, and the season outlook (P(0 losses), P(≤1 loss), optimized route from this pick). One tap records the pick. |
| **Rankings** | One row per team for the selected week with every model input: spread, moneyline, no-vig %, model %, FPI %, team/opponent FPI ratings, rest and travel, manual adjustments, best future spot, future counts, opportunity cost, season-path loss, used flag, Safety Score, grade, source and notes. Tap a row for the full Safety Score breakdown. Non-current weeks show the projected win probabilities. |
| **Planner** | The optimized Weeks 1–18 path: team, opponent, venue, spread, win %, grade, best future opportunity, opportunity cost, status (Locked / Recommended / Projected), actual result and running strike count. Re-solved on every refresh, pick or setting change. No team appears twice. |
| **Grid** | 32 teams × 18 weeks. Each cell shows the spread from the team's view and the opponent (`-7.5 vs CLE`, `+4.5 @ BUF`), coloured by win probability (dark green >82%, green 75–82%, yellow 68–75%, orange 60–68%, red <60%). Byes are marked, past results show W/L, your picks are starred. Per-team columns give the best and second-best future matchup and counts of future games above 75% and 80%. |
| **Picks** | Record or change the week's pick from the available teams (used teams are hidden). The Used Teams table shows week, opponent, result, availability and whether it was a strike. Results are automatic. |
| **Weekly Inputs** | Optional per-game overrides: manual win % (beats everything), injury / QB / weather adjustments in points of spread, estimated pool pick share, notes. Also holds the optional The Odds API key. |
| **Model Settings** | Strategy (Conservative / Balanced / Contrarian / Max Pool Equity), current-week override, and every model parameter with a description. |
| **Monte Carlo** | Simulates the rest of the season along four strategy routes and reports survival, probability of reaching Weeks 10/14/18, expected strikes and expected elimination week. |

The **Survivor Tools** menu (⋮ in the top bar) has Refresh NFL Data, Refresh Odds, Update Rankings,
Optimize Season Path, Record Weekly Pick, Run Monte Carlo and Reset Model. The refresh icon is the fast
"lines and scores only" refresh.

## Weekly routine

**Before the week:** tap refresh → read the Dashboard → optionally add a late injury in Weekly Inputs → record
the pick. **After the week:** refresh. The result, strike, used-team flag and the whole remaining route update
themselves. Details in [docs/WORKFLOW.md](docs/WORKFLOW.md).

## Build from source

```
cd survivor
./gradlew :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

JDK 17+ and the Android SDK (platform 36) are required; `local.properties` with `sdk.dir=...` or
`ANDROID_HOME` must point at it. The `engine` module is plain Kotlin and its tests run in under two seconds.

To run the end-to-end smoke test against recorded ESPN responses, point `SURVIVOR_REAL_DATA_DIR` at a directory
containing `week_1..18.json`, `powerindex.json` and `pred/<eventId>.json` (see `RealDataSmokeTest`); it prints
the Week-1 dashboard and strategy simulations.

## Project structure

```
survivor/
├── engine/                     Pure Kotlin model (no Android dependency), fully unit-tested
│   └── src/main/kotlin/com/survivor/engine/
│       ├── Teams.kt            32 teams, divisions, ESPN abbreviations, time zones
│       ├── Model.kt            Game, Season, Pick, Adjustment, ModelSettings (with descriptions), UserState
│       ├── Probability.kt      no-vig moneyline, spread ↔ win probability, discount, source resolution
│       ├── Schedule.kt         rest days, travel, divisional flags, FutureValue summaries
│       ├── Survival.kt         P(0 losses), P(exactly 1 loss), loss distribution
│       ├── Hungarian.kt        Kuhn–Munkres assignment
│       ├── Optimizer.kt        season path: Hungarian on −ln p + local search on P0+P1
│       ├── Safety.kt           Safety Score components, grades, tiers, leverage
│       ├── Evaluator.kt        one call → rankings, recommendation, route, planner, grid
│       ├── Explain.kt          plain-language decision explanation
│       ├── MonteCarlo.kt       season simulation
│       ├── Strategies.kt       the four comparison routes
│       └── data/               ESPN + Odds API parsers, JSON state codec, merge helpers
└── app/                        Jetpack Compose UI
    └── src/main/java/com/survivor/app/
        ├── data/               OkHttp fetcher, EspnClient, OddsApiClient, StateStore, SurvivorRepository
        └── ui/                 AppViewModel, Nav (tabs + Survivor Tools menu), theme, components, screens/
```

## Validation

Covered by the 45 unit tests (`engine` 40, `app` 5) and the real-data smoke test:

- all 32 teams and 18 weeks present; bye weeks appear as empty grid cells and are never assignable
- no team appears twice in an optimized route; locked picks are honoured
- used teams disappear from rankings and the route the moment a pick is recorded
- strikes are derived from final scores; a strike shrinks future-value weight and switches the objective to zero-loss
- market moneyline overrides spread, spread/FPI blend overrides projection-only, manual override beats all
- every probability is clamped to 1–99%; Safety Scores to 0–100
- future-value statistics use strictly future weeks
- dashboard rankings, grid cells and planner rows are the same numbers from one evaluation
- double-elimination P(0) and P(1) match brute-force enumeration
- spread → win probability matches historical closing-line favourite win rates within 1%
