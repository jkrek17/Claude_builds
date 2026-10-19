package com.compositioncoach.app.ui.camera

import android.content.res.Resources
import androidx.annotation.StringRes
import com.compositioncoach.app.R

/**
 * Every word the live chip can say, keyed so the wording table lives in `strings.xml` (translatable) while
 * [GuidanceFormatter] stays a plain JVM object. [english] is the launch copy, identical to `values/strings.xml`
 * (`ChipStringsResourceTest` pins that), and doubles as the fallback before resources are installed and as
 * the JVM tests' fake.
 */
enum class ChipKey(@StringRes val resId: Int, val english: String) {
    SLIGHTLY_RIGHT(R.string.chip_slightly_right, "Slightly right"),
    SLIGHTLY_LEFT(R.string.chip_slightly_left, "Slightly left"),
    RAISE(R.string.chip_raise, "Raise"),
    LOWER(R.string.chip_lower, "Lower"),
    CLOSER(R.string.chip_closer, "Closer"),
    STEP_BACK(R.string.chip_step_back, "Step back"),
    LEVEL(R.string.chip_level, "Level"),
    CLEAR_BACKGROUND(R.string.chip_clear_background, "Clear background"),
    PLAINER_BACKGROUND(R.string.chip_plainer_background, "Plainer background"),
    GIVE_LOOKING_ROOM(R.string.chip_give_looking_room, "Give looking room"),
    TOO_CLOSE_TO_EDGE(R.string.chip_too_close_to_edge, "Too close to edge"),
    SOMEONES_CUT_OFF(R.string.chip_someones_cut_off, "Someone's cut off"),
    CENTER_IT(R.string.chip_center_it, "Center it"),
    SHOOT(R.string.chip_shoot, "SHOOT"),
    POINT_AT_SUBJECT(R.string.chip_point_at_subject, "Point at a subject"),
    LOOKING_FOR_SUBJECT(R.string.chip_looking_for_subject, "Looking for a subject"),
    ARM_OUT(R.string.chip_arm_out, "Arm out a little"),
    EYES_CLOSED(R.string.chip_eyes_closed, "Eyes closed"),
    SOMEONES_TOO_SMALL(R.string.chip_someones_too_small, "Someone's too small"),
    DESC_SLIGHTLY_RIGHT(R.string.chip_desc_slightly_right, "Move slightly right"),
    DESC_SLIGHTLY_LEFT(R.string.chip_desc_slightly_left, "Move slightly left"),
    DESC_RAISE(R.string.chip_desc_raise, "Raise the camera"),
    DESC_LOWER(R.string.chip_desc_lower, "Lower the camera"),
    DESC_CLOSER(R.string.chip_desc_closer, "Move closer"),
    DESC_STEP_BACK(R.string.chip_desc_step_back, "Step back"),
    DESC_LEVEL(R.string.chip_desc_level, "Level the camera"),
    DESC_SHOOT(R.string.chip_desc_shoot, "Framing is ready, shoot now"),
    DESC_ARM_OUT(R.string.chip_desc_arm_out, "Hold the phone a little further away"),
}

/** Where chip words come from. Production reads resources; tests and previews use [English]. */
fun interface ChipStrings {
    operator fun get(key: ChipKey): String

    companion object {
        /** The launch copy, straight from [ChipKey.english]; the JVM tests' fake. */
        val English = ChipStrings { it.english }
    }
}

/** Reads the chip table from the app's resources, so a translation only needs a `values-xx/strings.xml`. */
class ResourceChipStrings(private val resources: Resources) : ChipStrings {
    override fun get(key: ChipKey): String = resources.getString(key.resId)
}
