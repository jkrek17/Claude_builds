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

* **camera** — `CameraScreen` + `CameraViewModel`. Full-screen `PreviewView` with Compose overlays:
  `ThirdsGridOverlay`, `CompositionOverlay` (target ring / horizon level / directional arrow — the arrow
  anchors to `DetectedSubject.anchorPoint`, which is already the box centre for an `OBJECT`-kind primary
  subject, no special-casing needed — plus a fading, restrained 1dp/45%-white rounded outline of the
  headline recommendation's `region`, when it has one and framing isn't shoot-ready yet),
  `DebugGeometryOverlay` + `DebugOverlay` (debug mode only — see "Debug mode guide" below), `ScoreBadge` and
  `GuidanceBanner` (both fade to 30% for ~1s right after a capture, see `CameraUiState.postCaptureFadeActive`
  below), `CameraTopBar` (shows the current shooting mode as a small chip when it isn't Auto — tap it to open
  Settings), `BottomControlBar` (shutter has a 90%-scale press animation and a brief white capture flash),
  `ZoomChip` (pinch-zoom ratio, e.g. "1.0×", fades out 1.2s after the last pinch delta), `FocusRingOverlay`
  (tap-to-focus ring, fades in over 150ms / out after 800ms), `OnboardingCard` (a single dismissible card
  shown once on first launch, see "Onboarding" below), and `CameraErrorOverlay` for camera init failures
  (shown for any `CameraBindResult.Failure`, including an `IllegalStateException` from CameraX binding —
  `Retry` clears the error and re-triggers the bind `DisposableEffect`). Volume-down also fires the shutter
  (`MainActivity.onKeyDown` → `AppContainer.volumeDownEvents`, a `SharedFlow` the screen collects).
* **privacy** — `PrivacyScreen`. A plain scrolling Compose screen showing `PrivacyPolicyText` (same
  wording as `docs/PRIVACY_POLICY.md`) — no network fetch, since the app has none.
* **review** — `ReviewScreen`. Shows the captured photo (Coil) next to the score, strengths,
  improvements, scene chip, a "Subject: object" line when `CompositionResult.primarySubject.kind` is
  `OBJECT`, and — when the shot was coached under a non-Auto shooting mode — a "`<Mode>` mode" chip (from
  `CompositionResult.intent`), all from the `CompositionResult` computed at capture time. Strengths and
  improvements are trimmed to the top two of each once there are more than four combined
  (`ReviewFormatter.trim`) so the screen stays readable at a glance. Keep pops back to camera; Retake
  deletes the photo via `CaptureRepository` first.
* **settings** — `SettingsScreen` + `SettingsViewModel`, backed by `SettingsRepository` (DataStore).
  "Shooting mode" is the first section on the screen: a chip selector over every `SceneIntent`
  (Auto/Portrait/Group/Landscape/Architecture/Object), since it's the setting people change most. A
  "Detection" section ("Detect objects", "Subject mask") controls `VisionFeatureToggles`, see below. The
  last section is "About": app name, `BuildConfig.VERSION_NAME`/`VERSION_CODE`, the "all analysis runs
  on your device" line, and a button into `PrivacyScreen`.

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
are the same number, so `OverlayMapper.toPx(point, viewWidthPx, viewHeightPx)` is a plain
`point.x * width, point.y * height` — see the class doc on `OverlayMapper` for the full argument and
what would break it (dropping the shared `ViewPort`, or switching to `FIT_CENTER`, which can
letterbox). `OverlayMapperTest` covers the four corners + centre + a rect.

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
  same way `lensFacing` already is. JPEG quality is 95; `setTargetRotation` is set from the display
  rotation on every bind so EXIF orientation matches actual device orientation.
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
  analysis latency and sampling interval,
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
* Text over the live camera preview relies on the same black scrim backgrounds the badge/banner already
  used (`Color.Black.copy(alpha = 0.3f–0.35f)`) for contrast — unchanged, just confirmed still in place.

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
