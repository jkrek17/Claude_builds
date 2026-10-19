# Wave 3: the best photo for every scene

**Status:** integrated. The four capture classes are wired into `CapturePipeline.capture` (`MergedCaptures`), the outcome specs, planner rules, notes, settings toggle and docs follow this contract; device checks remain.

What makes SceneRight's photos different from the stock camera's: full-resolution multi-frame capture,
merged for what each scene needs (noise, dynamic range, depth of field, motion, faces), with the
exposure length chosen by the scene rather than a timer. This document is the contract between the
four coding agents and the integrator. Read `docs/PRODUCT.md` (outcomes) and `app/README.md`
(capture pipeline, design tokens) first.

## Ground rules

- **Full resolution.** New merges work on the `ImageCapture` JPEG burst (`CameraController.captureBurst`,
  `CapturedJpeg` with bytes, width, height, rotation, sensor timestamp), never on the 1280x960 analysis
  stream, except where a pipeline is explicitly about the live stream (Motion). Memory is bounded by
  processing in horizontal strips through `BitmapRegionDecoder` with weight or alignment maps computed at
  a guide scale (1/4), exactly as `FocusStacker` + `FocusMerge` do today. A 12 MP burst of eight JPEGs
  held as bytes is fine (about 60 MB); eight decoded ARGB frames are not (about 400 MB).
- **Alignment.** Gyro first (`MotionSensor.angularDeltaBetween`, `GyroAlignment.contentShift` /
  `toBuffer` / `clamp`, as in `FocusStacker.capture`), then a cheap refinement on the guide-scale luma
  (block matching, ±8 guide pixels, sum of absolute differences). Pure functions, unit-tested with
  synthetic images.
- **Exposure control.** `LensControl` now offers `lockAutoExposure()` / `unlockAutoExposure()`
  (`CONTROL_AE_LOCK`), `setManualExposure(exposureNs, iso)` / `restoreAutoExposure()`
  (`CONTROL_AE_MODE_OFF`; returns null when the lens has no manual sensor control), and the capability
  flags `supportsAeLock`, `supportsManualExposure`, `exposureTimeRangeNs`, `isoRange`. Exposure
  compensation stays `CameraController.setExposureCompensation(index)` (EV steps from
  `CaptureSessionInfo.exposureCompensationStep`). All of these are ignored under a vendor extension,
  which is why every new pipeline needs the plain session (`CapturePipeline.needsPlainSession`).
- **The first frame is always saved first.** Every multi-frame capture saves a normal photo at once (the
  companion), so an experiment never costs the shot; the merged result is a second file with a suffix
  (`_merge`, `_night`, `_hdr`, `_group`, `_silk`, `_trails`, `_clear`) saved through
  `CaptureRepository.exportProcessedOriented` with the first frame's EXIF.
- **Never block the shutter for long.** Capture bursts in under about 1.5 s (Night and Motion may hold
  longer with the progress ring). Merging may take a few seconds afterwards; it runs on
  `Dispatchers.Default` and the review screen shows the companion until the merge lands.
- **Thermal and memory.** `PerformanceTier` from `ThermalPolicy` scales frame counts down
  (FULL: all, REDUCED: half, MINIMAL: the first frame only). `OutOfMemoryError` during a merge is
  caught: keep the companion, add a `degradedNote`.
- **Diagnostics.** Fill `CaptureDiagnostics.pipeline`, `framesStacked`, `degradedNote` and, where it
  exists, `exposureTimeNs` / `iso` per frame in the log line, so field testers can see what ran.
- **Tests.** Every merge is a pure function over `IntArray` ARGB (or luma) planes with a JVM unit test
  on synthetic images: known inputs, asserted outputs (noise drops, motion is rejected, a highlight is
  recovered, a face comes from the right frame). Capture orchestration is tested with fakes where the
  existing tests do it (`OutcomeCaptureTest`, `OutcomeBindingRulesTest`, `CameraUiStateWave2Test`).
- **Ownership is strict.** Each agent edits only the files it owns and adds new files only under its
  own paths. Shared files (`CapturePipeline.kt`, `Outcome.kt`, `OutcomeCapturePlanner.kt`,
  `CameraNotices.kt`, strings, docs other than this one) are the integrator's. Each agent exposes one
  capture class with the signature below and the integrator wires it into `CapturePipeline.capture`.

## The capture-class contract

```kotlin
// One per pipeline, in app/src/main/java/com/compositioncoach/app/camera/<area>/.
class XxxCapture(private val repository: CaptureRepository, private val motionSensor: MotionSensor) {
    class Result(
        val mergedUri: Uri?,          // null when only the companion survived
        val firstUri: Uri,            // the companion, saved first
        val first: CapturedJpeg,      // for EXIF-based diagnostics
        val framesUsed: Int,
        val captureMs: Long,
        val totalMs: Long,
        val note: String?,            // one plain sentence when degraded (English; the integrator maps it)
    )
    suspend fun capture(
        controller: CameraController, imageCapture: ImageCapture, lens: LensControl?, mirror: Boolean,
        tier: PerformanceTier, /* pipeline-specific parameters */
    ): Result?                        // null = cannot run here, caller falls back
}
```

