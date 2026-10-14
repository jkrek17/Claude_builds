package com.compositioncoach.composition.model

/**
 * Relative weight of each [MetricCategory] in the overall score. Weights are normalised over the categories
 * that are *applicable* for a given frame, so they need not sum to 100; the numbers below are chosen to read
 * like percentages for tunability.
 */
data class ScoreWeights(val weights: Map<MetricCategory, Float>) {
    operator fun get(category: MetricCategory): Float = weights[category] ?: 0f

    fun with(category: MetricCategory, weight: Float) = ScoreWeights(weights + (category to weight))

    companion object {
        val GENERAL = ScoreWeights(
            mapOf(
                MetricCategory.SUBJECT_PLACEMENT to 20f,
                MetricCategory.HORIZON to 10f,
                MetricCategory.HEADROOM to 8f,
                MetricCategory.CROPPING to 7f,
                MetricCategory.LOOKING_ROOM to 5f,
                MetricCategory.EDGE_TENSION to 8f,
                MetricCategory.BACKGROUND_DISTRACTION to 12f,
                MetricCategory.SUBJECT_SEPARATION to 8f,
                MetricCategory.BALANCE to 8f,
                MetricCategory.SYMMETRY to 6f,
                MetricCategory.NEGATIVE_SPACE to 3f,
                MetricCategory.LEADING_LINES to 3f,
                MetricCategory.SCENE_SPECIFIC to 2f,
            ),
        )

        val PORTRAIT = GENERAL
            .with(MetricCategory.SUBJECT_PLACEMENT, 20f)
            .with(MetricCategory.HORIZON, 6f)
            .with(MetricCategory.HEADROOM, 14f)
            .with(MetricCategory.CROPPING, 12f)
            .with(MetricCategory.LOOKING_ROOM, 10f)
            .with(MetricCategory.EDGE_TENSION, 8f)
            .with(MetricCategory.BACKGROUND_DISTRACTION, 14f)
            .with(MetricCategory.SUBJECT_SEPARATION, 8f)
            .with(MetricCategory.BALANCE, 4f)
            .with(MetricCategory.SYMMETRY, 2f)
            .with(MetricCategory.NEGATIVE_SPACE, 1f)
            .with(MetricCategory.LEADING_LINES, 1f)

        val GROUP_PORTRAIT = PORTRAIT
            .with(MetricCategory.SUBJECT_PLACEMENT, 16f)
            .with(MetricCategory.EDGE_TENSION, 14f)
            .with(MetricCategory.CROPPING, 14f)
            .with(MetricCategory.LOOKING_ROOM, 4f)
            .with(MetricCategory.BALANCE, 8f)

        val LANDSCAPE = GENERAL
            .with(MetricCategory.SUBJECT_PLACEMENT, 12f)
            .with(MetricCategory.HORIZON, 22f)
            .with(MetricCategory.HEADROOM, 0f)
            .with(MetricCategory.CROPPING, 0f)
            .with(MetricCategory.LOOKING_ROOM, 0f)
            .with(MetricCategory.EDGE_TENSION, 4f)
            .with(MetricCategory.BACKGROUND_DISTRACTION, 4f)
            .with(MetricCategory.SUBJECT_SEPARATION, 4f)
            .with(MetricCategory.BALANCE, 16f)
            .with(MetricCategory.SYMMETRY, 8f)
            .with(MetricCategory.NEGATIVE_SPACE, 6f)
            .with(MetricCategory.LEADING_LINES, 12f)
            .with(MetricCategory.SCENE_SPECIFIC, 12f)

        val ARCHITECTURE = GENERAL
            .with(MetricCategory.SUBJECT_PLACEMENT, 10f)
            .with(MetricCategory.HORIZON, 22f)
            .with(MetricCategory.HEADROOM, 0f)
            .with(MetricCategory.CROPPING, 0f)
            .with(MetricCategory.LOOKING_ROOM, 0f)
            .with(MetricCategory.EDGE_TENSION, 6f)
            .with(MetricCategory.BACKGROUND_DISTRACTION, 4f)
            .with(MetricCategory.SUBJECT_SEPARATION, 4f)
            .with(MetricCategory.BALANCE, 12f)
            .with(MetricCategory.SYMMETRY, 20f)
            .with(MetricCategory.NEGATIVE_SPACE, 4f)
            .with(MetricCategory.LEADING_LINES, 10f)
            .with(MetricCategory.SCENE_SPECIFIC, 8f)

        val OBJECT = GENERAL
            .with(MetricCategory.SUBJECT_PLACEMENT, 24f)
            .with(MetricCategory.HORIZON, 6f)
            .with(MetricCategory.HEADROOM, 0f)
            .with(MetricCategory.CROPPING, 4f)
            .with(MetricCategory.LOOKING_ROOM, 0f)
            .with(MetricCategory.EDGE_TENSION, 12f)
            .with(MetricCategory.BACKGROUND_DISTRACTION, 14f)
            .with(MetricCategory.SUBJECT_SEPARATION, 14f)
            .with(MetricCategory.BALANCE, 10f)
            .with(MetricCategory.SYMMETRY, 6f)
            .with(MetricCategory.NEGATIVE_SPACE, 6f)
            .with(MetricCategory.LEADING_LINES, 2f)

        fun forScene(type: SceneType): ScoreWeights = when (type) {
            SceneType.PORTRAIT -> PORTRAIT
            SceneType.GROUP_PORTRAIT -> GROUP_PORTRAIT
            SceneType.LANDSCAPE -> LANDSCAPE
            SceneType.ARCHITECTURE -> ARCHITECTURE
            SceneType.OBJECT -> OBJECT
            SceneType.GENERAL -> GENERAL
        }
    }
}
