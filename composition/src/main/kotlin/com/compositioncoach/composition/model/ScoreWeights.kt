package com.compositioncoach.composition.model

/**
 * Relative weight of each [MetricCategory] in the overall score. Weights are normalised over the categories
 * that are *applicable* for a given frame, so they need not sum to 100; the numbers below are chosen to read
 * like percentages for tunability.
 *
 * **[MetricCategory.LIGHT]** (light on the subject) was added after the other tables were tuned: PORTRAIT 10,
 * GROUP_PORTRAIT 8, OBJECT 6, GENERAL 4, LANDSCAPE 0, ARCHITECTURE 0. The points come out of the two
 * categories a portrait cares least about: PORTRAIT BALANCE 4 -> 2 and NEGATIVE_SPACE 1 -> 0.5,
 * GROUP_PORTRAIT BALANCE 8 -> 5 (its NEGATIVE_SPACE inherits PORTRAIT's 0.5). Because weights are
 * normalised over applicable categories, the tables no longer sum to the same total as before; what matters
 * is the ratio, and LIGHT only counts at all when a subject and luminance statistics exist.
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
                MetricCategory.LIGHT to 4f,
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
            .with(MetricCategory.BALANCE, 2f)
            .with(MetricCategory.SYMMETRY, 2f)
            .with(MetricCategory.NEGATIVE_SPACE, 0.5f)
            .with(MetricCategory.LEADING_LINES, 1f)
            .with(MetricCategory.LIGHT, 10f)

        val GROUP_PORTRAIT = PORTRAIT
            .with(MetricCategory.SUBJECT_PLACEMENT, 16f)
            .with(MetricCategory.EDGE_TENSION, 14f)
            .with(MetricCategory.CROPPING, 14f)
            .with(MetricCategory.LOOKING_ROOM, 4f)
            .with(MetricCategory.BALANCE, 5f)
            .with(MetricCategory.LIGHT, 8f)

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
            .with(MetricCategory.LIGHT, 0f)

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
            .with(MetricCategory.LIGHT, 0f)

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
            .with(MetricCategory.LIGHT, 6f)

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