Pure merge math lives in `vision/src/main/java/com/compositioncoach/vision/merge/` (JVM-testable, no
Android classes beyond `IntArray` maths). Strip orchestration (region decoding, bitmap assembly) lives
beside the capture class in `app`.

## Agent A: burst merge (Anything, Food & things, A person, Selfie, Night)

Owns: `app/.../camera/merge/BurstMergeCapture.kt`, `app/.../camera/merge/StripMerger.kt`,
`vision/.../merge/BurstAlign.kt`, `vision/.../merge/MotionRejectedAverage.kt`,
`vision/.../merge/DetailTransfer.kt`, `vision/.../merge/ToneLift.kt`, and their tests.

- **Capture:** N full-resolution frames back to back (`captureBurst`), N = 5 for BURST_MERGE, 8 for
  NIGHT_STACK (tier-scaled). Lock auto-exposure for the burst. For BURST_MERGE bias exposure a third
  of a stop under (compensation index) when the lens's step allows, and lift it back in the merge.
  For NIGHT_STACK, when the lens supports manual exposure, set the longest shutter the gyro's
  steadiness allows (STEADY: up to 1/8 s, SETTLING: 1/15 s, else auto) at the ISO auto-exposure was
  using divided by the shutter gain; otherwise auto with AE lock.
- **Align:** gyro shift per frame, refined by guide-scale block matching against the sharpest frame
  (the reference, chosen by `SharpnessScorer`).
- **Merge:** per-pixel weighted average on the guide: weight = 1 for the reference; for others
  `exp(-(|luma - ref|)^2 / (2 sigma^2))` with sigma from the local noise estimate, so moving subjects
  are rejected and static areas average. Weights upsampled bilinearly to full resolution per strip.
- **Detail:** add back high-frequency detail from the reference (reference minus its 3x3 blur, scaled
  by 0.5) so the average does not go soft. Night: gentle tone lift (shadows up, highlights held).
- **Output:** `_merge` / `_night`; `framesUsed`; note when fewer than 3 frames merged.
- **Tests:** noise variance of a merged synthetic flat field drops by about 1/N; a moving square is
  absent from the average; detail transfer preserves an edge; alignment recovers a known shift.

## Agent B: adaptive Motion holds (Silky water, Light trails, Clear the crowd)

Owns: `app/.../camera/MotionCapture.kt`, `app/.../camera/StackedCaptures.kt`,
`vision/.../FrameAccumulator.kt`, `vision/.../FrameStackingSource.kt`,
`vision/.../VisionPipelineStacking.kt` (or wherever `FrameStackingSource` is implemented in `vision`),
`vision/.../merge/Convergence.kt`, `app/.../camera/merge/StaticRestore.kt`, and their tests. May change
`CameraController.MOTION_ANALYSIS_RESOLUTION` and the resolution hint it passes (that constant only).

- **Fix the early cutoff.** The hold ends on time or convergence, never because a frame count computed
  at an assumed 12 fps filled. Frame caps come from the measured rate (`FrameFpsTracker`) or a stride.
- **Exposure.** Lock auto-exposure for the hold (`LensControl.lockAutoExposure`), after a 300 ms settle.
  Light trails: bias exposure one stop under before the lock so lights do not clip; where the lens
  supports manual exposure, use the longest shutter the stream's frame interval allows (frame interval
  minus 5 ms) so trails are continuous rather than dotted. Restore everything in `finally`.
- **Adaptive length.** The spec's `holdSeconds` is the minimum. Keep going until the effect converges
  or a cap is reached: Silky water (AVERAGE) converges when the running average changes by less than
  1 % mean absolute luma between the stack at N/2 and N frames, cap 6 s; Light trails (LIGHTEN)
  converges when a new frame raises fewer than 0.2 % of pixels, or more than 2 % of pixels are clipped,
  cap 10 s; Clear the crowd (MEDIAN) converges when the median changes in fewer than 0.5 % of pixels
  between N/2 and N, cap 15 s (use the ring with a stride so the memory budget covers it). Progress
  for the ring = max(time / minimum, convergence estimate). Too-shaky rule unchanged.
- **Resolution.** Raise the Motion analysis resolution to the largest 4:3 size the device sustains at
  8 fps or more (try 1920x1440, fall back to 1280x960 when the measured fps drops below 8 during the
  first second), and restore detail from the full-resolution companion still in the static regions: a
  temporal-variance mask at guide scale (low variance = static) selects the companion's pixels,
  upscaled stack elsewhere, feathered. Silky water then has sharp rocks and smooth water.
