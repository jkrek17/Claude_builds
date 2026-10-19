# JustRight

*The photo you meant to take.* Formerly Composition Coach (and, for one build, Eyebright).

A native Android camera for people who love photos but never learned photography: it takes the photo you
meant to take, then teaches you afterwards, in your own pictures, and learns what you like
([docs/PRODUCT.md](docs/PRODUCT.md)). It analyses the live preview **on the device** and shows one cue at a
time (a chevron on the edge to move toward, brackets for closer, a level), fires at the best moment, and
explains the shot on an after-action card. You pick the photograph you want (a person, a view, night, silky
water...), not a mode.

No cloud calls, no camera frames leave the phone.

This project lives in the `composition-coach/` folder of the
[Claude_builds](../README.md) repo. Run every command below from this folder, and open this folder (not
the repo root) in Android Studio.

- [Try it on your phone (no Android Studio needed)](#try-it-on-your-phone)
- [What's in the prototype](#whats-in-the-prototype)
- [Build from source](#build-from-source)
- [How it works](#how-it-works)
- [Project structure](#project-structure)
- [Composition scoring](#composition-scoring)
- [Recommendation engine](#recommendation-engine)
- [Performance strategy](#performance-strategy)
- [Privacy](#privacy)
- [Testing](#testing)
- [Roadmap and future enhancements](#roadmap-and-future-enhancements)

---

## Try it on your phone

You do not need Android Studio. Every push to GitHub runs the tests, lint and both builds, and publishes
two rolling pre-releases:

| Channel | Link | What it is |
|---|---|---|
| `release-latest` (recommended) | https://github.com/jkrek17/Claude_builds/releases/tag/release-latest | R8-shrunk release build, per-CPU APKs (arm64 ≈ 55 MB) |
| `debug-latest` | https://github.com/jkrek17/Claude_builds/releases/tag/debug-latest | Debuggable build with the same features (arm64 ≈ 74 MB) |

Both are signed with the same checked-in debug key until the release secrets in
[docs/RELEASE.md](docs/RELEASE.md) are configured, so they install as updates over each other. Once a real
upload key is configured, `release-latest` changes signature: uninstall once, then updates work again.

**Install (on the phone; no GitHub account needed)**

1. Open the `release-latest` link above.
2. Under **Assets**, tap `JustRight-release-arm64.apk` (use `-arm32` only on an old 32-bit phone).
3. Open the downloaded file. Allow installs from this source when Android asks.
4. On the Play Protect prompt, tap **More details**, then **Install anyway**. If that option is missing, turn
   off scanning temporarily: Play Store → profile picture → Play Protect → gear icon → "Scan apps with
   Play Protect", install, then turn it back on.
5. Open **JustRight**, grant camera access, point the camera at a subject.

Testers: [docs/TESTERS.md](docs/TESTERS.md) has these steps in plain language plus what feedback helps most.

**Alternative: from a specific CI run** (desktop browser, logged in; the GitHub mobile app hides artifacts):
Actions tab → latest green **JustRight (Android) CI** run → scroll to **Artifacts** at the bottom →
`composition-coach-debug-apk`, `composition-coach-release-apk` or `composition-coach-release-aab` (zipped).

Requirements: Android 8.0 (API 26) or newer with Google Play services. The ML Kit face model downloads
through Play services on first launch, so be online the first time. Object detection, pose and
segmentation models are bundled.

### What to try

| Do this | Expect |
|---|---|
| Frame a person with lots of empty space above their head | A chevron at the top edge, "Lower", and the ring round the shutter fills as you do |
| Put their face right at the frame edge | A chevron on the side to move toward, "Slightly left/right" |
| Tilt the phone a few degrees | A bubble level, "Level" |
| Tap the shutter | It waits (up to 4 s) for good framing and a steady hand, then shoots; hold the shutter to shoot at once |
| Look at the card after the shot | Keeper / Good / Try again, what worked, one thing to try next time, maybe a better crop |
| Swipe the strip above the shutter to "Silky water" and point at moving water | "Hold still for 3 seconds", a progress ring, then a smoothed photo plus the normal one |
| Pick "Night" | "Hold still", a 2-second ring, then the shot |
| Settings > Experience > Coach | The score number and a one-line reason under each cue |

Photos are saved to `Pictures/CompositionCoach` (the album keeps its original name). Turn on **Pro controls**
in Settings, then **More > Developer mode**, to see face/body boxes, every metric, timings and FPS.

## What's in the prototype

Status on a phone: **not yet verified** means the code is built and its logic is covered by the JVM tests, but no
automated device test exists and nobody has checked it on a phone since it was integrated. The Wave 1 camera
(preview, overlay alignment, live detection, capture, review, shooting modes) was checked by hand on a Pixel
(Android 15) before the Wave 2 integration; nothing has an automated on-device test.

**Outcomes** (the strip above the shutter; `docs/PRODUCT.md` "Outcomes, not modes")

| Outcome | What happens underneath | On a phone |
|---|---|---|
| Anything | auto scene, best available enhancement (vendor AUTO), sharpest of three, auto-level | not yet verified |
| A person | face coaching; vendor Bokeh when offered, else a background-blur "Portrait" look offered on review | not yet verified |
| A group | everyone in frame with eyes open before it fires; edge cuts block; timer hands over to readiness | not yet verified |
| A view | horizon coaching; HDR when offered, else a 3-distance focus stack when the lens can focus manually | not yet verified |
| Night | vendor Night when offered; 2 s steady hold with a progress ring | not yet verified |
| Light trails / Silky water / Clear the crowd | gyro-aligned Motion stack (lighten / average / median) of the live stream for 4 / 3 / 5 s, plus the normal first frame; "Too shaky, try again" | not yet verified |
| Food & things | object subject, light and background coaching | not yet verified |
| Selfie | front camera, mirror-aware cues, "Arm out a little", 3 s countdown | not yet verified |

Every outcome degrades to the best capture the phone can do and says so once ("Night isn't available on this
phone, using the best we can").

**Features**

| Feature | On a phone |
|---|---|
| Live coaching: one cue (chevron, brackets, level), readiness ring, Easy (default) and Coach | Wave 1 cues checked by hand; Easy/Coach not yet verified |
| Shoot at the peak (tap waits for ready + steady, hold to shoot now), steady-shot gate, volume-down shutter | not yet verified |
| Capture quality: vendor extensions, sharpest of three, HIGH_QUALITY ISP options, JPEG 100, auto-level with Compare | not yet verified |
| After-action card: verdict, what worked, try next time, "Not helpful", Details, swipe to keep | review screen checked by hand (Wave 1); card not yet verified |
| Better crop? (save as a copy), What if (hold), Portrait look (hold, save as a copy), Compare | not yet verified |
| Re-shoot ghost (Shoot again / Use as guide) | not yet verified |
| Self-timer 3 / 10 s, Group hand-over to readiness | not yet verified |
| Guide by vibration / Guide by voice | not yet verified |
| Taste model (keep / retake / crops / followed cues) and the "Your eye" screen, one-tap reset | not yet verified |
| Pro controls: exposure slider, zoom presets, enhancement pill, grid, guidance level, detection toggles, developer overlays | Wave 1 controls checked by hand; Pro switch not yet verified |
| Shutter sound only where required, open-source licences, Send feedback, crash report screen | not yet verified |
| Privacy: no INTERNET permission (build-enforced), no backup or transfer of settings and journal | build check on every push |

**Check these first on a phone, in this order** (`adb logcat -s CameraController CaptureDiagnostics StackedCaptures`
shows most of it):

1. First launch, camera allowed: Easy shows the outcome strip, one cue and no number; logcat shows **one**
   `Bound BACK enhancement=...` line, and only one more after returning from the review.
2. "Anything", tap the shutter: it fires within 4 s when framed and steady; the saved photo is full resolution
   (`fullRes=true`) and opens upright in the gallery in all four holds.
3. The card fills in after the shot (verdict, what worked, try next time, crops); Keep, Retake and swipe-down work.
4. Pick "Night": one rebind with the Night extension (or the "Night isn't available..." note), the 2 s ring,
   then the capture.
5. "Silky water" on running water: a rebind to the 1280x960 analysis stream, a 3 s ring, a `_silk` photo plus
   the normal one, Compare shows the normal one; shake it and get "Too shaky, try again" with no card.
6. "A view" on a lens with manual focus: `Focus stack: capture ... ms` in logcat, a `_stack` photo.
7. "A person" or "Selfie" with someone in frame: the Portrait chip, "Save as copy" writes a `_portrait` photo.
8. "Save crop" writes a `_crop` photo and only then says "Saved as a copy"; "Your eye" updates after a few keeps.
9. Settings: Coach shows the score; Pro shows zoom presets and the enhancement pill; a pill pick sticks until
   the next outcome change.
10. Guide by vibration gives distinct buzzes per direction; Guide by voice speaks the cue; the shutter sound
    plays once where required (or with `shutter_sound = ALWAYS`) and never twice.

---

## Build from source

### Prerequisites

- JDK 17 or newer
- Android SDK with `platforms;android-35` and `build-tools;35.0.0` (Android Studio installs these), with
  `ANDROID_HOME` set or a `local.properties` containing `sdk.dir=/path/to/sdk`
- A physical Android device with USB debugging enabled (the emulator's camera works but is not representative)

### Commands

```bash
# run all unit tests (composition engine tests are plain JVM and take seconds)
./gradlew :composition:test :vision:testDebugUnitTest :app:testDebugUnitTest

# build the debug APK
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# install on a connected device
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk

# build a release APK + AAB (R8-shrunk; signs with the debug keystore unless the four
# CC_RELEASE_* env vars are set — see docs/RELEASE.md)
./gradlew :app:assembleRelease :app:bundleRelease
# -> app/build/outputs/apk/release/app-release.apk
# -> app/build/outputs/bundle/release/app-release.aab
```

Or open the folder in Android Studio (Ladybug or newer), let it sync, and press Run.

See [docs/RELEASE.md](docs/RELEASE.md) for cutting a signed release, the CI release job, and Play
Console upload steps; [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md) and
[docs/PLAY_LISTING.md](docs/PLAY_LISTING.md) for the Play Store listing/policy material.

---

## How it works

```
CameraX ImageAnalysis (≈640×480, keep-only-latest)
        │
        ▼
:vision  VisionPipeline ─ AdaptiveSampler (5–10 evaluations/s, backs off under load)
        ├─ ImageStatisticsComputer   luminance / edge-density grid, symmetry, horizon estimate
        ├─ ML Kit Face Detection     boxes, eyes, head yaw/roll → gaze direction
        ├─ ML Kit Pose Detection     33 body landmarks (every other frame)
        ├─ ML Kit Object Detection   prominent objects (plate, glass, product) with coarse category
        ├─ ML Kit Selfie Segmentation 32×32 subject mask (every 3rd frame, perf ladder backs off)
        └─ OrientationSensor         device roll from the rotation vector
        │   → FrameAnalysis (all geometry normalized 0..1 in the upright, mirrored preview frame)
        ▼
:composition  CompositionCoach
        ├─ SceneIntent               Settings > Shooting mode overrides detection and coaches toward it
        ├─ SceneClassifier           portrait / group / landscape / architecture / object / general
        ├─ SubjectResolver           faces + bodies + objects → subjects; background faces ignored
        ├─ 13 CompositionAnalyzers   each returns score, confidence, severity, a Recommendation, geometry
        ├─ ScoreAggregator           scene-specific weights → 0..100
        ├─ RecommendationEngine      ranks by severity, confidence, expected gain, ease; max 1–3
        ├─ CompositionOptimizer      simulates ±7 % pans / ±10 % zoom, predicts the best move
        └─ CompositionSmoother       score EMA + advice hysteresis + shoot-ready hysteresis
        │   → SmoothedComposition
        ▼
:app  CameraViewModel → CameraScreen (PreviewView + Compose overlays), ReviewScreen, SettingsScreen
```

The `:composition` module has **no Android dependencies**: every scoring rule is a pure function of
`FrameAnalysis` and is unit-tested with synthetic geometry.

---

## Project structure

```
composition/   pure Kotlin: models, analyzers, engine, optimizer, smoother, tests   (see composition/README.md)
vision/        Android library: CameraX frame → FrameAnalysis via ML Kit + statistics (see vision/README.md)
app/           Compose UI, CameraX binding, capture, review, settings, debug overlay  (see app/README.md)
.github/       CI: unit tests + debug APK artifact on every push
```

Key data models (`composition/.../model/`):

| Type | Purpose |
|---|---|
| `NormalizedPoint`, `NormalizedRect` | Geometry in 0..1 upright-preview coordinates |
| `DetectedFace`, `DetectedBody`, `DetectedSubject` | Detections and the engine's merged "subject" |
| `ImageStatistics` | Downscaled luminance/edge grids, symmetry, horizon |
| `FrameAnalysis` | The single input to the engine |
| `SceneClassification`, `SceneType`, `GuidanceLevel` | Scene intent and coaching verbosity |
| `CompositionMetric` | One analyzer's output |
| `Recommendation`, `Direction`, `ReframeVector` | Actionable, directional advice |
| `ScoreWeights` | Per-scene weight tables (tunable in one file) |
| `CompositionResult`, `SmoothedComposition`, `OptimizationResult` | Engine outputs |

---

## Composition scoring

Each analyzer scores its own concept 0..1 with a confidence, or declares itself *not applicable*
(e.g. headroom without a face). The overall score is a confidence-weighted mean over applicable
metrics using the weights for the detected scene, then mapped to 0..100 with a small penalty for
high-severity issues.

Default weights (`ScoreWeights.kt`, read as percentages before normalisation):

| Category | General | Portrait | Group | Landscape | Architecture | Object |
|---|---|---|---|---|---|---|
| Subject placement | 20 | 20 | 16 | 12 | 10 | 24 |
| Horizon / orientation | 10 | 6 | 6 | 22 | 22 | 6 |
| Headroom | 8 | 14 | 14 | – | – | – |
| Cropping | 7 | 12 | 14 | – | – | 4 |
| Looking room | 5 | 10 | 4 | – | – | – |
| Edge tension | 8 | 8 | 14 | 4 | 6 | 12 |
| Background distraction | 12 | 14 | 14 | 4 | 4 | 14 |
| Subject separation | 8 | 8 | 8 | 4 | 4 | 14 |
| Balance | 8 | 4 | 8 | 16 | 12 | 10 |
| Symmetry | 6 | 2 | 2 | 8 | 20 | 6 |
| Negative space | 3 | 1 | 1 | 6 | 4 | 6 |
| Leading lines | 3 | 1 | 1 | 12 | 10 | 2 |
| Scene specific | 2 | 2 | 2 | 12 | 8 | 2 |

The score is explicitly a heuristic: "how strong does this framing look according to the app's
rules", not an objective measure of art. Centered, symmetric compositions are rewarded rather than
pushed toward a third.

---

## Recommendation engine

1. Every analyzer that finds a problem attaches a `Recommendation` with a plain physical
   instruction, a `Direction`, and where possible a `ReframeVector` (how far the camera should move,
   as a fraction of the frame).
2. Recommendations are ranked by severity → confidence → expected score gain → ease of correction.
3. Conflicting directions are merged or dropped (never "move left" and "move right" together).
4. The `CompositionOptimizer` simulates nearby framings by shifting the detected geometry
   (±7 % pan, ±10 % zoom) and re-scoring; if a different move predicts a materially better score it
   overrides the top directional advice and supplies the expected improvement.
5. The `CompositionSmoother` only swaps the displayed advice after the new candidate has been
   top-ranked for several consecutive cycles and the current advice has been shown long enough to
   act on; opposite directions require an extra confirmation. The score is an exponential moving
   average. Shoot-ready turns on at ≥ 88 and off below 84 so it does not flicker.
6. Guidance level: **Minimal** shows one high-severity item, **Balanced** two, **Coach** three
   with the photographic reason.

---

## Performance strategy

- Analysis runs on a ~640×480 YUV frame, never the full-resolution capture stream.
- `STRATEGY_KEEP_ONLY_LATEST` plus an adaptive sampler: target 100–200 ms between evaluations,
  automatically backing off to 500 ms on slow devices and recovering when there is headroom.
- Image statistics are computed straight from the Y plane into a small grid, with no Bitmap allocation.
- Face and pose detection run concurrently on a dedicated background dispatcher; pose runs every
  other frame and can be disabled in Settings (Battery saver).
- The composition engine is pure arithmetic on a handful of boxes and runs in well under a millisecond.
- The preview and analysis share one CameraX `ViewPort`, so overlay coordinates are a plain multiply.

---

## Privacy

All analysis is on-device. The app has no network permission and never uploads frames — enforced by an
automated Gradle check (`verifyNoInternetPermission`, wired into `./gradlew check`) that fails the build
if `android.permission.INTERNET` ever reappears in the merged manifest (a transitive ML Kit dependency
adds it by default; it's explicitly stripped in `app/src/main/AndroidManifest.xml`). Photos are saved
with the normal MediaStore mechanism to `Pictures/CompositionCoach`; overlays are never baked into the
image. The full policy is in [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md), also shown in-app at
**Settings → About → Privacy policy**.

---

## Testing

```bash
./gradlew :composition:test          # scoring, analyzers, smoother, optimizer, after-action report, crops, taste model
./gradlew :vision:testDebugUnitTest  # coordinate mapping, statistics, sampling, frame stacking, gyro alignment
./gradlew :app:testDebugUnitTest     # UI logic, settings, capture maths, review flow, Robolectric smoke tests
```

Reports land in `<module>/build/reports/tests/`.

---

## Status and verification

Verified automatically on every push (see `.github/workflows/composition-coach.yml` at the repo root):

- About 700 JVM tests across the three modules (136 engine incl. a 400-frame fuzz and golden real-world
  scenarios, 122 vision, 442 app incl. Robolectric launch and end-to-end smoke tests), all passing.
- Android Lint clean on `:app` and `:vision` with `abortOnError`.
- Debug and R8-shrunk release APKs per ABI, release AAB, `apksigner verify`, and a build check that the
  merged manifest carries no INTERNET permission.

Verified by hand on a physical phone (Pixel, Android 15) before the Wave 2 integration: preview and overlay
alignment, face and object detection, capture to the gallery, review screen, shooting modes, Play Protect
install flow. Everything Wave 2 added (outcomes, holds and stacks, the after-action card, crops, the taste
model, heads-up guidance) still needs a phone; see [What's in the prototype](#whats-in-the-prototype).

Play Store steps only the account owner can do are listed in [docs/RELEASE.md](docs/RELEASE.md):
create the upload key and the four CI secrets, publish the privacy policy at a public URL, fill in the
data-safety form from [docs/PLAY_LISTING.md](docs/PLAY_LISTING.md), and upload the AAB to an internal
testing track.

## Roadmap and future enhancements

Implemented: phases 1–5 of the original plan (functional camera, live score and directional guidance,
portrait coaching including looking room, cropping and background collisions, scene classification
with dynamic weights, a geometry-based simulated framing optimizer), plus shooting modes, prominent-
object subjects, mask-driven background and separation analysis, time-based smoothing, and a
Play-Store-ready release pipeline.

Not yet implemented / next steps:

- **Subject segmentation for objects** (the current mask is a person segmenter; ML Kit Subject
  Segmentation or a LiteRT model would extend mask-based advice to products and food).
- **Real leading-line and vanishing-point detection** (Hough transform / LSD) and converging-vertical
  correction advice for architecture.
- **True framing simulation**: re-run statistics on cropped/shifted frames instead of shifting only
  detected geometry.
- **Occasional high-level critique** from a larger vision model, off the real-time loop, with an
  explicit opt-in because it would leave the device.
- **Depth** from multi-camera / ToF where available for separation.
- ~~Per-user tuning of weights~~ — done: the on-device taste model and "Your eye" (Wave 2).
- **Landscape orientation** UI (the MVP is portrait-locked).
- ~~Release signing and R8 to shrink the APK further~~ — done: `assembleRelease`/`bundleRelease` are
  R8-shrunk (`isMinifyEnabled`/`isShrinkResources`) and sign with a real upload key when the four
  `CC_RELEASE_*` secrets are configured (falling back to the debug keystore otherwise so the build never
  breaks); see [docs/RELEASE.md](docs/RELEASE.md). The remaining Play Store steps that need an account
  owner's action (screenshots, Data Safety form submission, first Play Console upload) are tracked in
  [docs/PLAY_LISTING.md](docs/PLAY_LISTING.md) and [docs/RELEASE.md](docs/RELEASE.md).
