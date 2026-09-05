# :composition

Pure Kotlin/JVM module: turns one `FrameAnalysis` (faces, bodies, image statistics, device orientation)
into a `CompositionResult` — a 0..100 score, ranked photographer-facing advice, and a temporally smoothed
`SmoothedComposition` for the live preview. No Android dependencies; every analyzer and engine class is
unit-testable on the plain JVM.

## Architecture

```
FrameAnalysis
     |
     v
SceneClassifier.classify()        -> SceneClassification (type, isSymmetricScene, hasHorizon, isCloseUpPortrait)
     |
     v
SubjectResolver.resolve()         -> subjects: List<DetectedSubject>, primary: DetectedSubject?
     |
     v
AnalysisContext(frame, scene, subjects, primary, guidanceLevel)
     |
     v
13x CompositionAnalyzer.analyze() -> List<CompositionMetric>   (each wrapped in runCatching; a throwing
     |                                                           analyzer is skipped, never crashes the frame)
     v
ScoreAggregator.aggregate()       -> rawScore: Float (0..100)
     |
     v
RecommendationEngine.rank()       -> ranked, deduped, level-trimmed List<Recommendation>
     |
     v
CompositionOptimizer.optimize()   -> OptimizationResult (validates/overrides top rec, fills expectedImprovement)
     |
     v
CompositionResult (per-frame, stateless)
     |
     v
CompositionSmoother.update()      -> SmoothedComposition (EMA score + recommendation hysteresis)
     |
     v
CompositionCoach                  <- facade the :app module actually calls
```

`CompositionEngine.evaluate()` runs the whole top pipeline (everything above `CompositionSmoother`) and is
itself wrapped in a final `runCatching`, falling back to `CompositionResult.empty()` — the engine is
guaranteed not to throw regardless of what the vision layer sends it.

## Sign conventions (read this before touching any analyzer)

These are inherited from the model contract (`Recommendation.kt`, `FrameAnalysis.kt`) and used
consistently everywhere in this module:

- `ReframeVector.dx > 0` = pan the camera **right** → frame content shifts **left** (`x` decreases).
- `ReframeVector.dy > 0` = raise/tilt the camera **up** → frame content shifts **down** (`y` increases).
- `ReframeVector.zoom > 0` = move closer / zoom in (magnifies geometry about the frame centre `(0.5, 0.5)`).
- `ReframeVector.rollDegrees > 0` = rotate the **phone** clockwise.
- `DeviceOrientation.rollDegrees > 0` = the horizon **appears** rotated clockwise on screen, so the fix is
  a **negative** correction (`rollDegrees = -measuredRoll`), which resolves to `ROTATE_COUNTER_CLOCKWISE`.
