# :vision

Turns CameraX `ImageProxy` frames + device sensors into a `FrameAnalysis` (faces, bodies, image
statistics, device orientation) for the pure-Kotlin `:composition` engine to score. This module has
no UI; it is bound and driven entirely by `:app`.

## Public API

```
FrameAnalysisSource        // contract (unchanged, see vision/.../FrameAnalysisSource.kt)
VisionPipelineFactory      // .create(context) -> FrameAnalysisSource
VisionPipeline             // real implementation
OrientationSensor          // device roll/pitch relative to gravity
FrameCoordinateMapper      // rotation/crop/mirror math (pure Kotlin)
AdaptiveSampler            // frame-rate throttle (pure Kotlin)
ImageStatisticsComputer    // luma-plane statistics (pure Kotlin)
```

## Pipeline

```
CameraX ImageAnalysis.Analyzer
        │  (camera executor, one frame at a time, KEEP_ONLY_LATEST)
        ▼
AdaptiveSampler.shouldAccept()?
        │ no  → imageProxy.close(), return immediately (no allocation, no detector work)
        │ yes
        ▼
launch on a dedicated single-thread coroutine dispatcher ("vision-pipeline")
        │
        ├─ FrameCoordinateMapper(sensorSize, rotationDegrees, cropRect, mirror)
        │        (built once per frame; all coordinate math flows through this)
        │
        ├─ ImageStatisticsComputer.compute(yPlane, mapper)   ── luma grid, edges, symmetry,
        │                                                        horizon, dominant lines
        │
        ├─ (concurrently, via kotlinx-coroutines-play-services `.await()`)
        │     ├─ FaceDetector.process(InputImage)             ── every accepted frame
        │     └─ PoseDetector.process(InputImage)              ── every *other* accepted frame
        │                                                          (previous body result reused
        │                                                           on the frames in between)
        │
        ├─ OrientationSensor.current                          ── latest roll/pitch (own sensor
        │                                                          callback thread, no camera stall)
        │
        ├─ AdaptiveSampler.onFrameProcessed(latencyMs)         ── adapts the next interval
        │
        └─ FrameAnalysis emitted to a MutableSharedFlow(replay=1, extraBufferCapacity=1,
                                                          onBufferOverflow=DROP_OLDEST)
        │
        finally: imageProxy.close()   ── always, even on exception; this is what actually
                                          releases CameraX to deliver the next frame
```

A failure in a detector (or in luma decoding) is caught per-stage; the pipeline degrades to a
stats-only or, in the worst case, a bare `FrameAnalysis` rather than dropping the frame silently or
crashing the camera.

## Coordinate conventions

Two camera-buffer-space coordinate systems are bridged to the single normalized, upright,
front-mirrored space the rest of the app uses (see `Geometry.kt` in `:composition` for that
contract) — full derivation and KDoc live in `FrameCoordinateMapper.kt`:

- **Sensor space**: raw pixel coordinates in the buffer before rotation. `imageProxy.cropRect` and
  `ImageStatisticsComputer`'s own luma sampling work here.
- **Rotated-full space**: ML Kit rotates the buffer internally (`InputImage.fromMediaImage(image,
  rotationDegrees)`) and reports boxes/landmarks already rotated, in the *uncropped* rotated
  buffer's pixel coordinates (width/height swapped from sensor space at 90°/270°).

Both converge on: subtract the (rotated) crop-rect origin → divide by the (rotated) crop size (0..1
*of the analysis crop*, matching the CameraX ViewPort-aligned preview FOV) → mirror `x' = 1 - x` for
the front camera.

`DetectedFace.leftEye`/`rightEye` are swapped post-mirror so they always mean "whichever eye is
drawn further left on screen" (ML Kit's own LEFT_EYE/RIGHT_EYE are the *subject's* anatomical
left/right). `BodyLandmarkType` LEFT_*/RIGHT_* are **not** swapped — they keep meaning the subject's
own left/right regardless of mirroring, per that enum's contract.

`headEulerY` (yaw) and `headEulerZ` (roll) from ML Kit are both flipped in sign for the front camera
(mirroring reverses the sense of both a turned head and a tilted head as seen on screen). Gaze:
`|screenYaw| < 12°` → `CENTER`, else `RIGHT`/`LEFT` by the sign of `screenYaw`.

## Device orientation sign convention

Documented and derived from first principles (three independent concrete checks: level, straight
up, straight down, plus a 90° roll case) in `OrientationSensor.kt`'s KDoc:

```
rollDegrees  = atan2(gx, gy)     // positive = horizon appears clockwise on screen
pitchDegrees = atan2(-gz, gy)    // positive = camera pointed above the horizon
```

where `(gx, gy, gz)` is "the direction of up, expressed in the device's own local axes" — read
either off the fused rotation matrix (`TYPE_ROTATION_VECTOR` / `TYPE_GAME_ROTATION_VECTOR`) or
directly off a low-pass-filtered accelerometer as a last-resort fallback. `isReliable` is false
within ~25° of straight up/down (roll is numerically undefined there) or when no sensor exists.

## Detector settings

