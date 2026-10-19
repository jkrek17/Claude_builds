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
    OBJECT("Object / food"),

    /**
     * Front-camera self-portrait. Coached with exactly the [PORTRAIT] rules (forced PORTRAIT scene, faces-only
     * subjects, relaxed background-face threshold, "find your subject" when no face is in frame); the result
     * still reports `intent == SELFIE`. Mirror-aware wording of directional cues is the app's concern.
     * Appended last so existing ordinals are unchanged.
     */
    SELFIE("Selfie");

    /** The scene type this intent forces, or null for [AUTO]. */
    fun forcedSceneType(): SceneType? = when (this) {
        AUTO -> null
        PORTRAIT -> SceneType.PORTRAIT
        GROUP_PORTRAIT -> SceneType.GROUP_PORTRAIT
        LANDSCAPE -> SceneType.LANDSCAPE
        ARCHITECTURE -> SceneType.ARCHITECTURE
        OBJECT -> SceneType.OBJECT
        SELFIE -> SceneType.PORTRAIT
    }

    /**
     * The intent whose coaching rules this one uses: [SELFIE] is coached as [PORTRAIT]; every other value is
     * itself. The engine passes this to subject resolution and the analyzers, and keeps the declared intent
     * on the result.
     */
    fun rulesIntent(): SceneIntent = if (this == SELFIE) PORTRAIT else this
}
