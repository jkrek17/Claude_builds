# Composition Coach — `:app`

The camera-facing Compose app. This module owns the UI, CameraX binding, capture/storage, settings
persistence and navigation. It talks to `:composition` (`CompositionCoach`) and `:vision`
(`VisionPipelineFactory`) only through their public contracts — nothing under `composition/` or
`vision/` was touched.

## Screen map

```
CameraPermissionGate
  └── (granted) AppNavGraph  [navigation-compose, routes below]
        ├── "camera"   CameraScreen     (start destination)
        ├── "review"   ReviewScreen     (pushed on capture)
        ├── "settings" SettingsScreen   (pushed from the camera screen's gear icon)
        └── "privacy"  PrivacyScreen    (pushed from Settings → About → Privacy policy)
```

* **camera** — `CameraScreen` + `CameraViewModel`. Pixel-style **4:3 layout**: the `PreviewView` and
  *every* overlay that does normalized-to-pixel geometry math (`ThirdsGridOverlay`, `CompositionOverlay`,
  `DebugGeometryOverlay`, `FocusRingOverlay`, `CameraTopBar`, `ScoreBadge`, `GuidanceBanner`,
  `EmptySceneHint`, `OnboardingCard`, `DebugOverlay`) live inside one shared `Modifier.aspectRatio(3f/4f)`
  `Box`, full width, anchored below the status bar — that shared box, plus `PreviewView` staying
  `FILL_CENTER`, is what keeps `OverlayMapper`'s plain normalized-to-pixel multiply correct (see its class
  doc, and "Device rotation (Pixel style)" below for the rotation step that now precedes that multiply);
  the rest of the screen is plain black, holding `ZoomChip` and `BottomControlBar`.
  `CompositionOverlay` draws the target ring / horizon level / directional arrow — the arrow anchors to
  `DetectedSubject.anchorPoint` (already the box centre for an `OBJECT`-kind primary subject), points the
  way the *camera* should move (`Direction.RIGHT` → arrow points right, matching `ReframeVector`'s "dx>0 =
  pan right" convention), pulses a gentle 4dp/900ms translation, and is hidden together with the target
  ring whenever there's nothing to correct (`Direction.NONE` or shoot-ready); the horizon's level indicator
  snaps to `Accent.Ready` green and fades out 1.5s after becoming level; rotation advice shows a curved
  arrow around the level indicator instead — plus a fading, restrained 1dp/45%-white rounded outline of the
  headline recommendation's `region`, when it has one and framing isn't shoot-ready yet.
  `DebugGeometryOverlay` + `DebugOverlay` (debug mode only — see "Debug mode guide" below). `ScoreBadge`
  and `GuidanceBanner` (both fade to 30% for ~1s right after a capture, see
  `CameraUiState.postCaptureFadeActive` below); the badge uses tabular-figure numerals and a distinct
  shoot-ready state ("SHOOT" in `Accent.Ready`, the number smaller beneath); the banner shows a
  `DirectionIcon` (a real Material arrow, not a text glyph) instead of `GuidanceFormatter.glyphFor`'s
  Unicode character, reserves a fixed height for its headline so a longer instruction never resizes it, is
  capped to at most 3 lines total (headline, COACH-only reason, one secondary line — see "Device rotation
  (Pixel style)" below), and never exceeds 85% of the screen's width in portrait (60% of the preview's
  height in landscape). `EmptySceneHint` ("Point at a subject") shows under the badge
  before any scene is classified, and lingers 3s after one appears before fading out. `CameraTopBar` is now
  a translucent strip over the *top* of the preview carrying flash, the "DEBUG" chip, the shooting-mode chip
  (tap opens Settings), and the settings gear — flash moved here from the bottom bar, Pixel-style.
  `BottomControlBar` (in the black area below the preview) is gallery thumbnail — shutter — lens switch: the
  72dp shutter has a 90%-scale press animation, a brief white capture flash, and its ring animates to
  `Accent.Ready` with a soft glow once shoot-ready; the 44dp gallery thumbnail shows the last captured photo
  (see "Gallery thumbnail" below) and opens it with `ACTION_VIEW`. `ZoomChip` (pinch-zoom ratio, e.g.
  "1.0×", fades out 1.2s after the last pinch delta) sits above the shutter row. `FocusRingOverlay`
  (tap-to-focus ring, fades in over 150ms / out after 800ms), `OnboardingCard` (a single dismissible card
  shown once on first launch, see "Onboarding" below), and `CameraErrorOverlay` for camera init failures
  (shown for any `CameraBindResult.Failure`, including an `IllegalStateException` from CameraX binding —
  `Retry` clears the error and re-triggers the bind `DisposableEffect`). Volume-down also fires the shutter
  (`MainActivity.onKeyDown` → `AppContainer.volumeDownEvents`, a `SharedFlow` the screen collects).
* **privacy** — `PrivacyScreen`. A plain scrolling Compose screen showing `PrivacyPolicyText` (same
  wording as `docs/PRIVACY_POLICY.md`) — no network fetch, since the app has none.
* **review** — `ReviewScreen`. Full-bleed captured photo (Coil), letterboxed to the same 4:3 as the live
  preview, with a bottom card: the score (large, tabular figures), the scene/mode chips, a "Subject: object"
  line when `CompositionResult.primarySubject.kind` is `OBJECT`, and up to two strengths (`Accent.Ready`
  dot) and two improvements (`Accent.Warn` dot) in plain sentences — `ReviewFormatter.trim` always caps
  each list at `TRIMMED_COUNT`, independently of the other list's length. Three actions: **Retake**
  (outlined, deletes the photo via `CaptureRepository` first), **Share** (icon, `ACTION_SEND` with the
  photo's `Uri`), **Keep** (filled, pops back to camera). The system back gesture behaves exactly like
  **Keep** (`BackHandler`) — a captured photo is never silently discarded by backing out of this screen.
* **settings** — `SettingsScreen` + `SettingsViewModel`, backed by `SettingsRepository` (DataStore),
  grouped into titled, one-line-summarized `SettingsCard`s (`SettingsComponents.kt`): **Shooting mode**
  (a `SingleChoiceSegmentedButtonRow` when `SceneIntent` has few enough entries to fit one row without
  crowding, chips otherwise — `SceneIntentSelector`), **Guidance** (the guidance master switch, show-score,
  and the `GuidanceLevel` picker), **Overlays** (the rule-of-thirds grid), **Detection** ("Detect objects",
  "Subject mask", pose detection — controls `VisionFeatureToggles`, see below), **Performance** (battery
  saver, debug mode), and **About** (app name, `BuildConfig.VERSION_NAME`/`VERSION_CODE`, the "all analysis
  runs on your device" line, and a button into `PrivacyScreen`). Every switch row is at least 48dp tall.

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
CameraScreen (collectAsStateWithLifecycle) ── ScoreBadge / GuidanceBanner / overlays redraw
```

* **Shooting mode (`SceneIntent`)** tells the engine what the photographer says they're shooting;
  `CameraViewModel` reads it off `CoachSettings.sceneIntent` and forwards it to both
  `CompositionCoach.process(...)` (live preview) and `.evaluateOnce(...)` (capture-time review
  evaluation). Changing it in Settings calls `coach.reset()` on the next settings emission — same
  reasoning as the lens-switch reset — so smoothed score/recommendations from the old mode don't
  linger under the new one. The reset is skipped on the very first settings emission (app/process
  start) so restoring a persisted non-Auto mode doesn't reset a coach that never ran anything yet.
* **`awaitingSubject` UI state**: when `SmoothedComposition.awaitingSubject` is true (declared intent
  needs a subject — e.g. PORTRAIT — that isn't in frame yet), `displayScore` is the last meaningful
  score the engine held, not a live one, so the UI must not present it as current:
  * `ScoreBadge(awaitingSubject = true)` renders that held number at `GuidanceFormatter.badgeAlpha`
    (40%) with no tier colour (plain white), and shoot-ready styling ("— SHOOT", the pulse, the green
    tier) never shows regardless of `isShootReady`.
  * `GuidanceBanner(awaitingSubject = true)` ignores the normal primary/reason/secondary layout and
    instead shows the single find-subject recommendation's `title` as a small line
    (`GuidanceFormatter.awaitingSubjectTitleLine`, e.g. "Looking for a face") with its `instruction` as
    the headline (`awaitingSubjectHeadline`, e.g. "Move closer to your subject") — no directional
    glyph, since that recommendation carries no direction.
  * Both are pure `GuidanceFormatter` helpers, unit-tested on the JVM without needing the real engine
    to ever actually return `awaitingSubject = true` (see Known limitations below).

* `CameraViewModel` never touches a `Context` beyond what `AppContainer` (built from
  `Application`) already holds; it does not store a `PreviewView`, `Activity`, or `CameraController`.
* Per-frame scoring runs on `Dispatchers.Default` (`flowOn`); the sampled `onEach` that pushes into
  `MutableStateFlow` runs on the ViewModel's own dispatcher (`Main`, via `viewModelScope`).
* On shutter: `CaptureRepository.capture(...)` saves the JPEG, then
  `CompositionCoach.evaluateOnce(lastFrame, GuidanceLevel.COACH)` computes the one-off review result,
  both are parked in `AppContainer.reviewStore` (a tiny in-memory `StateFlow<ReviewEntry?>`, not a nav
  argument — richer objects don't round-trip through navigation-compose args), and the screen
  navigates to `"review"`.
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
  same way `lensFacing` already is. JPEG quality is 95; `ImageCapture.setTargetRotation` is set at bind
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
   before the existing plain normalized-to-pixel multiply. `CompositionOverlay` (target ring, level
   indicator, directional arrow, rotate glyph, region highlight) and `DebugGeometryOverlay` (object boxes,
   subject boxes/landmarks, mask heat) both go through it.
3. **Chrome counter-rotates in place by `+theta`.** Flash, the shooting-mode chip and the settings gear
   (`CameraTopBar`), the gallery thumbnail, shutter and lens switch (`BottomControlBar`), plus the score
   badge + guidance banner stack, each wrap in `RotatedChrome`, whose angle comes from
   `OverlayMapper.uprightChromeAngleDegrees`. `rememberControlCounterRotation` tracks an ever-accumulating
   (never-wrapped) target so `animateFloatAsState`'s 250 ms tween always takes the *shorter* turn (270 -> 0
   is a +90 hop, not a -270 spin).
4. **A rotated wide banner doesn't overflow its slot, and hugs the physical top edge.** `Modifier.rotate`
   only rotates pixels — it never changes the size the layout system believes an element has, so a wide
   banner rotated 90 degrees still reported its wide-short size and ran across the middle of the preview.
   `RotatedChrome` measures its content with width/height swapped at 90/270 and reports the *swapped* size
   to its parent (`RotatedChromeMath`, unit-tested). `CameraScreen` aligns that stack to whichever screen
   edge physical up currently appears at (`chromeStackEdgeFor`: top at 0, right at `ROTATION_90`, left at
   `ROTATION_270`, bottom at 180), so guidance never sits over the frame centre. `GuidanceBanner` is capped
   at 3 lines and, in landscape, at 60% of the preview's *height* (its long axis once rotated) vs. 85% of
   the screen's width in portrait.
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

## Camera/Activity lifecycle (`CameraViewModel`)

`CameraViewModel` exposes two equivalent-but-distinct pairs of lifecycle hooks, both idempotent and
both funnelling into the same private `resumePipeline()`/`pausePipeline()`:

* `onScreenStarted()` / `onScreenStopped()` — the pre-existing hooks, wired to
  `DisposableEffect(viewModel) { ... onDispose { ... } }` in `CameraScreen`, firing on Compose
  entry/exit.
* `onScreenResumed()` / `onScreenPaused()` — **new**; wire these to
  `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` / `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)` on
  the camera screen. Compose entry/exit and the Activity going to/from the background are different
  events — backgrounding the app (home button, app switch, a permission dialog from another app) does
  *not* leave composition, so before this hook existed the vision pipeline's orientation sensor stayed
  registered and its ML Kit detectors stayed allocated for as long as the app sat in the background.

Both pairs are safe to wire simultaneously (as `CameraScreen` now does): `resumePipeline()`/
`pausePipeline()` track their own active/inactive flag, so whichever hook fires first actually starts/stops
`frameSource`+`ThermalPolicy` and resets the coach smoother; the other is then a no-op until the pipeline
is paused again. `VisionPipeline.start()` after a `stop()` fully re-arms itself — every piece of
cross-frame cached state (last bodies/objects/subject-mask, `DetectorSchedule`'s frame counter,
`SegmentationCadence`'s degradation ladder) resets, not just the ML Kit detector instances — so resuming
from the background never shows a stale detection left over from before backgrounding.

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

## Photo saving, thumbnail and gallery-shortcut hooks

* After a successful capture, `CameraViewModel` records the saved `Uri` in
  `CameraUiState.lastPhotoUri` (process-lifetime; survives navigation but not process death) so the
  screen can show a gallery-shortcut thumbnail button. On a fresh process start, the same field is
  populated from `CaptureRepository.latestPhotoUri()` — a MediaStore query for the newest image under
  `Pictures/CompositionCoach` — so the button has something to show without waiting for the next capture.
* `CameraViewModel.loadThumbnail(uri, sizePx)` forwards to `CaptureRepository.loadThumbnail`, a suspend
  function (`Dispatchers.IO`) using `ContentResolver.loadThumbnail` on API 29+ (cheap, provider-side
  downsampling) or a bounds-then-`inSampleSize` decode below that; returns `null` on any failure rather
  than throwing, since a thumbnail is a nice-to-have, not something worth surfacing an error for.

## Settings keys (DataStore Preferences, `SettingsRepository`)

| Key | Type | Default | Meaning |
|---|---|---|---|
| `guidance_enabled` | bool | `true` | Master switch; off = preview only, coach never runs |
| `show_score` | bool | `true` | Show/hide `ScoreBadge` |
| `show_thirds_grid` | bool | `false` | Rule-of-thirds overlay |
| `guidance_level` | string (`GuidanceLevel` name) | `BALANCED` | MINIMAL / BALANCED / COACH |
| `pose_detection_enabled` | bool | `true` | Forwarded to `frameSource.setPoseDetectionEnabled` |
| `battery_saver` | bool | `false` | 200ms analysis interval instead of 100ms |
| `debug_mode` | bool | `false` | Shows `DebugOverlay` + `DebugGeometryOverlay` + the DEBUG chip |
| `scene_intent` | string (`SceneIntent` name) | `AUTO` | Shooting mode: AUTO / PORTRAIT / GROUP_PORTRAIT / LANDSCAPE / ARCHITECTURE / OBJECT |
| `detect_objects` | bool | `true` | Forwarded to `(frameSource as? VisionFeatureToggles)?.setObjectDetectionEnabled` |
| `subject_mask` | bool | `true` | User's own preference; see `CoachSettings.effectiveSubjectMaskEnabled` below for what's actually applied |
| `onboarding_seen` | bool | `false` | Whether the first-launch `OnboardingCard` has been dismissed |

An unrecognized or missing `guidance_level` value falls back to `BALANCED`, and an unrecognized,
missing, or legacy `scene_intent` value falls back to `AUTO`, rather than crashing
(`GuidanceLevelCodec` / `SceneIntentCodec`, unit-tested in `CoachSettingsTest`) — this is the intended
way to evolve the schema, not a bug to "fix" by renaming keys in place.

## Detection settings (`VisionFeatureToggles`)

Settings → a new "Detection" group, applied by `CameraViewModel` via `(frameSource as?
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

A single dismissible `OnboardingCard` ("Point at a subject. Follow the arrow. Shoot when it turns
green." + a "Got it" button) shows at the bottom of the camera screen whenever `onboarding_seen` is
false — which in practice means once, right after the camera permission is granted, since
`CameraScreen` only ever composes past `CameraPermissionGate`. Dismissing it persists `onboarding_seen`
via `CameraViewModel.onOnboardingDismissed()`; there is no other way to bring it back short of clearing
app data.

## Debug mode guide

Enable **Settings → Debug mode**. Two extra layers appear on the camera screen:

* `DebugGeometryOverlay` — draws, in this order (mask first so it sits *behind* everything else):
  1. the subject mask (`FrameAnalysis.subjectMask`, when present) as a faint green heat layer, one cell
     per mask grid cell, alpha proportional to that cell's probability and capped at 0.35 total;
  2. every raw `DetectedObject` from `FrameAnalysis.objects` as a dashed box labelled `category
     confidence%` — this can be a superset of the boxes that actually became a subject, since
     `:vision`'s `ObjectMapper` filters further (frame-coverage / face-IoU) before anything reaches
     `DetectedSubject`;
  3. every `DetectedSubject`'s box (primary subject in green, others in amber), plus face eye/nose points
     and in-frame pose landmarks.

  This overlay (plus `CompositionOverlay`'s restrained region-highlight outline, see the screen map above)
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

* Every `IconButton`/clickable exposes a `contentDescription` (flash mode, switch camera, settings,
  back, close). The shooting-mode chip in `CameraTopBar` (a `clickable` `Box`, not an `IconButton`) uses
  `Modifier.minimumInteractiveComponentSize()` so its tap target is still ≥ 48dp even though the visible
  pill is smaller.
* `ScoreBadge` collapses its internal `Text`s into one `clearAndSetSemantics { contentDescription = "Composition score $score" }`
  (or "…, ready to shoot" / the "looking for a subject" text) so TalkBack reads one clear sentence.
* `GuidanceBanner` does the same for the instruction text, and additionally sets
  `liveRegion = LiveRegionMode.Polite` on all three of its render paths (hold-framing hint, awaiting-subject,
  and the normal primary/reason/secondary layout) so TalkBack announces new advice as it changes, unprompted.
* Text over the live camera preview relies on the same `Scrim` token (black, 40% alpha) for contrast.

## Design tokens (`ui/theme/`)

One visual language, defined once and used everywhere instead of ad-hoc `Color(0xFF...)` literals in
screen code (the diagnostic-only `DebugGeometryOverlay`/`DebugOverlay`/`CrashReportScreen` are
deliberately exempt — they're meant to look like instrumentation, not the product):

* **`Accent.Ready`** (`#34D399`, green) and **`Accent.Warn`** (`#FFC857`, amber) are the *only* two
  non-neutral accents anywhere in the app — white/amber/green, nothing else. `Accent.Warn` also backs
  `MaterialTheme.colorScheme.primary`.
* **`Scrim`** (black, 40% alpha — within the 35-45% range) is the one translucent-panel style used behind
  the score badge, guidance banner, top bar, onboarding card, and zoom chip; **`ScrimStrong`** (85%) is for
  full-screen states that must read over any photo (camera error). **`OnScrim`**/**`OnScrimMuted`** are
  white / 70%-white text over those scrims.
* Type scale: the score badge's numerals use tabular figures (`fontFeatureSettings = "tnum"`, via
  `TextStyle`, not a bare `Text(fontSize=...)` call — Compose's `Text` has no such parameter) so a changing
  digit count never shifts the badge's width; instruction headlines use `titleMedium`; secondary/reason
  lines use `bodySmall` at `OnScrimMuted`. See `ui/theme/Type.kt`.
* Motion (`ui/theme/Type.kt`'s `Motion` object): state changes ease out over `Motion.STATE_CHANGE_MS`
  (220ms, inside the 180-250ms range), presses take `Motion.PRESS_MS` (120ms), crossfades take
  `Motion.CROSSFADE_MS` (200ms). No bounces/springs anywhere in the redesigned surfaces.

## Gallery thumbnail (`BottomControlBar`)

`CameraUiState`/`CameraViewModel` do not carry a `lastPhotoUri` field or a `loadThumbnail` helper — that
hook doesn't exist in this worktree (they're owned by the camera engineer's concurrent work; see the
top-level task's file-ownership split). `AppNavGraph` wires the thumbnail *optimistically* instead: it
collects `AppContainer.reviewStore.current` and remembers the last **non-null** `photoUri` it ever saw
(`rememberSaveable`, so it survives process death), since `reviewStore.clear()` (called on Keep/Retake)
would otherwise blank it the moment the user returns to the camera screen. That value is passed down as
`CameraScreen(lastPhotoUri = ...)`. Caveats of this approach, to fix once the real hook lands: it doesn't
know about a photo taken in a previous process (cold start shows the placeholder icon until the next
capture), and it decodes the full-size photo via `Coil` for a 44dp thumbnail rather than a pre-scaled one.

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

## Known limitations / what the integrator should know

* **The vision and composition stubs are gone.** `VisionPipelineFactory.create()` now returns a real
  `VisionPipeline` and `CompositionCoach.create()` a real `CompositionEngine`/`CompositionSmoother` —
  including object detection and the subject mask (see the Detection settings section above). `:app` was
  already written against the real public contracts, so no `:app` code changed as a *consequence* of the
  stubs going away; `analysisUnavailable`/`SmoothedComposition.EMPTY` are now only reached if
  `VisionPipeline` construction genuinely throws on a given device, not the default path.
* **`SceneIntent` is honoured by the engine now.** `:app`'s side of this was already exercised via
  `@Preview`s and the `GuidanceFormatter`/`CameraUiState` unit tests against a hand-built
  `SmoothedComposition` even before the engine set `awaitingSubject` for real; nothing there needed to
  change once it started doing so.
* **Legacy storage permission (API 26-28):** `CameraScreen` requests `WRITE_EXTERNAL_STORAGE` lazily,
  right before the first capture, only on API ≤ 28. API 29+ never needs it (scoped storage via
  `MediaStore` + `RELATIVE_PATH`).
* **No instrumentation tests** were added (not required); all tests are plain JUnit4 on the JVM
  (`OverlayMapperTest`, `GuidanceFormatterTest`, `CameraUiStateTest`, `CoachSettingsTest`,
  `ZoomChipFormatterTest`, `ReviewFormatterTest`). The `detect_objects`/`subject_mask`/`onboarding_seen`
  settings are covered the same way the existing keys are — `CoachSettingsTest` exercises the pure
  `CoachSettings`/`effectiveSubjectMaskEnabled` logic on the JVM; `SettingsRepository` itself needs a real
  DataStore-backed `Context` (no Robolectric in this module), so its read/write plumbing is exercised by
  the app running, not a JVM test — consistent with how the pre-existing keys were covered.
* **Front camera capture** sets `ImageCapture.Metadata.isReversedHorizontal = true` so the saved JPEG
  matches what the mirrored preview showed; this does not affect analysis, which is already mirrored
  by the vision layer's normalized-coordinate contract.
* **Device rotation** (see the dedicated section above): a capture taken in the brief window before the
  first `FrameAnalysis` arrives (fresh process start, phone already held sideways) uses the default
  `deviceRotationDegrees = 0` until the first frame updates `CameraUiState`, so its EXIF could reflect
  natural portrait for that one shot rather than the phone's actual held orientation — a narrow, one-shot
  window rather than a systemic issue, since every subsequent frame corrects it immediately.
* Pinch-to-zoom is still not exposed as a settings toggle — it's a direct gesture on the preview — but it
  now drives the `ZoomChip` readout described above.
* **Needs a physical device to confirm:** `CAPTURE_MODE_MAXIMIZE_QUALITY`'s actual shutter-to-saved-JPEG
  latency (the KDoc's "typically well under a second" is the documented CameraX trade-off, not measured
  here); `ThermalPolicy`'s live behaviour against `PowerManager.addThermalStatusListener` (Robolectric has
  no thermal hardware to simulate, so only the pure `ThermalPolicy.computeTier` mapping is unit-tested);
  the exposure-compensation range/step actually reported by `cameraInfo.exposureState` on a real sensor;
  and whether `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY` picks a capture resolution large enough that
  `CAPTURE_MODE_MAXIMIZE_QUALITY`'s processing time becomes noticeable on lower-end hardware (if so, the
  fallback ladder is already in place via `preferFastCapture`/`PerformanceTier`, just untuned against a
  real device's numbers).
