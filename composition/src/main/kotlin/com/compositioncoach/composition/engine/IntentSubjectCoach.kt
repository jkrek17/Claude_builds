package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.Severity

/**
 * Decides whether a declared [SceneIntent] needs a subject that simply is not in frame yet, and if so
 * builds the single "find your subject" [Recommendation] the engine shows instead of ordinary scoring.
 *
 * Some shooting modes are meaningless without a subject: PORTRAIT and GROUP_PORTRAIT are about a person
 * (or several) who has to actually be in frame; OBJECT is about one clear item. Rather than score a frame
 * that has nothing to score — and rather than let 12 unrelated analyzers argue about a photo of a wall —
 * the engine short-circuits straight to coaching the photographer *toward* the declared intent. LANDSCAPE
 * and ARCHITECTURE never need this: they are meaningful with `primary == null` (see [SubjectResolver]).
 *
 * The three recommendations below are deliberately plain, mode-level messages ("looking for X"), not
 * ordinary framing advice: [CompositionSmoother] shows them immediately, with no confirmation delay,
 * because they describe what the coach is currently doing rather than a correction to weigh against
 * competing advice.
 */
object IntentSubjectCoach {
    /** A [SceneIntent.GROUP_PORTRAIT] needs at least this many usable faces to stop "awaiting". */
    const val MIN_GROUP_FACES = 2

    /**
     * @param rawFrame the frame *before* [SubjectFilter] dropped incidental background faces — used only
     *   to tell "no face detected at all" from "a face was there but too small/too far", so the PORTRAIT
     *   message can ask the photographer to move closer instead of vaguely to point the camera somewhere.
     * @param resolution the subject resolution computed under this same [intent] (see [SubjectResolver]).
     * @return the "find subject" recommendation, or null when the intent already has what it needs
     *   (including every case where [intent] is AUTO, LANDSCAPE or ARCHITECTURE).
     */
    fun awaitingSubjectRecommendation(intent: SceneIntent, rawFrame: FrameAnalysis, resolution: SubjectResolution): Recommendation? =
        when (intent) {
            SceneIntent.PORTRAIT ->
                if (resolution.primary?.face == null) portraitRecommendation(hadAnyDetectedFace = rawFrame.faces.isNotEmpty()) else null
            SceneIntent.GROUP_PORTRAIT -> {
                val usableFaces = resolution.subjects.count { it.face != null }
                if (usableFaces < MIN_GROUP_FACES) groupRecommendation(foundExactlyOne = usableFaces == 1) else null
            }
            SceneIntent.OBJECT -> if (resolution.primary == null) objectRecommendation() else null
            SceneIntent.AUTO, SceneIntent.LANDSCAPE, SceneIntent.ARCHITECTURE -> null
        }

    private fun portraitRecommendation(hadAnyDetectedFace: Boolean) = Recommendation(
        id = "intent.portrait.find_subject",
        category = MetricCategory.SCENE_SPECIFIC,
        priority = Priority.MEDIUM,
        confidence = 1f,
        severity = Severity.MEDIUM,
        title = "Looking for a face",
        instruction = if (hadAnyDetectedFace) "Move closer to your subject" else "Point the camera at your subject",
        reason = "Portrait mode needs a face to compose around.",
        direction = if (hadAnyDetectedFace) Direction.CLOSER else Direction.NONE,
    )

    private fun groupRecommendation(foundExactlyOne: Boolean) = Recommendation(
        id = "intent.group.find_subjects",
        category = MetricCategory.SCENE_SPECIFIC,
        priority = Priority.MEDIUM,
        confidence = 1f,
        severity = Severity.MEDIUM,
        title = "Looking for faces",
        instruction = if (foundExactlyOne) "Step back to fit everyone in" else "Point the camera at the group",
        reason = "Group mode needs at least two faces to compose around.",
        direction = if (foundExactlyOne) Direction.BACK else Direction.NONE,
    )

    private fun objectRecommendation() = Recommendation(
        id = "intent.object.find_subject",
        category = MetricCategory.SCENE_SPECIFIC,
        priority = Priority.MEDIUM,
        confidence = 1f,
        severity = Severity.MEDIUM,
        title = "Looking for your subject",
        instruction = "Move closer to your subject",
        reason = "Object mode needs one clear subject to compose around.",
        direction = Direction.CLOSER,
    )
}
