# Composition Coach

> **Also in this repository:** [`survivor/`](survivor/README.md) — **Survivor Optimizer**, a separate native Android app
> that acts as an NFL survivor-pool command center (live DraftKings lines and ESPN FPI via ESPN's public JSON, a
> season-path optimizer, double-elimination survival math, Monte Carlo, and a plain-language weekly recommendation).
> It has its own Gradle build and CI workflow and does not affect Composition Coach.

A native Android camera app that acts as a real-time photography composition coach.
It analyzes the live camera preview **on the device**, shows a 0–100 composition score,
and tells you how to physically reframe the shot before you press the shutter:

> **82** &nbsp; Move slightly right →
>
> **94 — SHOOT** &nbsp; Great framing

No cloud calls, no camera frames leave the phone.

- [Try it on your phone (no Android Studio needed)](#try-it-on-your-phone)
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
2. Under **Assets**, tap `CompositionCoach-release-arm64.apk` (use `-arm32` only on an old 32-bit phone).
3. Open the downloaded file. Allow installs from this source when Android asks.
4. On the Play Protect prompt, tap **More details**, then **Install anyway**. If that option is missing, turn
   off scanning temporarily: Play Store → profile picture → Play Protect → gear icon → "Scan apps with
   Play Protect", install, then turn it back on.
5. Open **Composition Coach**, grant camera access, point the camera at a subject.

Testers: [docs/TESTERS.md](docs/TESTERS.md) has these steps in plain language plus what feedback helps most.

**Alternative: from a specific CI run** (desktop browser, logged in; the GitHub mobile app hides artifacts):
Actions tab → latest green **Android CI** run → scroll to **Artifacts** at the bottom →
`composition-coach-debug-apk`, `composition-coach-release-apk` or `composition-coach-release-aab` (zipped).

Requirements: Android 8.0 (API 26) or newer with Google Play services. The ML Kit face model downloads
through Play services on first launch, so be online the first time. Object detection, pose and
segmentation models are bundled.

### What to try

| Do this | Expect |
|---|---|
| Frame a person with lots of empty space above their head | "Lower camera slightly" and the score rises as you do |
| Put their face right at the frame edge | "Move slightly left/right" with an arrow |
| Tilt the phone a few degrees | "Rotate slightly clockwise / counter-clockwise" and a level indicator |
| Have them look toward the near edge | "Leave more room in front of subject" |
| Stand in front of a pole or tree trunk | "Move slightly left/right to clear the background" |
| Get everything right | Green **SHOOT** state, a haptic tick |
| Tap the shutter | A normal JPEG saved to `Pictures/CompositionCoach`, then a review screen with score, strengths and what could improve |

Turn on **Developer mode** in Settings to see face/body boxes, every metric, timings and FPS.

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
./gradlew :composition:test          # scoring, analyzers, smoother, optimizer, engine (synthetic geometry)
./gradlew :vision:testDebugUnitTest  # coordinate mapping, statistics, adaptive sampling
./gradlew :app:testDebugUnitTest     # overlay mapping, guidance formatting, settings defaults
```

Reports land in `<module>/build/reports/tests/`.

---

## Status and verification

Verified automatically on every push (see `.github/workflows/android.yml`):

- 222 JVM unit tests across the three modules (74 engine incl. a 400-frame fuzz and golden real-world
  scenarios, 50 vision, 98 app), all passing.
- Android Lint clean on `:app` and `:vision` with `abortOnError`.
- Debug and R8-shrunk release APKs per ABI, release AAB, `apksigner verify`, and a build check that the
  merged manifest carries no INTERNET permission.

Verified on a physical phone (Pixel, Android 15): preview and overlay alignment, face and object
detection, capture to the gallery, review screen, shooting modes, Play Protect install flow.

Still to confirm on device after the latest changes: the sign of the level indicator when the phone is
tilted (derived from first principles, see `OrientationSensor`), segmentation latency on mid-range
phones (watch `segmentation` in the developer overlay), and threshold tuning after more real sessions.

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
- **Per-user tuning** of weights and an in-app "why" explainer in Coach mode.
- **Landscape orientation** UI (the MVP is portrait-locked).
- ~~Release signing and R8 to shrink the APK further~~ — done: `assembleRelease`/`bundleRelease` are
  R8-shrunk (`isMinifyEnabled`/`isShrinkResources`) and sign with a real upload key when the four
  `CC_RELEASE_*` secrets are configured (falling back to the debug keystore otherwise so the build never
  breaks); see [docs/RELEASE.md](docs/RELEASE.md). The remaining Play Store steps that need an account
  owner's action (screenshots, Data Safety form submission, first Play Console upload) are tracked in
  [docs/PLAY_LISTING.md](docs/PLAY_LISTING.md) and [docs/RELEASE.md](docs/RELEASE.md).