- **Face**: `PERFORMANCE_MODE_FAST`, `LANDMARK_MODE_ALL`, `CLASSIFICATION_MODE_NONE`,
  `enableTracking()`, `minFaceSize = 0.1f`. Runs on every accepted frame. ML Kit exposes no scalar
  per-face confidence score, so `DetectedFace.confidence` is always `1f`.
- **Pose**: `com.google.mlkit.vision.pose.defaults.PoseDetectorOptions` (the "fast"/base model, not
  the separate accurate library), `STREAM_MODE`. Runs on every *other* accepted frame; the previous
  `DetectedBody` list is reused on the frames in between (`setPoseDetectionEnabled(false)` disables
  it entirely and clears reuse). Body bounds are the clamped bounding box of landmarks with
  `inFrameLikelihood > 0.5`.
- Both run **concurrently** (`async`/`await` inside `coroutineScope`) on a dedicated single-thread
  coroutine dispatcher, via `kotlinx-coroutines-play-services`'s `Task.await()`.

## Sampling policy (`AdaptiveSampler`)

Target interval 120 ms (~8 fps), clamped to 80..500 ms. A frame is accepted once at least
`currentIntervalMs` has elapsed since the last accepted frame; otherwise it's closed immediately
with zero detector work. After each accepted frame, `onFrameProcessed(latencyMs)` adapts the
interval: if latency exceeded the interval (falling behind), the interval jumps up to cover the
observed latency plus margin; if there's comfortable headroom, it eases back down 20 ms at a time
towards the configured target. `setTargetIntervalMs` (from `FrameAnalysisSource`) raises/lowers the
floor immediately.

## Image statistics (`ImageStatisticsComputer`)

Backward-samples the Y plane into a 96×96 upright intermediate buffer (via
`FrameCoordinateMapper.normalizedPointToSensor`, handling rotation/crop/mirror in one place), runs a
3×3 Sobel filter for per-pixel gradients, then block-averages down to the requested output grid
(24×24 by default). `meanLuminance`/`contrast` come from the finer 96×96 buffer. Horizontal/vertical
symmetry blend a mirror-diff score toward a neutral 0.5 based on how much edge content the frame
actually has (frame-wide mean edge density saturates confidence at 6%) — otherwise a real photo,
where edges are sparse, would wash out to ~0.5 for reasons unrelated to genuine (a)symmetry, and a
blank wall would falsely claim "perfect" symmetry. The horizon estimate and "dominant lines" are
explicitly experimental cheap heuristics (row/column gradient tracking, not a true Hough transform);
an empty list or a `null` horizon under low confidence is expected, correct behaviour.

## Performance notes

- No per-frame `Bitmap` allocation anywhere in the pipeline; the Y plane is read directly into a
  reused-shape `ByteArray` and everything downstream operates on primitive arrays.
- `analyze()` never blocks the camera-supplied executor beyond the `AdaptiveSampler` check (a few
  volatile reads/writes); all real work happens on this class's own dispatcher.
- The `ImageProxy` is closed in a `finally` block on that dispatcher — CameraX's
  `STRATEGY_KEEP_ONLY_LATEST` backpressure is gated on that `close()`, not on `analyze()` returning.

## Known limitations

- Horizon/dominant-line detection is a simple heuristic (row/column gradient tracking), not a real
  vanishing-point or Hough-transform detector; treat it as a soft hint, not ground truth.
- `OrientationSensor`'s roll/pitch derivation could not be verified against a physical device in
  this environment; it is derived and cross-checked from first principles (see its KDoc) rather than
  hardware-tested.
- Pose detection reuses the previous frame's result on off-frames rather than interpolating, which
  can look slightly stale for fast-moving subjects at low sampling rates.
- `ImageStatisticsComputer`'s symmetry confidence saturation constant (6% mean edge density) is a
  reasonable default, not empirically tuned against a corpus of real photos.

## What the `:app` integrator needs to know

- **`ImageAnalysis` config**: output format `YUV_420_888` (default), backpressure strategy
  `STRATEGY_KEEP_ONLY_LATEST`, target resolution around 640×480. Bind a CameraX `ViewPort` shared
  with the `Preview` use case so `imageProxy.cropRect` matches the preview's field of view — this
  pipeline normalizes coordinates within that crop rect, not the full sensor buffer.
- **Executor**: call `imageAnalysis.setAnalyzer(executor, frameAnalysisSource.imageAnalyzer)` with
  any executor (a single dedicated background thread is fine); this class does its own heavy work on
  its own internal dispatcher regardless, so the calling executor is only used for the (very cheap)
  throttle check.
- **Lifecycle**: call `start()` when the camera screen becomes active (registers the orientation
  sensor and (re)creates the ML Kit detectors) and `stop()` when it stops/pauses (unregisters the
  sensor and closes the detectors). `start()` after `stop()` recreates fresh detector instances.
- **`setFrontCamera(isFront)`**: call whenever the bound camera selector changes, *before* frames
  from that camera start arriving — it controls mirroring for every coordinate this module reports.
- **`setPoseDetectionEnabled(false)`**: wire to a performance/settings toggle if desired; pose is the
  more expensive detector.
- **`setTargetIntervalMs`**: optional; the sampler adapts on its own, this just moves the floor.
- Collect `frames` (a conflated `SharedFlow`, replay=1) from a `LifecycleOwner`-scoped coroutine.
