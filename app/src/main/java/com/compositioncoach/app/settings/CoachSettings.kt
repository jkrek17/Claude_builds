package com.compositioncoach.app.settings

import com.compositioncoach.composition.model.GuidanceLevel

/** User-configurable behavior of the coaching pipeline and overlay UI. Persisted via [SettingsRepository]. */
data class CoachSettings(
    val guidanceEnabled: Boolean = true,
    val showScore: Boolean = true,
    val showThirdsGrid: Boolean = false,
    val guidanceLevel: GuidanceLevel = GuidanceLevel.BALANCED,
    val poseDetectionEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val debugMode: Boolean = false,
) {
    /** Target interval between vision analyses, see [FrameAnalysisSource.setTargetIntervalMs]. */
    val analysisIntervalMs: Long get() = if (batterySaver) BATTERY_SAVER_INTERVAL_MS else DEFAULT_INTERVAL_MS

    companion object {
        const val DEFAULT_INTERVAL_MS = 100L
        const val BATTERY_SAVER_INTERVAL_MS = 200L
    }
}

/** One-line descriptions shown next to each [GuidanceLevel] choice in Settings. */
fun GuidanceLevel.description(): String = when (this) {
    GuidanceLevel.MINIMAL -> "Only the score and a shoot-ready cue. No instructions."
    GuidanceLevel.BALANCED -> "One clear instruction at a time. Good for everyday shooting."
    GuidanceLevel.COACH -> "Instructions plus the reasoning behind them, for learning."
}

fun GuidanceLevel.label(): String = when (this) {
    GuidanceLevel.MINIMAL -> "Minimal"
    GuidanceLevel.BALANCED -> "Balanced"
    GuidanceLevel.COACH -> "Coach"
}

/**
 * Serializes [GuidanceLevel] to/from the plain string DataStore stores it as. Pulled out of
 * [SettingsRepository] (which needs a real DataStore/Context) so the round-trip and the "unknown or
 * missing value falls back to BALANCED" behavior are unit-testable without Robolectric.
 */
object GuidanceLevelCodec {
    fun encode(level: GuidanceLevel): String = level.name

    fun decode(raw: String?): GuidanceLevel =
        raw?.let { runCatching { GuidanceLevel.valueOf(it) }.getOrNull() } ?: GuidanceLevel.BALANCED
}
