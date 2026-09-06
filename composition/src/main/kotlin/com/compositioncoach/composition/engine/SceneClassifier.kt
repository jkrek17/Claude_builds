package com.compositioncoach.composition.engine

import com.compositioncoach.composition.engine.StatsHeuristics.hasBrighterTopThanBottom
import com.compositioncoach.composition.engine.StatsHeuristics.strongHorizontalLine
import com.compositioncoach.composition.engine.StatsHeuristics.verticalLineCount
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType

/**
 * Cheap heuristic scene classifier. Runs before subject resolution so it only ever looks at raw
 * [FrameAnalysis] data (face count/size, image statistics) — never at the resolved subject list.
 *
 * Rules, in priority order:
 *  1. Exactly one face -> [SceneType.PORTRAIT]. A face whose box height exceeds [CLOSE_UP_FACE_HEIGHT]
 *     of the frame is a tight head-and-shoulders crop ([SceneClassification.isCloseUpPortrait]); several
 *     rules downstream (headroom, cropping) relax for that case.
 *  2. Two or more faces, each at least [MIN_GROUP_FACE_HEIGHT] tall (so a crowd of distant, incidental
 *     faces doesn't get treated as a deliberate group portrait) -> [SceneType.GROUP_PORTRAIT].
 *  3. No faces, and the object detector found a decently sized, confident object (see [objectSceneOf]) ->
 *     [SceneType.OBJECT] straight away, with confidence derived from the object detector's own confidence.
 *     This runs *before* the statistics-based heuristics below because a real detection is a much more
 *     direct signal than guessing from an edge-density grid — this is what turns "a pint on a pub table
 *     with a distant, incidental face" into an OBJECT scene instead of a weak GENERAL one.
 *  4. No faces, no qualifying object, and either strong left-right mirror symmetry or several
 *     near-vertical dominant lines (building edges) -> [SceneType.ARCHITECTURE].
 *  5. No faces, no qualifying object, and either a device/vision-reported horizon angle, or a strong
 *     roughly-horizontal dominant line together with a brighter top half (sky-over-ground) ->
 *     [SceneType.LANDSCAPE] with `hasHorizon = true`.
 *  6. No faces, no qualifying object, and a single compact region of high edge density stands out sharply
 *     against a much quieter background -> [SceneType.OBJECT] (e.g. a product or still-life shot), via the
 *     older statistics-only heuristic ([objectOf]) for when there is no object detector signal at all.
 *  7. Otherwise [SceneType.GENERAL].
 *
 * [SceneClassification.isSymmetricScene] is computed independently of the type above (a symmetric scene
 * can be architecture, but could equally be a centred portrait), so [SubjectPlacementAnalyzer] and
 * [SymmetryAnalyzer] can both consult it.
 *
 * **Declared intent.** When the photographer picks a shooting mode ([SceneIntent] other than `AUTO`),
 * [CompositionEngine] does not call [classify] at all — the type is simply the one they declared. It
 * still needs the same derived flags a detected scene would have (symmetry, visual horizon, close-up
 * face), so it calls [forcedClassification], which reuses the exact same per-flag heuristics ([symmetryOf],
 * [visualHorizonOf], [closeUpFaceOf]) that [classify] itself is built from below.
 */
object SceneClassifier {
    const val CLOSE_UP_FACE_HEIGHT = 0.35f
    const val MIN_GROUP_FACE_HEIGHT = 0.05f
    const val SYMMETRY_THRESHOLD = 0.75f
    const val MIN_VERTICAL_LINES_FOR_ARCHITECTURE = 3
    const val HORIZON_LINE_MIN_STRENGTH = 0.5f
    const val HOT_REGION_CONTRAST_FOR_OBJECT = 1.8f
    const val HOT_REGION_MIN_EDGE = 0.3f

    /** An object detection must clear this fraction of frame area to drive scene classification. */
    const val MIN_OBJECT_AREA_FOR_SCENE = 0.03f

    /** ...and this confidence, so a shaky low-confidence detection doesn't flip the whole scene type. */
    const val MIN_OBJECT_CONFIDENCE_FOR_SCENE = 0.5f

    fun classify(frame: FrameAnalysis): SceneClassification {
        val faces = frame.faces
        val stats = frame.stats
        val isSymmetric = symmetryOf(stats)

        if (faces.size == 1) {
            return portraitOf(faces[0], isSymmetric)
        }
        if (faces.size >= 2 && faces.count { it.bounds.height >= MIN_GROUP_FACE_HEIGHT } >= 2) {
            val confidence = 0.85f * faces.map { it.confidence }.average().toFloat().coerceIn(0.3f, 1f)
            return SceneClassification(SceneType.GROUP_PORTRAIT, confidence, isSymmetricScene = isSymmetric)
        }
        if (faces.isEmpty()) {
            objectSceneOf(frame.objects, isSymmetric)?.let { return it }
            if (stats != null) {
                architectureOf(stats, isSymmetric)?.let { return it }
                landscapeOf(stats, isSymmetric)?.let { return it }
                objectOf(stats, isSymmetric)?.let { return it }
            }
        }
        return SceneClassification(SceneType.GENERAL, confidence = 0.4f, isSymmetricScene = isSymmetric)
    }

