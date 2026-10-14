package com.compositioncoach.vision

import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory

/**
 * A single ML Kit object-detector result, already converted to normalized upright/mirrored,
 * frame-clamped coordinates by [FrameCoordinateMapper.rotatedFullRectToNormalized]. Deliberately a
 * plain data class with no ML Kit or Android types so [ObjectMapper] is directly unit-testable with
 * synthetic input.
 */
data class RawDetectedObject(
    /** Already clamped to the frame (0..1) — [ObjectMapper.map]'s frame-coverage filter assumes it. */
    val bounds: NormalizedRect,
    val trackingId: Int? = null,
    /** The detector's top classification label text (one of ML Kit's coarse category strings), or
     * null when classification found nothing/is disabled. */
    val labelText: String? = null,
    /** Confidence of [labelText]; ignored (and [ObjectMapper] falls back to `1f`) when [labelText] is null. */
    val labelConfidence: Float? = null,
)

/**
 * Pure-Kotlin mapping and filtering for ML Kit's on-device object detector, kept out of
 * [VisionPipeline] so it can be unit-tested without Robolectric or a real `DetectedObject`.
 *
 * ML Kit's on-device object detector (unlike the face/pose detectors) is a generic "prominent
 * object" detector: it happily boxes the entire scene, or boxes the person already covered by
 * [DetectedFace]/`DetectedBody`. Two filters keep [map]'s output meaning "a *distinct* thing worth
 * composing around, that isn't already accounted for":
 *
 *  - **Frame-covering boxes are dropped**: a box covering more than [FRAME_COVERAGE_LIMIT] of the
 *    frame is "the scene", not an object in it.
 *  - **Face-overlapping boxes are dropped**: a box whose IoU with any detected face exceeds
 *    [FACE_IOU_LIMIT] is the person, who is already represented by [com.compositioncoach.composition.model.DetectedFace]
 *    /`DetectedBody` — the object detector is not a person detector and shouldn't double up with one.
 *
 * The remainder is capped to [MAX_OBJECTS], largest box (by normalized area) first, since the
 * composition engine only ever needs to reason about a small number of prominent objects.
 */
object ObjectMapper {
    /** A box covering more of the frame than this is "the scene", not a distinct object. */
    const val FRAME_COVERAGE_LIMIT = 0.85f

    /** A box overlapping a face by more than this IoU is the person, not a separate object. */
    const val FACE_IOU_LIMIT = 0.3f

    /** At most this many objects are kept, largest first. */
    const val MAX_OBJECTS = 3

    /**
     * Maps ML Kit's coarse on-device object-detection label text to [ObjectCategory]. ML Kit's
     * default classifier reports exactly one of "Fashion good", "Food", "Home good", "Place",
     * "Plant" or "Unknown" (or no label at all when classification is disabled); anything else
     * (including null/"Unknown") maps to [ObjectCategory.UNKNOWN].
     */
    fun labelToCategory(labelText: String?): ObjectCategory = when (labelText) {
        "Fashion good" -> ObjectCategory.FASHION_GOOD
        "Food" -> ObjectCategory.FOOD
        "Home good" -> ObjectCategory.HOME_GOOD
        "Place" -> ObjectCategory.PLACE
        "Plant" -> ObjectCategory.PLANT
        else -> ObjectCategory.UNKNOWN
    }

    /**
     * Filters and maps [raw] detections against [faceBounds] (already-mapped [com.compositioncoach.composition.model.DetectedFace.bounds]
     * for this same frame). Returned ids are the detector's own [RawDetectedObject.trackingId] when
     * available, else the object's rank (0-based) in the returned (post-filter, post-sort) list.
     */
    fun map(raw: List<RawDetectedObject>, faceBounds: List<NormalizedRect>): List<DetectedObject> =
        raw.asSequence()
            .filter { it.bounds.area <= FRAME_COVERAGE_LIMIT }
            .filter { obj -> faceBounds.none { face -> obj.bounds.intersectionOverUnion(face) > FACE_IOU_LIMIT } }
            .sortedByDescending { it.bounds.area }
            .take(MAX_OBJECTS)
            .mapIndexed { index, obj ->
                DetectedObject(
                    id = obj.trackingId ?: index,
                    bounds = obj.bounds,
                    category = labelToCategory(obj.labelText),
                    confidence = obj.labelConfidence ?: 1f,
                    trackingId = obj.trackingId,
                )
            }
            .toList()
}
