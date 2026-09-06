package com.compositioncoach.composition.engine

import com.compositioncoach.composition.geometry.centralityDistance
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SubjectKind
import kotlin.math.sqrt

/** The result of resolving a frame's faces/bodies (and, rarely, statistics) into composable subjects. */
data class SubjectResolution(val subjects: List<DetectedSubject>, val primary: DetectedSubject?)

/**
 * Turns raw detections into the [DetectedSubject] list the rest of the engine reasons about.
 *
 * Merge rule: a face is paired with a body when the face's centre falls inside the body's box, or the
 * two boxes overlap by at least [FACE_BODY_MIN_IOU] — whichever detector's boxes are looser, this keeps
 * false near-misses (a bystander's face next to someone else's body) from merging. A face that pairs with
 * a body becomes a [SubjectKind.PERSON] subject (using the union of both boxes so cropping/edge checks
 * see the whole person); an unpaired face is a [SubjectKind.FACE] subject; a body with no matching face
 * (back turned, face out of frame) is also [SubjectKind.PERSON].
 *
 * Salience blends three cheap, order-of-magnitude signals: how much of the frame the subject fills, how
 * close to the frame centre it sits, and the detector's own confidence. It is only used to pick the
 * *primary* subject and to order [CompositionResult.subjects]; the analyzers themselves only look at the
 * primary subject plus the raw frame.
 *
 * When there are no people at all, [resolve] conservatively looks for one dominant, compact cluster of
 * edge energy (a plausible "object") and promotes it to a [SubjectKind.SALIENT_REGION] subject *only*
 * when it stands out sharply from the background (see [StatsHeuristics.compactHighEdgeRegion]) — a
 * busy-but-uniform texture (grass, gravel, a crowd of foliage) must not be mistaken for a subject, so a
 * weak or absent signal simply yields no subject at all (`primary = null`) rather than guessing.
 *
 * **Declared intent changes which of the above rules apply** (see [rulesFor]):
 *  - `AUTO` (and no other intent listed below): the rules described above, unchanged.
 *  - [SceneIntent.PORTRAIT] / [SceneIntent.GROUP_PORTRAIT]: the photographer told us there is a person, so
 *    only face-backed subjects count — a body with no matching face is dropped, and the salient-region
 *    fallback never runs (a plate on the table must never outrank a person the app is supposed to be
 *    coaching towards).
 *  - [SceneIntent.LANDSCAPE]: people are incidental to a landscape; faces and bodies are excluded from
 *    resolution entirely (`primary` is always null unless nothing else applies either, in which case it's
 *    still null — a landscape simply may not have a "subject").
 *  - [SceneIntent.ARCHITECTURE]: likewise excludes people, but the salient-region fallback still runs (a
 *    strongly distinct architectural detail is a legitimate subject).
 *  - [SceneIntent.OBJECT]: faces and bodies are ignored — a person incidentally in frame with a product
 *    shot is not the subject — and the salient-region fallback runs with a relaxed contrast requirement
 *    ([OBJECT_SALIENT_REGION_MIN_CONTRAST]) since a plate or a glass on a table is often a subtler signal
 *    than the general-purpose "object scene" heuristic is tuned for.
 */
object SubjectResolver {
    const val FACE_BODY_MIN_IOU = 0.05f

    // Salience weighting: area matters most (a large subject dominates the frame), centrality next,
    // detector confidence a smaller tie-breaker (all our detectors are already reasonably confident).
    private const val WEIGHT_AREA = 0.5f
    private const val WEIGHT_CENTRALITY = 0.35f
    private const val WEIGHT_CONFIDENCE = 0.15f

