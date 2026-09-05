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
