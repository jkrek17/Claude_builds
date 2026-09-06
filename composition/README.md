# :composition

Pure Kotlin/JVM module: turns one `FrameAnalysis` (faces, bodies, non-person objects, a foreground subject
mask, image statistics, device orientation) into a `CompositionResult` — a 0..100 score, ranked
photographer-facing advice, and a temporally smoothed `SmoothedComposition` for the live preview. No
Android dependencies; every analyzer and engine class is unit-testable on the plain JVM.

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
CompositionSmoother.update()      -> SmoothedComposition (EMA score + advice-slot hysteresis + display geometry)
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
- `ReframeVector.toMoveSubject(from, to)` computes `dx = from.x - to.x`, `dy = to.y - from.y` — note the
  asymmetry: `dx` is *current minus target*, `dy` is *target minus current*. This is not a typo: it falls
  straight out of `FrameTransform`'s forward simulation (`x -= dx`, `y += dy`, see below) — solving each
  for the vector that carries a point from `from` to `to` gives `dx = from.x - to.x` but `dy = to.y -
  from.y`, because `x` and `y` enter the forward transform with opposite signs. `GeometryTest` checks both
  axes independently (`reframe vector to move subject right of thirds line says move right`, `subject too
  high/low in frame means raise/lower camera`). All of this module's placement-style analyzers
  (`SubjectPlacementAnalyzer`, `SymmetryAnalyzer`, `BalanceAnalyzer`, the landscape horizon rule in
  `SceneSpecificAnalyzer`) must use this exact formula, so a subject sitting too low/right and a subject
  sitting too high/left always resolve to the same physical instruction across every analyzer — a prior
  version of the landscape horizon rule got the `dy` term backwards (`horizonY - nearestThird` instead of
  `nearestThird - horizonY`), which told the photographer to move the camera in exactly the wrong vertical
  direction for every off-thirds horizon; see the "Quality gate" section below for how the robustness
  suite now guards against a regression there.
- `FrameTransform` (used only by `CompositionOptimizer`) implements the *forward* simulation directly from
  the two bullet points above (`x -= dx`, `y += dy`, then scale by `1 + zoom` about the centre) — it does
  not depend on `toMoveSubject` and is the one place in this module you can sanity-check the sign
  convention against a diagram.

## Analyzers

