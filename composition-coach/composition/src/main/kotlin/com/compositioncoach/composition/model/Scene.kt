package com.compositioncoach.composition.model

/** Broad photographic intent inferred from the frame; drives scoring weights and which advice is relevant. */
enum class SceneType { PORTRAIT, GROUP_PORTRAIT, LANDSCAPE, ARCHITECTURE, OBJECT, GENERAL }

data class SceneClassification(
    val type: SceneType,
    val confidence: Float,
    /** True when the frame shows strong mirror symmetry, so centred framing should be rewarded, not penalised. */
    val isSymmetricScene: Boolean = false,
    /** True when a visual horizon line was detected. */
    val hasHorizon: Boolean = false,
    /** True for a tight head-and-shoulders / face crop where limb-cropping and headroom rules should relax. */
    val isCloseUpPortrait: Boolean = false,
) {
    companion object {
        val UNKNOWN = SceneClassification(SceneType.GENERAL, 0f)
    }
}

/** How chatty the coach is. */
enum class GuidanceLevel { MINIMAL, BALANCED, COACH }

/**
 * What the photographer *told us* they are shooting (Settings > Shooting mode). [AUTO] leaves scene detection to
 * the [SceneClassifier]; any other value overrides the detected [SceneType], selects that scene's score weights,
 * and lets the engine coach *toward* the intent (e.g. PORTRAIT with no face found -> "Move closer to your subject").
 */
enum class SceneIntent(val label: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    GROUP_PORTRAIT("Group"),
    LANDSCAPE("Landscape"),
    ARCHITECTURE("Architecture"),
    OBJECT("Object / food");

    /** The scene type this intent forces, or null for [AUTO]. */
    fun forcedSceneType(): SceneType? = when (this) {
        AUTO -> null
        PORTRAIT -> SceneType.PORTRAIT
        GROUP_PORTRAIT -> SceneType.GROUP_PORTRAIT
        LANDSCAPE -> SceneType.LANDSCAPE
        ARCHITECTURE -> SceneType.ARCHITECTURE
        OBJECT -> SceneType.OBJECT
    }
}
