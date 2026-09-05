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
        └── "settings" SettingsScreen   (pushed from the camera screen's gear icon)
```

* **camera** — `CameraScreen` + `CameraViewModel`. Full-screen `PreviewView` with Compose overlays:
  `ThirdsGridOverlay`, `CompositionOverlay` (target ring / horizon level / directional arrow),
  `DebugGeometryOverlay` + `DebugOverlay` (debug mode only), `ScoreBadge`, `GuidanceBanner`,
  `CameraTopBar`, `BottomControlBar`, and `CameraErrorOverlay` for camera init failures.
* **review** — `ReviewScreen`. Shows the captured photo (Coil) next to the score, strengths,
  improvements and scene chip from the `CompositionResult` computed at capture time. Keep pops back
  to camera; Retake deletes the photo via `CaptureRepository` first.
* **settings** — `SettingsScreen` + `SettingsViewModel`, backed by `SettingsRepository` (DataStore).

## State flow

```
FrameAnalysisSource.frames (vision)
        │  (skipped entirely if guidanceEnabled == false)
        ▼
CompositionCoach.process(frame, guidanceLevel)   ── every frame, keeps the smoother warm
        ▼
SmoothedComposition  ── sampled to ~10 Hz (kotlinx.coroutines sample())
        ▼
CameraUiState (StateFlow)  ── pure `withX(...)` reducer functions, see CameraUiState.kt
        ▼
CameraScreen (collectAsStateWithLifecycle) ── ScoreBadge / GuidanceBanner / overlays redraw
```

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

An unrecognized or missing `guidance_level` value falls back to `BALANCED` rather than crashing
(`GuidanceLevelCodec`, unit-tested in `CoachSettingsTest`) — this is the intended way to evolve the
schema, not a bug to "fix" by renaming keys in place.

## Debug mode guide

Enable **Settings → Debug mode**. Two extra layers appear on the camera screen:

* `DebugGeometryOverlay` — every `DetectedSubject`'s box (primary subject in green, others in amber),
  plus face eye/nose points and in-frame pose landmarks. This is the *only* place bounding
  boxes/regions are drawn; `CompositionOverlay` never draws them, debug or not.
* `DebugOverlay` — a collapsible, scrollable panel (tap the "DEBUG ▾/▸" header) listing: scene type +
  confidence, raw vs. smoothed score, engine time, FPS, last analysis latency and sampling interval,
  every `CompositionMetric` (category/score/confidence/severity/applicable), every recommendation id
  with priority/confidence/direction, per-detector timings (`FrameAnalysis.detectorTimings`), subject
  count, and — when the engine populates it — the optimizer's current score, improvement and
  candidate framings.
* A small "DEBUG" chip appears top-left as a reminder the mode is on even if the panel is collapsed.

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
