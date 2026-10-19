# SceneRight — `:app`

The camera-facing Compose app (package `com.compositioncoach.app`; the product is SceneRight, see
`docs/BRAND.md`). This module owns the UI, CameraX binding, capture and storage, settings, the photo journal
and navigation. It talks to `:composition` (`CompositionCoach`, `AfterActionReport`, `CropSuggester`,
`AdaptiveWeights`) and `:vision` (`VisionPipelineFactory`, `StillImageAnalyzer`, `FrameStackingSource`) only
through their public contracts. What the product does is `docs/PRODUCT.md`; this file is how the app does it.

## Screen map

```
CameraPermissionGate
  └── (granted) AppNavGraph  [navigation-compose, routes below]
        ├── "camera"    CameraScreen     (start destination)
        ├── "review"    ReviewScreen     (pushed on capture: the after-action card)
        ├── "settings"  SettingsScreen   (camera gear icon)
        ├── "your_eye"  YourEyeScreen    (Settings → Your eye, or gallery thumbnail long-press → Your eye)
        ├── "privacy"   PrivacyScreen    (Settings → About → Privacy)
        └── "licences"  LicencesScreen   (Settings → About → Open-source licences)
```

Every screen pushed over the camera shares the camera destination's `CameraViewModel` (its back-stack entry is
always there), so review decisions, the Enhancement row and both "reset coaching" buttons go through one object.
Every preference, including Easy/Coach and Pro, is a `CoachSettings` key in `SettingsRepository` (table below);
the camera reads them from `CameraUiState.settings`, Settings writes them through `SettingsViewModel`.

