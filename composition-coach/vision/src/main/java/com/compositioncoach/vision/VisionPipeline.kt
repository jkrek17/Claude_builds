package com.compositioncoach.vision

import android.content.Context
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.SubjectMask
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.Executors

/**
 * Real [FrameAnalysisSource] implementation: turns CameraX [ImageProxy] frames into [FrameAnalysis]
 * by running [ImageStatisticsComputer] and ML Kit face/pose detection, throttled by [AdaptiveSampler]
 * and enriched with [OrientationSensor] readings.
 *
 * ## Threading
 * [imageAnalyzer] is designed to be bound with `ImageAnalysis.setAnalyzer(executor, analyzer)` on
 * whatever executor the app chooses (a dedicated single-thread executor is recommended, per CameraX
 * guidance). `analyze()` itself never blocks beyond the (very cheap) throttle check in
 * [AdaptiveSampler.shouldAccept] — a rejected frame is closed immediately and synchronously; an
 * accepted frame's real work (luma stats + ML Kit detectors) is launched onto a dedicated
 * single-thread coroutine dispatcher owned by this class, and the [ImageProxy] is **always** closed
 * in a `finally` block on that dispatcher once the work (or a caught failure) completes — this is
 * what actually gates CameraX's `STRATEGY_KEEP_ONLY_LATEST` backpressure into delivering the next
 * frame, not the return from `analyze()`.
 *
 * ## Coordinate handling
 * All detector outputs are converted to the *physically* upright (not just display-upright — see
 * [UprightRotation]), crop-relative, front-camera-mirrored
 * [com.compositioncoach.composition.model.NormalizedPoint] space via a fresh [FrameCoordinateMapper]
 * built per frame from `imageProxy.cropRect`, [UprightRotation.computeUprightRotationDegrees] (fed
 * `imageProxy.imageInfo.rotationDegrees` and [OrientationSensor.deviceRotationDegrees]), and
 * [setFrontCamera]. The same corrected rotation is what's handed to ML Kit's `InputImage.fromMediaImage`,
 * so every detector already sees a physically-upright buffer — this is what keeps headroom/horizon/thirds
 * analysis correct when the phone is held sideways, even though the app (and the live preview) stay
 * portrait-locked, Pixel-Camera-style. [FrameAnalysis.frameWidth]/`frameHeight` follow suit: they're the
 * physical-upright size, so a landscape hold reports wider-than-tall. See that class's KDoc for the full
 * derivation, and [FrameAnalysis.deviceRotationDegrees] for what `:app` does with the device-rotation
 * value carried alongside this geometry.
 *
 * ## Failure handling
 * Detector failures (ML Kit task failure, a malformed plane, etc.) are caught per-detector; a frame
 * that fails detection entirely still emits an [ImageStatistics]-only (or, in the worst case, a bare)
 * [FrameAnalysis] rather than dropping the frame or crashing the camera pipeline.
 *
 * ## Detector cadence, object detection and segmentation
 * Faces run on every accepted frame; pose, objects and the selfie mask on the cadences of [DetectorSchedule] and
 * [SegmentationCadence] (reusing the previous result in between), all toggleable through [VisionFeatureToggles].
 * The clients themselves, and the full cadence table, are on [MlKitDetectors].
 *
 * ## Motion stacking
 * As a [FrameStackingSource], accepted frames can also be fed to a [FrameAccumulator] (see
 * [StackingSession]); detectors are skipped for those frames. ML Kit result mapping lives in
 * [MlKitResultMapping], shared with [StillImageAnalyzer].
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class VisionPipeline(private val context: Context) : FrameAnalysisSource, VisionFeatureToggles, FrameStackingSource {

    private val sampler = AdaptiveSampler()
    private val orientationSensor = OrientationSensor(context)
    private val segmentationCadence = SegmentationCadence()
    private val detectorSchedule = DetectorSchedule()
    private val classificationGate = FaceClassificationGate()

    @Volatile private var groupModeEnabled = false

    /** The Motion stack in progress and its progress; see [FrameStackingSource] and [StackingFeed]. */
    private val stackFeed = StackingFeed()

    /** Faces of the previous frame: re-reported while stacking, and their count drives [classificationGate]. */
    @Volatile private var lastFaces: List<DetectedFace> = emptyList()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vision-pipeline") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val pipelineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var isFrontCamera = false
    @Volatile private var poseDetectionEnabled = true
    @Volatile private var objectDetectionEnabled = true
    @Volatile private var segmentationEnabled = true

    /** Whether [start] has been called without a matching [stop] yet; makes both idempotent. */
    @Volatile private var running = false

    /** The settings-driven floor set via [setTargetIntervalMs]; combined with [tierTargetIntervalMs]. */
    @Volatile private var settingsTargetIntervalMs = AdaptiveSampler.DEFAULT_INTERVAL_MS

    /** The [PerformanceTier]-driven floor set via [setPerformanceTier]; combined with [settingsTargetIntervalMs]. */
    @Volatile private var tierTargetIntervalMs = PerformanceTier.FULL.targetIntervalMs

    /** The ML Kit clients ([MlKitDetectors]) and the luma statistics reader (pipeline thread only). */
    private val detectors = MlKitDetectors()
    private val lumaStats = LumaStatsReader()

    /** Scratch copy of a raw segmentation mask's float confidences; only touched from [dispatcher]. */
    private var maskFloatScratch = FloatArray(0)

    @Volatile private var lastBodies: List<DetectedBody> = emptyList()

    /** Reused on frames [DetectorSchedule] skips the object detector but still marks reusable. */
    @Volatile private var lastObjects: List<DetectedObject> = emptyList()

    /** Whether the previous frame reported any face or body — gates [SegmentationCadence]. */
    @Volatile private var hadSubjectLastFrame = false

    /** Reused when segmentation doesn't run this frame; a fresh [SubjectMask] on frames that do. */
    @Volatile private var lastSubjectMask: SubjectMask? = null

    private val _frames = MutableSharedFlow<FrameAnalysis>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val frames: Flow<FrameAnalysis> = _frames

    override val imageAnalyzer = ImageAnalysis.Analyzer { imageProxy -> onFrame(imageProxy) }

    private fun onFrame(imageProxy: ImageProxy) {
        if (!sampler.shouldAccept()) {
            imageProxy.close()
            return
        }
        pipelineScope.launch { processFrame(imageProxy) }
    }

    private suspend fun processFrame(imageProxy: ImageProxy) {
        val totalStart = System.nanoTime()
        var statsMs = 0L
        var faceMs = 0L
        var poseMs = 0L
        var objectMs = 0L
        var segmentationMs = 0L
        try {
            // imageInfo.rotationDegrees only turns the buffer upright for the app's fixed (portrait)
            // target rotation; UprightRotation corrects that further by the phone's actual physical
            // rotation so every detector, the mapper, and frameWidth/Height below all agree on
            // physical-up rather than display-up. See UprightRotation's KDoc for the derivation.
            val displayUprightRotation = imageProxy.imageInfo.rotationDegrees
            // Platform-listener-backed (OrientationEventListener); read synchronously, 0 if not yet known.
            val deviceRotation = orientationSensor.deviceRotationDegrees
            val rotationSource = orientationSensor.lastRotationSource
            val front = isFrontCamera
            // The correction's sign depends on which way the camera faces (the front camera's optical
            // axis is reversed, so physically rotating the phone sweeps the scene the other way inside
            // its buffer) — see UprightRotation's KDoc.
            val rotation = UprightRotation.computeUprightRotationDegrees(displayUprightRotation, deviceRotation, front)
            val crop: Rect = imageProxy.cropRect
            val mapper = FrameCoordinateMapper(
                sensorWidth = imageProxy.width,
                sensorHeight = imageProxy.height,
                rotationDegrees = rotation,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
                mirror = front,
            )

            val stats = lumaStats.read(imageProxy, mapper).also { statsMs = it.second }.first

            var faces: List<DetectedFace> = emptyList()
            var bodies: List<DetectedBody> = lastBodies
            var objects: List<DetectedObject> = emptyList()
            var subjectMask: SubjectMask? = lastSubjectMask

            // Captured before this frame's own faces/bodies are known: SegmentationCadence gates on
            // whether a subject was present *last* frame (this frame's answer isn't available until
            // face/pose detection below finishes, which is exactly the work being gated).
            val wasSubjectPresent = hadSubjectLastFrame

            // While a Motion stack is running the frame feeds the stack instead of the detectors (the last
            // detections are re-reported), so the stack gets as many frames as possible.
            val stackingSession = stackFeed.session
            if (stackingSession != null) {
                runCatching { stackFeed.feed(stackingSession, imageProxy, front) }
                    .onFailure { Log.w(TAG, "Could not add a frame to the stack", it) }
                faces = lastFaces
                objects = lastObjects
            }

            val mediaImage = if (stackingSession == null) imageProxy.image else null
            if (mediaImage != null) {
                val input = InputImage.fromMediaImage(mediaImage, rotation)
                val classify = classificationGate.update(lastFaces.size, System.nanoTime() / 1_000_000, groupModeEnabled)
                // DetectorSchedule owns pose/object cadence (including the PerformanceTier ladder);
                // called exactly once per accepted frame that reaches detection, same as the counter
                // it replaces.
                val schedule = detectorSchedule.onAcceptedFrame()
                val doPose = poseDetectionEnabled && schedule.runPose
                val doObjects = objectDetectionEnabled && schedule.runObjects
                // Short-circuits (cadence bookkeeping only advances while segmentation is enabled and
                // permitted by the current tier) so toggling either off and back on doesn't skew the
                // interval/mask-age tracked in between.
                val doSegmentation = segmentationEnabled &&
                    detectorSchedule.segmentationAllowedByTier() &&
                    segmentationCadence.onAcceptedFrame(wasSubjectPresent)

                coroutineScope {
                    val faceDeferred = async {
                        val start = System.nanoTime()
                        val result = runCatching { detectors.face(classify).process(input).await() }.getOrDefault(emptyList())
                        faceMs = (System.nanoTime() - start) / 1_000_000
                        result
                    }
                    val poseDeferred = if (doPose) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { detectors.pose().process(input).await() }.getOrNull()
                            poseMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null
                    val objectDeferred = if (doObjects) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { detectors.objects().process(input).await() }.getOrDefault(emptyList())
                            objectMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null
                    val segmentationDeferred = if (doSegmentation) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { detectors.segmenter().process(input).await() }.getOrNull()
                            segmentationMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null

                    faces = faceDeferred.await().mapIndexed { index, face -> MlKitResultMapping.mapFace(index, face, mapper) }
                    lastFaces = faces
                    if (poseDeferred != null) {
                        val pose = poseDeferred.await()
                        bodies = pose?.let { MlKitResultMapping.mapPose(it, mapper) }?.let { listOf(it) } ?: emptyList()
                        lastBodies = bodies
                    }
                    if (objectDeferred != null) {
                        val rawObjects = objectDeferred.await().map { MlKitResultMapping.mapRawObject(it, mapper) }
                        objects = ObjectMapper.map(rawObjects, faces.map { it.bounds })
                        lastObjects = objects
                    } else if (schedule.reuseStaleObjects) {
                        // DetectorSchedule already bounds how many frames old this is allowed to be
                        // (OBJECT_STALE_LIMIT_FRAMES); ML Kit's object detector carries tracking ids,
                        // so a couple of frames' reuse reads as continuity rather than a stale glitch.
                        objects = lastObjects
                    } else {
                        objects = emptyList()
                    }
                    if (segmentationDeferred != null) {
                        // A null result means either the detector genuinely failed (caught above) or
                        // process() legitimately returned nothing; either way keep the previous mask
                        // (already the default for `subjectMask`) rather than reporting a hole.
                        segmentationDeferred.await()?.let { mask ->
                            val (built, scratch) = MlKitResultMapping.subjectMask(mask, mapper, maskFloatScratch)
                            maskFloatScratch = scratch
                            subjectMask = built
                            lastSubjectMask = built
                        }
                    }
                }

                hadSubjectLastFrame = faces.isNotEmpty() || bodies.isNotEmpty()
            }

            val totalMs = (System.nanoTime() - totalStart) / 1_000_000
            sampler.onFrameProcessed(totalMs)
            segmentationCadence.recordLatency(totalMs)

            _frames.tryEmit(
                FrameAnalysis(
                    timestampNanos = imageProxy.imageInfo.timestamp,
                    frameWidth = mapper.outputWidth,
                    frameHeight = mapper.outputHeight,
                    faces = faces,
                    bodies = bodies,
                    stats = stats,
                    objects = objects,
                    subjectMask = subjectMask,
                    orientation = orientationSensor.current,
                    isFrontCamera = front,
                    analysisLatencyMs = totalMs,
                    deviceRotationDegrees = deviceRotation,
                    analysisRotationDegrees = rotation,
                    detectorTimings = mapOf(
                        "faces" to faceMs,
                        "pose" to poseMs,
                        "objects" to objectMs,
                        "segmentation" to segmentationMs,
                        "stats" to statsMs,
                        "total" to totalMs,
                        // Not a wall-time: accepted frames since the reported subjectMask was last
                        // refreshed (0 = refreshed this frame). See SegmentationCadence's KDoc.
                        "mask_age" to segmentationCadence.maskAgeFrames.toLong(),
                        // Not a wall-time either: who produced deviceRotationDegrees. 0 = platform
                        // OrientationEventListener (normal), 1 = gravity-quantizer fallback.
                        "rotation_src" to rotationSource.toLong(),
                    ),
                ),
            )
        } catch (t: Throwable) {
            // Never let a detector/decoding failure crash the camera pipeline: emit a minimal frame.
            val totalMs = (System.nanoTime() - totalStart) / 1_000_000
            sampler.onFrameProcessed(totalMs)
            runCatching {
                _frames.tryEmit(
                    FrameAnalysis(
                        timestampNanos = imageProxy.imageInfo.timestamp,
                        frameWidth = imageProxy.width,
                        frameHeight = imageProxy.height,
                        isFrontCamera = isFrontCamera,
                        orientation = orientationSensor.current,
                        analysisLatencyMs = totalMs,
                        deviceRotationDegrees = orientationSensor.deviceRotationDegrees,
                        analysisRotationDegrees = imageProxy.imageInfo.rotationDegrees,
                        detectorTimings = mapOf("rotation_src" to orientationSensor.lastRotationSource.toLong()),
                    ),
                )
            }
        } finally {
            imageProxy.close()
        }
    }

    // --- FrameAnalysisSource -----------------------------------------------------------------

    override fun setFrontCamera(isFront: Boolean) {
        isFrontCamera = isFront
    }

    /**
     * Sets the settings-driven interval floor. The *effective* floor handed to [sampler] is
     * `maxOf(this, the PerformanceTier floor from [setPerformanceTier])` — see [applyEffectiveInterval]
     * — so neither caller can accidentally speed the pipeline up past what the other requires.
     */
    override fun setTargetIntervalMs(intervalMs: Long) {
        settingsTargetIntervalMs = intervalMs
        applyEffectiveInterval()
    }

    override fun setPoseDetectionEnabled(enabled: Boolean) {
        poseDetectionEnabled = enabled
        // Mirror setSegmentationEnabled(false)'s "clear cached reuse" behaviour: leaving the last
        // pose result cached forever after the user (or a performance tier) turns pose off would
        // silently report a body that's no longer being tracked at all.
        if (!enabled) lastBodies = emptyList()
    }

    private fun applyEffectiveInterval() {
        // A Motion stack lasts a few seconds and skips the detectors, so it may run faster than either floor.
        val floor = if (stackFeed.session != null) STACKING_INTERVAL_MS else maxOf(settingsTargetIntervalMs, tierTargetIntervalMs)
        sampler.setTargetIntervalMs(floor)
    }

    // --- VisionFeatureToggles ----------------------------------------------------------------

    override fun setObjectDetectionEnabled(enabled: Boolean) {
        objectDetectionEnabled = enabled
        if (!enabled) lastObjects = emptyList()
    }

    override fun setSegmentationEnabled(enabled: Boolean) {
        segmentationEnabled = enabled
        if (!enabled) lastSubjectMask = null
    }

    /**
     * See [PerformanceTier]/[DetectorSchedule] for what each tier changes. Also clears cached
     * pose/mask reuse when the new tier forces that detector off, for the same "don't report a stale
     * result forever" reason [setPoseDetectionEnabled]/[setSegmentationEnabled] already clear it on an
     * explicit user toggle — a tier is just another source of "this detector is off right now".
     */
    override fun setPerformanceTier(tier: PerformanceTier) {
        detectorSchedule.setTier(tier)
        tierTargetIntervalMs = tier.targetIntervalMs
        applyEffectiveInterval()
        if (tier == PerformanceTier.MINIMAL) lastBodies = emptyList()
        if (tier != PerformanceTier.FULL) lastSubjectMask = null
    }

    /** See [VisionFeatureToggles.setGroupModeEnabled] and [FaceClassificationGate]. */
    override fun setGroupModeEnabled(enabled: Boolean) {
        groupModeEnabled = enabled
    }

    /**
     * Eagerly warms all four ML Kit clients so the first frame doesn't pay their construction cost.
     * This is a *best-effort* warm-up ([MlKitDetectors.warmUp]), not a precondition for the pipeline to run:
     * the clients are retried lazily on the next frame that needs them, and every per-frame call site already
     * wraps `.process(...)` (where a lazily-constructed client's `getClient()` would otherwise throw) in
     * `runCatching`. A device where ML Kit's `MlKitContext` failed to initialize (its `ContentProvider` merged
     * out, or a bundled-model native library missing for this ABI) must not crash the app on screen entry.
     *
     * Idempotent: a second call while already running (e.g. both `onScreenStarted()` and an
     * Activity-lifecycle `onScreenResumed()` calling this on the same underlying pipeline instance) is
     * a no-op rather than re-registering the sensor listener or double-warming the detectors.
     *
     * A fresh start also *fully* re-arms the pipeline rather than only reconstructing detectors: every
     * piece of cross-frame cached state ([lastBodies], [lastObjects], [lastSubjectMask],
     * [hadSubjectLastFrame], [detectorSchedule]'s frame counter, [segmentationCadence]'s degradation
     * ladder) is reset first. Without this, a pipeline stopped mid-degraded (say, backgrounded while
     * thermally throttled) would resume already degraded, and a stale cached mask/body/object list
     * from *before* the stop could be shown for up to a few frames as if it were live.
     */
    override fun start() {
        if (running) return
        running = true
        lastBodies = emptyList()
        lastObjects = emptyList()
        lastFaces = emptyList()
        lastSubjectMask = null
        hadSubjectLastFrame = false
        classificationGate.reset()
        detectorSchedule.reset()
        segmentationCadence.reset()
        // Register the rotation listener / orientation sensor first (before the slow detector warm-up) so
        // the first frames after start() already carry the current device rotation; this is lifecycle
        // bound: stop() unregisters it.
        runCatching { orientationSensor.start() }
            .onFailure { Log.e(TAG, "Orientation sensor failed to start; orientation metadata will be stale", it) }
        detectors.warmUp()
    }

    /** Idempotent counterpart to [start]; a second call while already stopped is a no-op. */
    override fun stop() {
        if (!running) return
        running = false
        orientationSensor.stop()
        detectors.closeAll()
        stackFeed.clear()
        applyEffectiveInterval()
    }

    // --- FrameStackingSource -----------------------------------------------------------------

    override val stackingProgress: StateFlow<Float?> get() = stackFeed.progress

    override val stackingStatus: StateFlow<StackingStatus?> get() = stackFeed.status

    override fun startAccumulating(
        mode: StackBlend,
        maxFrames: Int,
        aligner: FrameAligner?,
        focalToLongEdge: Float,
        maxShiftFraction: Float,
    ): Boolean {
        if (!running) return false
        stackFeed.start(StackingSession(mode, maxFrames.coerceAtLeast(1), aligner, focalToLongEdge, maxShiftFraction, orientationSensor.deviceRotationDegrees))
        applyEffectiveInterval()
        return true
    }

    override suspend fun stopAccumulating(): AccumulatedImage? {
        // Detach on the pipeline thread so no frame is half-added; the (possibly slow) result runs elsewhere.
        val session = withContext(dispatcher) { stackFeed.detach() }
        applyEffectiveInterval()
        return withContext(Dispatchers.Default) { session?.result() }
    }

    companion object {
        private const val TAG = "VisionPipeline"

        /** Sampler floor while stacking: detectors are skipped, so the pipeline can take frames faster. */
        private const val STACKING_INTERVAL_MS = 40L
    }
}
