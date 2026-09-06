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
  doc); the rest of the screen is plain black, holding `ZoomChip` and `BottomControlBar`.
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
  Unicode character, reserves a fixed height for its headline so a longer instruction never resizes it, and
  never exceeds 85% of the screen's width. `EmptySceneHint` ("Point at a subject") shows under the badge
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
* If the vision pipeline fails to construct for any reason, `AppContainer.frameSourceOrNull()` catches
  that once and logs it; `CameraController.bind()` is called with a `null` analyzer and simply does not
  add an `ImageAnalysis` use case, so preview + capture keep working and the UI shows a small "Analysis
  unavailable" note (`CameraUiState.analysisUnavailable`) — see "Known limitations" below.
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
* Pinch-to-zoom is still not exposed as a settings toggle — it's a direct gesture on the preview — but it
  now drives the `ZoomChip` readout described above.