* **camera** — `CameraScreen` + `CameraViewModel`. 4:3 preview box holding the `PreviewView` and every overlay
  that maps normalized geometry (`GhostOverlay`, `ThirdsGridOverlay` (Pro), `CoachingLayer`,
  `DebugGeometryOverlay` (Pro + developer mode), `FocusRingOverlay`, `ExposureSlider` (Pro), `CameraTopBar`,
  `TimerCountdownOverlay`, `DegradedNoteChip`, `OnboardingCard`, `DebugOverlay`). What is drawn is derived in
  `CameraChromeModel.of(state)`:
  * **Easy** (`experience_mode` = EASY, default): no score number, one cue chip, no secondary icons, no reasons.
    **Coach**: the score chip (`CameraUiState.showScoreNumber`: Coach and Show score), the secondary icon row and
    a one-line reason under the chip (`GuidanceFormatter.reasonLine`).
  * **Pro controls** (`pro_controls`) reveal the exposure slider, zoom presets, enhancement pill, grid and debug
    overlays; off, they are not composed at all.
  * Shoot-ready (the green ring, "SHOOT", the shoot-at-the-peak trigger, the haptic tick) is
    `CameraUiState.peakReady`: the group's readiness in a group scene (and never ready in Group before a group is
    in frame), else the engine's `isShootReady`; never while the engine is still awaiting a subject.
  * The cue chip is replaced (`LiveChipRules.overrideChip`) by, in order: the hold chip (`HoldCues`: "Hold still"
    for Night, "Hold still for 4 seconds" for Light trails...), the group's first blocker (`GroupCues.chip`),
    Selfie's "Arm out a little" (face > 45 % of the height, `SelfieCues`). While `holdProgress != null` the
    coaching layer dims to `HOLD_DIMMED_ALPHA` and the shutter's inner ring fills with the progress.
  * `CameraTopBar`: flash, the self-timer clock (`TimerButton`, Off / 3 s / 10 s via `onTimerSelected`; in Group
    the label reads "when everyone's ready"), the enhancement pill (Pro), settings. `timerCountdown` shows as big
    numerals centred over the preview. Selfie arms a 3 s countdown by default (`SelfieTimerDefault`).
  * `CameraBottomZone`, bottom up: shutter row (`GalleryThumbnail` with a long-press menu "Your eye" / "Use as
    guide", `ShutterButton` with `GroupDots` around the arc when `groupStatus != null`, lens switch),
    `OutcomeStrip` (all `Outcome.entries` in plain words, tap or swipe, haptic tick, `onOutcomeSelected`), zoom
    presets (Pro), the SHOOT / Hold still chip.
  * `GhostOverlay`: `ghostRect` (`ghostRectFor`: the still's subject mask bounds, else the subject box) as a 1dp
    dashed 40 % white rectangle; tap hides it. Gone after 20 s or the next capture (`ReshootGhost`).
  * `outcomeDegradedNote` (a `DegradedNote`, text from `strings.xml`) shows once, for 3 s, each time the outcome is
    picked. A Motion hold that shook too much shows the snackbar "Too shaky, try again" (`CameraNotice`).
  * Heads-up guidance (`HeadsUpGuidance.kt` pure table and planner, `HeadsUpOutputs.kt` `HapticGuide` /
    `VoiceGuide`): Settings > More > Guide by vibration (`haptic_guidance`) / Guide by voice (`voice_guidance`).
    The app declares `VIBRATE` (a normal permission, no prompt) so the guide plays real waveforms; without a
    vibrator it falls back to view haptics.
* **review** — `ReviewScreen` (photo area `ReviewPhotoArea`, `SwipeToKeep`) + `AfterActionCard`: score ring and
  verdict (`report.verdict`, or from the live score while `report == null`), What worked (≤ 3 green), Try next
  time (one amber line with "+N", "Not helpful" → `onNotHelpful`, "Shoot again" → the ghost), Better crop?
  (`CropCardsRow`: up to 3 cards clipping the same photo, tap previews the window dashed on the full photo, "Save
  crop" → `CameraViewModel.onCropChosen`: `exportCrop`, then the `CROP_CHOSEN` learning event), What if (hold:
  `whatIf` crop with "72 → 83"), Portrait (hold: the background-separated preview; "Save as copy" →
  `onSavePortraitCopy`: `exportProcessed(…, "_portrait")`), Compare (hold: `originalPhotoUri`, the pre-level copy
  or a stack's first frame), Details (outcome, diagnostics, every measure). "Saved as a copy" only shows once the
  file is written. Keep is the default: back, the arrow and a downward swipe all Keep; Keep / Retake call
  `onReviewDecision(kept)`. `review_card_mode` (Settings > More > "Card only when there's something to say") is
  honoured by `AfterActionLogic.showCard`.
* **settings** — `SettingsScreen` + `SettingsViewModel` (`SettingsRepository` only): Experience (Easy · Coach;
  Coach adds Show score), Your eye, Pro controls (reveals Guidance level and Grid); More: capture rows
  (Enhancement only under Pro), Guide by vibration, Guide by voice, Card only when there's something to say,
  Composition guidance, Battery saver, Pro-only detection toggles and Developer mode, Reset coaching preferences;
  About: privacy line, Privacy, Open-source licences, Send feedback (share sheet with the last card as text,
  `FeedbackText`), version. The outcome strip on the camera is the shooting-mode control.
* **your_eye** — `YourEyeScreen` over `YourEyeData` from `JournalYourEyeSource` (`YourEyeMapper` over
  `di/YourEyeRepository`'s `YourEyeSnapshot`: the photo journal plus the taste model): keepers this month, three
  tendencies, advice followed most / skipped most (advice shown at least 3 times, named by `AdviceLabels`), best
  three, Start fresh (confirmed). Start fresh and Settings' Reset coaching preferences both call
  `CameraViewModel.onResetCoachingPreferences` (neutral weights, empty journal).
* **privacy** — `PrivacyScreen`, the policy text (`PrivacyPolicyText`), no network.
* **licences** — `LicencesScreen`, a static list (CameraX, ML Kit, Compose/AndroidX, Coil, Kotlin), Apache 2.0.

Every user-facing string of these screens is in `res/values/strings.xml`, including the live chip table
(`ChipKey` / `ResourceChipStrings`, installed by `AppNavGraph`; `ChipStrings.English` is the JVM tests' fake and
`ChipStringsResourceTest` keeps the two identical), the hold chips, the "Coaching paused in …" chip, the degraded
notes and the advice names. The engine's own sentences (what worked, try next time) come from `:composition` in
English.

## Ports between the state layer and the capture side

Two interfaces in `di/` keep the view model testable without CameraX or ML Kit; `AppContainer` binds the real
implementations, and tests use fakes.

| Port | Bound to | What it does |
|---|---|---|
| `StillAnalysisPort.analyze` | `CaptureStillAnalysisPort` | `CaptureRepository.decodeForAnalysis` (EXIF-aware, ~1024 px) → `StillImageAnalyzer.analyze(bitmap, isFront, alreadyMirrored = true)` |
| `StillAnalysisPort.separateBackground` | same | `decodeFull` → `BackgroundSeparator.isApplicable/apply` → a private preview in `cacheDir/originals` (never automatic in the gallery; "Save as copy" exports it) |
| `CaptureOrchestrationPort.capture` | `PipelineCaptureOrchestrationPort` | `CapturePipeline.capture(outcome, request, frameSource as? FrameStackingSource)` |
| `.awaitSteady(outcome)` | same | the 450 ms steady gate, or Night's 2 s steady hold (`CapturePipeline.awaitSteadyHold`) |
| `.holdProgress` | same | `CapturePipeline.holdProgress`: the Motion stack's or the steady hold's 0..1 |
| `.pipelineSupport` | same | Motion when the frame source is a `FrameStackingSource` and analysis is not paused; focus stack when `lensControl.supportsFocusStack` |
| `.setGroupModeEnabled` | same | `VisionFeatureToggles.setGroupModeEnabled` (eyes-open classification) |
| `.playShutterSound` | same | only the `shutter_sound = ALWAYS` click where the region does not already force one: the pipeline itself plays the mandatory click inside `capture`, so it never sounds twice |

## Coaching layer (`CoachingLayer`, `docs/COACHING_UI.md`)

Spatial first, minimal text: the photographer is shown *where to move*, and words are only ever a label on
the cue. `GuidanceFormatter` decides **what** each piece says; `CoachingLayer` decides **where** it goes;
no composable re-derives either.

* **Readiness arc** — `ReadinessArc` (pure: sweep and colour) + `drawReadinessArc` (the shared drawing).
  Sweeps clockwise from 8 o'clock (150° in Compose's from-3-o'clock, clockwise-positive angles), closing
  the circle at 100. White below 70, `Accent.Warn` to 87, `Accent.Ready` at 88+; shoot-ready always paints
  the full green ring with a soft outer glow, whatever the number says. Dims to 30% while
  `awaitingSubject`, since the score behind it is a held, stale value.
* **Primary cue** — exactly one, chosen by `GuidanceFormatter.cueFor`:

  | Headline | Cue |
  |---|---|
  | LEFT / RIGHT / UP / DOWN | `EdgeChevron` — 64dp, on the *physical* edge that direction points at, breathing 0.55↔0.8 alpha over 1.2s |
  | CLOSER / BACK | `CornerBrackets` — four 18dp brackets easing 8dp inward / outward over 1.4s |
  | ROTATE_* (or a directionless HORIZON) | `BubbleLevel` — fixed ticks plus a 48dp line rotated by the smoothed roll, snapping green when level and fading 1.5s later |
  | anything else | chip only |

  Which *screen* edge a chevron anchors on comes from `CueEdge.forDirection`, which rotates the
  direction's own unit vector through `OverlayMapper.rotateVectorToDisplay` — the same call every piece of
  overlay geometry goes through — rather than a hand-written table that could drift from the truth table.
  At `ROTATION_90` (right edge up) physical right is the screen's **bottom**, so that is where the "move
  right" chevron goes; `CueEdgeTest` pins all sixteen cases. A `Raise` cue puts the chevron on the
  physical-top edge, where the score chip already lives: the *chrome stack* slides along that edge to make
  room (`CueEdge.alignmentShiftedAlongEdge`) rather than the chevron being pushed off the edge it exists to
  point out of — except at the screen's own top edge, where the top bar has already pushed the stack 60dp
  clear.
* **Cue chip** — the only live text: 1-3 words on the standard scrim, 200ms crossfade, capped at 55% of
  the preview's short side. Attached to the chevron when there is one (12dp inside it), otherwise stacked
  under the score chip at the physical top. It is a **polite live region** and announces a spoken sentence
  ("Move slightly right") rather than its abbreviated words. Wording is `GuidanceFormatter.chipText`'s
  table, verbatim from the spec, with a three-word trim of the recommendation's title as the fallback for
  any category the table doesn't name.
* **Score chip** — `ScoreChip`, one 32sp tabular numeral in a compact chip at the physical top, tinted by
  tier and nothing else (no pulse, no bump). Coach only, and only with Settings > Show score on. Announces "Composition
  score 82".
* **Secondary advice** — `SecondaryAdviceRow`, up to two 28dp icon chips, never sentences. Tapping one at
  Coach level replaces the cue chip with that recommendation's one-line explanation for 3s. Icons
  (Material Symbols, outlined): horizon → horizontal rule, headroom → vertical align top, edge tension →
  crop, background → layers, separation → contrast, balance → balance, symmetry → flip; and, for the
  categories the spec's table doesn't name, placement → centre focus, cropping → crop free, looking room →
  visibility, negative space → width, leading lines → timeline, scene-specific → landscape.
* **Frame marks** — `CoachingOverlay` draws only the target ring (18dp, 1.5dp stroke, 70% white, at
  `displayTarget`) and the region highlight (1dp, 45% white, rounded, from `displayRegion`). The old
  subject-anchored directional arrow is **gone**: the chevron carries direction now, and nothing sits over
  the centre of the frame except the ring.
* **Quiet states** — no advice and a score ≥ 70: the chip clears and a check mark blinks beside the shutter
  for a second. No scene yet: "Point at a subject", lingering 3s after a subject appears. Awaiting a
  subject: the find-subject recommendation's title ("Looking for a face") with the arc dimmed.
* **Guidance levels** — Minimal shows a cue only for `severity >= MEDIUM` and no secondary icons (and,
  since the chip is a *label on* a cue, suppressing the cue suppresses its chip); Balanced shows the cue,
  the chip and one icon; Coach shows two, tappable.
* **Reduced motion** — `rememberAnimationsEnabled()` reads `Settings.Global.ANIMATOR_DURATION_SCALE` once
  and is threaded down as a plain parameter (so previews and tests can pin it). At scale 0 the breathe and
  the bracket ease stop and every transition becomes an instant cut, via `motionDuration(...)`.
* **Feedback** — a light haptic tick on a mode change and on the shutter press; the single medium tick on
  entering shoot-ready still comes from `CameraViewModel`'s `ShootReadyHapticTick` event (fired once per
  entry, not per frame). No sounds.

## State flow

```
FrameAnalysisSource.frames (vision)
        │  (skipped entirely if guidanceEnabled == false)
        ▼
CompositionCoach.process(frame, guidanceLevel, sceneIntent)   ── every frame, keeps the smoother warm
        ▼
SmoothedComposition  ── sampled to ~10 Hz (kotlinx.coroutines sample())
        ▼
CameraUiState (StateFlow)  ── pure `withX(...)` reducer functions, see CameraUiState.kt
        ▼
CameraScreen (collectAsStateWithLifecycle) ── CoachingLayer / shutter arc / overlays redraw
```

* **Outcome → scene intent.** The engine is told what the photographer is shooting through
  `CoachSettings.sceneIntent`, which is always derived from the selected `outcome` (`OutcomeSpec.sceneIntent`).
  `CameraViewModel` forwards it to both `CompositionCoach.process(...)` (live) and `.evaluateOnce(...)` (at
  capture). A change resets the smoother (`coach.reset()`), except on the very first settings emission.
* **The view model's collaborators**, each in its own file: `LiveFrameProcessor` (per-frame scoring, the 10 Hz
  sample, shoot-ready tick, followed-cue feedback), `OutcomeBinding` (outcome side effects and capture plan,
  below), `CaptureFlowCoordinator` (timer and shutter), `ReviewFlowCoordinator` (the card's background work and
  learning signals), `ReviewCopies` (crop and portrait copies), `PostCaptureLevelling`, `ZoomEaser`,
  `ReshootGhost`, `PipelineLifecycle`, `CoachingWeights` (the taste model plus the outcome's focus).
* **`awaitingSubject` UI state**: when `SmoothedComposition.awaitingSubject` is true (declared intent
  needs a subject — e.g. PORTRAIT — that isn't in frame yet), `displayScore` is the last meaningful
  score the engine held, not a live one, so the UI must not present it as current:
  * The readiness arc and `ScoreChip` dim to `GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA` (30%), and
    shoot-ready styling (the full green ring, the "SHOOT" chip) never shows regardless of `isShootReady`
    — `GuidanceFormatter.effectiveShootReady` is the single gate for that.
  * `CoachingLayer` shows no spatial cue at all and puts the find-subject recommendation's `title`
    (`GuidanceFormatter.awaitingSubjectChipText`, e.g. "Looking for a face") in the cue chip instead.
  * All of it is pure `GuidanceFormatter` logic, unit-tested on the JVM without needing the real engine
    to ever actually return `awaitingSubject = true` (see Known limitations below).

* `CameraViewModel` never touches a `Context` beyond what `AppContainer` (built from
  `Application`) already holds; it does not store a `PreviewView` or `Activity`. It *does* keep the
  `CameraController` handed back in `CameraBindResult.Success.controller` (application-context only once the
  screen unbinds it), so zoom presets, exposure, enhancement rebinds and the capture pipeline act on the
  session the screen bound.
* Per-frame scoring runs on `Dispatchers.Default` (`flowOn`); the sampled `onEach` that pushes into
  `MutableStateFlow` runs on the ViewModel's own dispatcher (`Main`, via `viewModelScope`).
* On shutter: the capture pipeline (see "Capture pipeline" below) saves the JPEG, then
  `CompositionCoach.evaluateOnce(frameAtShutter, GuidanceLevel.COACH, intent, weights)` computes the live-frame
  review result on `Dispatchers.Default`, the entry is parked in `AppContainer.reviewStore` (a tiny in-memory
  `StateFlow<ReviewEntry?>`, not a nav argument) and the screen navigates to `"review"`, where
  `ReviewFlowCoordinator` fills in the still analysis, report, crops and portrait preview as they arrive.
* Overlays never draw anything Compose renders as part of the *photo* — the `PreviewView` and the
  overlay `Canvas`/composables are separate layers; `ImageCapture` reads frames from the camera
  pipeline directly, so nothing drawn by Compose can leak into the saved JPEG.

## CameraX binding notes (ViewPort / crop alignment)

`CameraController.bind()` builds `Preview`, `ImageCapture` and `ImageAnalysis` and binds all three
together as **one `UseCaseGroup`** sharing **one `ViewPort`** derived from the `PreviewView`
(`previewView.viewPort`, falling back to a `ViewPort.Builder` from the view's own aspect ratio if the
view hasn't been laid out yet). That single choice is what makes the rest of the overlay math trivial:

* CameraX crops the `ImageAnalysis` stream to the *same field of view* the `Preview` shows — not a
  wider or differently-cropped sensor crop.
* `:vision`'s `FrameAnalysisSource` normalizes all detector geometry (faces, bodies, target points,
  the horizon line) to that same upright, front-camera-mirrored analysis frame — `NormalizedPoint`/
  `NormalizedRect` are 0..1 fractions of *that* frame.
* `PreviewView`'s `FILL_CENTER` scale type (set in `CameraScreen`) stretches/crops that same field of
  view to exactly fill the view's pixel bounds, with no letterboxing.

Put together: "fraction of the visible frame" and "fraction of the `PreviewView`'s own width/height"
are the same number — *once the frame's own physical-up rotation has been undone*, see "Device rotation
(Pixel style)" below — so `OverlayMapper.toPx(point, deviceRotationDegrees, viewWidthPx, viewHeightPx)` is
a rotation followed by a plain `point.x * width, point.y * height`. See the class doc on `OverlayMapper`
for the full argument and what would break it (dropping the shared `ViewPort`, or switching to
`FIT_CENTER`, which can letterbox). `OverlayMapperTest` covers the four corners + centre + a rect, at all
four rotations.

Other binding details:

* **Capture** targets 4:3 at the sensor's highest available resolution (`ResolutionSelector` with
  `AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY` + `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY`)
  — the classic Pixel-style full-quality still aspect. **Analysis** stays ~640×480 4:3 (its own
  `ResolutionSelector`, `ResolutionStrategy(640x480, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`), output
  format `YUV_420_888`, backpressure `STRATEGY_KEEP_ONLY_LATEST`, analyzer on a dedicated single-thread
  executor. Both use cases still share the one `ViewPort` from the preview (see above), so despite the
  different pixel sizes and independent `ResolutionSelector`s, all three use cases crop to the identical
  field of view — see the "why capture is cropped to the shared ViewPort" section of `CameraController`'s
  class doc for the full reasoning.
* `ImageCapture` uses `CAPTURE_MODE_MAXIMIZE_QUALITY` by default (this is a coaching camera whose whole
  point is a better final photo, worth the extra processing time once framing is dialed in), falling back
  to `CAPTURE_MODE_MINIMIZE_LATENCY` when `CameraController.setPreferFastCapture(true)` has been called —
  driven by `CameraUiState.preferFastCapture` (true when either the user's own battery-saver setting or a
  degraded `PerformanceTier`, see below, calls for it). CameraX fixes an `ImageCapture`'s capture mode at
  `Builder.build()` time, so this only takes effect on the *next* bind — a caller that wants it to react
  live should include whatever drives `preferFastCapture` in the rebind `DisposableEffect`'s keys, the
  same way `lensFacing` already is. JPEG quality is 100 (see "Capture pipeline"); `ImageCapture.setTargetRotation` is set at bind
  time from the display rotation (always `ROTATION_0` for this portrait-locked app) *and* kept live
  afterwards from the phone's actual physical rotation — see "Device rotation (Pixel style)" below for
  why a bind-time-only value isn't enough for correct EXIF.
* `CameraController.setExposureCompensation(index)` sets exposure compensation, clamped to the bound
  camera's supported range; that range comes back in `CameraBindResult.Success.exposureRange`
  (`android.util.Range<Int>`, both bounds `0` when unsupported) and is mirrored into
  `CameraUiState.exposureRange`/`exposureIndex` (a plain Kotlin `IntRange`, deliberately not the Android
  type — see that field's KDoc for why) for a slider to size and position itself against.
* `CameraController.bind()` retries once, after 300ms, on `IllegalStateException` or
  `CameraUnavailableException` (both observed as transient CameraX failures — a state race right after
  another `Activity`/app instance released the camera, or the camera service briefly busy) before
  reporting `CameraBindResult.Failure`; any other exception fails immediately. `unbind()` marshals onto
  the main thread if called from anywhere else, since CameraX requires bind/unbind to run there.
* If the vision pipeline fails to construct for any reason, `AppContainer.frameSourceOrNull()` catches
  that once and logs it; `CameraController.bind()` is called with a `null` analyzer and simply does not
  add an `ImageAnalysis` use case, so preview + capture keep working and the UI shows a small "Analysis
  unavailable" note (`CameraUiState.analysisUnavailable`) — see "Known limitations" below.
* Lens switching: `CameraViewModel.onSwitchLensRequested()` flips the desired facing in state, which
  re-keys a `DisposableEffect` that re-binds; once bound, `onCameraBindResult` calls
  `frameSource.setFrontCamera(...)` and `coach.reset()` so the smoother doesn't blend across camera
  switches, and updates `CameraUiState.hasFlashUnit`/`exposureRange` from the fresh `CameraBindResult` —
  both are re-derived on every bind, so switching to/from a lens with no flash unit or a different
  exposure range is reflected immediately. If the requested lens isn't available, `CameraController`
  falls back to the other one and logs it.
* Flash cycles OFF → AUTO → ON and is hidden entirely when `cameraInfo.hasFlashUnit()` is false.
  Tap-to-focus uses `PreviewView.meteringPointFactory` with AE+AF and a 3s auto-cancel; pinch-zoom is
  wired via `detectTransformGestures` (optional per the spec, included).

## Device rotation (Pixel style)

Like the stock Pixel Camera, this app is `android:screenOrientation="portrait"` and the live preview
content **never** rotates. Everything else has to compensate for that, and getting one sign wrong here has
shipped twice. So this section states the whole chain as a truth table derived from physics, and every row
of it is asserted in `RotationTruthTableTest` (`app/src/test/.../ui/camera/`), which starts from a gravity
vector and runs the *same* functions the app runs.

### The conventions everything is derived from

* **Device axes** (`SensorEvent`): `+x` out the screen's RIGHT edge, `+y` out its TOP edge, `+z` out of the
  screen face. A stationary phone reports the direction of **up** in those axes.
* **`Surface.ROTATION_90` = the phone turned 90 degrees counter-clockwise from natural** (as its user sees
  it), so its RIGHT edge points up. `deviceRotationDegrees` (from `:vision`'s gravity-derived
  `DeviceRotationQuantizer`, carried on `FrameAnalysis`) is in exactly that sense.
* **Screen coordinates**: x right, y down. `Modifier.rotate` / `graphicsLayer.rotationZ` is
  **clockwise-positive**.
* **The analysis frame** (`Geometry.kt`) is physically upright: in it, world up is `(0, -1)` and world
  right is `(1, 0)`, front camera mirrored so `x = 0` is always the left edge on screen.

### The truth table

| hold | gravity (up in device axes) | `deviceRotationDegrees` | analysis rotation (rear / front) | physical top-centre of the scene appears at | physical right points | chrome angle | `ImageCapture.targetRotation` |
|---|---|---|---|---|---|---|---|
| portrait (natural) | `( 0, +g, 0)` | 0 | 90 / 270 | screen top-centre | screen right | 0 | `ROTATION_0` |
| right edge up | `(+g, 0, 0)` | 90 | 0 / 0 | screen **right**-centre | screen **down** | **+90** | `ROTATION_90` |
| upside down | `( 0, -g, 0)` | 180 | 270 / 90 | screen bottom-centre | screen left | 180 | `ROTATION_180` |
| left edge up | `(-g, 0, 0)` | 270 | 180 / 180 | screen **left**-centre | screen **up** | **-90** | `ROTATION_270` |

(Analysis rotation assumes the usual mount, `imageInfo.rotationDegrees` = 90 rear / 270 front; it is
`R0 - theta` for the rear camera and `R0 + theta` for the front — see `:vision`'s README.)

The single fact that generates the middle three columns: **the portrait-locked display frame is the
physical-up frame turned clockwise by `deviceRotationDegrees`.** With the phone turned `theta` CCW its axes
are `x_hat = cos(theta)*right + sin(theta)*up` and `y_hat = -sin(theta)*right + cos(theta)*up`, so world up
is drawn at `(sin theta, -cos theta)` and world right at `(cos theta, sin theta)` — both the unrotated
vector turned clockwise by `theta`. Chrome then needs `+theta` clockwise, because a glyph rotated clockwise
by `phi` has its own up at `(sin phi, -cos phi)`, and for it to read upright to the photographer that must
equal where physical up appears.

### Where each column is implemented

1. **Analysis reasons in true physical-up coordinates.** `:vision` corrects `imageInfo.rotationDegrees` by
   `deviceRotationDegrees` (`UprightRotation`, see `:vision`'s README) before building
   `FrameCoordinateMapper` or handing ML Kit its `InputImage`, so every detector, the luma statistics, the
   segmentation mask, and the horizon/thirds/headroom logic downstream all see a physically-upright frame —
   `FrameAnalysis.frameWidth`/`frameHeight` follow suit (a landscape hold reports wider-than-tall).
   `DeviceOrientation.rollDegrees` is re-referenced onto `deviceRotationDegrees` so a level hold reads ~0
   in every orientation, and positive still means "the horizon appears rotated clockwise in the analysis
   frame".
2. **Overlays rotate the *coordinates*, not the canvas.** `OverlayMapper.rotatePointToDisplay` /
   `rotateRectToDisplay` / `rotateVectorToDisplay` apply exactly the clockwise-by-`theta` rotation above
   before the existing plain normalized-to-pixel multiply. `CoachingOverlay` (target ring, region
   highlight), `CueEdge.forDirection` (which screen edge an edge chevron anchors on), `HorizonRoll`
   (the bubble level's angle) and `DebugGeometryOverlay` (object boxes, subject boxes/landmarks, mask
   heat) all go through it — none of them re-derives the rotation convention.
3. **Chrome counter-rotates in place by `+theta`.** Flash and the settings gear (`CameraTopBar`), the
   gallery thumbnail, shutter and lens switch (`BottomControlBar`), the physical-top stack (score chip,
   bubble level, cue chip, secondary icons) and a chevron-attached cue chip each wrap in `RotatedChrome`,
   whose angle comes from
   `OverlayMapper.uprightChromeAngleDegrees`. `rememberControlCounterRotation` tracks an ever-accumulating
   (never-wrapped) target so `animateFloatAsState`'s 250 ms tween always takes the *shorter* turn (270 -> 0
   is a +90 hop, not a -270 spin).
4. **A rotated wide banner doesn't overflow its slot, and hugs the physical top edge.** `Modifier.rotate`
   only rotates pixels — it never changes the size the layout system believes an element has, so a wide
   banner rotated 90 degrees still reported its wide-short size and ran across the middle of the preview.
   `RotatedChrome` measures its content with width/height swapped at 90/270 and reports the *swapped* size
   to its parent (`RotatedChromeMath`, unit-tested). `CameraScreen` aligns that stack to whichever screen
   edge physical up currently appears at (`chromeStackEdgeFor`, and its `CueEdge.physicalTop` twin —
   `CueEdgeTest` pins the two to the same answer: top at 0, right at `ROTATION_90`, left at `ROTATION_270`,
   bottom at 180), so guidance never sits over the frame centre. The cue chip is one line of 1-3 words
   capped at 55% of the preview's short side, so a rotated chip can never become a wall of text.
5. **Capture EXIF follows the physical rotation.** `CameraController.setCaptureRotationDegrees` sets
   `ImageCapture.targetRotation` live (no rebind needed) from `CaptureRotation.surfaceRotationFor`, called
   by `CameraScreen`'s `LaunchedEffect(uiState.deviceRotationDegrees)`. The mapping is the identity on
   `deviceRotationDegrees` because `Surface.ROTATION_*` is defined counter-clockwise-positive, the same
   sense the quantizer reports in — note that Android's own `OrientationEventListener` snippet is
   clockwise-positive and swaps 90/270 if pasted verbatim. `Preview`'s `targetRotation` is deliberately
   left alone: the preview content must never rotate. `ReviewScreen`'s photo box sizes itself off the
   decoded image's aspect ratio (Coil `intrinsicSize`) so a landscape capture is letterboxed rather than
   squeezed into a portrait box.

### The two sign errors that shipped, and why they hid each other

`OverlayMapper` used `phi = 360 - theta` instead of `theta`. That is 180 degrees out at both `ROTATION_90`
and `ROTATION_270` (and coincidentally correct at 0 and 180, which is why portrait always looked fine):
every overlay point was point-reflected through the frame centre, and "move slightly right" drew an arrow
at the screen's **top** instead of its bottom — the photographed field bug.

The chrome angle was then *derived from that same inverted mapping*, with a compensating negation
(`atan2(-v.x, -v.y)`), so chrome came out correct — meaning a reviewer fixing only the obvious half would
have flipped chrome straight back to the original `-theta` bug ("Move slightly right" reading
bottom-to-top). Both halves are now pinned independently: `RotationTruthTableTest` derives each from
gravity, and `RotatedChromeRotationTest` (Robolectric + Compose) checks the rotation that actually reaches
the screen, by asserting that a left/right marker pair ends up top/bottom — a bounding box alone cannot
tell +90 from -90, which is exactly how a sign error ships.

Separately, `UprightRotation` used the rear-camera formula for both facings, which left the **front**
camera's analysis frame upside down in both landscape holds (`R0 - theta` vs `R0 + theta` differ by 180
degrees there — invisible to any check that only looks at the frame's aspect ratio). See `:vision`'s
README.

### The debug readout

With debug mode on, `DebugOverlay` shows one line:

```
Rot: device=<0/90/180/270> upright=<n> chrome=<angle> roll=<n>
```

`device` is `FrameAnalysis.deviceRotationDegrees`, `upright` is `FrameAnalysis.analysisRotationDegrees`
(the clockwise rotation `:vision` applied to the raw buffer), `chrome` is the clockwise Compose angle the
chrome is rotating by, and `roll` is `DeviceOrientation.rollDegrees` (residual tilt after re-referencing).
Read against the truth table above, for the rear camera:

| hold | expected line (level phone) |
|---|---|
| portrait | `Rot: device=0 upright=90 chrome=0 roll=0` |
| right edge up | `Rot: device=90 upright=0 chrome=90 roll=0` |
| upside down | `Rot: device=180 upright=270 chrome=-180 roll=0` |
| left edge up | `Rot: device=270 upright=180 chrome=-90 roll=0` |

`chrome` is reported in `(-180, 180]`, so 180 may read as `-180` and 270 reads as `-90`; those are the same
angles. `roll` should stay within a degree or two of 0 whenever the phone is level, in *every* hold, and go
positive when the horizon looks rotated clockwise on screen. Switching to the front camera changes only the
`upright` column (270 / 0 / 90 / 180 for the four holds).

**What still needs a physical device to confirm:** that the four holds read as the table above; that the
level indicator reads level and headroom/thirds advice makes sense for a person in frame in each; that
every chrome icon and the banner's text read upright; that a saved photo opens upright in the gallery; that
the score+banner stack hugs the right edge without crossing the frame centre; and whether 250 ms is the
right tween for the chrome counter-rotation.

## Camera/Activity lifecycle (`CameraViewModel`, `PipelineLifecycle`)

`CameraScreen` wires two pairs of hooks: `onScreenStarted()` / `onScreenStopped()` from a
`DisposableEffect(viewModel)` (composition entry/exit) and `onScreenResumed()` / `onScreenPaused()` from
`LifecycleEventEffect(ON_RESUME/ON_PAUSE)` (the Activity going to/from the background, which does *not* leave
composition). Both funnel into `PipelineLifecycle.resume()` / `pause()`, which track their own flag, so whichever
fires first starts or stops the vision pipeline, `ThermalPolicy` and the gyro (and resets the smoother on resume,
cancels a running self-timer on pause); the other is a no-op. `VisionPipeline.start()` after a `stop()` fully
re-arms itself (cached detections, `DetectorSchedule`, `SegmentationCadence`), so resuming never shows a stale
detection.

## Thermal/battery-saver performance tiers (`ThermalPolicy`, `PerformanceTier`)

`camera/ThermalPolicy` listens to `PowerManager.addThermalStatusListener` (API 29+) and battery saver
(`PowerManager.isPowerSaveMode` + `ACTION_POWER_SAVE_MODE_CHANGED`) and derives a
`com.compositioncoach.vision.PerformanceTier` (FULL / REDUCED / MINIMAL — see that enum's KDoc and
`:vision`'s README for exactly what each tier changes in the detector cadence). `CameraViewModel` collects
`AppContainer.thermalPolicy.tier`, forwards it to the pipeline
(`(frameSource as? VisionFeatureToggles)?.setPerformanceTier(tier)`) and surfaces it as
`CameraUiState.performanceTier` for the debug overlay. This is a separate signal from the user's own
`battery_saver` setting (which independently affects the analysis interval and forces the subject mask
off) — the two combine (via `CameraUiState.preferFastCapture` for capture mode, and inside `VisionPipeline`
itself for the analysis interval, both `maxOf`/"more conservative wins") rather than one overriding the
other. `ThermalPolicy.start()`/`stop()` are called from the same `resumePipeline()`/`pausePipeline()` as
the vision pipeline above.

## Capture pipeline (shutter press to saved file)

Field report: "photos are not as sharp as the Google stock app". The stock app's advantage is mostly HDR+:
a burst of underexposed RAW frames aligned and merged on the ISP before demosaicing, which no third-party
API exposes directly. What a third-party app *can* do is below, in the order it happens. Code:
`CameraController` (bind-time configuration), `CapturePipeline` (steps 2-5), `AutoLevelProcessor` (step 6),
orchestrated by `CameraViewModel.onShutterClick`.

**At bind time** (before any shutter press):

* **Extensions** (`capture_enhancement`, default AUTO): the lens is asked which of AUTO/HDR/NIGHT its vendor
  extensions library offers; the chosen one binds through `ExtensionsManager.getExtensionEnabledCameraSelector`,
  routing the still capture through the vendor's own multi-frame pipeline. If that session cannot also carry
  `ImageAnalysis` (`isImageAnalysisSupported`), Preview + ImageCapture bind under the extension and analysis
  is not bound: `CameraUiState.analysisPausedByEnhancement` is true and live coaching pauses until the user
  picks Off (or an analysis-capable mode). That trade is only made on an explicit HDR/Night choice: AUTO, the
  default, is offered only when its session keeps analysis (`CaptureQuality.offeredEnhancements`), so the
  default never opens the app with coaching off; when AUTO is not offered the default resolves to OFF and
  lucky imaging takes over. `CaptureSessionInfo.enhancementsPausingCoaching` lists the modes that would pause
  it, so a chip can say so before it is picked. The view model owns the rebind on a change (`OutcomeBinding`,
  see "Outcome pipelines"); a change during a capture is deferred until it finishes.
* **Camera2 request options** (no extension active, not conserving power, each only if advertised):
  NOISE_REDUCTION, EDGE, COLOR_CORRECTION_ABERRATION, HOT_PIXEL and DISTORTION_CORRECTION at HIGH_QUALITY,
  OIS on. `TONEMAP_MODE` is deliberately left alone (HIGH_QUALITY would replace the vendor's tuned tone
  curve). The same values go on Preview too, because CameraX merges every use case's interop options into the
  one repeating request anyway.
* **Full resolution, no re-encode:** JPEG quality 100; the capture stream size is compared with the largest
  4:3 JPEG size in the stream configuration map and the ViewPort crop with the whole stream. Logged at bind
  (`CameraController` tag, WARN if not full resolution) and carried into every capture's diagnostics.
* A `CaptureResultTap` (`Camera2Interop` session capture callback) records each still request's
  `TotalCaptureResult`, so diagnostics can report what the ISP actually did.

**On the shutter press:**

1. **Snapshot** the shutter-time `FrameAnalysis` (for the review score) and orientation (for auto-level):
   the frame's own `DeviceOrientation` if it is under 1 s old, else `MotionSensor`'s gravity fallback.
2. **Steady-shot gate** (always on; no setting): `MotionSensor` classifies gyro RMS angular speed over a
   150 ms window (STEADY < 0.08 rad/s, MOVING > 0.35, hysteresis 0.12 / 0.26). If MOVING, wait up to 450 ms
   for STEADY/SLIGHT_MOTION (`CameraUiState.isWaitingForSteady` is true meanwhile), then shoot regardless.
   **Night** instead holds for its 2 s of continuous steadiness (`CapturePipeline.awaitSteadyHold`: a MOVING
   reading restarts the count, the shutter ring fills with the progress, "Hold still" is the chip) and shoots
   anyway after 5 s; without a gyro it is a plain 2 s countdown.
3. **Capture**, per the outcome's plan (see "Outcome pipelines" below), one of:
   * **Lucky imaging** (`burst_sharpest`, effective only with no extension active, flash OFF, not
     conserving power; `CameraUiState.burstSharpestActive`): three in-memory `takePicture` requests queued
     back to back; each JPEG's centre half is decoded at 1:2 and scored by `SharpnessScorer` (variance of
     the Laplacian); the sharpest frame's *original* JPEG bytes are written to MediaStore with the EXIF
     orientation from `ImageInfo.rotationDegrees` plus, for the front camera, a display-space mirror
     (`ExifOrientations.tagFor`). The others are discarded. Any failure falls back to a single capture.
   * **Single capture** through CameraX's own MediaStore file path (extensions, flash, power saving, or
     burst off).
4. **Diagnostics** (`CaptureDiagnostics` on `ReviewEntry.diagnostics`): ISO, shutter, aperture and focal
   length from the tapped capture result, else from EXIF; noise-reduction/edge modes and OIS from the
   result; enhancement, capture mode, JPEG quality, steadiness at capture, wait time, zoom, EV index,
   burst scores and pick, configured size and full-resolution verdict, shutter-to-saved latency.
5. **Review**: the `ReviewEntry` is put in `ReviewStore` and the screen navigates, so the photo appears as
   soon as it is saved.
6. **Auto-level** (`auto_level`, default **on**, "fix what can be fixed silently"): if the shutter-time roll was reliable and 0.7-8 degrees, the saved JPEG is
   decoded, rotated by -roll about its centre, cropped to the largest same-aspect rectangle
   (`AutoLevelGeometry`), re-encoded at 100 and written over the same Uri on `Dispatchers.Default`/IO, EXIF
   orientation and capture tags preserved. The entry is then replaced with `autoLevelAppliedDegrees` set,
   `revision + 1`, `processing = false` and `originalPhotoUri` (a private copy of the pre-level JPEG in
   `cacheDir/originals`, never the gallery, written only when levelling actually ran; `OriginalCopyStore`).
   The review image keys its Coil memory and disk caches on `"$uri#$revision"`, shows "Levelling…" while
   `processing`, and offers Compare while `originalPhotoUri` is set. The copy is deleted when the entry leaves
   `ReviewStore` (Keep, Retake, a newer capture; `staleOriginalOf`), at once if the review ended while
   levelling ran, and purged on the next start after a process death (`PostCaptureLevelling`). Applies to
   extension captures too, since it is a purely geometric step. It crops the edges a little and resamples every
   pixel, which is why Compare is always there and the setting can be turned off.
7. **Log**: one INFO line per capture, tag `CaptureDiagnostics` (`adb logcat -s CaptureDiagnostics`), after
   step 6 when it runs:

```
capture size=4080x3072 configured=4080x3072 fullRes=true lens=BACK enhancement=OFF mode=MAXIMIZE_QUALITY+BURST3
jpegQ=100 iso=50 shutter=1/120 s f=1.68 focal=6.9mm nr=HIGH_QUALITY edge=HIGH_QUALITY ois=true steadiness=STEADY
waitedMs=0 zoom=1.00 ev=0 burst=[812.4,901.7,655.0] picked=1 autoLevel=-1.40 latencyMs=780
```

Zoom presets (`CameraUiState.zoomPresets`, `ZoomPresets.compute`): 0.6 or the lens minimum rounded up to 0.1
when the lens goes below 1x, then 1, 2 and (when `maxZoom >= 5`) 5; `onZoomPresetSelected` eases there over
~200 ms in log-zoom steps (CameraX itself does not animate `setZoomRatio`), and `zoomRatio` follows the
camera's own `ZoomState`, so pinch-zoom updates it too (the pinch goes through `onPinchZoom`, which also cancels
a preset ease still in flight). Exposure compensation (`onExposureCompensationChanged`)
is applied by the VM and resets to 0 on every rebind, as CameraX itself does.

Performance: nothing in the pipeline runs on the main thread except queuing `takePicture` (a CameraX
requirement); decoding/scoring run on `Dispatchers.Default`, file I/O on `Dispatchers.IO`, and analysis
frames are simply dropped (`STRATEGY_KEEP_ONLY_LATEST`) while the ISP is busy. Target: burst
shutter-to-saved under 1.2 s on a Pixel (`latencyMs` in the log line measures exactly that).

### Outcome pipelines (Wave 2)

`CapturePipeline.capture(outcome, request, stacking = frameSource as? FrameStackingSource)` captures an
`Outcome` with the pipeline its `OutcomeSpec` asks for, degraded to what the device and the current bind can
do. The steady gate (`awaitSteady`) and auto-level stay where they were (before and after the call). The
decision is `OutcomeCapturePlanner.plan(outcome, availableEnhancements, PipelineSupport)` (pure, tested):

| Spec asks for | Runs | Falls back to |
|---|---|---|
| enhancement preference list | first one the lens offers (OFF always) | missing *named* first choice (Night, HDR, Portrait) -> `degradedNote` "Night isn't available on this phone, using the best we can"; missing AUTO is not reported |
| SINGLE | `captureSingle` | - |
| BURST_SHARPEST | lucky imaging (no extension active) | SINGLE under an extension (the vendor merges frames itself) |
| FOCUS_STACK | `FocusStacker` (no extension, `LensControl.supportsFocusStack`; binds OFF rather than AUTO when the lens can stack) | SINGLE under HDR; BURST_SHARPEST without manual focus (noted) |
| MOTION_AVERAGE / _LIGHTEN / _MEDIAN | `MotionCapture` over the live stream | BURST_SHARPEST without live frames (noted) |

The enhancement is chosen at **bind** time, so `OutcomeBinding` (in the view model) plans when the outcome changes
and on every bind result: `controller.setEnhancementOverride(override)` and
`controller.setAnalysisResolutionHint(MOTION_ANALYSIS_RESOLUTION for Motion outcomes, else null)`, then a rebind
when the next bind would request a different enhancement than the current one or the analysis size changed.
The screen calls `CameraViewModel.configureController` on its fresh controller before each bind, so returning
from the review never binds twice. Precedence: a real outcome change makes the plan's enhancement the override
(and persists it as `capture_enhancement`); the user's own pick on the pill or in Settings becomes the override
until the next outcome change; on first launch there is none and the persisted preference stands.
`CaptureSessionInfo.requestedEnhancement` keeps reporting the persisted `capture_enhancement`;
`outcomeEnhancement` reports the override. The plan's degradation becomes `outcomeDegraded` and
`outcomeDegradedNote` (`DegradedNote.of(plan)`, the translatable twin of `CapturePlan.degradedNote`, which stays in
the diagnostics log).

* **Motion** (`MotionCapture`): starts stacking (gyro-aligned, see `:vision`'s README), takes a normal still
  through CameraX in parallel (the first frame, so the experiment never costs the shot), holds for
  `holdSeconds` while `CapturePipeline.holdProgress` (0..1, the larger of time and frame progress) drives the
  ring, and ends early as `MotionStatus.TOO_SHAKY` once `MotionSensor` has said MOVING for more than 25 % of
  the hold. Too shaky: the first frame is kept in the gallery, there is no review, and the camera says "Too shaky,
  try again". Otherwise the review shows the stack, with the first frame as its Compare original. The stack is turned upright (lossless quarter turns) and saved with
  `CaptureRepository.exportProcessed` (`_silk`, `_trails`, `_clear`); `ShotResult.uri` is the stack and
  `companionUri` the still. Too shaky / too few frames: `uri` is the still, `motionStatus` says why and
  `degradedNote` carries "Too shaky, try again". Resolution is the analysis stream's (1280x960 with the hint).
* **Focus stack** (`FocusStacker`): three in-memory captures at 0 (infinity), 1/3 and 2/3 of
  `LENS_INFO_MINIMUM_FOCUS_DISTANCE` diopters (`Camera2CameraControl`: `CONTROL_AF_MODE_OFF` +
  `LENS_FOCUS_DISTANCE`, 180 ms settle each, AF restored in `finally`), the infinity frame saved untouched as
  the first frame, then `FocusMerge`: Laplacian energy at three window sizes on 8x-downscaled guides,
  `e^4`-normalized and smoothed weights, bilinear upsampling, per-pixel blend at full resolution in 256-row
  strips (`BitmapRegionDecoder`; peak ~ one full-size output bitmap), gyro shift between exposures mapped into
  the stored buffer. Saved in stored orientation with the first frame's EXIF orientation and capture facts
  (`_stack`). Target <= 2.5 s for the three captures; the merge and encode add ~1-2 s on a 12 MP sensor
  (estimate) and are logged (`Focus stack: capture N ms, total M ms`).
* **Shutter sound**: the outcome capture calls `ShutterSound.playIfMandatory()` first, which plays
  `MediaActionSound.SHUTTER_CLICK` only when `ShutterSoundPolicy.isMandatory` (API 33+
  `MediaActionSound.mustPlayShutterSound()`; else the legacy `Camera.CameraInfo.canDisableShutterSound`,
  the `ro.camera.sound.forced` property via `getprop`, and a JP/KR network or SIM country). Cached per process.
  `shutter_sound = ALWAYS` adds the click everywhere else, through the capture port (never twice).
* **Diagnostics** gain `pipeline`, `framesStacked`, `degradedNote` (appended to the log line only when set:
  `... latencyMs=780 pipeline=MOTION_AVERAGE stacked=31`).

**Review-side processing** (`CaptureRepository`, `BackgroundSeparator`):

* `decodeForAnalysis(uri, maxLongEdgePx = 1024)`: EXIF-aware (rotation and selfie mirror), power-of-two
  subsampled at decode then scaled, for `StillImageAnalyzer`. `decodeFull(uri)`: same at full size.
* `exportCrop(uri, rect, suffix = "_crop")`: full decode, crop by the upright normalized rect, JPEG 100, EXIF
  capture facts copied with orientation NORMAL and the new size, `<original>_crop.jpg` in the same album.
  `exportProcessed(bitmap, nameSuffix)`: `CC_<timestamp><suffix>.jpg`, JPEG 100, orientation NORMAL.
* `BackgroundSeparator.apply(bitmap, mask, strength)` (fallback for phones without the vendor Bokeh
  extension, for Portrait and Selfie captures; offered only when `isApplicable(mask)`: coverage 8-70 %): the
  result is a private preview (`OriginalCopyStore.savePreview`, deleted when the review ends); "Save as copy"
  writes it with `exportProcessed(bitmap, "_portrait")`. Feathered 32x32 mask upscaled
  bilinearly, background darkened x0.75 and box-blurred twice (radius 1 % of the long edge) at half
  resolution, recomposited at full resolution; subject pixels are copied unchanged. Pure core `applyArgb`.

## Photo saving, thumbnail and gallery-shortcut hooks

* After a successful capture, `CameraViewModel` records the saved `Uri` in
  `CameraUiState.lastPhotoUri` (process-lifetime; survives navigation but not process death) so the
  screen can show a gallery-shortcut thumbnail button. On a fresh process start, the same field is
  populated from `CaptureRepository.latestPhotoUri()` — a MediaStore query for the newest image under
  `Pictures/CompositionCoach` — so the button has something to show without waiting for the next capture.
* The 44 dp thumbnail loads through Coil, sized to the view. `CaptureRepository.loadThumbnail` (provider-side
  `ContentResolver.loadThumbnail` on API 29+, a bounds-then-`inSampleSize` decode below) is there for a
  pre-scaled thumbnail if Coil's ever shows up in a profile.

## Image-quality controls (`EnhancementPill`, shoot at the peak, zoom, exposure, diagnostics)

Same restraint as the coaching layer: every control is hidden when the camera cannot back it, live text is
one to three words, and every piece of arithmetic lives in a pure object with a JVM test.

* **Enhancement pill** (`EnhancementPill`, logic in `EnhancementCycle`). A compact "Auto" / "HDR" /
  "Night" / "Off" pill centred in the top strip, shown only when `CameraUiState.availableEnhancements`
  holds something beyond OFF. Tap = next option in enum order (Off is always in the cycle so a plain capture
  is one tap away; a value the new lens doesn't offer restarts at the first real enhancement), 200 ms
  crossfade, light tick. Long-press = a small menu of every option with a check on the current one, and a muted
  "pauses coaching" note beside each option in `CaptureSessionInfo.enhancementsPausingCoaching`. Calls
  `CameraViewModel.onEnhancementSelected`, which persists `capture_enhancement` and owns the rebind (the
  screen's bind `DisposableEffect` deliberately does not key on the enhancement). The pill shows
  `CameraUiState.enhancement`, i.e. what is running (set optimistically on a pick, then from the bind). Counter-rotates in `RotatedChrome` like the icons (the
  long-press menu is a popup and does not rotate).
* **Coaching paused chip.** `CoachingLayer(pausedText = …)` replaces the cue chip at the physical top with
  `EnhancementCycle.pausedText(...)` ("Coaching paused in Night") and stands every cue and icon down. Wired
  through `CameraChromeModel.pausedText` = `EnhancementCycle.pausedTextFor(uiState.enhancement,
  uiState.analysisPausedByEnhancement)` (never "paused in Off" during the rebind after picking Off).
* **Steady hint** (`SteadyHint`). While the steady-shot gate is holding the shutter
  (`CameraUiState.isWaitingForSteady`), or while `steadiness == MOVING` *and* the shutter is engaged (pressed,
  armed, capturing) or the framing is shoot-ready, the chip slot above the shutter reads "Hold still"
  instead of "SHOOT" (same `CueChip`, polite live region, "Hold the phone still"). Never during ordinary
  framing. Once shown it stays at least 500 ms (`SteadyHint.MIN_VISIBLE_MS`, `rememberHoldStillVisible`), so a
  gyro reading hovering at the threshold cannot make it blink. The readiness arc drops to 70 % while MOVING (`SteadyHint.arcAlpha`; awaiting-subject's 30 %
  wins), so the user learns that movement holds the shot back.
* **Shoot at the peak** (`ShootAtPeakController`, pure; `rememberShootAtPeakState`, the Compose glue).
  Hold the shutter >= 350 ms: a double light tick, the white disc shrinks to 50 dp and a thin 1.5 dp
  countdown ring drains inside the arc over 4 s, and a tiny "Auto" sits under the shutter. The app calls
  `onShutterClick` (with the medium tick) on the first frame where `isShootReady && steadiness != MOVING`
  (`SteadyHint.peakReached`), or at 4 s regardless, then disarms. Release before 350 ms = ordinary capture;
  release while armed = cancel. With **Auto shoot when ready** on, every tap arms immediately and stays
  armed after the finger lifts; a second tap cancels. TalkBack: "Take photo" click, "Shoot when ready"
  long-press (arms as a latch, since holding isn't possible). The shutter's touch target is now the fixed
  72 dp ring rather than the 58 dp disc, so the shrinking disc can never slide out from under a held
  finger. Reduced motion: the countdown ring stays full instead of draining.
* **Zoom presets** (`ZoomPresetRow`, logic in `ZoomPresets`). Round 36 dp chips (48 dp targets) above the
  mode strip from `CameraUiState.zoomPresets`, hidden with fewer than two. The lit chip is the largest
  preset at or below the live ratio and reads that ratio ("1.4×" while pinched between 1 and 2); the others
  read their bare preset ("0.6", "2", "5"). Whenever the camera sits on a preset,
  `CameraUiState.selectedZoomPreset` (within 5%) decides the lit chip (`ZoomPresets.litIndex`). Tap =
  `onZoomPresetSelected` (eases there). The displayed ratio
  (`ZoomDisplayState`) follows a pinch immediately and is overwritten by any change to
  `CameraUiState.zoomRatio`; the "1.0×" pinch readout is unchanged.
* **Exposure slider** (`ExposureSlider`, logic in `ExposureText`). After each tap-to-focus, a vertical
  128 dp track with a sun thumb and "+0.7" EV text appears beside the focus ring (right of it, or left
  near the right edge) for 2.5 s, held open while dragging. Drag up = brighter; each new index goes only to
  `onExposureCompensationChanged` (the view model applies it to the bound camera). A rebind resets
  `exposureIndex` to 0 and the thumb, including a drag in progress, follows it. Hidden when
  `exposureRange` is `0..0`. EV per step comes from the bound camera's
  `ExposureState.exposureCompensationStep` (read off `CameraBindResult.Success.camera` at bind), falling
  back to `ExposureText.fallbackStepEv` (±2 EV conventions) if unreadable. TalkBack gets a range with a
  set-progress action and "Exposure plus 0.7".
* **Review diagnostics** (`DiagnosticsPanel`, logic in `DiagnosticsFormatter`). Under the strengths and
  improvements, one `OnScrimMuted` bodySmall line — "12 MP · 1/120 s · ISO 160 · Night · steady" — from
  `ReviewEntry.diagnostics`, omitting whatever is null and hidden entirely when nothing was reported. Tap
  (48 dp target) expands a two-column table: resolution, shutter, ISO, aperture, focal length, lens,
  capture size and full-resolution verdict, OIS, enhancement, capture mode, burst ("Kept 2 of 3 · 812, 902,
  655"), JPEG quality, noise reduction and sharpening (edge) modes, steadiness and wait, zoom, exposure
  compensation (raw steps: the EV size per step is not in the diagnostics), auto-level angle, shutter latency.
  Rows the capture did not report are left out.
* **Compare** (`CompareChip`). Shown over the review photo while `ReviewEntry.originalPhotoUri` is set (auto-level
  ran, or a stack kept its first frame); holding it shows that photo (TalkBack: click toggles).
* **Settings > More** opens with `CaptureSettingsRows`, all persisted: **Enhancement** (segmented over Off +
  what the bound lens offers, from the camera back-stack entry's view model; writes through
  `onEnhancementSelected`, which makes it the override until the next outcome change), **Sharpest of burst** (`burst_sharpest`), **Auto-level horizon** (`auto_level`),
  **Auto shoot when ready** (`auto_shoot`, read by `CameraScreen` from `CameraUiState.settings`).
  The Enhancement row shows the *persisted* choice (`EnhancementRowModel`): an explicit HDR/Night the current
  lens lacks stays selected, greyed, with "Not available on this camera" (the pill meanwhile shows what is
  running); the AUTO default on a lens without AUTO shows Off, which is what runs; a lens with nothing beyond
  Off gets a disabled row.
* **Shutter long-press = shoot at the peak.** `docs/APP_UX.md` still says long-press on the shutter is
  reserved; it now means shoot-at-the-peak (above), and Settings > More > **Auto shoot when ready** makes an
  ordinary tap do the same. This README is the current word until the spec is revised.
* **Testing the wiring.** What `CameraScreen` draws from `CameraUiState` lives in `CameraPreviewChrome` and
  `CameraBottomChrome` (`CameraChrome.kt`, derived values in `CameraChromeModel`), so
  `CameraChromeSmokeTest` composes both under Robolectric with a fake "Night running, coaching paused,
  waiting for steady, 0.6/1/2 presets" state at rotation 0 and 90.

## Settings keys (DataStore Preferences, `SettingsRepository`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `guidance_enabled` | bool | `true` | Master switch; off = preview only, coach never runs |
| `show_score` | bool | `true` | Stored "show the number" preference. What the screen does is `CoachSettings.effectiveShowScore(mode)` / `CameraUiState.showScoreNumber`: never in Easy, this value in Coach, so it survives switching |
| `show_thirds_grid` | bool | `false` | Rule-of-thirds overlay |
| `guidance_level` | string (`GuidanceLevel` name) | `BALANCED` | MINIMAL / BALANCED / COACH |
| `pose_detection_enabled` | bool | `true` | Forwarded to `frameSource.setPoseDetectionEnabled` |
| `battery_saver` | bool | `false` | 200ms analysis interval instead of 100ms |
| `debug_mode` | bool | `false` | Shows `DebugOverlay` + `DebugGeometryOverlay` + the DEBUG chip |
| `scene_intent` | string (`SceneIntent` name) | `AUTO` | **Derived**, no longer user-set: always written as `outcome.spec.sceneIntent`; read only to migrate a Wave 1 install to the matching `outcome` (`Outcome.forSceneIntent`) |
| `detect_objects` | bool | `true` | Forwarded to `(frameSource as? VisionFeatureToggles)?.setObjectDetectionEnabled` |
| `subject_mask` | bool | `true` | User's own preference; see `CoachSettings.effectiveSubjectMaskEnabled` below for what's actually applied |
| `onboarding_seen` | bool | `false` | Whether the first-launch `OnboardingCard` has been dismissed |
| `capture_enhancement` | string (`CaptureEnhancement` name) | `AUTO` | OFF / AUTO / HDR / NIGHT / BOKEH: CameraX Extensions session for captures; falls back to OFF for a bind whose lens does not offer it. Selecting an outcome sets it to the first of the outcome's preferences the lens offers |
| `burst_sharpest` | bool | `true` | Lucky imaging (three frames, keep the sharpest); only runs when no extension is active, the flash is off, the device is not conserving power and the outcome's pipeline is not SINGLE |
| `auto_level` | bool | `true` | Straighten 0.7-8 degree horizon tilts after saving (Easy default: "fix what can be fixed silently"; Compare shows the original) |
| `auto_shoot` | bool | `true` | "Auto shoot when ready": every shutter tap arms shoot-at-the-peak, like a long-press (Easy default) |
| `experience_mode` | string (`ExperienceMode` name) | `EASY` | EASY / COACH (`docs/PRODUCT.md`, "Two experiences") |
| `pro_controls` | bool | `false` | Reveals exposure, zoom presets, enhancement chips, grid, guidance level, detection toggles, developer mode |
| `outcome` | string (`Outcome` name) | `ANYTHING` | The photograph the user wants; drives scene intent, enhancement, capture pipeline, hold instruction, front lens (Selfie), group mode |
| `review_card_mode` | string (`ReviewCardMode` name) | `ALWAYS` | ALWAYS / ONLY_WHEN_USEFUL (a Try-next-time or a crop) |
| `haptic_guidance` | bool | `false` | "Guide by vibration" |
| `voice_guidance` | bool | `false` | "Guide by voice" |
| `timer_seconds` | int | `0` | Self-timer: 0 (off), 3 or 10; other values snap to the nearest (`TimerSecondsCodec`) |
| `adaptive_weights` | string (`AdaptiveWeights.serialize()`) | `""` (NEUTRAL) | The taste model; written back normalised. "Reset coaching preferences" sets NEUTRAL and clears the photo journal |
| `shutter_sound` | string (`ShutterSoundMode` name) | `AUTO` | AUTO: only where the device reports the shutter sound mandatory (`CameraInfo.mustPlayShutterSound()`); ALWAYS: every capture. No "never" |

An unrecognized or missing `guidance_level` value falls back to `BALANCED`, rather than crashing
(`GuidanceLevelCodec`, unit-tested in `CoachSettingsTest`) — this is the intended
way to evolve the schema, not a bug to "fix" by renaming keys in place. `capture_enhancement` falls back to
`AUTO` the same way (`CaptureEnhancementCodec`); AUTO is "AUTO when the lens offers it, else OFF" at bind time.
The Wave 2 enums fall back to their defaults the same way (`OutcomeCodec`, `EnumCodec`), and a corrupt
`adaptive_weights` string degrades to NEUTRAL. Defaults apply to keys that were never written: a Wave 1 install
that had saved any setting keeps its explicit `auto_level`/`auto_shoot`/`show_score` values. Every key's
read/write round trip is unit-tested on the JVM through the pure `Preferences.toCoachSettings()` /
`MutablePreferences.writeCoachSettings()` pair that `SettingsRepository` itself uses (`CaptureSettingsTest`,
`WaveTwoSettingsTest`).

## Detection settings (`VisionFeatureToggles`)

Settings > More (Pro only), applied by `CameraViewModel` (`FrameAnalysisSource.applySettings`) via `(frameSource as?
VisionFeatureToggles)` (a safe cast — the interface is `:vision`'s, not part of the `FrameAnalysisSource`
contract `:app` otherwise depends on) every time settings change, same as the existing
`pose_detection_enabled` wiring:

* **"Detect objects"** (`detect_objects`, default on) → `setObjectDetectionEnabled`. Subtitle: "Finds
  plates, drinks, products and other subjects."
* **"Subject mask"** (`subject_mask`, default on) → `setSegmentationEnabled`, but the toggle actually
  applied is `CoachSettings.effectiveSubjectMaskEnabled` (`subjectMaskEnabled && !batterySaver`) —
  segmentation is the most expensive detector `:vision` runs (see its README), so **battery saver forces
  it off** regardless of the user's own preference. The row shows the effective (forced) value and is
  disabled while battery saver is on; its subtitle switches from "…; uses more battery" to "…; off while
  battery saver is on" (`CoachSettings.subjectMaskSubtitle()`) so the override is never silent.

## Onboarding

A single dismissible `OnboardingCard` ("Point · Follow the cue · Shoot when green" + a "Got it"
button) shows at the bottom of the camera screen whenever `onboarding_seen` is
false — which in practice means once, right after the camera permission is granted, since
`CameraScreen` only ever composes past `CameraPermissionGate`. Dismissing it persists `onboarding_seen`
via `CameraViewModel.onOnboardingDismissed()`; there is no other way to bring it back short of clearing
app data.

## Debug mode guide

Enable **Settings → Pro controls**, then **More → Developer mode**. Two extra layers appear on the camera screen:

* `DebugGeometryOverlay` — draws, in this order (mask first so it sits *behind* everything else):
  1. the subject mask (`FrameAnalysis.subjectMask`, when present) as a faint green heat layer, one cell
     per mask grid cell, alpha proportional to that cell's probability and capped at 0.35 total;
  2. every raw `DetectedObject` from `FrameAnalysis.objects` as a dashed box labelled `category
     confidence%` — this can be a superset of the boxes that actually became a subject, since
     `:vision`'s `ObjectMapper` filters further (frame-coverage / face-IoU) before anything reaches
     `DetectedSubject`;
  3. every `DetectedSubject`'s box (primary subject in green, others in amber), plus face eye/nose points
     and in-frame pose landmarks.

  This overlay (plus `CoachingOverlay`'s restrained region-highlight outline, see the screen map above)
  is the *only* place bounding boxes and the mask are drawn; outside debug mode, boxes never appear.
* `DebugOverlay` — a collapsible, scrollable panel (tap the "DEBUG ▾/▸" header) listing: scene type +
  confidence + the declared shooting mode (`Intent:`), raw vs. smoothed score, engine time, FPS, last
  analysis latency and sampling interval, the one-line rotation readout
  (`Rot: device=... upright=... chrome=... roll=...` — the whole rotation chain in one place; see
  "Device rotation (Pixel style)" above for what it must read in each of the four holds),
  every `CompositionMetric` (category/score/confidence/severity/applicable), every recommendation id
  with priority/confidence/direction, per-detector timings (`FrameAnalysis.detectorTimings` — a generic
  map, so `:vision`'s `"objects"`/`"segmentation"`/`"mask_age"` entries show up for free; `"mask_age"` is
  labelled "N frames" rather than "Nms" since it counts accepted frames since the mask last refreshed,
  not wall-clock time, see `:vision`'s README), subject count, and — when the engine populates it — the
  optimizer's current score, improvement and candidate framings.
* A small "DEBUG" chip appears top-left as a reminder the mode is on even if the panel is collapsed.

## Crash safety

* `CompositionCoachApp.onCreate()` installs a `Thread.setDefaultUncaughtExceptionHandler` that logs the
  full stack trace under the `CompositionCoachApp` tag and then delegates to whatever handler was
  previously installed (the platform default, normally) — it does not swallow the crash or change
  whether the process terminates, it only guarantees the crash is visible in logcat first.
* `CameraViewModel.observeFrames()` wraps each frame's `coach.process(...)` call in a `try`/`catch`: a
  detector/engine exception on one frame logs and falls back to the previous composition rather than
  terminating the flow — coaching keeps running on the next frame. A `CoroutineExceptionHandler`
  (`frameProcessingExceptionHandler`) is layered on top of that as a last-resort net for anything that
  still escapes the per-frame `try`/`catch` (e.g. from the FPS tracking in `onEach`).
* `CameraController.bind()` already catches every `Throwable` (including `IllegalStateException` from
  `ProcessCameraProvider.bindToLifecycle`) and returns `CameraBindResult.Failure`; `CameraViewModel`
  turns that into `CameraUiState.cameraError`, which `CameraScreen` renders as `CameraErrorOverlay` with
  a **Retry** button that clears the error and re-runs the bind `DisposableEffect`.

## Accessibility

* Every `IconButton`/clickable exposes a `contentDescription` (flash mode, switch camera, settings, back,
  gallery, shutter, share, keep, the "More" disclosure, the quiet-state check mark). Small visible targets
  that aren't `IconButton`s — the secondary advice chips — use
  `Modifier.minimumInteractiveComponentSize()` so their tap target is still ≥ 48dp; settings rows are 56dp
  tall with 48dp controls.
* `ScoreChip` collapses to one `clearAndSetSemantics { contentDescription = "Composition score 82" }` and
  is a polite live region, so TalkBack reads one clear sentence and hears a meaningful change unprompted.
* `CueChip` is the coaching layer's live region: `liveRegion = LiveRegionMode.Polite` plus a spoken
  description ("Move slightly right"), not the abbreviated words on screen ("Slightly right"). It is the
  *only* element carrying the cue's meaning — chevrons, corner brackets, the bubble level and the target
  ring / region highlight are all decorative (`clearAndSetSemantics {}`, no semantics at all), exactly as
  `docs/COACHING_UI.md` requires.
* Text over the live camera preview relies on the same `Scrim` token (black, 40% alpha) for contrast:
  white on that scrim over a mid-grey frame clears 4.5:1 comfortably, and the two accents are used for
  glyphs and rings rather than for body text.
* Reduced motion is respected: at `Settings.Global.ANIMATOR_DURATION_SCALE == 0` the chevron breathe and
  bracket ease stop and every transition becomes an instant cut (`rememberAnimationsEnabled` /
  `motionDuration`).

## Design tokens (`ui/theme/`)

One visual language, defined once and used everywhere instead of ad-hoc `Color(0xFF...)` literals in
screen code (the diagnostic-only `DebugGeometryOverlay`/`DebugOverlay`/`CrashReportScreen` are
deliberately exempt — they're meant to look like instrumentation, not the product):

* **`Accent.Ready`** (`#34D399`, green) and **`Accent.Warn`** (`#FFC857`, amber) are the only two
  signal colours over the live preview — white/amber/green, nothing else. **`Accent.Action`**
  (`#E9A17E`, the brand's Apricot, with `Accent.OnAction` Ink text) backs
  `MaterialTheme.colorScheme.primary` for main actions only; it never stands in for a status colour.
  Surfaces are `Background` `#15171B`, `Surface` `#24282E`, text `OnSurface` `#F2F3F4` and
  `OnSurfaceMuted` `#B7BDC4` (the app colour system in docs/BRAND.md).
* **`Scrim`** (black, 40% alpha — within the 35-45% range) is the one translucent-panel style used behind
  the score chip, cue chip, secondary advice chips, top bar, onboarding card, review mode chip and zoom
  chip; **`ScrimStrong`** (85%) is for full-screen states that must read over any photo (camera error).
  **`OnScrim`**/**`OnScrimMuted`** are white / 70%-white text over those scrims.
* Type scale: the score chip is one 32sp numeral with tabular figures (`fontFeatureSettings = "tnum"`, via
  `TextStyle`, not a bare `Text(fontSize=...)` call — Compose's `Text` has no such parameter) so a changing
  digit count never shifts the chip's width; the cue chip uses `titleMedium`; secondary lines use
  `bodySmall` at `OnScrimMuted`. See `ui/theme/Type.kt`.
* Icons are Material Symbols **outlined** (`Icons.Outlined.*` from `material-icons-extended`) everywhere in
  the product surfaces — filled variants are not used.
* Motion (`ui/theme/Type.kt`'s `Motion` object): state changes ease out over `Motion.STATE_CHANGE_MS`
  (220ms, inside the 180-250ms range), presses take `Motion.PRESS_MS` (120ms), crossfades take
  `Motion.CROSSFADE_MS` (200ms). No bounces/springs anywhere in the redesigned surfaces.

## Gallery thumbnail (`BottomControlBar`)

The thumbnail shows `CameraUiState.lastPhotoUri` (set by every capture, restored from
`CaptureRepository.latestPhotoUri()` on a fresh process start), falling back to the last photo the review store
held (`AppNavGraph`). Its long-press menu offers "Your eye" and "Use as guide" (the last reviewed photo's subject
outline as the ghost).

## Release build

* `release` build type: `isMinifyEnabled = true`, `isShrinkResources = true`, R8 rules in
  `app/proguard-rules.pro` (CameraX, ML Kit, DataStore, and a `-keepclassmembers enum` rule for
  `:composition`'s model enums — `GuidanceLevelCodec`/`SceneIntentCodec` round-trip them through
  `Enum.valueOf(Class, name)`, the one place in this app that isn't a plain direct call R8 can already
  see through). No `abiFilters` on `release` (the AAB's own per-ABI splits handle that); `debug` keeps
  its `arm64-v8a`/`armeabi-v7a` filter.
* `versionCode`/`versionName` are derived in `app/build.gradle.kts` (git commit count / `"0.9.<code>"`
  by default, both overridable via env vars) and shown in **Settings → About**
  (`BuildConfig.VERSION_NAME`/`VERSION_CODE`).
* `verifyNoInternetPermission` (wired into `check`) fails the build if `android.permission.INTERNET`
  ever reappears in the merged manifest — it's already there transitively via ML Kit's object-detection
  artifact and is explicitly stripped with `tools:node="remove"` in `AndroidManifest.xml`.
* See [docs/RELEASE.md](../docs/RELEASE.md) for signing key setup and the CI release job, and
  [docs/PRIVACY_POLICY.md](../docs/PRIVACY_POLICY.md) / [docs/PLAY_LISTING.md](../docs/PLAY_LISTING.md)
  for the Play Store policy/listing material this module's `PrivacyScreen` and About section reflect.

## Tests

All tests run on the JVM (`./gradlew :app:testDebugUnitTest`): plain JUnit for the pure logic (`OverlayMapperTest`,
`RotationTruthTableTest`, `GuidanceFormatterTest`, `ReadinessArcTest`, `CueEdgeTest`, `HorizonRollTest`,
`CameraUiStateTest`, `CameraUiStateWave2Test`, `CameraChromeModelTest`, `CoachSettingsTest`, `CaptureSettingsTest`,
`WaveTwoSettingsTest`, `ShootAtPeakControllerTest`, `DiagnosticsFormatterTest`, `ImageQualityControlsLogicTest`,
`CaptureUiWiringLogicTest`, `EnhancementRowModelTest`, `OriginalCopyStoreTest`, `OutcomePlannerTest`,
`OutcomeCaptureTest`, `FeedbackTrackerTest`, `PhotoJournalTest`, `YourEyeCalculatorTest`, the capture maths
`SharpnessScorerTest`, `AutoLevelGeometryTest`, `SteadinessClassifierTest`, `ZoomPresetsTest`, `CaptureQualityTest`,
`CaptureDiagnosticsTest`, `FocusMergeTest`, `BackgroundSeparatorTest`, ...) and Robolectric where Android types or
Compose are involved:

* `AppLaunchTest`: the real `MainActivity` survives launch with and without the camera permission, with the real
  (failing under Robolectric) CameraX and ML Kit.
* `EndToEndSmokeTest`: the real activity, navigation, `SettingsRepository`, `CameraViewModel`, composition engine
  and chrome, with only the vision pipeline (a fake frame source streaming a face) and the CameraX bind (a fake
  bind result) replaced. Seeded to Easy: the outcome strip and shutter compose and no score number does; switched
  to Coach + Pro while running: the score chip and zoom presets appear.
* `CameraChromeSmokeTest`, `CoachingLayerSmokeTest`, `RotatedChromeRotationTest`: the chrome and coaching layer
  composed from hand-built states (rotation, paused coaching, Pro on/off).
* `ReviewFlowCoordinatorTest`, `ReviewOriginalsTest`, `OutcomeBindingRulesTest` (bind targets, degraded notes,
  hold chips), `YourEyeMapperTest`, `ChipStringsResourceTest` (the English chip table equals `strings.xml`).

## Known limitations

* **Everything camera- and ML-specific is unverified until it runs on a phone**: CameraX binding and extensions,
  the capture pipelines (burst, Motion stack, focus stack, Night hold), ML Kit detection (live and on the still),
  heads-up vibration and voice, the shutter-sound policy, `ThermalPolicy`. The JVM tests cover the decisions and
  the maths around them, not the hardware. The device checklist is in the top-level README.
* **Legacy storage permission (API 26-28):** `CameraScreen` requests `WRITE_EXTERNAL_STORAGE` lazily, right before
  the first capture, only on API ≤ 28. API 29+ never needs it (scoped storage via `MediaStore` + `RELATIVE_PATH`).
* **Front camera capture** sets `ImageCapture.Metadata.isReversedHorizontal = true` (single capture) or writes a
  display-space mirrored EXIF orientation (lucky imaging) so the saved JPEG matches the mirrored preview; the
  still analysis therefore decodes an already mirrored selfie (`alreadyMirrored = true`).
* **Device rotation:** a capture in the brief window before the first `FrameAnalysis` (fresh start, phone already
  sideways) uses the gravity fallback's rotation, which may still be 0 for that one shot.
* **Exposure** in the review's diagnostics is in raw compensation steps (`CaptureDiagnostics` has no EV-per-step).
* **Retake on a stack** deletes the stacked photo; its first frame stays in the gallery as an ordinary photo.
* **Portrait copies** from the review carry no EXIF capture facts (the preview is re-encoded from pixels).
* **Settings from pre-integration builds:** test builds that stored Easy/Coach, Pro or the heads-up guides in the
  short-lived `ui_preferences` DataStore start from the defaults again (Easy, Pro off, guides off).
* **Needs a device to tune:** `CAPTURE_MODE_MAXIMIZE_QUALITY` latency, the steady-shot thresholds against real
  hand shake, the exposure range/step a real sensor reports, and whether the highest capture resolution makes
  processing noticeably slow on low-end phones (the `preferFastCapture` / `PerformanceTier` ladder is in place).