    fun resolve(frame: FrameAnalysis, intent: SceneIntent = SceneIntent.AUTO): SubjectResolution {
        val rules = rulesFor(intent)
        val usedBodies = mutableSetOf<Int>()
        val subjects = mutableListOf<DetectedSubject>()
        var nextId = 0

        if (rules.useFaces) {
            for (face in frame.faces) {
                val body = frame.bodies.firstOrNull { b ->
                    b.id !in usedBodies && (b.bounds.contains(face.center) || b.bounds.intersectionOverUnion(face.bounds) >= FACE_BODY_MIN_IOU)
                }
                if (body != null) usedBodies += body.id
                val bounds = if (body != null) unionOf(face.bounds, body.bounds) else face.bounds
                subjects += DetectedSubject(
                    id = nextId++,
                    kind = if (body != null) SubjectKind.PERSON else SubjectKind.FACE,
                    bounds = bounds,
                    face = face,
                    body = body,
                    salience = salienceOf(bounds, face.confidence),
                )
            }
        }
        if (rules.includeBodyOnlySubjects) {
            for (body in frame.bodies) {
                if (body.id in usedBodies) continue
                subjects += DetectedSubject(
                    id = nextId++,
                    kind = SubjectKind.PERSON,
                    bounds = body.bounds,
                    face = null,
                    body = body,
                    salience = salienceOf(body.bounds, body.confidence),
                )
            }
        }

        if (subjects.isEmpty() && rules.includeSalientFallback) {
            frame.stats?.let { stats ->
                val hot = with(StatsHeuristics) { stats.compactHighEdgeRegion() }
                if (hot != null && hot.contrastRatio >= rules.salientMinContrast && hot.rect.area in MIN_SALIENT_AREA..MAX_SALIENT_AREA) {
                    subjects += DetectedSubject(
                        id = nextId++,
                        kind = SubjectKind.SALIENT_REGION,
                        bounds = hot.rect,
                        salience = salienceOf(hot.rect, (hot.contrastRatio / 4f).coerceIn(0.3f, 0.9f)),
                    )
                }
            }
        }

        val primary = subjects.maxByOrNull { it.salience }
        val marked = subjects.map { it.copy(isPrimary = it.id == primary?.id) }
        return SubjectResolution(marked.sortedByDescending { it.salience }, marked.firstOrNull { it.isPrimary })
    }

    /** Which detections may become subjects, and on what terms, for one declared [SceneIntent]. */
    private data class ResolutionRules(
        val useFaces: Boolean,
        val includeBodyOnlySubjects: Boolean,
        val includeSalientFallback: Boolean,
        val salientMinContrast: Float,
    )

    private fun rulesFor(intent: SceneIntent): ResolutionRules = when (intent) {
        SceneIntent.AUTO -> ResolutionRules(
            useFaces = true, includeBodyOnlySubjects = true, includeSalientFallback = true,
            salientMinContrast = SALIENT_REGION_MIN_CONTRAST,
        )
        SceneIntent.PORTRAIT, SceneIntent.GROUP_PORTRAIT -> ResolutionRules(
            useFaces = true, includeBodyOnlySubjects = false, includeSalientFallback = false,
            salientMinContrast = SALIENT_REGION_MIN_CONTRAST,
        )
        SceneIntent.LANDSCAPE -> ResolutionRules(
            useFaces = false, includeBodyOnlySubjects = false, includeSalientFallback = false,
            salientMinContrast = SALIENT_REGION_MIN_CONTRAST,
        )
        SceneIntent.ARCHITECTURE -> ResolutionRules(
            useFaces = false, includeBodyOnlySubjects = false, includeSalientFallback = true,
            salientMinContrast = SALIENT_REGION_MIN_CONTRAST,
        )
        SceneIntent.OBJECT -> ResolutionRules(
            useFaces = false, includeBodyOnlySubjects = false, includeSalientFallback = true,
            salientMinContrast = OBJECT_SALIENT_REGION_MIN_CONTRAST,
        )
    }

    private fun salienceOf(bounds: NormalizedRect, confidence: Float): Float {
        val areaScore = sqrt(bounds.area.coerceIn(0f, 1f)) // sqrt so a subject filling 25% of the frame isn't near-maxed already
        val centralityScore = (1f - bounds.centralityDistance() / MAX_CENTRALITY_DISTANCE).coerceIn(0f, 1f)
        return (WEIGHT_AREA * areaScore + WEIGHT_CENTRALITY * centralityScore + WEIGHT_CONFIDENCE * confidence.coerceIn(0f, 1f))
            .coerceIn(0f, 1f)
    }

    private fun unionOf(a: NormalizedRect, b: NormalizedRect) = NormalizedRect(
        minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom),
    )

    // Distance from centre to a corner of the unit square, i.e. the maximum possible centrality distance.
    private val MAX_CENTRALITY_DISTANCE = sqrt(0.5f * 0.5f + 0.5f * 0.5f)

    private const val SALIENT_REGION_MIN_CONTRAST = 2.2f

    /** Relaxed hot-region contrast requirement for [SceneIntent.OBJECT]: a plate or glass on a table is
     * often a subtler signal than the general-purpose "is this frame an object scene at all" heuristic
     * ([SceneClassifier.HOT_REGION_CONTRAST_FOR_OBJECT]) is tuned for — here the photographer has already
     * told us to look for a subject, so it is worth finding a fainter one. */
    private const val OBJECT_SALIENT_REGION_MIN_CONTRAST = 1.6f

    private const val MIN_SALIENT_AREA = 0.02f
    private const val MAX_SALIENT_AREA = 0.5f
}
