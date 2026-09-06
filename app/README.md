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
  `ThirdsGridOverlay`, `CompositionOverlay` (target ring / horizon level / directional arrow),
  `DebugGeometryOverlay` + `DebugOverlay` (debug mode only), `ScoreBadge`, `GuidanceBanner`,
  `CameraTopBar` (shows the current shooting mode as a small chip when it isn't Auto — tap it to open
  Settings), `BottomControlBar`, and `CameraErrorOverlay` for camera init failures (shown for any
  `CameraBindResult.Failure`, including an `IllegalStateException` from CameraX binding — `Retry` clears
  the error and re-triggers the bind `DisposableEffect`).
* **privacy** — `PrivacyScreen`. A plain scrolling Compose screen showing `PrivacyPolicyText` (same
  wording as `docs/PRIVACY_POLICY.md`) — no network fetch, since the app has none.
* **review** — `ReviewScreen`. Shows the captured photo (Coil) next to the score, strengths,
  improvements, scene chip and — when the shot was coached under a non-Auto shooting mode — a
  "`<Mode>` mode" chip (from `CompositionResult.intent`), all from the `CompositionResult` computed at
  capture time. Keep pops back to camera; Retake deletes the photo via `CaptureRepository` first.
* **settings** — `SettingsScreen` + `SettingsViewModel`, backed by `SettingsRepository` (DataStore).
  "Shooting mode" is the first section on the screen: a chip selector over every `SceneIntent`
  (Auto/Portrait/Group/Landscape/Architecture/Object), since it's the setting people change most. The
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

* Analysis resolution targets ~640×480 via `ResolutionSelector` (4:3 `AspectRatioStrategy` +
  `ResolutionStrategy(640x480, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)`), output format
  `YUV_420_888`, backpressure `STRATEGY_KEEP_ONLY_LATEST`, analyzer on a dedicated single-thread
  executor.
* `ImageCapture` uses `CAPTURE_MODE_MINIMIZE_LATENCY` (a live-coaching camera should feel snappy on
  the shutter; see the comment in `CameraController` for the trade-off against
  `MAXIMIZE_QUALITY`), JPEG quality 95.
* If the vision pipeline is unavailable (the `:vision` stub throws `NotImplementedError`), the
  `AppContainer` catches it once and logs it; `CameraController.bind()` is called with a `null`
  analyzer and simply does not add an `ImageAnalysis` use case, so preview + capture keep working and
  the UI shows a small "Analysis unavailable" note (`CameraUiState.analysisUnavailable`).
* Lens switching: `CameraViewModel.onSwitchLensRequested()` flips the desired facing in state, which
  re-keys a `DisposableEffect` that re-binds; once bound, `onCameraBindResult` calls
  `frameSource.setFrontCamera(...)` and `coach.reset()` so the smoother doesn't blend across camera
  switches. If the requested lens isn't available, `CameraController` falls back to the other one and
  logs it.
* Flash cycles OFF → AUTO → ON and is hidden entirely when `cameraInfo.hasFlashUnit()` is false.
  Tap-to-focus uses `PreviewView.meteringPointFactory`; pinch-zoom is wired via
  `detectTransformGestures` (optional per the spec, included).

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

An unrecognized or missing `guidance_level` value falls back to `BALANCED`, and an unrecognized,
missing, or legacy `scene_intent` value falls back to `AUTO`, rather than crashing
(`GuidanceLevelCodec` / `SceneIntentCodec`, unit-tested in `CoachSettingsTest`) — this is the intended
way to evolve the schema, not a bug to "fix" by renaming keys in place.

## Debug mode guide

Enable **Settings → Debug mode**. Two extra layers appear on the camera screen:

* `DebugGeometryOverlay` — every `DetectedSubject`'s box (primary subject in green, others in amber),
  plus face eye/nose points and in-frame pose landmarks. This is the *only* place bounding
  boxes/regions are drawn; `CompositionOverlay` never draws them, debug or not.
* `DebugOverlay` — a collapsible, scrollable panel (tap the "DEBUG ▾/▸" header) listing: scene type +
  confidence + the declared shooting mode (`Intent:`), raw vs. smoothed score, engine time, FPS, last
  analysis latency and sampling interval,
  every `CompositionMetric` (category/score/confidence/severity/applicable), every recommendation id
  with priority/confidence/direction, per-detector timings (`FrameAnalysis.detectorTimings`), subject
  count, and — when the engine populates it — the optimizer's current score, improvement and
  candidate framings.
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

* **The vision pipeline is a stub in this worktree.** `VisionPipelineFactory.create()` throws
  `NotImplementedError`; `AppContainer.frameSourceOrNull()` catches that once at construction and
  logs it. Until the real `:vision` pipeline lands, the app runs with a working camera (preview +
  capture) but `composition` stays `SmoothedComposition.EMPTY` and `analysisUnavailable` stays true —
  this is expected, not a bug in `:app`.
* **The composition engine is also a stub.** `CompositionCoach.create()` wires real
  `CompositionEngine`/`CompositionSmoother` instances, but per the current `:composition` stub they
  return empty `CompositionResult`s. `:app` is written against the *real* public contracts
  (`SmoothedComposition`, `CompositionResult`, `Recommendation`, etc.), so no changes should be needed
  in `:app` once both stubs are replaced — only re-verify the overlay geometry assumptions
  (`OverlayGeometry.TargetPoint`/`Line` placement, `Severity` on the `HORIZON` metric) once real data
  is flowing.
* **`SceneIntent` is plumbed through but not yet honoured by the engine in this worktree** —
  `CompositionEngine.evaluate(frame, level, intent)` accepts it but always returns
  `awaitingSubject = false` (see the STATUS note on that method). `:app` reads `settings.sceneIntent`,
  forwards it to `process`/`evaluateOnce`, resets the coach on a mode change, and renders every
  `awaitingSubject` UI path (`ScoreBadge`, `GuidanceBanner`) — those are exercised via `@Preview`s and
  the `GuidanceFormatter`/`CameraUiState` unit tests with a hand-built `SmoothedComposition`, not via a
  live engine result, since the live engine in this worktree never sets the flag. No `:app` changes
  should be needed once `:composition` honours the intent for real.
* **Legacy storage permission (API 26-28):** `CameraScreen` requests `WRITE_EXTERNAL_STORAGE` lazily,
  right before the first capture, only on API ≤ 28. API 29+ never needs it (scoped storage via
  `MediaStore` + `RELATIVE_PATH`).
* **No instrumentation tests** were added (not required); all tests are plain JUnit4 on the JVM
  (`OverlayMapperTest`, `GuidanceFormatterTest`, `CameraUiStateTest`, `CoachSettingsTest`).
* **Front camera capture** sets `ImageCapture.Metadata.isReversedHorizontal = true` so the saved JPEG
  matches what the mirrored preview showed; this does not affect analysis, which is already mirrored
  by the vision layer's normalized-coordinate contract.
* Pinch-to-zoom is implemented but not exposed in any settings UI (it's a direct gesture on the
  preview, per the "optional" note in the spec).
