# Composition Coach

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

You do not need Android Studio to install and test the app. Every push to GitHub builds a debug
APK with GitHub Actions.

1. Open the repository on GitHub and click the **Actions** tab.
2. Open the most recent green **Android CI** run.
3. Scroll to **Artifacts** and download `composition-coach-debug-apk` (a `.zip` containing `app-debug.apk`).
4. Copy `app-debug.apk` to your phone (email it to yourself, AirDrop-equivalent, USB, Google Drive, …) and tap it.
5. Android will ask you to allow installs from this source (Chrome, Files, Gmail, …). Allow it once and install.
6. Open **Composition Coach**, grant the camera permission, point the camera at a person.

Requirements: Android 8.0 (API 26) or newer, Google Play Services (for ML Kit face detection; the
face model downloads automatically on first launch, so be online the first time).

If you would rather run from a computer, see [Build from source](#build-from-source).

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
```

Or open the folder in Android Studio (Ladybug or newer), let it sync, and press Run.

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
        └─ OrientationSensor         device roll from the rotation vector
        │   → FrameAnalysis (all geometry normalized 0..1 in the upright, mirrored preview frame)
        ▼
:composition  CompositionCoach
        ├─ SceneClassifier           portrait / group / landscape / architecture / object / general
        ├─ SubjectResolver           merges faces + bodies into subjects, picks the primary one
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

All analysis is on-device. The app has no network permission and never uploads frames. Photos are
saved with the normal MediaStore mechanism to `Pictures/CompositionCoach`; overlays are never baked
into the image.

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

What has been verified in this environment:

- All three modules compile together and the debug APK assembles.
- 88 JVM unit tests pass: composition engine (45), vision math and statistics (21), app state and mapping (22).

What has **not** been verified yet, because no physical device was available where this was built:

- Live camera behaviour on a real phone: preview alignment of the overlays, ML Kit model download, capture
  and gallery save. The code paths are complete, not mocked, but they need a real-device run.
- The sign of the device-orientation roll. It is derived from first principles and cross-checked in the
  KDoc of `OrientationSensor`, but tilt the phone and confirm the level indicator and the
  "rotate clockwise / counter-clockwise" advice move the right way. Developer mode shows the raw angle.
- Threshold tuning. Headroom, edge and background thresholds are sensible starting points; expect to
  adjust them after a few real sessions using the developer overlay.

## Roadmap and future enhancements

Implemented in this MVP: phases 1–5 of the plan at an initial, heuristic level (functional camera,
live score and directional guidance, portrait coaching including looking room, cropping and
background collisions, scene classification with dynamic weights, and a geometry-based simulated
framing optimizer).

Not yet implemented / next steps:

- **Subject segmentation** (ML Kit Selfie Segmentation or a LiteRT model) for far better
  subject-separation and background-distraction estimates than luminance/edge heuristics.
- **Generic object detection** so non-human subjects (food, products, pets) get real subject boxes
  instead of a salient-region guess.
- **Real leading-line and vanishing-point detection** (Hough transform / LSD) and converging-vertical
  correction advice for architecture.
- **True framing simulation**: re-run statistics on cropped/shifted frames instead of shifting only
  detected geometry.
- **Occasional high-level critique** from a larger vision model, off the real-time loop, with an
  explicit opt-in because it would leave the device.
- **Depth** from multi-camera / ToF where available for separation.
- **Per-user tuning** of weights and an in-app "why" explainer in Coach mode.
- **Landscape orientation** UI (the MVP is portrait-locked).
- **Release signing, ABI splits and R8** to shrink the APK (the debug build bundles all ABIs).
