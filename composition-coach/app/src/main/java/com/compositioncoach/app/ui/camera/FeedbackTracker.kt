package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * Derives the taste model's learning signals ([Feedback]) from what the user does. Pure: time comes in as an
 * argument, so every rule is unit-tested (`FeedbackTrackerTest`). [CameraViewModel] feeds it each UI frame
 * and the review decisions; the events go to `AdaptiveWeights` and the photo journal.
 *
 * Rules:
 *  * **FOLLOWED(category)**: the headline cue's id disappears from the active cues within [followWindowMs]
 *    (6 s) of first being shown as the headline, while a subject stays in frame. A cue that is merely demoted
 *    (still active) or that disappears because the subject left does not count; one that lingers longer than
 *    the window does not count either (the user was not acting on it).
 *  * **IGNORED_AND_KEPT / IGNORED_AND_RETAKEN(category)**: a cue was the headline at capture and the user then
 *    kept / retook the photo ([reviewDecision]).
 *  * **NOT_HELPFUL(category)**: "Not helpful" on the after-action card, category of its Try-next-time ([notHelpful]).
 *  * **CROP_CHOSEN(aspect, tighter)**: the user saved a suggested crop ([cropChosen]); `tighter` when the crop
 *    keeps less than [TIGHT_CROP_AREA] of the photo.
 */
class FeedbackTracker(private val followWindowMs: Long = FOLLOW_WINDOW_MS) {

    /** A live cue: the recommendation id (stable across frames) and what it is about. */
    data class Cue(val id: String, val category: MetricCategory)

    private data class Tracked(val cue: Cue, val shownAtMs: Long)

    private var tracked: Tracked? = null
    private val followedSinceCapture = mutableListOf<MetricCategory>()

    /** The cue currently showing as the headline (what a capture right now would "ignore"). */
    var headline: Cue? = null
        private set

    /**
     * One UI frame at [nowMs]: [headlineCue] is the headline cue on screen, [activeIds] every active cue's id,
     * [subjectPresent] whether a subject is in frame. Returns FOLLOWED when a tracked cue just resolved.
     */
    fun onFrame(nowMs: Long, headlineCue: Cue?, activeIds: Set<String>, subjectPresent: Boolean): Feedback? {
        headline = headlineCue
        if (!subjectPresent) {
            tracked = null
            return null
        }
        var event: Feedback? = null
        val current = tracked
        if (current != null && current.cue.id !in activeIds) {
            if (nowMs - current.shownAtMs <= followWindowMs) {
                event = Feedback.FOLLOWED(current.cue.category)
                followedSinceCapture += current.cue.category
            }
            tracked = null
        }
        if (headlineCue != null && tracked?.cue?.id != headlineCue.id) {
            tracked = Tracked(headlineCue, nowMs)
        } else if (headlineCue == null && tracked != null && tracked!!.cue.id in activeIds) {
            // The headline went away but the cue is still active somewhere below: it is no longer being shown.
            tracked = null
        }
        return event
    }

    /** Convenience over [onFrame] for the engine's smoothed output. */
    fun onComposition(nowMs: Long, composition: SmoothedComposition): Feedback? {
        val headlineRec = composition.primaryRecommendation
        return onFrame(
            nowMs = nowMs,
            headlineCue = headlineRec?.toCue(),
            activeIds = composition.activeRecommendations.mapTo(HashSet()) { it.id },
            subjectPresent = composition.primarySubject != null && !composition.awaitingSubject,
        )
    }

    /**
     * Called at capture: returns the cue showing (to remember with the photo) and the categories followed since
     * the previous capture (for the journal), and starts a new run-up.
     */
    fun onCapture(): Pair<Cue?, List<MetricCategory>> {
        val followed = followedSinceCapture.toList()
        followedSinceCapture.clear()
        tracked = null
        return headline to followed
    }

    /** The coach was reset (lens switch, outcome change): nothing in flight can be credited. */
    fun reset() {
        tracked = null
        headline = null
    }

    companion object {
        const val FOLLOW_WINDOW_MS = 6_000L

        /** A chosen crop keeping less than this fraction of the photo's area counts as "tighter". */
        const val TIGHT_CROP_AREA = 0.6f

        fun Recommendation.toCue(): Cue = Cue(id, category)

        /** Keep / Retake after a capture taken with [cueCategoryAtCapture] showing (null = no cue, no event). */
        fun reviewDecision(cueCategoryAtCapture: MetricCategory?, kept: Boolean): Feedback? = cueCategoryAtCapture?.let {
            if (kept) Feedback.IGNORED_AND_KEPT(it) else Feedback.IGNORED_AND_RETAKEN(it)
        }

        /** "Not helpful" on [report]'s Try-next-time; null when the card had none. */
        fun notHelpful(report: AfterActionReport?): Feedback? = report?.tryNextTime?.category?.let { Feedback.NOT_HELPFUL(it) }

        fun cropChosen(crop: CropSuggestion): Feedback = Feedback.CROP_CHOSEN(crop.aspect, isTighter(crop.rect))

        fun isTighter(rect: NormalizedRect): Boolean = rect.area < TIGHT_CROP_AREA
    }
}
