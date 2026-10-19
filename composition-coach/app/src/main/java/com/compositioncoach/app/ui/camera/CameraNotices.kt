package com.compositioncoach.app.ui.camera

import androidx.annotation.StringRes
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CapturePlan
import com.compositioncoach.app.camera.OutcomeCapturePlanner
import com.compositioncoach.app.camera.StackedCaptures
import com.compositioncoach.app.camera.merge.BurstMergeCapture
import com.compositioncoach.app.camera.merge.ExposureBracketCapture
import com.compositioncoach.app.camera.merge.GroupCompositeCapture
import com.compositioncoach.app.outcome.Outcome

/**
 * The notes the camera says when a photo is not getting what it asked for (`docs/PRODUCT.md`, "Graceful
 * degradation"), as string resources so they can be translated. Two sources, one list:
 *  - the capture plan ([of]): once per pick, when the phone cannot do what the outcome asked for;
 *  - a finished shot ([fromEnglish]): the English sentence a capture class put in
 *    `CaptureDiagnostics.degradedNote` (which stays English for the log line) mapped back to its entry, so the
 *    review can show it in the reader's language ("The frames didn't line up, so this is the single photo.").
 *
 * [english] is the sentence an entry stands for, matched exactly; null for entries reached another way.
 */
enum class DegradedNote(@StringRes val resId: Int, val english: String? = null) {
    NO_PORTRAIT_BLUR(R.string.degraded_no_portrait_blur),
    NO_NIGHT(R.string.degraded_no_night),
    NO_ENHANCEMENT(R.string.degraded_no_enhancement),
    NO_MOTION(R.string.degraded_no_motion, OutcomeCapturePlanner.MOTION_UNAVAILABLE),

    // Burst merge (Anything, A person, Selfie, Night stack).
    BURST_THERMAL(R.string.degraded_burst_thermal, BurstMergeCapture.NOTE_THERMAL),
    BURST_FAILED(R.string.degraded_burst_failed, BurstMergeCapture.NOTE_BURST_FAILED),
    BURST_ONE_FRAME(R.string.degraded_burst_one_frame, BurstMergeCapture.NOTE_ONE_FRAME),
    BURST_MERGE_FAILED(R.string.degraded_burst_merge_failed, BurstMergeCapture.NOTE_MERGE_FAILED),
    BURST_OUT_OF_MEMORY(R.string.degraded_burst_out_of_memory, BurstMergeCapture.NOTE_OUT_OF_MEMORY),
    /** `BurstMergeCapture.noteFewFrames(n)` carries a count, so it is matched by [FEW_FRAMES]. */
    BURST_FEW_FRAMES(R.string.degraded_burst_few_frames),

    // Exposure bracket (A view).
    BRACKET_COLLAPSED(R.string.degraded_bracket_collapsed, ExposureBracketCapture.NOTE_COLLAPSED),
    BRACKET_THERMAL(R.string.degraded_bracket_thermal, ExposureBracketCapture.NOTE_THERMAL),
    BRACKET_PARTIAL(R.string.degraded_bracket_partial, ExposureBracketCapture.NOTE_PARTIAL),
    BRACKET_MERGE_FAILED(R.string.degraded_bracket_merge_failed, ExposureBracketCapture.NOTE_MERGE_FAILED),
    BRACKET_LOW_MEMORY(R.string.degraded_bracket_low_memory, ExposureBracketCapture.NOTE_LOW_MEMORY),