- **Tests:** a synthetic stream that converges early stops early; one that never converges stops at
  the cap; the early-cutoff bug cannot recur (30 fps stream, 4 s minimum, hold lasts at least 4 s);
  the static mask picks the still where variance is zero.

## Agent C: exposure bracket and near focus (A view, Food & things)

Owns: `app/.../camera/FocusStacker.kt`, `app/.../camera/FocusMerge.kt`,
`app/.../camera/merge/ExposureBracketCapture.kt`, `vision/.../merge/ExposureFusion.kt`, and their
tests.

- **Bracket:** three full-resolution frames at -2, 0, +2 EV through exposure compensation (clamped to
  the lens's range; when the range is narrower, use its ends), auto-exposure locked between frames,
  compensation restored in `finally`. Gyro plus guide-scale alignment as in Agent A.
- **Fuse:** Mertens exposure fusion: per-pixel weights from contrast (Laplacian magnitude), saturation
  and well-exposedness (Gaussian around mid-grey), normalised across frames, blended with a 4-level
  Laplacian pyramid at guide scale and the full-resolution strips blended with the upsampled weight
  maps (the same strip pattern as `FocusMerge`). No tone mapping beyond that; no halos.
- **With the focus stack (A view):** when the lens can focus-stack, capture the three focus distances
  at 0 EV plus the -2 and +2 EV frames at the far distance (five frames, about 1.5 s), focus-merge the
  three 0 EV frames, then fuse that result with the two brackets. When it cannot, bracket alone.
- **Food & things:** `FocusStacker` takes the focus distances as a parameter; for FOOD use the near set
  (minimum, 0.8 x minimum, 0.6 x minimum diopters) and lock white balance to the auto result for the
  stack (`CONTROL_AWB_LOCK` through a small addition to `LensControl` is the integrator's; until then
  skip the lock).
- **Output:** `_hdr`; `framesUsed`; note when the bracket collapsed to one frame.
- **Tests:** a synthetic scene with a clipped sky in the 0 EV frame and detail in -2 EV recovers that
  detail; a dark corner recovers from +2 EV; weights sum to one; a flat field fuses to itself.

## Agent D: faces (A group, A person, Selfie)

Owns: `app/.../camera/merge/GroupCompositeCapture.kt`, `app/.../camera/BackgroundSeparator.kt`,
`vision/.../merge/FaceComposite.kt`, `vision/.../merge/MaskRefine.kt`, `vision/.../StillImageAnalyzer.kt`
(face detection on burst frames only; keep its public API), and their tests.

- **Group composite:** a burst of five full-resolution frames (tier-scaled), auto-exposure locked.
  Detect faces on each frame at guide scale (ML Kit face detection with classification on, as
  `StillImageAnalyzer` already does; `eyesOpenProbability` and bounds per face). Match faces across
  frames by centre distance. Choose the base frame (most eyes open, then sharpest), then for every face
  whose best frame differs (both eyes open with probability above 0.6 and a higher sharpness score over
  the face box), copy that face box, grown by 25 % and feathered over 8 % of its width, from its best
  frame into the base after alignment. Never composite when alignment residual over the face box is
  large (sum of absolute differences above a threshold): keep the base.
- **Mask refinement (A person, Selfie):** `BackgroundSeparator`'s blur mask is refined with a guided
  filter on luma (radius 8 at guide scale) so hair and shoulders get a soft, accurate edge; the
  selfie blur radius is 60 % of the rear one; the blur is depth-free, so keep the current fallback.
- **Output:** `_group`; `framesUsed`; note when no face improved and the base was kept.
- **Tests:** a synthetic pair where face A is sharper in frame 1 and face B in frame 2 yields a
  composite with both sharp; a face with closed eyes in the base is replaced; a large residual keeps
  the base; the guided filter sharpens a blurred mask edge toward a luma edge.

## The integrator (after all four land)

- Wire each capture class into `CapturePipeline.capture` behind its `CapturePipeline` value; map each
  `note` to a `DegradedNote` string resource.
- Update `Outcome` specs: ANYTHING, FOOD, PORTRAIT, SELFIE -> BURST_MERGE (PORTRAIT and SELFIE keep
  BOKEH_MASK); GROUP -> GROUP_COMPOSITE; LANDSCAPE -> EXPOSURE_BRACKET (which includes the focus stack
  when available); NIGHT -> NIGHT_STACK when the NIGHT extension is absent, else the extension.
- Planner rules: merges under an extension become SINGLE (not reported); NIGHT_STACK is the fallback
  for a missing NIGHT extension and is reported as the *better* path, not a degradation.
- `ThermalPolicy` tier is passed through `ShotRequest`.
- Settings: a "Multi-frame merges" toggle under Pro (default on) that drops every merge to its single
  or burst fallback, for testers comparing against the stock camera.
- Docs: `PRODUCT.md` outcomes table, `app/README.md` pipeline section, `TESTERS.md` what to compare.
- Full gate, device checklist, push.