    /**
     * A real object-detector hit big and confident enough to read as the deliberate subject of the frame
     * (a plate, a pint glass, a product) — see [SubjectResolver.MIN_OBJECT_SUBJECT_AREA] for the matching
     * subject-hood floor. Picks the single best candidate by confidence × area when several qualify.
     */
    private fun objectSceneOf(objects: List<DetectedObject>, isSymmetric: Boolean): SceneClassification? {
        val best = objects
            .filter { it.bounds.area >= MIN_OBJECT_AREA_FOR_SCENE && it.confidence >= MIN_OBJECT_CONFIDENCE_FOR_SCENE }
            .maxByOrNull { it.confidence * it.bounds.area }
            ?: return null
        return SceneClassification(SceneType.OBJECT, best.confidence.coerceIn(0.4f, 0.95f), isSymmetricScene = isSymmetric)
    }

    /**
     * Builds the [SceneClassification] for a photographer-declared [type] (any [SceneIntent] other than
     * `AUTO`): the type itself is a given, but [SceneClassification.isSymmetricScene], [SceneClassification.hasHorizon]
     * and [SceneClassification.isCloseUpPortrait] are still read off the frame, exactly as [classify] would derive
     * them, so every downstream analyzer that consults those flags behaves the same either way.
     */
    fun forcedClassification(frame: FrameAnalysis, type: SceneType): SceneClassification = SceneClassification(
        type = type,
        confidence = 1f,
        isSymmetricScene = symmetryOf(frame.stats),
        hasHorizon = visualHorizonOf(frame.stats),
        isCloseUpPortrait = closeUpFaceOf(frame.faces),
    )

    /** Strong left-right mirror symmetry, independent of what (if anything) that implies about scene type. */
    fun symmetryOf(stats: ImageStatistics?): Boolean = stats != null && stats.horizontalSymmetry >= SYMMETRY_THRESHOLD

    /** A visually plausible horizon: either a device/vision-reported angle, or a strong horizontal line over a brighter sky. */
    fun visualHorizonOf(stats: ImageStatistics?): Boolean {
        if (stats == null) return false
        val reportedHorizon = stats.estimatedHorizonAngleDegrees != null
        val lineHorizon = stats.strongHorizontalLine(HORIZON_LINE_MIN_STRENGTH) != null && stats.hasBrighterTopThanBottom()
        return reportedHorizon || lineHorizon
    }

    /** True when the largest of [faces] is tight enough to read as a head-and-shoulders close-up crop. */
    fun closeUpFaceOf(faces: List<DetectedFace>): Boolean =
        faces.maxByOrNull { it.bounds.height }?.let { it.bounds.height > CLOSE_UP_FACE_HEIGHT } ?: false

    private fun portraitOf(face: DetectedFace, isSymmetric: Boolean): SceneClassification {
        return SceneClassification(
            type = SceneType.PORTRAIT,
            confidence = 0.9f * face.confidence.coerceIn(0.3f, 1f),
            isSymmetricScene = isSymmetric,
            isCloseUpPortrait = closeUpFaceOf(listOf(face)),
        )
    }

    private fun architectureOf(stats: ImageStatistics, isSymmetric: Boolean): SceneClassification? {
        val verticals = stats.verticalLineCount()
        val strongSymmetry = stats.horizontalSymmetry >= SYMMETRY_THRESHOLD
        val strongVerticals = verticals >= MIN_VERTICAL_LINES_FOR_ARCHITECTURE
        if (!strongSymmetry && !strongVerticals) return null
        val confidence = (0.4f + 0.3f * (if (strongSymmetry) 1f else 0f) + 0.05f * verticals).coerceIn(0.4f, 0.95f)
        return SceneClassification(SceneType.ARCHITECTURE, confidence, isSymmetricScene = isSymmetric)
    }

    private fun landscapeOf(stats: ImageStatistics, isSymmetric: Boolean): SceneClassification? {
        if (!visualHorizonOf(stats)) return null
        val reportedHorizon = stats.estimatedHorizonAngleDegrees != null
        val confidence = if (reportedHorizon) 0.85f else 0.6f
        return SceneClassification(SceneType.LANDSCAPE, confidence, isSymmetricScene = isSymmetric, hasHorizon = true)
    }

    private fun objectOf(stats: ImageStatistics, isSymmetric: Boolean): SceneClassification? {
        val hot = with(StatsHeuristics) { stats.compactHighEdgeRegion() } ?: return null
        if (hot.contrastRatio < HOT_REGION_CONTRAST_FOR_OBJECT || hot.rect.area <= 0f) return null
        if (stats.regionEdgeDensity(hot.rect) < HOT_REGION_MIN_EDGE) return null
        val confidence = (hot.contrastRatio / 4f).coerceIn(0.4f, 0.85f)
        return SceneClassification(SceneType.OBJECT, confidence, isSymmetricScene = isSymmetric)
    }
}