    // Group composite (A group).
    GROUP_ONE_FRAME(R.string.degraded_group_one_frame, GroupCompositeCapture.NOTE_ONE_FRAME),
    GROUP_NO_FACES(R.string.degraded_group_no_faces, GroupCompositeCapture.NOTE_NO_FACES),
    GROUP_DETECTION_FAILED(R.string.degraded_group_detection_failed, GroupCompositeCapture.NOTE_DETECTION_FAILED),
    GROUP_NO_IMPROVEMENT(R.string.degraded_group_no_improvement, GroupCompositeCapture.NOTE_NO_IMPROVEMENT),
    GROUP_MOVED(R.string.degraded_group_moved, GroupCompositeCapture.NOTE_MOVED),
    GROUP_OUT_OF_MEMORY(R.string.degraded_group_out_of_memory, GroupCompositeCapture.NOTE_OUT_OF_MEMORY),
    GROUP_MERGE_FAILED(R.string.degraded_group_merge_failed, GroupCompositeCapture.NOTE_MERGE_FAILED),

    // Stacks (Motion holds, the focus stack).
    STACK_NO_FRAMES(R.string.degraded_stack_no_frames, StackedCaptures.NO_FRAMES_NOTE),
    STACK_SAVE_FAILED(R.string.degraded_stack_save_failed, StackedCaptures.STACK_SAVE_FAILED),
    FOCUS_MERGE_FAILED(R.string.degraded_focus_merge_failed, StackedCaptures.FOCUS_MERGE_FAILED),
    DETAIL_RESTORE_FAILED(R.string.degraded_detail_restore_failed, StackedCaptures.DETAIL_RESTORE_FAILED),
    ;

    companion object {
        private val FEW_FRAMES = Regex("""Only \d+ frames lined up, so the merge is light\.""")
        private val byEnglish: Map<String, DegradedNote> = entries.mapNotNull { note -> note.english?.let { it to note } }.toMap()

        /** The note for [plan]: a missing first-choice enhancement first, then a pipeline fallback; null when none. */
        fun of(plan: CapturePlan): DegradedNote? {
            when (plan.missingEnhancement) {
                CaptureEnhancement.BOKEH -> return NO_PORTRAIT_BLUR
                CaptureEnhancement.NIGHT -> return NO_NIGHT
                null -> Unit
                else -> return NO_ENHANCEMENT
            }
            return when {
                plan.requested.isMotion && plan.pipeline != plan.requested -> NO_MOTION
                else -> null
            }
        }

        /**
         * The translatable note for the English sentence a capture put in `CaptureDiagnostics.degradedNote`, or
         * null for no note or one that has no entry (the review then shows nothing rather than English).
         */
        fun fromEnglish(note: String?): DegradedNote? = when {
            note == null -> null
            FEW_FRAMES.matches(note) -> BURST_FEW_FRAMES
            else -> byEnglish[note]
        }
    }
}

/** One-shot UI events the screen reacts to but that don't belong in persistent state. */
sealed interface CameraEvent {
    data object NavigateToReview : CameraEvent
    data object ShootReadyHapticTick : CameraEvent

    /** A snackbar-style message ("Too shaky, try again"). */
    data class Notice(val notice: CameraNotice) : CameraEvent
}

/** A one-off message the camera screen shows as a snackbar ([CameraEvent.Notice]). */
enum class CameraNotice(@StringRes val resId: Int) {
    /** A Motion hold aborted on heavy motion; only the normal first frame was saved (`docs/PRODUCT.md`). */
    TOO_SHAKY(R.string.notice_too_shaky),
}

/** The live chip while a hold runs, as a translatable [ChipKey] (`OutcomeSpec.instruction` is the English copy). */
object HoldCues {
    fun chipKey(outcome: Outcome): ChipKey? = when (outcome) {
        Outcome.NIGHT -> ChipKey.HOLD_STILL
        Outcome.SILKY_WATER -> ChipKey.HOLD_STILL_3S
        Outcome.LIGHT_TRAILS -> ChipKey.HOLD_STILL_4S
        Outcome.CLEAR_CROWD -> ChipKey.HOLD_STILL_5S
        else -> null
    }

    /** The chip text for [outcome]'s hold, from the installed chip strings; [fallback] for an outcome without a key. */
    fun text(outcome: Outcome, fallback: String): String = chipKey(outcome)?.let { GuidanceFormatter.strings[it] } ?: fallback
}