| Analyzer | Category | Key thresholds |
|---|---|---|
| `HorizonAnalyzer` | HORIZON | dead zone 2.5° (1.5° when intent is LANDSCAPE/ARCHITECTURE), LOW→3°, MEDIUM→6°, HIGH beyond; score reaches 0 at 15° |
| `SubjectPlacementAnalyzer` | SUBJECT_PLACEMENT | centred tolerance 5%, placement dead zone 5%, portrait eye-line targets the lower-third row (upper part of frame) |
| `HeadroomAnalyzer` | HEADROOM | ideal headroom 3-15% (narrows as face grows), tight ≤2%, excessive >25%, close-ups exempt from the tight side |
| `LookingRoomAnalyzer` | LOOKING_ROOM | yaw fallback threshold 12°, needs ≥1 face-width of room, outer-35%-of-frame gate, skipped for 2+ faces / GROUP_PORTRAIT |
| `EdgeTensionAnalyzer` | EDGE_TENSION | 4% edge margin, also checks wrist/ankle/foot landmarks ≥0.5 in-frame likelihood; for GROUP_PORTRAIT, any partially cut face is HIGH severity regardless of which subject is primary; with no body but a [subject mask](#objects-and-the-subject-mask), the subject box is widened to `mask.bounds()` before measuring edge distance |
| `CroppingAnalyzer` | CROPPING | 3% joint-to-edge margin on ankle/knee/hip/wrist/elbow + head-top; needs a body — or, with no body but a mask reaching the bottom edge while the face is high and small (a full-body shot plausibly intended), a LOW-severity "Step back slightly" guess at a waist/knee cut |
| `BackgroundDistractionAnalyzer` | BACKGROUND_DISTRACTION | no mask: column-above-head edge ratio ≥1.6x background, ring ratio ≥1.4x, brightness contrast ≥0.22. With a mask: edge energy in the band above `mask.bounds()` (excluding mask cells) vs. the frame background, plus a "pole through the head" detector (a ≤2-cell-wide column of high edge density touching the mask top and reaching ≥3 cells above it, with clearly quieter neighbours) |
| `SubjectSeparationAnalyzer` | SUBJECT_SEPARATION | no mask: flags when both luminance and edge-density separation from a background ring fall below ~0.35 (confidence 0.5). With a mask: same idea but measured against the mask's own silhouette rather than a bounding box (confidence 0.85) |
| `BalanceAnalyzer` | BALANCE | no mask: flags visual-weight centroid >14% off-centre when not explained by the primary subject. With a mask: flags a subject sitting off to one side (`SUBJECT_OFFSET_THRESHOLD`) when the *other* side has low non-mask edge energy and the subject has no looking room that way — never for symmetric scenes |
| `SymmetryAnalyzer` | SYMMETRY | applicable only above 0.6 symmetry; flags >4% off-centre when symmetry ≥ that |
| `NegativeSpaceAnalyzer` | NEGATIVE_SPACE | neutral 0.75 score always; flags a subject under 3% of frame area (4% for an OBJECT-kind subject) outside LANDSCAPE |
| `LeadingLinesAnalyzer` | LEADING_LINES | still low-weight and somewhat exploratory, but no longer silent: a line within 8% of the subject/thirds point → positive strength text; a line clearly leading away (≥20%) → LOW severity "Move so the lines lead toward your subject", only in LANDSCAPE/ARCHITECTURE |
| `SceneSpecificAnalyzer` | SCENE_SPECIFIC | LANDSCAPE: horizon vs. nearest third row (weaker signal accepted when intent is LANDSCAPE); ARCHITECTURE: converging near-vertical lines (±4° opposite deviation); declared PORTRAIT intent: "move closer" when the face is < 12% of frame height |

All physical instruction strings are centralised in `InstructionText` (direction → plain action) so every
analyzer that just needs "move that way" says it identically; a few analyzers (`HeadroomAnalyzer`,
`CroppingAnalyzer`, `BackgroundDistractionAnalyzer`, `SceneSpecificAnalyzer`'s architecture case) use their
own literal, more specific phrasing per the module brief (e.g. "Move closer to crop above the knees").

## Objects and the subject mask

`:vision` sends two additional signals this module now uses: `FrameAnalysis.objects` (a
`DetectedObject` per prominent non-person item — a plate, a glass, a product — every frame) and
`FrameAnalysis.subjectMask` (a coarse foreground-probability grid, only computed while a person is in
frame, and possibly a few frames stale — see `detectorTimings["mask_age"]`). Field testing showed the
previous faces-or-nothing model let an incidental background face steal a shot that was actually about a
pint glass on the table; both signals close different halves of that gap.

**Objects as subjects** (`SubjectResolver`, `SubjectFilter` unaffected — this is additive, not a
replacement for face filtering):
- A `DetectedObject` becomes a `SubjectKind.OBJECT` `DetectedSubject` once it clears a size floor: 3% of
  frame area under `AUTO`/`SceneIntent.OBJECT`, 15% under `SceneIntent.LANDSCAPE`/`ARCHITECTURE` (a small
  object is rarely *the* subject of a landscape), and objects are excluded entirely under
  `SceneIntent.PORTRAIT`/`GROUP_PORTRAIT` (the photographer told us there is a person to shoot).
- Salience is **multiplicative** (area × centrality × confidence, all 0..1) rather than the weighted sum
  used for people — an object only scores well when it is large, central, *and* confidently detected, not
  merely one of the three — plus a +0.15 bonus for categories people deliberately photograph (FOOD,
  HOME_GOOD, FASHION_GOOD, PLANT) and a −0.2 penalty for PLACE (a location tag on incidental background).
- **Choosing the primary subject across kinds** (`SubjectResolver.choosePrimary`): a person whose face is
  ≥8% of frame height (`DOMINANT_FACE_HEIGHT`) unconditionally outranks every object; below that size, the
  single most salient subject of *any* kind wins. This is exactly how a pint glass beats a stranger's face
  in the background, once that background face has already been dropped as incidental by `SubjectFilter`.
- In `AUTO` scene classification, `SceneClassifier` now checks `frame.objects` for a qualifying detection
  (≥3% area, ≥0.5 confidence) *before* the older stats-only heuristics (architecture/landscape/object) —
  a real detection is a much more direct signal than guessing from an edge-density grid, and this is what
  turns the pint-on-a-table frame into `SceneType.OBJECT` instead of a shrug `GENERAL`.
- `SubjectPlacementAnalyzer`: for an OBJECT subject the anchor is always the box centre (no eye-line), and
  centred framing is valid when the scene is symmetric *or* the object is large (≥25% of frame area,
  `LARGE_OBJECT_AREA`) — otherwise it snaps to the nearest thirds intersection like any other subject.
  `NegativeSpaceAnalyzer` uses a slightly higher "too small" floor for objects (4% vs. 3% for a person —
  an object needs a bit more presence to read as deliberate). Headroom, looking room and cropping remain
  person-only (they all gate on `DetectedSubject.face`/`.body`, which an object subject never has).

**Mask-driven analyzers** (`SubjectSeparationAnalyzer`, `BackgroundDistractionAnalyzer`, `BalanceAnalyzer`,
plus a narrower fallback in `EdgeTensionAnalyzer`/`CroppingAnalyzer`): every one of these falls back to its
original luminance/edge-grid-only heuristic whenever `FrameAnalysis.subjectMask` is null — see each
analyzer's own kdoc for the precise fallback — so a device/frame with no segmenter output behaves exactly
as before this module's changes. `MaskHeuristics` (new) holds the shared cell-mapping helpers: the mask and
the engine's own `ImageStatistics` grid are never assumed to share a resolution, so every helper walks the
mask's grid and maps each cell to a normalized frame point before sampling the stats grid at that point.
See the Analyzers table above for what each one does differently with a mask; in short, everything gets
more precise (silhouette-shaped rather than bounding-box-shaped) and more confident (0.85 vs. 0.5-0.7
without one).

## Scene weights

Per-scene category weights live in `ScoreWeights` (model layer, not owned by this module) — see that file
for the full table. In short: portraits weight HEADROOM/CROPPING/LOOKING_ROOM/BACKGROUND_DISTRACTION much
higher than landscapes, which instead weight HORIZON/BALANCE/SYMMETRY/LEADING_LINES/SCENE_SPECIFIC highest
and zero out the people-only categories entirely.

`ScoreAggregator` combines each applicable metric's `score` weighted by `weight(category) * confidence`,
normalises over only the applicable metrics (an inapplicable metric never drags the score toward zero),
then subtracts a small flat penalty (3.5 points, capped at 20) per HIGH-severity applicable metric so one
glaring problem visibly caps the score instead of hiding in an average.

## Intent (shooting mode)

`AnalysisContext.intent` / `CompositionResult.intent` carry the photographer's declared shooting mode
(Settings > Shooting mode in `:app`), a `SceneIntent`. `AUTO` (the default) leaves everything to
`SceneClassifier`/`SubjectResolver` exactly as before this feature — every AUTO code path is unchanged.
Any other value:

1. **Overrides scene detection.** `CompositionEngine` skips `SceneClassifier.classify()` and instead calls
   `SceneClassifier.forcedClassification(frame, intent.forcedSceneType())`: the `type` is simply the
   declared one, but `isSymmetricScene`, `hasHorizon` and `isCloseUpPortrait` are still derived from the
   frame via the same heuristics `classify()` itself uses (`symmetryOf`, `visualHorizonOf`, `closeUpFaceOf`)
   — a downstream analyzer that consults those flags behaves the same either way.
2. **Changes which detections count as subjects**, per `SubjectResolver`'s per-intent rules (see the table
   below).
3. **May put the frame in "awaiting subject" mode** (`CompositionResult.awaitingSubject = true`) — see below.

| Intent | Scene forced to | Subject rules (`SubjectResolver`) | Analyzer tweaks |
|---|---|---|---|
| `AUTO` | (detected) | unchanged | unchanged |
| `PORTRAIT` | `PORTRAIT` | faces only (no body-only subjects, no salient-region fallback); background-face threshold relaxes 8% → 4% of frame height (`SubjectFilter.PORTRAIT_INTENT_MIN_SUBJECT_FACE_HEIGHT`) | `HorizonAnalyzer` unaffected; `SceneSpecificAnalyzer` adds "Move closer to your subject" (`intent.portrait.too_small`) when the face is present but < 12% of frame height |
| `GROUP_PORTRAIT` | `GROUP_PORTRAIT` | same as PORTRAIT (faces only, no fallback), same relaxed 4% threshold | `LookingRoomAnalyzer` not applicable (scene-type gated, same as AUTO group portraits); `EdgeTensionAnalyzer` checks *every* face, not just the primary subject, and treats any one touching/leaving the frame edge as `Severity.HIGH` ("Someone is cut off — step back") |
| `LANDSCAPE` | `LANDSCAPE` | faces/bodies excluded entirely; `primary` is always `null` | `HorizonAnalyzer` dead zone tightens 2.5° → 1.5°; `NegativeSpaceAnalyzer` never flags "too small" (scene-type gated, automatic); `SceneSpecificAnalyzer` accepts a weaker horizontal-line/luminance-transition signal for horizon placement |
| `ARCHITECTURE` | `ARCHITECTURE` | faces/bodies excluded; salient-region fallback still runs (a strongly distinct architectural detail can be `primary`) | `HorizonAnalyzer` dead zone tightens to 1.5°; converging-verticals advice unchanged |
| `OBJECT` | `OBJECT` | faces/bodies ignored; salient-region fallback runs with a relaxed contrast requirement (2.2 → 1.6, `SubjectResolver.OBJECT_SALIENT_REGION_MIN_CONTRAST`) | unaffected beyond the subject change |

**Awaiting subject.** PORTRAIT, GROUP_PORTRAIT and OBJECT are meaningless without their kind of subject, so
when one isn't in frame yet `IntentSubjectCoach` short-circuits the engine to a minimal result instead of
scoring an empty/irrelevant frame: `score`/`rawScore = 0` (not meaningful — the UI should not display them),
`metrics` contains only the `HORIZON` metric (still useful advice while searching), and `recommendations`
is exactly one "find your subject" message. LANDSCAPE and ARCHITECTURE never await a subject (`primary ==
null` is a perfectly normal result for them).

| Intent | Trigger | Recommendation id | Instruction |
|---|---|---|---|
| `PORTRAIT` | no usable face (post-filter) | `intent.portrait.find_subject` | "Move closer to your subject" if a face was detected but too small, else "Point the camera at your subject" |
| `GROUP_PORTRAIT` | fewer than 2 usable faces | `intent.group.find_subjects` | "Step back to fit everyone in" if exactly one face was found, else "Point the camera at the group" |
| `OBJECT` | no salient region found | `intent.object.find_subject` | "Move closer to your subject" |

`CompositionSmoother` treats `awaitingSubject` specially: the displayed score is **held** at its last real
value (the meaningless 0 is never fed into the EMA), shoot-ready is forced off, and the "find subject"
message is shown as the headline immediately — no confirmation delay, since it is a mode message describing
what the coach is doing, not competing framing advice. The moment a real subject reappears, the "find
subject" headline and its state are dropped immediately (not held for `recommendationMinHoldMs`) so normal
ranking resumes from a clean slate.

## Smoothing (`CompositionSmoother` / `SmoothingConfig`)

All tuning constants live in one `SmoothingConfig` data class. Everything is expressed in **time**, driven
by the frame timestamps, so a phone analysing 5 frames/s behaves the same as one managing 10. When
timestamps do not advance (tests, duplicate stamps) a nominal 100 ms per update is assumed; gaps longer
than 500 ms (app paused, camera switch) count as 500 ms.

| Constant | Default | Meaning |
|---|---|---|
| `scoreTimeConstantMs` | 2000 | EMA time constant for the score (63 % of a step change after 2 s) |
| `scoreTimeConstantOnSubjectChangeMs` | 350 | much faster constant for ~0.6 s after the subject count changes |
| `displayDeadband` | 3 | the on-screen number does not move until the EMA has drifted ≥ 3 points from it |
| `displayMinHoldMs` | 1000 | minimum time the on-screen number stays put between changes |
| `displaySnapDelta` | 10 | a change this large is shown immediately (a real reframe) |
| `displayRoundTo` | 1 | the displayed score rounds to the nearest multiple of this many points; left at 1 (plain nearest-integer), a one-line tune to e.g. 5 for a coarser, even calmer "70 / 75 / 80"-style readout if field feedback ever asks for it |
| `recommendationConfirmMs` | 800 | a challenger must be top-ranked continuously this long before replacing the headline |
| `recommendationMinHoldMs` | 2000 | the headline is protected from replacement until shown this long |
| `recommendationMinShowMs` | 1500 | advice never disappears before it has been on screen this long, even if its issue clears immediately |
| `issueGoneMs` | 1200 | if the current line's issue has been absent this long (subject still visible) it is dropped |
| `subjectLostDropMs` | 2500 | subject-related advice survives a subject-missing gap up to this long before it's dropped (a detector blink is not evidence the issue was fixed) |
| `oppositeDirectionExtraConfirmMs` | 500 | extra confirmation when the challenger is the opposite action (LEFT↔RIGHT, UP↔DOWN, CW↔CCW, CLOSER↔BACK) |
| `directionFlipHoldMs` | 600 | when the *same* advice id flips to the opposite direction frame-to-frame (subject jittering on top of its target), the previously displayed direction/instruction is held until the new one persists this long |
| `secondaryRecommendationConfirmMs` | 1200 | like `recommendationConfirmMs`, but for the up-to-two secondary advice lines |
| `secondaryRecommendationMinHoldMs` | 2500 | like `recommendationMinHoldMs`, but for the secondary lines |
| `geometryTimeConstantMs` | 400 | EMA time constant for every `display*` overlay geometry field (subject anchor, placement target, headline region, horizon angle) |
| `geometryHoldMs` | 600 | how long a `display*` geometry field holds its last value through a momentary detector blink before clearing to null |
| `shootReadyEnterScore` / `shootReadyExitScore` | 88 / 84 | hysteresis band so "shoot ready" doesn't chatter at the boundary |
| `shootReadyEnterHoldMs` | 400 | the score must stay above the enter threshold this long before SHOOT lights up |

Shoot-ready also requires no applicable metric at `Severity.HIGH` in the latest raw result.

**Advice slots.** Every displayed advice line — not just the headline — is smoothed by its own `AdviceSlot`
hysteresis state machine (confirm time, minimum hold/show time, issue-gone/subject-lost dropping, and
same-id direction-flip suppression). One slot runs the headline; up to two more run the secondary lines
(`GuidanceLevel.COACH` can show 3 total), each fed the raw ranked list with the ids already claimed by
higher-priority slots removed, and confirming/holding slightly longer than the headline
(`secondaryRecommendationConfirmMs` / `secondaryRecommendationMinHoldMs`) since a line read less urgently
has no reason to churn faster than the headline above it. Nothing unsmoothed reaches the UI any more — a
secondary line settles down exactly like the headline does — and the smoother actively prevents the same
id from ever appearing twice across `SmoothedComposition.activeRecommendations` (e.g. a recommendation
being promoted from secondary to headline drops it from the secondary slot immediately rather than
waiting out that slot's own confirmation delay).

**Display geometry.** `SmoothedComposition` also carries a time-smoothed counterpart of the raw per-frame
overlay geometry, so the preview's arrow/ring/highlight/level line hold as steady as the headline and the
score do — the UI should always draw these, never anything read straight off `SmoothedComposition.raw`:

| Field | Type | Driven by | UI element |
|---|---|---|---|
| `displayAnchor` | `NormalizedPoint?` | EMA of the primary subject's `anchorPoint`; resets (snaps) when the primary subject's id/kind changes | the framing **arrow**'s tip |
| `displayTarget` | `NormalizedPoint?` | EMA of the `SUBJECT_PLACEMENT` analyzer's `TargetPoint`; null unless the headline is placement/looking-room/edge-tension advice and the anchor is outside the placement dead zone | the **target ring** |
| `displayRegion` | `NormalizedRect?` | EMA of the headline recommendation's `region`; null if none, snaps when the headline id changes | the **region highlight** |
| `displayHorizon` | `OverlayGeometry.Line?` | EMA of the measured horizon *angle*, then redrawn as a line (linearly blending the two endpoints directly would not track a rotation correctly) | the **level line** |
| `displaySubjectBounds` | `NormalizedRect?` | EMA of the primary subject's bounding box; same reset behaviour as `displayAnchor` | any UI wanting the whole silhouette |

Every field uses `geometryTimeConstantMs` (~400 ms, faster than the score's constant since geometry should
visibly track a deliberate reframe quickly — it's per-frame jitter this smooths, not slow drift), holds its
last value for `geometryHoldMs` (~600 ms) through a momentary detector blink before clearing to null, and is
clamped into the visible `0..1` frame.

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

- `LeadingLinesAnalyzer` is still explicitly experimental (see its kdoc): proximity-to-a-point is a weak
  proxy for "this line leads the eye to the subject." It now produces a (low-severity, low-weight)
  recommendation in LANDSCAPE/ARCHITECTURE when a line clearly leads away from the subject, but still never
  does in a portrait/object scene, and its confidence is capped low everywhere.
- `SceneClassifier` and `SubjectResolver`'s stats-only "salient region" / "object scene" detection (used
  when there is no object-detector signal at all) are deliberately conservative heuristics over the
  downscaled edge-density grid, not real saliency detection — a busy but uniform background (foliage,
  gravel) is designed to *not* trigger either one, at the cost of sometimes missing genuinely interesting
  but low-contrast objects. This limitation does not apply to a real `DetectedObject` from the object
  detector, which is trusted directly.
- `CompositionOptimizer` / `FrameTransform` only re-transform face/body geometry across candidates (see
  their kdocs) — an OBJECT-kind primary subject is therefore frozen across all six simulated candidates
  just like `ImageStatistics` is, so the optimizer contributes nothing extra for an object-only frame
  beyond validating that no face/body-driven move helps. Recomputing object geometry per candidate would
  need the same kind of transform `FrameTransform` already does for faces/bodies; nothing prevents adding
  it later, it just wasn't needed for this pass.
- The pole-through-the-head detector in `BackgroundDistractionAnalyzer` and the mask-based waist/knee-cut
  guess in `CroppingAnalyzer` are both read off coarse (~32-cell) mask geometry, not a real object detector
  or confirmed joint position — both are deliberately capped at MEDIUM/LOW severity rather than HIGH for
  that reason.
- See the "sign conventions" section above for the one specific spot (`ReframeVector.toMoveSubject`'s
  vertical sign) worth a second look from the model owner.

## Golden scenarios (`GoldenScenariosTest`)

End-to-end fixtures run through the whole `CompositionEngine`, each modelling one situation called out by
name in the module brief. Every scenario checks the same four things — detected scene, primary subject
kind, the headline recommendation id (or its absence, or just "is this issue flagged somewhere" when the
optimizer's own validation logic could legitimately reorder the headline), and a loose score band — loose
on purpose, so this suite catches a wrong *category* of outcome without becoming brittle against future
weight tuning.

| Scenario | Scene | Primary subject | Headline (or key signal) |
|---|---|---|---|
| Pint on a table, distant background face | OBJECT | OBJECT | no HEADROOM advice at all |
| Centred close-up portrait | PORTRAIT (close-up) | FACE | no SUBJECT_PLACEMENT advice |
| Full-body shot cut at the ankles | PORTRAIT | PERSON | `cropping.feet` |
| Group of three, one face at the frame edge | GROUP_PORTRAIT | PERSON/FACE | `edge.tension.group_cutoff` at HIGH |
| Landscape, tilted + dead-centre horizon | LANDSCAPE | none | HORIZON + SCENE_SPECIFIC both flagged |
| Symmetric hallway, off-centre subject | ARCHITECTURE | SALIENT_REGION | `symmetry.offcenter` |
| Symmetric hallway, centred subject | ARCHITECTURE | SALIENT_REGION | no SYMMETRY advice |
| Portrait with a pole above the head (mask) | PORTRAIT | FACE | `background.pole` |

New `SyntheticFrames` fixtures backing these: `objectAt` (a `DetectedObject` builder), `maskFromRect` (a
`SubjectMask` whose cells inside a rect are subject, everywhere else background), `maskWithPoleAbove`,
`statsWithNarrowPoleAbove` (a narrow high-edge column on the *same* grid resolution as a paired mask, so
`MaskHeuristics`' cell mapping is exact), `statsWithHorizonStep` (a luminance-only horizon step that won't
also get mistaken for a compact salient region the way `statsWithHorizonLine`'s edge spike would), and
`offCenterSalientRegion` (a symmetric-scene fixture with an adjustable off-centre hot patch).

## Quality gate

`EngineRobustnessTest` fuzzes `CompositionEngine.default().evaluate()` over hundreds of randomly
generated `FrameAnalysis` inputs (seeded `Random(42)` for reproducibility — 0-4 faces of random
size/position including partially or fully out of frame, optional bodies with random/missing landmarks,
0-2 random objects, random stats grids sized 8..32 per axis, an optional `SubjectMask` on a *different*
grid size than the paired stats grid, random device orientation including unreliable readings, and every
`GuidanceLevel`/`SceneIntent` combination) and asserts the engine never throws and always honours:

- `score` is in `0..100` and `rawScore` is in `0f..100f` and never `NaN`.
- Every `CompositionMetric.score` and `.confidence`, and every `Recommendation.confidence`, is in `0f..1f`
  and never `NaN`.
- `recommendations.size <= 3`.
- No two active recommendations point in opposite `Direction`s (LEFT/RIGHT, UP/DOWN, ROTATE_CW/CCW,
  CLOSER/BACK) at once.
- Every `Recommendation` carrying a `vector` has `direction == vector.primaryDirection()` or
  `Direction.NONE` — a recommendation's stated direction can never contradict its own reframe vector.
- No metric carries both a `strength` and an `issue` string at once — contradictory "this is good" /
  "this is a problem" messages must never both surface from the same category on the same frame.
- No metric with `applicable = false` carries a `recommendation`.
- `awaitingSubject == true` implies exactly one recommendation (the "find your subject" message).

A second fuzz pass in the same file runs every random frame's result back through a single persistent
`CompositionSmoother` and additionally asserts every `SmoothedComposition.display*` geometry field is
either `null` or within the `0..1` frame, and that no id is ever shown twice across
`activeRecommendations` (the headline and the up-to-two secondary lines never collide).

`CompositionSmootherTest`'s fuzz cases feed the smoother random timestamp gaps (0, negative, a few ms,
several seconds) and assert `displayScore` always stays in `0..100` and never `NaN`, and that a constant
raw score held for several seconds makes the displayed score settle to within ±1 of it.

`EnginePerformanceTest` runs 200 realistic frames through the full engine (including the optimizer's six
per-candidate re-simulations) and prints the mean per-frame time — not a pass/fail timing assertion (too
flaky across machines), just a smoke test that the whole pipeline still runs end-to-end and a number to
watch when tuning analyzer cost.

Bugs this suite caught and fixed, as a record of why each invariant above exists:

- `SceneSpecificAnalyzer`'s landscape horizon-repositioning advice had the vertical `ReframeVector` sign
  backwards (`dy = horizonY - nearestThird` instead of `nearestThird - horizonY`), so it told the
  photographer to move the camera in exactly the wrong direction for every off-thirds horizon.
- `SubjectPlacementAnalyzer`, `HeadroomAnalyzer`, `LookingRoomAnalyzer`, `EdgeTensionAnalyzer`,
  `BackgroundDistractionAnalyzer`'s no-mask path, and `SubjectSeparationAnalyzer` each computed their
  `strength` (or `recommendation`) string from a threshold on `score` independently of whether a
  `recommendation` (and therefore an `issue` string) was already active for a nearby-but-distinct
  threshold on the same underlying measurement — in each case a narrow band of inputs could satisfy both
  conditions at once, showing a positive "strength" line and a contradictory "issue"/recommendation for
  the same metric on the same frame. Fixed by gating each `strength` on `recommendation == null` rather
  than re-deriving a second, independently-tuned threshold.

## Running

```
./gradlew :composition:test            # 80 tests, no Android SDK required
./gradlew :composition:compileKotlin   # warnings-clean
```
