package com.compositioncoach.vision

import android.util.Log
import androidx.camera.core.ImageProxy
import com.compositioncoach.composition.model.ImageStatistics
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions

/**
 * The streaming ML Kit clients [VisionPipeline] runs: face (plus a second, classifying face client for group
 * scenes, since ML Kit fixes classification at client construction), pose, object detection and selfie
 * segmentation, all in `STREAM_MODE`. Clients are built lazily on first use and guarded by one lock, so a client
 * that failed to construct is simply retried on the next frame that needs it; [warmUp] builds them eagerly on
 * `start()` and [closeAll] releases them on `stop()`.
 *
 * Every detector's per-frame cadence (which accepted frames it actually runs on) is centralized in
 * [DetectorSchedule] and [SegmentationCadence], not in this class or the pipeline; see their KDoc for the full
 * baseline + [PerformanceTier] ladder. In short:
 *  - **Faces** run every accepted frame, always.
 *  - **Pose** runs every 2nd accepted frame at [PerformanceTier.FULL] (every 3rd at
 *    [PerformanceTier.REDUCED], never at [PerformanceTier.MINIMAL]); the previous
 *    [com.compositioncoach.composition.model.DetectedBody] list is reused on off-cadence frames.
 *  - The **object detector** (`STREAM_MODE`, multiple objects, classification on) runs every 2nd
 *    accepted frame, offset from pose so the two never land on the same frame (off entirely at
 *    [PerformanceTier.MINIMAL]), concurrently with the face detector on the same [InputImage]. Raw
 *    results are filtered and mapped by [ObjectMapper] (frame-covering and face-overlapping boxes
 *    dropped, top 3 by area kept); the previous filtered list is reused for up to two off-cadence
 *    frames (ML Kit's tracking ids make that read as continuity, not a glitch) before falling back to
 *    empty. All three of the above are toggleable via [VisionFeatureToggles] (implemented by
 *    [VisionPipeline] in addition to [FrameAnalysisSource]).
 *  - **Selfie segmentation** (`STREAM_MODE`, raw-size mask) runs on a cadence managed by
 *    [SegmentationCadence] *and* gated off entirely below [PerformanceTier.FULL]: every 3rd accepted
 *    frame by default, only when the *previous* frame had a face or body (it's a person segmenter —
 *    pointing it at a still life wastes a frame's worth of inference), and backed off to every 6th
 *    frame under sustained latency (the "thermal/perf ladder"; see [SegmentationCadence]'s KDoc). The
 *    previous mask is reused unchanged on off-cadence frames; [MaskDownsampler] converts a fresh raw
 *    mask to a 32x32 [SubjectMask] using the same [FrameCoordinateMapper] built for this frame's
 *    faces/objects.
 *  - **Face classification** (eyes open, smiling) runs only in group scenes: while
 *    `setGroupModeEnabled` is on, or once [FaceClassificationGate] has seen two or more faces for a second.
 *    ML Kit fixes classification at client construction, so a second face client is used for it.
 */
internal class MlKitDetectors {
    private val lock = Any()
    private var faceDetector: FaceDetector? = null

    /** Same as [faceDetector] plus eyes-open/smiling classification; built on first use (group scenes). */
    private var classifyingFaceDetector: FaceDetector? = null
    private var poseDetector: PoseDetector? = null
    private var objectDetector: ObjectDetector? = null
    private var segmenter: Segmenter? = null

    private val faceDetectorOptions: FaceDetectorOptions by lazy { faceOptions(FaceDetectorOptions.CLASSIFICATION_MODE_NONE) }

    /** ML Kit fixes classification at client construction, so group scenes use a second client. */
    private val classifyingFaceDetectorOptions: FaceDetectorOptions by lazy { faceOptions(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) }

    private fun faceOptions(classification: Int): FaceDetectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(classification)
        .enableTracking()
        .setMinFaceSize(0.1f)
        .build()

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

    fun face(classify: Boolean): FaceDetector = synchronized(lock) {
        if (classify) {
            classifyingFaceDetector ?: FaceDetection.getClient(classifyingFaceDetectorOptions).also { classifyingFaceDetector = it }
        } else {
            faceDetector ?: FaceDetection.getClient(faceDetectorOptions).also { faceDetector = it }
        }
    }

    fun pose(): PoseDetector = synchronized(lock) {
        poseDetector ?: PoseDetection.getClient(poseDetectorOptions).also { poseDetector = it }
    }

    fun objects(): ObjectDetector = synchronized(lock) {
        objectDetector ?: ObjectDetection.getClient(objectDetectorOptions).also { objectDetector = it }
    }

    fun segmenter(): Segmenter = synchronized(lock) {
        segmenter ?: Segmentation.getClient(segmenterOptions).also { segmenter = it }
    }

    /**
     * Eagerly builds the four main clients so the first frame doesn't pay their construction cost. Best effort:
     * each client is isolated ([build]) so one failing (say, the object model's native library missing on an ABI)
     * does not skip the other three, and the getters above retry lazily on the next frame that needs it.
     */
    fun warmUp() = synchronized(lock) {
        if (faceDetector == null) faceDetector = build("face") { FaceDetection.getClient(faceDetectorOptions) }
        if (poseDetector == null) poseDetector = build("pose") { PoseDetection.getClient(poseDetectorOptions) }
        if (objectDetector == null) objectDetector = build("object") { ObjectDetection.getClient(objectDetectorOptions) }
        if (segmenter == null) segmenter = build("segmentation") { Segmentation.getClient(segmenterOptions) }
    }

    /** Closes and forgets every client (the next start or frame builds them again). */
    fun closeAll() = synchronized(lock) {
        faceDetector?.close()
        faceDetector = null
        classifyingFaceDetector?.close()
        classifyingFaceDetector = null
        poseDetector?.close()
        poseDetector = null
        objectDetector?.close()
        objectDetector = null
        segmenter?.close()
        segmenter = null
    }

    /** Runs [construct], logging and swallowing any failure instead of propagating it (see [warmUp]). */
    private fun <T> build(label: String, construct: () -> T): T? =
        runCatching(construct)
            .onFailure { Log.e(TAG, "Failed to construct the $label ML Kit client; that feature will be unavailable", it) }
            .getOrNull()

    private companion object {
        /** The pipeline's tag, so `adb logcat -s VisionPipeline` still shows client failures. */
        const val TAG = "VisionPipeline"
    }
}

/**
 * The frame's luma statistics ([ImageStatisticsComputer]) from the Y plane, reusing one scratch array across
 * frames (processing is confined to the single pipeline thread) so the ~300 KB copy does not churn the GC at
 * 10 Hz. Returns the statistics (null on failure) and the time taken in ms.
 */
internal class LumaStatsReader {
    private var scratch = ByteArray(0)

    fun read(imageProxy: ImageProxy, mapper: FrameCoordinateMapper): Pair<ImageStatistics?, Long> {
        val start = System.nanoTime()
        val stats = runCatching {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer.duplicate()
            val needed = buffer.remaining()
            val bytes = scratch.takeIf { it.size >= needed } ?: ByteArray(needed).also { scratch = it }
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
}