- `ReframeVector.toMoveSubject(from, to)` computes `dx = from.x - to.x`, `dy = from.y - to.y` (see
  `GeometryTest` for the contract's own x-axis check). All of this module's placement-style analyzers
  (`SubjectPlacementAnalyzer`, `SymmetryAnalyzer`, `BalanceAnalyzer`, the landscape horizon rule in
  `SceneSpecificAnalyzer`) use this exact formula, so a subject sitting too low/right and a subject
  sitting too high/left always resolve to the same physical instruction across every analyzer.
- `FrameTransform` (used only by `CompositionOptimizer`) implements the *forward* simulation directly from
  the two bullet points above (`x -= dx`, `y += dy`, then scale by `1 + zoom` about the centre) — it does
  not depend on `toMoveSubject` and is the one place in this module you can sanity-check the sign
  convention against a diagram.

**Note for the model owner / other module integrators:** taken completely literally, `dy`'s "camera
raise ⇒ content shifts down" semantics and `toMoveSubject`'s `dy = from.y - to.y` formula imply that
moving a subject that is currently too *low* in frame up toward an upper-thirds target resolves to
`Direction.UP` ("Raise camera"), whereas a strict optical-translation derivation of "raise camera shifts
content down" would suggest the opposite fix (lower the camera) for that same case. This module always
just calls `toMoveSubject` + `.primaryDirection()` as directed by the module brief and takes whatever
`Direction` falls out, rather than re-deriving the sign per analyzer — so the whole module is internally
consistent, but the two doc-comments in the model layer are worth a second look from whoever owns them
next, in case the vertical sign was intended to mirror the horizontal one exactly.

## Analyzers

| Analyzer | Category | Key thresholds |
|---|---|---|
| `HorizonAnalyzer` | HORIZON | dead zone 1.5°, LOW→3°, MEDIUM→6°, HIGH beyond; score reaches 0 at 15° |
| `SubjectPlacementAnalyzer` | SUBJECT_PLACEMENT | centred tolerance 5%, placement dead zone 5%, portrait eye-line targets the lower-third row (upper part of frame) |
| `HeadroomAnalyzer` | HEADROOM | ideal headroom 3-15% (narrows as face grows), tight ≤2%, excessive >25%, close-ups exempt from the tight side |
| `LookingRoomAnalyzer` | LOOKING_ROOM | yaw fallback threshold 12°, needs ≥1 face-width of room, outer-35%-of-frame gate, skipped for 2+ faces |
| `EdgeTensionAnalyzer` | EDGE_TENSION | 4% edge margin, also checks wrist/ankle/foot landmarks ≥0.5 in-frame likelihood |
| `CroppingAnalyzer` | CROPPING | 3% joint-to-edge margin on ankle/knee/hip/wrist/elbow + head-top; body required |
| `BackgroundDistractionAnalyzer` | BACKGROUND_DISTRACTION | column-above-head edge ratio ≥1.6x background, ring ratio ≥1.4x, brightness contrast ≥0.22 |
| `SubjectSeparationAnalyzer` | SUBJECT_SEPARATION | flags when both luminance and edge-density separation from a background ring fall below ~0.35 |
| `BalanceAnalyzer` | BALANCE | flags visual-weight centroid >14% off-centre when not explained by the primary subject |
| `SymmetryAnalyzer` | SYMMETRY | applicable only above 0.6 symmetry; flags >4% off-centre when symmetry ≥ that |
| `NegativeSpaceAnalyzer` | NEGATIVE_SPACE | neutral 0.75 score always; flags only a <3%-of-frame subject outside LANDSCAPE |
| `LeadingLinesAnalyzer` | LEADING_LINES | EXPERIMENTAL — proximity of a dominant line's infinite extension to the subject/thirds point; never recommends, strength text only |
| `SceneSpecificAnalyzer` | SCENE_SPECIFIC | LANDSCAPE: horizon vs. nearest third row; ARCHITECTURE: converging near-vertical lines (±4° opposite deviation) |

All physical instruction strings are centralised in `InstructionText` (direction → plain action) so every
analyzer that just needs "move that way" says it identically; a few analyzers (`HeadroomAnalyzer`,
`CroppingAnalyzer`, `BackgroundDistractionAnalyzer`, `SceneSpecificAnalyzer`'s architecture case) use their
own literal, more specific phrasing per the module brief (e.g. "Move closer to crop above the knees").

## Scene weights

Per-scene category weights live in `ScoreWeights` (model layer, not owned by this module) — see that file
for the full table. In short: portraits weight HEADROOM/CROPPING/LOOKING_ROOM/BACKGROUND_DISTRACTION much
higher than landscapes, which instead weight HORIZON/BALANCE/SYMMETRY/LEADING_LINES/SCENE_SPECIFIC highest
and zero out the people-only categories entirely.

`ScoreAggregator` combines each applicable metric's `score` weighted by `weight(category) * confidence`,
normalises over only the applicable metrics (an inapplicable metric never drags the score toward zero),
then subtracts a small flat penalty (3.5 points, capped at 20) per HIGH-severity applicable metric so one
glaring problem visibly caps the score instead of hiding in an average.

## Smoothing (`CompositionSmoother` / `SmoothingConfig`)

All tuning constants live in one `SmoothingConfig` data class. Everything is expressed in **time**, driven
by the frame timestamps, so a phone analysing 5 frames/s behaves the same as one managing 10. When
timestamps do not advance (tests, duplicate stamps) a nominal 100 ms per update is assumed; gaps longer
than 500 ms (app paused, camera switch) count as 500 ms.

| Constant | Default | Meaning |
|---|---|---|
| `scoreTimeConstantMs` | 1500 | EMA time constant for the score (63 % of a step change after 1.5 s) |
| `scoreTimeConstantOnSubjectChangeMs` | 350 | much faster constant for ~0.6 s after the subject count changes |
| `displayDeadband` | 2 | the on-screen number does not move until the EMA has drifted ≥ 2 points from it |
| `displayMinHoldMs` | 700 | minimum time the on-screen number stays put between changes |
| `displaySnapDelta` | 10 | a change this large is shown immediately (a real reframe) |
| `recommendationConfirmMs` | 800 | a challenger must be top-ranked continuously this long before replacing the headline |
| `recommendationMinHoldMs` | 2000 | the headline is protected from replacement until shown this long |
| `issueGoneMs` | 700 | if the headline's issue has been absent this long it is dropped at once (bypasses the hold) |
| `oppositeDirectionExtraConfirmMs` | 500 | extra confirmation when the challenger is the opposite action (LEFT↔RIGHT, UP↔DOWN, CW↔CCW, CLOSER↔BACK) |
| `shootReadyEnterScore` / `shootReadyExitScore` | 88 / 84 | hysteresis band so "shoot ready" doesn't chatter at the boundary |
| `shootReadyEnterHoldMs` | 400 | the score must stay above the enter threshold this long before SHOOT lights up |

Shoot-ready also requires no applicable metric at `Severity.HIGH` in the latest raw result.

Only the single headline recommendation is smoothed with hysteresis; any secondary recommendations
(`GuidanceLevel.COACH` can show up to 3) ride along unsmoothed straight from the raw per-frame result,
since a secondary line flickering is far less disruptive than the main instruction changing every second.

## Optimizer (`CompositionOptimizer`)

Simulates 6 small candidate moves (±7% pan, ±7% tilt, ±10% zoom) via `FrameTransform`, re-resolves
subjects, re-runs every analyzer, and re-aggregates a predicted score for each — cheap because it only
transforms face/body geometry. **Known approximation**: `ImageStatistics` (luminance/edge grids, symmetry,
horizon) is left untouched across candidates (recomputing a downscaled image per hypothetical crop is
exactly the cost this optimizer exists to avoid), and scene classification is not re-run per candidate
either (a 7-10% reframe essentially never changes portrait-vs-landscape). This means candidates only
meaningfully move face/body-driven categories (subject placement, headroom, looking room, edge tension,
cropping) — exactly the ones a small reframe changes fastest.

The engine uses the optimizer's best candidate two ways: if it beats the current score by ≥3 points and a
ranked recommendation already argues for that same direction, that recommendation is promoted to the
front (an independent simulation *validating* existing advice, never inventing new advice the analyzers
didn't produce); and any recommendation missing `expectedImprovement` gets it filled in from whichever
candidate shares its direction.

## Tuning

- Every threshold is a named `const val` at the bottom of its analyzer/engine file — change it there, not
  inline.
- Scene weights live in `ScoreWeights` (model layer); ping the model owner for weight table changes, or
  build a new `ScoreWeights` preset additively.
- Smoothing constants are all in `SmoothingConfig`; pass a non-default instance to
  `CompositionSmoother(config = ...)` for a different feel (e.g. a faster demo mode) without touching the
  class.

## Known limitations

- `LeadingLinesAnalyzer` is explicitly experimental (see its kdoc): proximity-to-a-point is a weak proxy
  for "this line leads the eye to the subject," and it is weighted low everywhere and never produces a
  recommendation.
- `SceneClassifier` and `SubjectResolver`'s "salient region" / "object scene" detection are deliberately
  conservative heuristics over the downscaled edge-density grid, not real saliency detection — a busy but
  uniform background (foliage, gravel) is designed to *not* trigger either one, at the cost of sometimes
  missing genuinely interesting but low-contrast objects.
- `CompositionOptimizer`'s two approximations (frozen statistics, frozen scene classification across
  candidates) are documented above and in the class's own kdoc.
- See the "sign conventions" section above for the one specific spot (`ReframeVector.toMoveSubject`'s
  vertical sign) worth a second look from the model owner.

## Running

```
./gradlew :composition:test            # 43 tests, no Android SDK required
./gradlew :composition:compileKotlin   # warnings-clean
```
