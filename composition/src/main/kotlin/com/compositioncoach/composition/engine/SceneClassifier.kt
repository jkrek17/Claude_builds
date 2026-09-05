package com.compositioncoach.composition.engine

import com.compositioncoach.composition.engine.StatsHeuristics.hasBrighterTopThanBottom
import com.compositioncoach.composition.engine.StatsHeuristics.strongHorizontalLine
import com.compositioncoach.composition.engine.StatsHeuristics.verticalLineCount
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.SceneClassification
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
 *  3. No faces, and either strong left-right mirror symmetry or several near-vertical dominant lines
 *     (building edges) -> [SceneType.ARCHITECTURE].
 *  4. No faces, and either a device/vision-reported horizon angle, or a strong roughly-horizontal
 *     dominant line together with a brighter top half (sky-over-ground) -> [SceneType.LANDSCAPE] with
 *     `hasHorizon = true`.
 *  5. No faces, and a single compact region of high edge density stands out sharply against a much
 *     quieter background -> [SceneType.OBJECT] (e.g. a product or still-life shot).
 *  6. Otherwise [SceneType.GENERAL].
 *
 * [SceneClassification.isSymmetricScene] is computed independently of the type above (a symmetric scene
 * can be architecture, but could equally be a centred portrait), so [SubjectPlacementAnalyzer] and
 * [SymmetryAnalyzer] can both consult it.
 */
object SceneClassifier {
    const val CLOSE_UP_FACE_HEIGHT = 0.35f
    const val MIN_GROUP_FACE_HEIGHT = 0.05f
    const val SYMMETRY_THRESHOLD = 0.75f
    const val MIN_VERTICAL_LINES_FOR_ARCHITECTURE = 3
    const val HORIZON_LINE_MIN_STRENGTH = 0.5f
    const val HOT_REGION_CONTRAST_FOR_OBJECT = 1.8f
    const val HOT_REGION_MIN_EDGE = 0.3f

    fun classify(frame: FrameAnalysis): SceneClassification {
        val faces = frame.faces
        val stats = frame.stats
        val isSymmetric = stats != null && stats.horizontalSymmetry >= SYMMETRY_THRESHOLD

        if (faces.size == 1) {
            return portraitOf(faces[0], isSymmetric)
        }
        if (faces.size >= 2 && faces.count { it.bounds.height >= MIN_GROUP_FACE_HEIGHT } >= 2) {
            val confidence = 0.85f * faces.map { it.confidence }.average().toFloat().coerceIn(0.3f, 1f)
            return SceneClassification(SceneType.GROUP_PORTRAIT, confidence, isSymmetricScene = isSymmetric)
        }
        if (faces.isEmpty() && stats != null) {
            architectureOf(stats, isSymmetric)?.let { return it }
            landscapeOf(stats, isSymmetric)?.let { return it }
            objectOf(stats, isSymmetric)?.let { return it }
        }
        return SceneClassification(SceneType.GENERAL, confidence = 0.4f, isSymmetricScene = isSymmetric)
    }

    private fun portraitOf(face: DetectedFace, isSymmetric: Boolean): SceneClassification {
        val closeUp = face.bounds.height > CLOSE_UP_FACE_HEIGHT
        return SceneClassification(
            type = SceneType.PORTRAIT,
            confidence = 0.9f * face.confidence.coerceIn(0.3f, 1f),
            isSymmetricScene = isSymmetric,
            isCloseUpPortrait = closeUp,
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
        val reportedHorizon = stats.estimatedHorizonAngleDegrees != null
        val lineHorizon = stats.strongHorizontalLine(HORIZON_LINE_MIN_STRENGTH) != null && stats.hasBrighterTopThanBottom()
        if (!reportedHorizon && !lineHorizon) return null
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
