package com.compositioncoach.app.camera.merge

import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.vision.PerformanceTier
import kotlin.math.roundToInt

/** Which burst merge runs: the everyday one or the Night stack. */
enum class BurstMergeMode(
    /** File-name suffix of the merged photo (`CC_..._merge.jpg`). */
    val suffix: String,
    /** Frames captured at [PerformanceTier.FULL]. */
    val frames: Int,
) {
    BURST_MERGE("_merge", 5),
    NIGHT_STACK("_night", 8),
}

/**
 * The pure decisions behind [BurstMergeCapture], kept apart so they are unit-tested on the JVM: how many
 * frames the thermal tier allows, how far to bias the exposure under, and which shutter and ISO the Night
 * stack asks for.
 */
object BurstMergePlanner {
    /** Under-exposure of the everyday burst, in stops: highlights survive, and the merge lifts it back. */
    const val BIAS_STOPS = 1f / 3f

    /** The largest exposure-compensation step for which a bias is applied (a coarser step would under-expose too much). */
    const val MAX_BIAS_STEP = 0.5f

    /** Night: the longest hand-held shutter when the phone is [Steadiness.STEADY]. */
    const val STEADY_SHUTTER_NS = 125_000_000L

    /** Night: the longest shutter when the phone is [Steadiness.SLIGHT_MOTION] (1/15 s). */
    const val SLIGHT_MOTION_SHUTTER_NS = 66_666_667L

    /** A longer shutter must at least be this much longer than auto-exposure's to be worth the manual path. */
    const val MIN_SHUTTER_GAIN = 1.25f

    /** Frames to capture: all at FULL, half (rounded up) at REDUCED, the first frame only at MINIMAL. */
    fun frameCount(mode: BurstMergeMode, tier: PerformanceTier): Int = when (tier) {
        PerformanceTier.FULL -> mode.frames
        PerformanceTier.REDUCED -> (mode.frames + 1) / 2
        PerformanceTier.MINIMAL -> 1
    }

    /**
     * How many exposure-compensation steps to go down for a [BIAS_STOPS] bias, as a negative number (0 when the
     * lens's [stepEv] is unknown, zero or too coarse).
     */
    fun biasIndexDelta(stepEv: Float): Int {
        if (!stepEv.isFinite() || stepEv <= 0f || stepEv > MAX_BIAS_STEP) return 0
        return -(BIAS_STOPS / stepEv).roundToInt().coerceAtLeast(1)
    }

    /** The stops the merge must lift back after the compensation actually moved by [appliedIndexDelta] steps of [stepEv]. */
    fun liftStops(appliedIndexDelta: Int, stepEv: Float): Float =
        if (appliedIndexDelta >= 0 || !stepEv.isFinite() || stepEv <= 0f) 0f else -appliedIndexDelta * stepEv

    /** The shutter the gyro's steadiness allows for the Night stack, or null (auto-exposure stays in charge). */
    fun nightShutterNs(steadiness: Steadiness): Long? = when (steadiness) {
        Steadiness.STEADY -> STEADY_SHUTTER_NS
        Steadiness.SLIGHT_MOTION -> SLIGHT_MOTION_SHUTTER_NS
        Steadiness.MOVING -> null
    }

    /** A manual exposure for the Night stack. */
    data class NightExposure(val exposureNs: Long, val iso: Int)

    /**
     * The manual exposure with the longest shutter [steadiness] allows and the same total exposure as
     * auto-exposure's `autoExposureNs` at `autoIso`: the ISO falls by the shutter's gain (less noise per frame).
     * When the sensor's ISO floor stops the ISO from falling that far, the shutter is shortened to keep the
     * brightness. Null when there is nothing to gain (a [MIN_SHUTTER_GAIN] longer shutter is not reachable).
     */
    fun nightExposure(
        autoExposureNs: Long,
        autoIso: Int,
        steadiness: Steadiness,
        exposureRangeNs: LongRange?,
        isoRange: IntRange?,
    ): NightExposure? {
        if (autoExposureNs <= 0L || autoIso <= 0) return null
        var shutter = nightShutterNs(steadiness) ?: return null
        if (exposureRangeNs != null) shutter = shutter.coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        var iso = autoIso.toDouble() * autoExposureNs / shutter
        if (isoRange != null && iso < isoRange.first) {
            iso = isoRange.first.toDouble()
            shutter = (autoIso.toDouble() * autoExposureNs / iso).toLong()
            if (exposureRangeNs != null) shutter = shutter.coerceIn(exposureRangeNs.first, exposureRangeNs.last)
        }
        if (isoRange != null) iso = iso.coerceIn(isoRange.first.toDouble(), isoRange.last.toDouble())
        if (shutter < autoExposureNs * MIN_SHUTTER_GAIN) return null
        return NightExposure(shutter, iso.roundToInt())
    }
}
