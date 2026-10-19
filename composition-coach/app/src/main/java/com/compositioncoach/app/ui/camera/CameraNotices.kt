package com.compositioncoach.app.ui.camera

import androidx.annotation.StringRes
import com.compositioncoach.app.R
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CapturePlan
import com.compositioncoach.app.outcome.CapturePipeline
import com.compositioncoach.app.outcome.Outcome

/**
 * The once-per-pick note when an outcome cannot get what it asked for on this phone (`docs/PRODUCT.md`,
 * "Graceful degradation"), as a string resource so it can be translated. Derived from the capture plan
 * ([of]) by the same rules as `CapturePlan.degradedNote`, whose English sentence stays in the capture
 * diagnostics log.
 */
enum class DegradedNote(@StringRes val resId: Int) {
    NO_PORTRAIT_BLUR(R.string.degraded_no_portrait_blur),
    NO_NIGHT(R.string.degraded_no_night),
    NO_ENHANCEMENT(R.string.degraded_no_enhancement),
    NO_FOCUS_STACK(R.string.degraded_no_focus_stack),
    NO_MOTION(R.string.degraded_no_motion),
    ;

    companion object {
        /** The note for [plan]: a missing first-choice enhancement first, then a pipeline fallback; null when none. */
        fun of(plan: CapturePlan): DegradedNote? {
            when (plan.missingEnhancement) {
                CaptureEnhancement.BOKEH -> return NO_PORTRAIT_BLUR
                CaptureEnhancement.NIGHT -> return NO_NIGHT
                null -> Unit
                else -> return NO_ENHANCEMENT
            }
            return when {
                plan.requested == CapturePipeline.FOCUS_STACK && plan.pipeline == CapturePipeline.BURST_SHARPEST -> NO_FOCUS_STACK
                plan.requested.isMotion && plan.pipeline != plan.requested -> NO_MOTION
                else -> null
            }
        }

        private val CapturePipeline.isMotion: Boolean
            get() = this == CapturePipeline.MOTION_AVERAGE || this == CapturePipeline.MOTION_LIGHTEN || this == CapturePipeline.MOTION_MEDIAN
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
