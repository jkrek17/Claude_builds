package com.compositioncoach.vision

import android.content.Context
import android.graphics.Rect
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.BodyLandmarkType
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SubjectMask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

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
 * All detector outputs are converted to the upright, crop-relative, front-camera-mirrored
 * [com.compositioncoach.composition.model.NormalizedPoint] space via a fresh [FrameCoordinateMapper]
 * built per frame from `imageProxy.cropRect`, `imageProxy.imageInfo.rotationDegrees`, and
 * [setFrontCamera]. See that class's KDoc for the full derivation.
 *
 * ## Failure handling
 * Detector failures (ML Kit task failure, a malformed plane, etc.) are caught per-detector; a frame
 * that fails detection entirely still emits an [ImageStatistics]-only (or, in the worst case, a bare)
 * [FrameAnalysis] rather than dropping the frame or crashing the camera pipeline.
 *
 * ## Object detection and segmentation
 * Two more ML Kit detectors run alongside face/pose, both toggleable via [VisionFeatureToggles]
 * (implemented by this class in addition to [FrameAnalysisSource]):
 *  - The **object detector** (`STREAM_MODE`, multiple objects, classification on) runs every accepted
 *    frame, concurrently with the face detector on the same [InputImage]. Raw results are filtered and
 *    mapped by [ObjectMapper] (frame-covering and face-overlapping boxes dropped, top 3 by area kept).
 *  - **Selfie segmentation** (`STREAM_MODE`, raw-size mask) runs on a cadence managed by
 *    [SegmentationCadence]: every 3rd accepted frame by default, only when the *previous* frame had a
 *    face or body (it's a person segmenter — pointing it at a still life wastes a frame's worth of
 *    inference), and backed off to every 6th frame under sustained latency (the "thermal/perf ladder";
 *    see [SegmentationCadence]'s KDoc). The previous mask is reused unchanged on off-cadence frames;
 *    [MaskDownsampler] converts a fresh raw mask to a 32x32 [SubjectMask] using the same
 *    [FrameCoordinateMapper] built for this frame's faces/objects.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
class VisionPipeline(private val context: Context) : FrameAnalysisSource, VisionFeatureToggles {

    private val sampler = AdaptiveSampler()
    private val orientationSensor = OrientationSensor(context)
    private val segmentationCadence = SegmentationCadence()

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "vision-pipeline") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val pipelineScope = CoroutineScope(SupervisorJob() + dispatcher)

    @Volatile private var isFrontCamera = false
    @Volatile private var poseDetectionEnabled = true
    @Volatile private var objectDetectionEnabled = true
    @Volatile private var segmentationEnabled = true

    private val detectorLock = Any()
    private var faceDetector: FaceDetector? = null
    private var poseDetector: PoseDetector? = null
    private var objectDetector: ObjectDetector? = null
    private var segmenter: Segmenter? = null

    /** Scratch copy of the Y plane; only touched from [dispatcher]. */
    private var lumaScratch = ByteArray(0)

    /** Scratch copy of a raw segmentation mask's float confidences; only touched from [dispatcher]. */
    private var maskFloatScratch = FloatArray(0)

    private val poseFrameCounter = AtomicInteger(0)
    @Volatile private var lastBodies: List<DetectedBody> = emptyList()

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

    private val faceDetectorOptions: FaceDetectorOptions by lazy {
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .setMinFaceSize(0.1f)
            .build()
    }

    private val poseDetectorOptions: PoseDetectorOptions by lazy {
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    }

    private val objectDetectorOptions: ObjectDetectorOptions by lazy {
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptionsBase.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    }

    private val segmenterOptions: SelfieSegmenterOptions by lazy {
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build()
    }

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
            val rotation = imageProxy.imageInfo.rotationDegrees
            val crop: Rect = imageProxy.cropRect
            val front = isFrontCamera
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

            val stats = computeStats(imageProxy, mapper).also { statsMs = it.second }.first

            var faces: List<DetectedFace> = emptyList()
            var bodies: List<DetectedBody> = lastBodies
            var objects: List<DetectedObject> = emptyList()
            var subjectMask: SubjectMask? = lastSubjectMask

            // Captured before this frame's own faces/bodies are known: SegmentationCadence gates on
            // whether a subject was present *last* frame (this frame's answer isn't available until
            // face/pose detection below finishes, which is exactly the work being gated).
            val wasSubjectPresent = hadSubjectLastFrame

            val mediaImage = imageProxy.image
            if (mediaImage != null) {
                val input = InputImage.fromMediaImage(mediaImage, rotation)
                val doPose = poseDetectionEnabled && (poseFrameCounter.getAndIncrement() % 2 == 0)
                val doObjects = objectDetectionEnabled
                // Short-circuits (cadence bookkeeping only advances while segmentation is enabled) so
                // toggling it off and back on doesn't skew the interval/mask-age tracked in between.
                val doSegmentation = segmentationEnabled && segmentationCadence.onAcceptedFrame(wasSubjectPresent)

                coroutineScope {
                    val faceDeferred = async {
                        val start = System.nanoTime()
                        val result = runCatching { getFaceDetector().process(input).await() }.getOrDefault(emptyList())
                        faceMs = (System.nanoTime() - start) / 1_000_000
                        result
                    }
                    val poseDeferred = if (doPose) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { getPoseDetector().process(input).await() }.getOrNull()
                            poseMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null
                    val objectDeferred = if (doObjects) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { getObjectDetector().process(input).await() }.getOrDefault(emptyList())
                            objectMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null
                    val segmentationDeferred = if (doSegmentation) {
                        async {
                            val start = System.nanoTime()
                            val result = runCatching { getSegmenter().process(input).await() }.getOrNull()
                            segmentationMs = (System.nanoTime() - start) / 1_000_000
                            result
                        }
                    } else null

                    faces = faceDeferred.await().mapIndexed { index, face -> mapFace(index, face, mapper) }
                    if (poseDeferred != null) {
                        val pose = poseDeferred.await()
                        bodies = pose?.let { mapPose(it, mapper) }?.let { listOf(it) } ?: emptyList()
                        lastBodies = bodies
                    }
                    if (objectDeferred != null) {
                        val rawObjects = objectDeferred.await().map { mapRawObject(it, mapper) }
                        objects = ObjectMapper.map(rawObjects, faces.map { it.bounds })
                    }
                    if (segmentationDeferred != null) {
                        // A null result means either the detector genuinely failed (caught above) or
                        // process() legitimately returned nothing; either way keep the previous mask
                        // (already the default for `subjectMask`) rather than reporting a hole.
                        segmentationDeferred.await()?.let { mask ->
                            subjectMask = buildSubjectMask(mask, mapper)
                            lastSubjectMask = subjectMask
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
                    ),
                )
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun computeStats(imageProxy: ImageProxy, mapper: FrameCoordinateMapper): Pair<ImageStatistics?, Long> {
        val start = System.nanoTime()
        val stats = runCatching {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer.duplicate()
            // Reuse one scratch array across frames (processing is confined to the single pipeline thread)
            // so the ~300 KB Y plane copy does not churn the GC at 10 Hz.
            val needed = buffer.remaining()
            val bytes = lumaScratch.takeIf { it.size >= needed } ?: ByteArray(needed).also { lumaScratch = it }
            buffer.get(bytes, 0, needed)
            ImageStatisticsComputer.compute(
                luma = bytes,
                width = imageProxy.width,
                height = imageProxy.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
                mapper = mapper,
            )
        }.getOrNull()
        val ms = (System.nanoTime() - start) / 1_000_000
        return stats to ms
    }

    // --- ML Kit result mapping -------------------------------------------------------------

    /**
     * Maps an ML Kit [Face] to [DetectedFace].
     *
     * ML Kit's `headEulerAngleY` (yaw) is documented as positive when the face is rotated toward the
     * *camera's* right (i.e. the right side of the un-mirrored, rotated image ML Kit sees). The
     * contract for [DetectedFace.headEulerY] is screen-space: positive = face turned toward the
     * screen's right. For the rear camera the preview isn't mirrored, so camera-right == screen-right
     * and the sign is unchanged; for the front camera the preview *is* mirrored, so camera-right
     * becomes screen-left and the sign must flip. The same mirroring flips the sense of `headEulerZ`
     * (roll) and swaps which detected eye lands on the screen-left vs screen-right side (ML Kit's
     * LEFT_EYE/RIGHT_EYE are the *subject's own* anatomical left/right, which is why they need
     * swapping post-mirror to keep [DetectedFace.leftEye] meaning "whichever eye is drawn further
     * left on screen" for downstream composition logic). Pose landmarks below intentionally do *not*
     * get this swap: [BodyLandmarkType] documents that LEFT/RIGHT there are the subject's own
     * left/right regardless of mirroring.
     */
    private fun mapFace(id: Int, face: Face, mapper: FrameCoordinateMapper): DetectedFace {
        val box = face.boundingBox
        val bounds: NormalizedRect = mapper
            .rotatedFullRectToNormalized(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            .clampToFrame()

        fun landmark(type: Int) = face.getLandmark(type)?.position?.let { mapper.rotatedFullPointToNormalized(it.x, it.y) }

        var leftEye = landmark(FaceLandmark.LEFT_EYE)
        var rightEye = landmark(FaceLandmark.RIGHT_EYE)
        val noseBase = landmark(FaceLandmark.NOSE_BASE)
        if (mapper.mirror) {
            val tmp = leftEye
            leftEye = rightEye
            rightEye = tmp
        }

        val rawYaw = face.headEulerAngleY
        val rawRoll = face.headEulerAngleZ
        val screenYaw = if (mapper.mirror) -rawYaw else rawYaw
        val screenRoll = if (mapper.mirror) -rawRoll else rawRoll

        val gaze = when {
            abs(screenYaw) < GAZE_CENTER_THRESHOLD_DEGREES -> GazeDirection.CENTER
            screenYaw > 0f -> GazeDirection.RIGHT
            else -> GazeDirection.LEFT
        }

        return DetectedFace(
            id = face.trackingId ?: id,
            bounds = bounds,
            leftEye = leftEye,
            rightEye = rightEye,
            noseBase = noseBase,
            headEulerY = screenYaw,
            headEulerZ = screenRoll,
            gaze = gaze,
            // ML Kit's face detector does not expose a scalar detection-confidence score; 1f is the
            // documented default for "no confidence information available" per DetectedFace's KDoc.
            confidence = 1f,
            smilingProbability = face.smilingProbability,
        )
    }

    private fun mapPose(pose: Pose, mapper: FrameCoordinateMapper): DetectedBody? {
        val landmarks = LinkedHashMap<BodyLandmarkType, BodyLandmark>()
        for (lm in pose.allPoseLandmarks) {
            val type = POSE_LANDMARK_MAP[lm.landmarkType] ?: continue
            val pos = mapper.rotatedFullPointToNormalized(lm.position.x, lm.position.y)
            landmarks[type] = BodyLandmark(type, pos, lm.inFrameLikelihood)
        }
        if (landmarks.isEmpty()) return null
        val visible = landmarks.values.filter { it.inFrameLikelihood > 0.5f }
        if (visible.isEmpty()) return null

        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (v in visible) {
            left = minOf(left, v.position.x)
            right = maxOf(right, v.position.x)
            top = minOf(top, v.position.y)
            bottom = maxOf(bottom, v.position.y)
        }
        val bounds = NormalizedRect(left, top, right, bottom).clampToFrame()
        return DetectedBody(
            id = 0,
            bounds = bounds,
            landmarks = landmarks,
            confidence = visible.size.toFloat() / landmarks.size,
        )
    }

    /**
     * Maps an ML Kit object-detection result (rotated-full pixel space, per [ObjectDetector]'s
     * contract — same space as [Face.getBoundingBox]) to a [RawDetectedObject] in normalized,
     * frame-clamped coordinates. Filtering/categorization happens separately in [ObjectMapper] so it
     * stays unit-testable without a real ML Kit `DetectedObject`.
     */
    private fun mapRawObject(obj: com.google.mlkit.vision.objects.DetectedObject, mapper: FrameCoordinateMapper): RawDetectedObject {
        val box = obj.boundingBox
        val bounds = mapper
            .rotatedFullRectToNormalized(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            .clampToFrame()
        val topLabel = obj.labels.maxByOrNull { it.confidence }
        return RawDetectedObject(
            bounds = bounds,
            trackingId = obj.trackingId,
            labelText = topLabel?.text,
            labelConfidence = topLabel?.confidence,
        )
    }

    /**
     * Converts an ML Kit raw-size [SegmentationMask] (a direct `ByteBuffer` of per-pixel float
     * confidences, row-major, in rotated-full pixel space) into a 32x32 [SubjectMask] via
     * [MaskDownsampler]. The `ByteBuffer` -> `FloatArray` copy reuses [maskFloatScratch] (this
     * happens only on the frames [SegmentationCadence] actually runs the segmenter, i.e. every 3rd-6th
     * accepted frame, not every frame) but the *output* grid is always a fresh array: the returned
     * [SubjectMask] is a stable value that [lastSubjectMask] and downstream callers may hold across
     * several subsequent frames (while segmentation is off-cadence), so it must never alias a buffer
     * this class goes on to mutate for a later frame.
     */
    private fun buildSubjectMask(mask: SegmentationMask, mapper: FrameCoordinateMapper): SubjectMask {
        val width = mask.width
        val height = mask.height
        val needed = width * height
        val floats = maskFloatScratch.takeIf { it.size >= needed } ?: FloatArray(needed).also { maskFloatScratch = it }
        val buffer = mask.buffer
        buffer.rewind()
        // ML Kit's segmentation buffers are populated by native code in the platform's native byte
        // order; this matches the reference integration pattern (read via a float view, no manual
        // per-byte assembly).
        buffer.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats, 0, needed)
        val grid = MaskDownsampler.downsample(
            source = floats,
            sourceWidth = width,
            sourceHeight = height,
            mapper = mapper,
            gridWidth = SUBJECT_MASK_GRID_SIZE,
            gridHeight = SUBJECT_MASK_GRID_SIZE,
            out = FloatArray(SUBJECT_MASK_GRID_SIZE * SUBJECT_MASK_GRID_SIZE),
        )
        return SubjectMask(SUBJECT_MASK_GRID_SIZE, SUBJECT_MASK_GRID_SIZE, grid)
    }

    private fun getFaceDetector(): FaceDetector = synchronized(detectorLock) {
        faceDetector ?: FaceDetection.getClient(faceDetectorOptions).also { faceDetector = it }
    }

    private fun getPoseDetector(): PoseDetector = synchronized(detectorLock) {
        poseDetector ?: PoseDetection.getClient(poseDetectorOptions).also { poseDetector = it }
    }

    private fun getObjectDetector(): ObjectDetector = synchronized(detectorLock) {
        objectDetector ?: ObjectDetection.getClient(objectDetectorOptions).also { objectDetector = it }
    }

    private fun getSegmenter(): Segmenter = synchronized(detectorLock) {
        segmenter ?: Segmentation.getClient(segmenterOptions).also { segmenter = it }
    }

    // --- FrameAnalysisSource -----------------------------------------------------------------

    override fun setFrontCamera(isFront: Boolean) {
        isFrontCamera = isFront
    }

    override fun setTargetIntervalMs(intervalMs: Long) {
        sampler.setTargetIntervalMs(intervalMs)
    }

    override fun setPoseDetectionEnabled(enabled: Boolean) {
        poseDetectionEnabled = enabled
    }

    // --- VisionFeatureToggles ----------------------------------------------------------------

    override fun setObjectDetectionEnabled(enabled: Boolean) {
        objectDetectionEnabled = enabled
    }

    override fun setSegmentationEnabled(enabled: Boolean) {
        segmentationEnabled = enabled
        if (!enabled) lastSubjectMask = null
    }

    override fun start() {
        synchronized(detectorLock) {
            if (faceDetector == null) faceDetector = FaceDetection.getClient(faceDetectorOptions)
            if (poseDetector == null) poseDetector = PoseDetection.getClient(poseDetectorOptions)
            if (objectDetector == null) objectDetector = ObjectDetection.getClient(objectDetectorOptions)
            if (segmenter == null) segmenter = Segmentation.getClient(segmenterOptions)
        }
        orientationSensor.start()
    }

    override fun stop() {
        orientationSensor.stop()
        synchronized(detectorLock) {
            faceDetector?.close()
            faceDetector = null
            poseDetector?.close()
            poseDetector = null
            objectDetector?.close()
            objectDetector = null
            segmenter?.close()
            segmenter = null
        }
    }

    companion object {
        private const val GAZE_CENTER_THRESHOLD_DEGREES = 12f
        private const val SUBJECT_MASK_GRID_SIZE = MaskDownsampler.DEFAULT_GRID_SIZE

        private val POSE_LANDMARK_MAP: Map<Int, BodyLandmarkType> = mapOf(
            PoseLandmark.NOSE to BodyLandmarkType.NOSE,
            PoseLandmark.LEFT_EYE to BodyLandmarkType.LEFT_EYE,
            PoseLandmark.RIGHT_EYE to BodyLandmarkType.RIGHT_EYE,
            PoseLandmark.LEFT_EAR to BodyLandmarkType.LEFT_EAR,
            PoseLandmark.RIGHT_EAR to BodyLandmarkType.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER to BodyLandmarkType.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER to BodyLandmarkType.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW to BodyLandmarkType.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to BodyLandmarkType.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST to BodyLandmarkType.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST to BodyLandmarkType.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP to BodyLandmarkType.LEFT_HIP,
            PoseLandmark.RIGHT_HIP to BodyLandmarkType.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE to BodyLandmarkType.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE to BodyLandmarkType.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE to BodyLandmarkType.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE to BodyLandmarkType.RIGHT_ANKLE,
            PoseLandmark.LEFT_HEEL to BodyLandmarkType.LEFT_HEEL,
            PoseLandmark.RIGHT_HEEL to BodyLandmarkType.RIGHT_HEEL,
            PoseLandmark.LEFT_FOOT_INDEX to BodyLandmarkType.LEFT_FOOT_INDEX,
            PoseLandmark.RIGHT_FOOT_INDEX to BodyLandmarkType.RIGHT_FOOT_INDEX,
        )
    }
}
