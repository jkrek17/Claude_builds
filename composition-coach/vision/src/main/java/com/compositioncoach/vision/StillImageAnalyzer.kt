package com.compositioncoach.vision

import android.graphics.Bitmap
import android.util.Log
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.SubjectMask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The engine's view of a *captured* photo (the review screen's after-action card, crops and what-if), as
 * opposed to the last preview frame. Runs ML Kit face detection (accurate mode, landmarks, classification so
 * [DetectedFace.eyesOpenProbability] is filled), object detection, selfie segmentation (only when a face was
 * found: it is a person segmenter) and [ImageStatisticsComputer], on a copy downscaled to at most
 * [MAX_LONG_EDGE_PX] on the long edge. Geometry is normalized, so the working size does not change any result.
 *
 * ## Orientation and mirroring
 * The bitmap must already be upright (`CaptureRepository.decodeForAnalysis` applies the EXIF orientation), so
 * the mapper's rotation is 0 and [FrameAnalysis.deviceRotationDegrees] is 0. A front-camera photo is saved
 * mirrored (the selfie the user framed), so the decoded bitmap *is* what the user saw: by default it is not
 * mirrored again ([alreadyMirrored] = true), and ML Kit's yaw and eye sides then come out exactly as the live
 * path reports them for the mirrored preview. Pass `alreadyMirrored = false` for a raw (un-mirrored) front
 * image to get the live path's mirror applied here instead.
 *
 * ## Detectors and failure
 * Each call builds its own single-image-mode clients and closes them afterwards (no shared state with the
 * streaming [VisionPipeline], so the two can run at once). Never throws (except cancellation): a detector that
 * fails contributes nothing, and if everything fails the result is a stats-only (or bare) frame.
 */
class StillImageAnalyzer {

    /** Analyses [bitmap] on [Dispatchers.Default]; see the class doc. */
    suspend fun analyze(bitmap: Bitmap, isFrontCamera: Boolean, alreadyMirrored: Boolean = true): FrameAnalysis =
        withContext(Dispatchers.Default) {
            val start = System.nanoTime()
            val working = runCatching { workingCopy(bitmap) }.getOrNull()
                ?: return@withContext FrameAnalysis(0L, bitmap.width, bitmap.height, isFrontCamera = isFrontCamera)
            try {
                val w = working.width
                val h = working.height
                val mapper = FrameCoordinateMapper(w, h, 0, 0, 0, w, h, mirror = isFrontCamera && !alreadyMirrored)
                val timings = LinkedHashMap<String, Long>()
                val stats = timed(timings, "stats") { runCatching { statistics(working, mapper) }.getOrNull() }
                val detections = runCatching { detect(working, mapper, timings) }
                    .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Still detection failed", it) }
                    .getOrNull()
                timings["total"] = (System.nanoTime() - start) / 1_000_000
                FrameAnalysis(
                    timestampNanos = System.nanoTime(),
                    frameWidth = w,
                    frameHeight = h,
                    faces = detections?.faces.orEmpty(),
                    stats = stats,
                    objects = detections?.objects.orEmpty(),
                    subjectMask = detections?.mask,
                    isFrontCamera = isFrontCamera,
                    analysisLatencyMs = timings["total"] ?: 0L,
                    detectorTimings = timings,
                    deviceRotationDegrees = 0,
                    analysisRotationDegrees = 0,
                )
            } finally {
                if (working !== bitmap) working.recycle()
            }
        }

