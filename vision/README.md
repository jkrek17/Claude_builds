# :vision

Turns CameraX `ImageProxy` frames + device sensors into a `FrameAnalysis` (faces, bodies, image
statistics, device orientation) for the pure-Kotlin `:composition` engine to score. This module has
no UI; it is bound and driven entirely by `:app`.

## Public API

```
FrameAnalysisSource        // contract (unchanged, see vision/.../FrameAnalysisSource.kt)
VisionFeatureToggles       // additional settings :app can opt into (object/segmentation toggles)
VisionPipelineFactory      // .create(context) -> FrameAnalysisSource
VisionPipeline             // real implementation (also implements VisionFeatureToggles)
OrientationSensor          // device roll/pitch relative to gravity
FrameCoordinateMapper      // rotation/crop/mirror math (pure Kotlin)
AdaptiveSampler            // frame-rate throttle (pure Kotlin)
ImageStatisticsComputer    // luma-plane statistics (pure Kotlin)
ObjectMapper               // ML Kit object-detection label mapping + filtering (pure Kotlin)
MaskDownsampler            // segmentation-mask -> SubjectMask grid resampling (pure Kotlin)
SegmentationCadence        // segmentation run cadence + thermal/perf ladder (pure Kotlin)
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
        ├─ (concurrently, via kotlinx-coroutines-play-services `.await()`, same InputImage shared)
        │     ├─ FaceDetector.process(InputImage)             ── every accepted frame
        │     ├─ PoseDetector.process(InputImage)              ── every *other* accepted frame
        │     │                                                    (previous body result reused
        │     │                                                     on the frames in between)
        │     ├─ ObjectDetector.process(InputImage)            ── every accepted frame (toggleable)
        │     │                                                    → ObjectMapper filters/maps
        │     └─ Segmenter.process(InputImage)                 ── every 3rd..6th accepted frame,
        │                                                           only when the previous frame had
        │                                                           a face/body (SegmentationCadence);
        │                                                           previous SubjectMask reused
        │                                                           otherwise → MaskDownsampler
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

`DetectedObject.bounds` uses the exact same `rotatedFullRectToNormalized` path as faces (ML Kit's
object detector reports boxes in the same rotated-full pixel space as `Face.boundingBox`).

**Segmentation mask mapping** is the one detector output that *isn't* a point/rect in rotated-full
space: ML Kit's raw-size selfie-segmentation mask is a `ByteBuffer` of per-pixel float confidences at
the *model's own* resolution (e.g. some fixed size unrelated to the sensor buffer), still covering the
same rotated-full field of view. `MaskDownsampler` walks the *output* grid backward instead of
resizing the source forward: for each of the 32×32 output cells (a few sub-samples per cell, box-
averaged), `FrameCoordinateMapper.normalizedPointToRotatedFull` (new — the public inverse of
`rotatedFullPointToNormalized`) gives the rotated-full pixel position, which is then rescaled by
`sourceSize / mapper.rotatedFullWidth|Height` to land on a source-mask pixel. This is the same
backward-sampling shape `ImageStatisticsComputer` uses for the luma plane, one coordinate space over.

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
- **Objects**: `com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions`, `STREAM_MODE`,
  `enableMultipleObjects()`, `enableClassification()` (the default on-device model — no bundled/custom
  model). Runs on every accepted frame (toggleable via `VisionFeatureToggles.setObjectDetectionEnabled`),
  concurrently with the face detector on the same `InputImage`. Raw results are filtered/mapped by
  `ObjectMapper`, which is what actually enforces the "prominent object, not the whole scene, not the
  person" contract — see its KDoc:
    - drop boxes covering more than 85% of the frame (that's the scene, not an object in it);
    - drop boxes whose IoU with any detected face exceeds 0.3 (that's the person, already covered by
      `DetectedFace`/`DetectedBody`);
    - keep at most 3, largest (by normalized area) first.
  Category comes from ML Kit's coarse label text (`"Fashion good"`/`"Food"`/`"Home good"`/`"Place"`/
  `"Plant"` → the matching `ObjectCategory`, anything else → `UNKNOWN`); confidence from that label
  when present, else `1f`. Object detection does **not** reuse stale results across frames the way
  pose does — when disabled or when nothing survives filtering, `objects` is simply empty.
- **Segmentation**: `SelfieSegmenterOptions`, `STREAM_MODE`, `enableRawSizeMask()` (skips ML Kit's own
  upscale to input size, since we downsample ourselves anyway — cheaper and we control the resampling).
  Cadence and gating are owned by `SegmentationCadence` (toggleable via
  `VisionFeatureToggles.setSegmentationEnabled`); see the dedicated section below. On a frame that
  doesn't run it, the previous `SubjectMask` is reused unchanged (not reprocessed, not interpolated).
- All four detectors run **concurrently** (`async`/`await` inside `coroutineScope`) on a dedicated
  single-thread coroutine dispatcher, via `kotlinx-coroutines-play-services`'s `Task.await()`, sharing
  one `InputImage` per frame (ML Kit permits this). Each detector's failure is caught independently —
  one detector throwing never prevents the others' results from being used.

## Segmentation cadence and the thermal/perf ladder (`SegmentationCadence`)

Selfie segmentation is the most expensive detector here, and it's a *person* segmenter — running it
on a frame with no face and no body (a still life: a pint, a plate, a product on a table) produces a
mask of nothing and wastes a frame's worth of inference. `SegmentationCadence` combines three rules
(full derivation in its KDoc):

1. **Subject gating**: segmentation is skipped entirely unless the *previous* frame had at least one
   face or body. (Necessarily the previous frame, not this one — knowing whether *this* frame has a
   subject requires running face/pose detection first, which is exactly the work being avoided.)
2. **Base cadence**: when gated open, segmentation runs every 3rd accepted frame; the previous mask
   is reused on the two frames in between.
3. **Thermal/perf ladder**: `VisionPipeline` feeds each frame's *total* analysis latency into
   `recordLatency`. 5 consecutive frames over 180ms push the cadence from every 3rd to every 6th
   accepted frame; recovery is immediate (not streak-gated) — a single frame under 120ms restores
   every-3rd. This is deliberately asymmetric: resisting a one-off latency spike (a GC pause) before
   degrading, but recovering promptly once the device genuinely has headroom again, since segmentation
   is real accuracy signal the engine goes without while backed off. This ladder is independent of (in
   addition to) `AdaptiveSampler`'s own overall frame-rate backoff — it reacts specifically to
   segmentation's own cost.

`SegmentationCadence.maskAgeFrames` (how many accepted frames old the current mask is, 0 = refreshed
this frame) is surfaced as `detectorTimings["mask_age"]` — **not** a wall-clock time like the other
`detectorTimings` entries, despite living in the same map; it lets the engine (or a debug overlay)
tell "no mask yet" apart from "mask from 40 frames ago, subject may have moved".

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

- No per-frame `Bitmap` allocation anywhere in the pipeline; the Y plane and the segmentation mask are
  both read directly into reused-shape primitive arrays (`lumaScratch`, `maskFloatScratch`).
- `analyze()` never blocks the camera-supplied executor beyond the `AdaptiveSampler` check (a few
  volatile reads/writes); all real work happens on this class's own dispatcher.
- The `ImageProxy` is closed in a `finally` block on that dispatcher — CameraX's
  `STRATEGY_KEEP_ONLY_LATEST` backpressure is gated on that `close()`, not on `analyze()` returning.
- The one deliberate exception to "no allocation": each frame that actually runs segmentation (every
  3rd-6th accepted frame, not every frame) allocates one fresh 32×32 `FloatArray` (4KB) for the
  emitted `SubjectMask`. This is necessary, not an oversight — `SubjectMask` is a value callers may
  hold across several subsequent frames (while segmentation is off-cadence, `VisionPipeline` hands out
  the *same* `SubjectMask` instance unchanged), so its backing array must never alias a scratch buffer
  this class goes on to mutate for a later frame. The raw mask's `ByteBuffer → FloatArray` conversion
  that feeds `MaskDownsampler`, by contrast, does reuse `maskFloatScratch` across refreshes, since that
  buffer is only ever read synchronously within the same call.

## Known limitations

- Horizon/dominant-line detection is a simple heuristic (row/column gradient tracking), not a real
  vanishing-point or Hough-transform detector; treat it as a soft hint, not ground truth.
- `OrientationSensor`'s roll/pitch derivation could not be verified against a physical device in
  this environment; it is derived and cross-checked from first principles (see its KDoc) rather than
  hardware-tested.
- Pose detection reuses the previous frame's result on off-frames rather than interpolating, which
  can look slightly stale for fast-moving subjects at low sampling rates. The same is true of
  `SubjectMask` on segmentation off-cadence frames, more so given the coarser 3-6 frame cadence.
- `ImageStatisticsComputer`'s symmetry confidence saturation constant (6% mean edge density) is a
  reasonable default, not empirically tuned against a corpus of real photos.
- `ObjectMapper`'s 85% frame-coverage and 0.3 face-IoU thresholds, and `SegmentationCadence`'s 180ms/
  120ms/5-frame perf-ladder constants, are reasonable defaults reasoned from the detectors' documented
  behaviour, not benchmarked against physical devices in this environment (mirroring the existing
  caveat on `ImageStatisticsComputer`'s own tuning constant above).
- The segmentation buffer's byte order is read as `ByteOrder.nativeOrder()` (matching the reference
  ML Kit integration pattern of reading it as a float view with no manual per-byte assembly); this
  could not be cross-checked against a physical device's actual buffer either.

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
  sensor and (re)creates all four ML Kit detectors — face, pose, object, segmenter) and `stop()` when
  it stops/pauses (unregisters the sensor and closes all four). `start()` after `stop()` recreates
  fresh detector instances.
- **`setFrontCamera(isFront)`**: call whenever the bound camera selector changes, *before* frames
  from that camera start arriving — it controls mirroring for every coordinate this module reports.
- **`setPoseDetectionEnabled(false)`**: wire to a performance/settings toggle if desired; pose is the
  more expensive detector.
- **`VisionFeatureToggles`**: `setObjectDetectionEnabled`/`setSegmentationEnabled` (both default
  `true`) live on this separate interface, not on `FrameAnalysisSource` itself, so they don't widen
  the contract `:app` already depends on. Opt in with a safe cast:
  `(frameAnalysisSource as? VisionFeatureToggles)?.setSegmentationEnabled(false)`. Disabling
  segmentation clears the cached mask immediately (mirrors pose's "clears reuse" behaviour) rather
  than leaving a stale one reported forever; disabling object detection just reports empty (objects
  are never reused/stale the way pose bodies and the segmentation mask are).
- **`setTargetIntervalMs`**: optional; the sampler adapts on its own, this just moves the floor.
- Collect `frames` (a conflated `SharedFlow`, replay=1) from a `LifecycleOwner`-scoped coroutine.
- **Expected added per-frame cost** (see the "Segmentation cadence" section above for why these are
  amortized, not always paid): the object detector, like face detection, runs every accepted frame —
  budget for it in the same "must fit inside `AdaptiveSampler`'s interval" way. Segmentation only runs
  every 3rd-6th accepted frame and is skipped outright on any frame with no prior subject, so its cost
  is heavily amortized; `MaskDownsampler`'s own resampling work (32×32 grid × 16 source samples/cell)
  is pure arithmetic on primitive arrays with no allocation on the hot path, sub-millisecond regardless
  of device. Neither detector's absolute on-device latency has been benchmarked in this environment —
  budget it the same way the existing face/pose costs are: verify against `detectorTimings` on a real
  device before tuning `AdaptiveSampler`'s target interval or `SegmentationCadence`'s thresholds.
