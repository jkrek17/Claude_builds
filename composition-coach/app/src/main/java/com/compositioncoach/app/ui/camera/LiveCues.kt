package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.composition.engine.GroupReadiness
import com.compositioncoach.composition.model.GroupStatus
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectKind

/** The self-timer's cycle and labels (`docs/WAVE2.md` section 10). Pure, `LiveCuesTest`. */
object TimerLogic {
    /** Off, 3 s, 10 s, in the order the clock icon cycles them. */
    val OPTIONS = listOf(0, 3, 10)

    /** Selfie's default countdown when the shutter is tapped. */
    const val SELFIE_DEFAULT_SECONDS = 3

    /** The next value a tap on the clock icon selects; an unknown value starts the cycle again at Off. */
    fun next(current: Int): Int {
        val index = OPTIONS.indexOf(current)
        return if (index < 0) OPTIONS.first() else OPTIONS[(index + 1) % OPTIONS.size]
    }

    enum class LabelKind { OFF, SECONDS, WHEN_EVERYONE_READY }

    /** In Group the timer hands over to readiness, so its label says so instead of a number. */
    fun labelKind(seconds: Int, outcome: Outcome): LabelKind = when {
        seconds <= 0 -> LabelKind.OFF
        outcome == Outcome.GROUP -> LabelKind.WHEN_EVERYONE_READY
        else -> LabelKind.SECONDS
    }

    enum class SelfieTimerAction { ARM, DISARM, NONE }

    /**
     * Selfie gets a 3-second countdown by default: entering Selfie with the timer off arms it ([ARM]); leaving
     * Selfie disarms it again only if the app armed it ([autoArmed]) and the user has not changed it since.
     */
    fun selfieTimer(previous: Outcome?, next: Outcome, currentSeconds: Int, autoArmed: Boolean): SelfieTimerAction = when {
        next == Outcome.SELFIE && previous != Outcome.SELFIE && currentSeconds == 0 -> SelfieTimerAction.ARM
        previous == Outcome.SELFIE && next != Outcome.SELFIE && autoArmed && currentSeconds == SELFIE_DEFAULT_SECONDS ->
            SelfieTimerAction.DISARM
        else -> SelfieTimerAction.NONE
    }
}

/** Group readiness on the live screen: the dots around the readiness arc and the blocker chip. */
object GroupCues {
    /** More dots than this would crowd the ring; the chip still names the blocker. */
    const val MAX_DOTS = 12

    /** Degrees between neighbouring dots, clustered over 12 o'clock. */
    const val DOT_SPACING_DEGREES = 18f

    /** One flag per face (capped at [MAX_DOTS]): the first `facesReady` are filled. */
    fun dots(status: GroupStatus): List<Boolean> {
        val count = status.facesTotal.coerceIn(0, MAX_DOTS)
        val ready = status.facesReady.coerceIn(0, count)
        return List(count) { it < ready }
    }

    /**
     * The angle (Compose convention: 0 = 3 o'clock, clockwise positive) of dot [index] of [count], spread
     * symmetrically around 12 o'clock (-90).
     */
    fun dotAngleDegrees(index: Int, count: Int): Float = -90f + (index - (count - 1) / 2f) * DOT_SPACING_DEGREES

    /** The chip while the group is not ready: the first blocker, in the app's own (translatable) words. */
    fun chip(status: GroupStatus?): LiveChip? {
        if (status == null || status.ready) return null
        val blocker = status.blockers.firstOrNull() ?: return null
        val text = when (blocker) {
            GroupReadiness.BLOCKER_CUT_OFF -> GuidanceFormatter.strings[ChipKey.SOMEONES_CUT_OFF]
            GroupReadiness.BLOCKER_EYES_CLOSED -> GuidanceFormatter.strings[ChipKey.EYES_CLOSED]
            GroupReadiness.BLOCKER_TOO_SMALL -> GuidanceFormatter.strings[ChipKey.SOMEONES_TOO_SMALL]
            else -> blocker
        }
        return LiveChip(text)
    }
}

/**
 * Selfie cues. The front preview is mirrored, so the ordinary chip wording ("Slightly right") already
 * matches what the user sees on screen; nothing is flipped here. What Selfie adds is "Arm out a little" when
 * the face fills more than [ARM_OUT_FACE_HEIGHT] of the frame height.
 */
object SelfieCues {
    const val ARM_OUT_FACE_HEIGHT = 0.45f

    /** The face's height as a share of the frame, or null when no face is the subject. */
    fun faceHeight(composition: SmoothedComposition): Float? {
        val subject = composition.primarySubject ?: return null
        return subject.face?.bounds?.height ?: subject.bounds.height.takeIf { subject.kind == SubjectKind.FACE }
    }

    fun armOut(outcome: Outcome, composition: SmoothedComposition): Boolean =
        outcome == Outcome.SELFIE && (faceHeight(composition) ?: 0f) > ARM_OUT_FACE_HEIGHT

    fun armOutChip(): LiveChip = LiveChip(GuidanceFormatter.strings[ChipKey.ARM_OUT], GuidanceFormatter.strings[ChipKey.DESC_ARM_OUT])
}

/** What replaces the live cue chip, most important first: a hold, the group blocker, then Selfie's arm-out. */
object LiveChipRules {
    fun overrideChip(state: CameraUiState): LiveChip? =
        state.holdInstruction?.let { LiveChip(it) }
            ?: GroupCues.chip(state.groupStatus)
            ?: SelfieCues.armOutChip().takeIf { SelfieCues.armOut(state.outcome, state.composition) }
}
