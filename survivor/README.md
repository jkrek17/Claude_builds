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
- [Evaluation of the selection process on real 2026 lines](docs/EVALUATION.md)
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

The bottom bar has five destinations - Home, Rankings, Season, Picks, More - so the whole app is always at
most two taps away.

| Screen | Purpose |
|---|---|
| **Home** (Dashboard) | A status strip (week, strike pips, teams used, data freshness); the hero recommendation card (team and opponent logos, win %, grade chip, spread/moneyline/source, the "Record for Week N" button or a Locked state); a **Decision robustness** row from `RobustPlanner` ("Chosen in N% of 150 line scenarios", a Toss-up chip below 60%, and a note when a different team is more robust on average); an expandable **Why this pick** (why safe / why now / what we give up, plus the future-value warning); three expandable **Alternatives**; a **Top 10 this week** list linking to Rankings; and a **Season outlook** card (P(win the pool) for your pool size, expected pool-end week, P(0 losses), P(≤1 loss), expected weeks alive, and the route as a horizontally-scrolling strip of team-logo chips with win % and a lock marker). First run shows a welcome screen with a pool-size and strategy quick setup before the one **Download NFL data** button. |
| **Rankings** | Card list by default (rank, logo, team, matchup, win %, grade chip, future-cost label, path-loss %) with a toggle to the full table (every model input: spread, moneyline, no-vig %, model %, FPI %, team/opponent FPI ratings, rest and travel, manual adjustments, best future spot, opportunity cost, used flag, source and notes). Tap any row for the full Safety Score breakdown in a bottom sheet. Non-current weeks show the projected win probabilities. |
| **Season → Planner** | The optimized Weeks 1–18 path as a timeline list (logo, team, matchup, win %, grade chip, status chip, opportunity cost, best future spot, result and running strike count), with the analytic summary (P(0 losses), P(≤1 loss), survive, weeks planned) on top. Re-solved on every refresh, pick or setting change. No team appears twice. |
| **Season → Grid** | 32 teams × 18 weeks with team logos in the frozen column. Each cell shows the spread from the team's view and the opponent, coloured by win probability. Byes are marked, past results show W/L, your picks are starred. Tap a team for its future-value detail. |
| **Picks** | Record or change the week's pick from the available teams, shown as selectable rows with logos (used teams are hidden). The Used Teams list shows week, opponent, result chip and strike status. Results are automatic. |
| **More** | Weekly Inputs, Model Settings, Simulation, How to use and About, each with an icon and one-line description. About shows the app version and every data timestamp. |
| **More → Weekly Inputs** | Optional per-game overrides: manual win % (beats everything), injury / QB / weather adjustments in points of spread, estimated pool pick share, notes; the line-movement history card; and the optional The Odds API key. |
| **More → Model Settings** | Three groups: **Your pool** (entries, payout-split assumption, field average win probability, and a plain-language line on what the pool size means for the model); **Strategy** (route objective radio cards, horizon weight when Blended, ownership strategy preset, current-week override); **Advanced model parameters**, collapsed by default, with every weight and a Restore defaults button. |
| **More → Simulation** | Two sections: the route Monte Carlo comparison (now including expected weeks alive and pool-win probability), and a closed-loop **policy comparison** (`PolicySimulator`, 200-2000 re-decided seasons) across Greedy, Optimized, Pool-win, Zero-loss and Threshold-guard, with a plain-language reading of which policy wins for surviving the season vs. lasting the most weeks. |

The **Survivor Tools** menu (⋮ in the top bar) has Refresh NFL Data, Refresh Odds, Update Rankings,
Optimize Season Path, Record Weekly Pick, Run Monte Carlo and Reset Model. The refresh icon is the fast
"lines and scores only" refresh.

## Design

Deep-green primary with warm neutral surfaces, a 4/8/16 dp spacing rhythm and 16 dp rounded cards throughout
(`ui/theme/Theme.kt`), with light and dark schemes tuned for contrast. Tier colors (green/yellow/orange/red
for Strong/Acceptable/Risky/Avoid) appear as chips and accents rather than full-row backgrounds. Team logos
(`ui/components/TeamLogo.kt`, via Coil) appear in the hero card, alternatives, rankings, the route strip,
Picks and the Grid's team column, each falling back to a tinted abbreviation circle offline. Long-form content
(Why this pick, alternatives, Advanced settings) uses a shared `Expandable` component so the everyday screens
stay short by default.

## Weekly routine

**Before the week:** tap refresh → read the Dashboard → optionally add a late injury in Weekly Inputs → record
the pick. **After the week:** refresh. The result, strike, used-team flag and the whole remaining route update
themselves. Details in [docs/WORKFLOW.md](docs/WORKFLOW.md).

## Automatic updates

A background WorkManager job refreshes lines and scores every few hours (configurable in **More → Model
Settings → Automatic updates**, on by default) and sends a notification when the recommended pick changes, a
recorded pick's result comes in, or the weekend arrives with no pick recorded. Manual refresh still works
exactly the same - automatic updates just mean you often don't have to.

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

Covered by the 49 unit tests (`engine` 40, `app` 9) and the real-data smoke test:

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
