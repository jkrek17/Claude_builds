package com.compositioncoach.composition.model

/**
 * Shape of a suggested crop. [ORIGINAL] keeps the photo's own aspect ratio; the others are fixed output
 * aspect ratios (width / height, in pixels).
 */
enum class CropAspect(val label: String, val widthOverHeight: Float?) {
    ORIGINAL("Original", null),
    FOUR_FIVE("4:5", 4f / 5f),
    SQUARE("1:1", 1f),
}

/**
 * One crop the review screen can offer ("Better crop?"), produced by
 * [com.compositioncoach.composition.engine.CropSuggester].
 *
 * @param rect the crop window in the photo's normalized coordinates (always inside `0..1`).
 * @param predictedScore the engine's score for the photo cropped to [rect] (0..100).
 * @param headline a few plain words for the crop card ("Tighter, eyes on the third", "Square portrait").
 */
data class CropSuggestion(
    val rect: NormalizedRect,
    val aspect: CropAspect,
    val predictedScore: Int,
    val headline: String,
)