    /**
     * Face detection only, for the frames of a group-photo burst (`GroupCompositeCapture`): one detector (the
     * same accurate-mode, classification-on options as [analyze], so [DetectedFace.eyesOpenProbability] is
     * filled) shared by all [bitmaps], which must be upright software bitmaps; each is run at its own size
     * (callers pass guide-scale decodes, at most [MAX_LONG_EDGE_PX] on the long edge is plenty). Bounds are
     * normalized in the given bitmap, unmirrored. The result has one entry per bitmap: the faces, or null
     * when the detector failed for that frame (so "no faces" and "could not look" stay distinguishable).
     * Never throws except on cancellation; the detector is closed afterwards.
     */
    suspend fun detectFaces(bitmaps: List<Bitmap>): List<List<DetectedFace>?> = withContext(Dispatchers.Default) {
        val client = runCatching { FaceDetection.getClient(FACE_OPTIONS) }.getOrNull()
            ?: return@withContext bitmaps.map { null }
        try {
            bitmaps.map { bitmap ->
                runCatching {
                    val mapper = FrameCoordinateMapper(bitmap.width, bitmap.height, 0, 0, 0, bitmap.width, bitmap.height, mirror = false)
                    client.process(InputImage.fromBitmap(bitmap, 0)).await().mapIndexed { i, face -> MlKitResultMapping.mapFace(i, face, mapper) }
                }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Burst face detection failed", it) }
                    .getOrNull()
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private class Detections(val faces: List<DetectedFace>, val objects: List<DetectedObject>, val mask: SubjectMask?)

    private suspend fun detect(bitmap: Bitmap, mapper: FrameCoordinateMapper, timings: MutableMap<String, Long>): Detections {
        val input = InputImage.fromBitmap(bitmap, 0)
        val faceClient = FaceDetection.getClient(FACE_OPTIONS)
        val objectClient = ObjectDetection.getClient(OBJECT_OPTIONS)
        try {
            return coroutineScope {
                val facesAsync = async {
                    timed(timings, "faces") { runCatching { faceClient.process(input).await() }.getOrDefault(emptyList()) }
                }
                val objectsAsync = async {
                    timed(timings, "objects") { runCatching { objectClient.process(input).await() }.getOrDefault(emptyList()) }
                }
                val faces = facesAsync.await().mapIndexed { i, face -> MlKitResultMapping.mapFace(i, face, mapper) }
                val raw = objectsAsync.await().map { MlKitResultMapping.mapRawObject(it, mapper) }
                val objects = ObjectMapper.map(raw, faces.map { it.bounds })
                val mask = if (faces.isNotEmpty()) timed(timings, "segmentation") { segment(input, mapper) } else null
                Detections(faces, objects, mask)
            }
        } finally {
            runCatching { faceClient.close() }
            runCatching { objectClient.close() }
        }
    }

    private suspend fun segment(input: InputImage, mapper: FrameCoordinateMapper): SubjectMask? {
        val client = Segmentation.getClient(SEGMENTER_OPTIONS)
        return try {
            val mask = runCatching { client.process(input).await() }.getOrNull() ?: return null
            MlKitResultMapping.subjectMask(mask, mapper, FloatArray(0)).first
        } finally {
            runCatching { client.close() }
        }
    }

    private fun statistics(bitmap: Bitmap, mapper: FrameCoordinateMapper): ImageStatistics {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = lumaFromArgb(pixels)
        return ImageStatisticsComputer.compute(luma, w, h, rowStride = w, pixelStride = 1, mapper = mapper)
    }

    private inline fun <T> timed(timings: MutableMap<String, Long>, key: String, block: () -> T): T {
        val start = System.nanoTime()
        return block().also { timings[key] = (System.nanoTime() - start) / 1_000_000 }
    }

    companion object {
        private const val TAG = "StillImageAnalyzer"

        /** Working size: ML Kit's models run far below this, and the statistics grid is 96 px square. */
        const val MAX_LONG_EDGE_PX = 1024

        /** BT.601 luma (0..255) of opaque ARGB pixels, as the Y plane the live path reads. */
        fun lumaFromArgb(pixels: IntArray): ByteArray = ByteArray(pixels.size) { i ->
            val c = pixels[i]
            ((77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8).toByte()
        }

        /** Target size for a bitmap of [width] x [height] so its long edge is at most [maxLongEdge] (never upscales). */
        fun workingSize(width: Int, height: Int, maxLongEdge: Int = MAX_LONG_EDGE_PX): Pair<Int, Int> {
            val long = maxOf(width, height)
            if (long <= maxLongEdge) return width to height
            val scale = maxLongEdge.toFloat() / long
            return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
        }

        /** A software ARGB_8888 copy at the working size (ML Kit cannot read hardware bitmaps). */
        private fun workingCopy(bitmap: Bitmap): Bitmap {
            val (w, h) = workingSize(bitmap.width, bitmap.height)
            // HARDWARE (and any non-ARGB_8888) bitmaps are copied: ML Kit and getPixels need software pixels.
            val software = if (bitmap.config != Bitmap.Config.ARGB_8888) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            if (w == software.width && h == software.height) return software
            val scaled = Bitmap.createScaledBitmap(software, w, h, true)
            if (software !== bitmap && software !== scaled) software.recycle()
            return scaled
        }

        private val FACE_OPTIONS: FaceDetectorOptions by lazy {
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.05f)
                .build()
        }

        private val OBJECT_OPTIONS: ObjectDetectorOptions by lazy {
            ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptionsBase.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
        }

        private val SEGMENTER_OPTIONS: SelfieSegmenterOptions by lazy {
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .enableRawSizeMask()
                .build()
        }
    }
}
