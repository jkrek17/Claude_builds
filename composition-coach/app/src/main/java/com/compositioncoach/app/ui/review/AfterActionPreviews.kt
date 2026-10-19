package com.compositioncoach.app.ui.review

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.tooling.preview.Preview
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.TryNextTime
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect

/** A believable review entry for previews and Robolectric tests; no camera, no engine run. */
internal fun fakeReviewEntry(
    score: Int = 74,
    analysing: Boolean = false,
    withReport: Boolean = true,
    withCrops: Boolean = true,
    tryNext: Boolean = true,
): ReviewEntry {
    val result = CompositionResult.empty().copy(
        score = score,
        strengths = listOf("Level horizon", "Subject sits on a strong thirds point", "Nice light on the face"),
    )
    val report = AfterActionReport(
        verdict = AfterActionReport.verdictFor(score, hasHighSeverity = false),
        worked = listOf("Level horizon", "Subject sits off centre, where it reads well", "Nice light on the face"),
        tryNextTime = if (tryNext) {
            TryNextTime("A step to the left would have cleared the pole behind them (+9)", 9, Direction.LEFT, MetricCategory.BACKGROUND_DISTRACTION)
        } else {
            null
        },
        lightNote = null,
    )
    return ReviewEntry(
        photoUri = Uri.parse("content://preview/photo/1"),
        result = result,
        diagnostics = CaptureDiagnostics(),
        report = if (withReport) report else null,
        crops = if (withCrops) {
            listOf(
                CropSuggestion(NormalizedRect(0.1f, 0.05f, 0.9f, 0.85f), CropAspect.ORIGINAL, score + 9, "Tighter"),
                CropSuggestion(NormalizedRect(0.15f, 0.1f, 0.85f, 0.62f), CropAspect.SQUARE, score + 6, "Square"),
            )
        } else {
            emptyList()
        },
        whatIf = NormalizedRect(0.05f, 0.05f, 0.85f, 0.85f),
        analysing = analysing,
        outcome = Outcome.PORTRAIT,
    )
}

@Composable
private fun CardPreview(entry: ReviewEntry, view: CardViewState = CardViewState()) {
    CompositionCoachTheme {
        AfterActionCard(
            entry = entry,
            painter = ColorPainter(Scrim),
            photoAspect = 0.75f,
            view = view,
            callbacks = CardCallbacks(),
            animationsEnabled = false,
        )
    }
}

@Preview(name = "After-action card, Good with crops", showBackground = true, backgroundColor = 0xFF000000, heightDp = 700)
@Composable
private fun AfterActionGoodPreview() = CardPreview(fakeReviewEntry())

@Preview(name = "After-action card, Keeper, nothing to change", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AfterActionKeeperPreview() = CardPreview(fakeReviewEntry(score = 91, withCrops = false, tryNext = false))

@Preview(name = "After-action card, analysing", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun AfterActionAnalysingPreview() = CardPreview(fakeReviewEntry(score = 55, analysing = true, withReport = false, withCrops = false))

@Preview(name = "After-action card, crop selected", showBackground = true, backgroundColor = 0xFF000000, heightDp = 700)
@Composable
private fun AfterActionCropSelectedPreview() {
    val entry = fakeReviewEntry()
    CardPreview(entry, CardViewState(selectedCrop = entry.crops.first()))
}
