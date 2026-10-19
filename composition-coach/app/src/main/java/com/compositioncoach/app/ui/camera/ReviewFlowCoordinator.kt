package com.compositioncoach.app.ui.camera

import android.net.Uri
import android.util.Log
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.di.JournalRecord
import com.compositioncoach.app.di.PhotoJournal
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.di.ReviewStore
import com.compositioncoach.app.di.StillAnalysisPort
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.PostProcess
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.CompositionCoach
import com.compositioncoach.composition.engine.CropSuggester
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Everything known about a capture at the moment its file was saved, handed to [ReviewFlowCoordinator.onCaptured]. */
internal data class CapturedShot(
    val uri: Uri,
    val diagnostics: CaptureDiagnostics,
    /** The engine's COACH evaluation of the live frame at the shutter (fills the card before the still analysis). */
    val liveResult: CompositionResult,
    /** That live frame, for the crop/what-if fallback when the still photo cannot be analysed. */
    val liveFrame: FrameAnalysis?,
    val isFrontCamera: Boolean,
    val outcome: Outcome,
    val intent: SceneIntent,
    /** The taste model the engine used (user weights plus the outcome's focus). */
    val weights: AdaptiveWeights,
    /** The headline cue showing when the shutter fired. */
    val cueAtCapture: FeedbackTracker.Cue?,
    /** Live cues followed since the previous capture. */
    val followedSinceLastCapture: List<MetricCategory>,
    /** Auto-level will rewrite the file; still analysis waits for it. */
    val levelling: Boolean,
    /** The normal first frame saved alongside a stack (Motion or focus), for the review's Compare; null otherwise. */
    val companionUri: Uri? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * The after-action half of the shutter: puts the [ReviewEntry] up at once with the live frame's report (so the
 * card is never empty), journals the capture, then analyses the *saved* photo in the background and fills the
 * entry in piece by piece ([ReviewStore.updateIfCurrent]; `analysing` stays true until the last piece):
 *
 *  1. waits for auto-level to finish rewriting the file (`processing` false), so the analysis sees the final
 *     pixels and its geometry matches what the review shows;
 *  2. [StillAnalysisPort.analyze] -> `stillAnalysis`;
 *  3. `coach.evaluateOnce(still, COACH, intent, weights)` -> `stillResult`, `report`, `whatIf` (the optimizer's
 *     best framing as a crop), and the journal's score/verdict/try-next-time;
 *  4. [CropSuggester.suggest] -> `crops`;
 *  5. for a `BOKEH_MASK` outcome with a usable subject mask (8-70 % of the frame),
 *     [StillAnalysisPort.separateBackground] -> `processedPreviewUri`.
 * When the port cannot analyse the photo, steps 3-4 fall back to the live frame (unless auto-level changed the
 * photo's geometry). The work is cancelled as soon as the review moves on to another photo (or is closed).
 *
 * Also turns the review's Keep / Retake / Not helpful / crop choices into learning events ([applyFeedback]) and
 * journal updates. Each decision counts once per photo.
 */
internal class ReviewFlowCoordinator(
    private val scope: CoroutineScope,
    private val reviewStore: ReviewStore,
    private val journal: PhotoJournal,
    private val stillAnalysis: StillAnalysisPort,
    private val coach: CompositionCoach,
    private val cropSuggester: CropSuggester,
    private val applyFeedback: suspend (Feedback) -> Unit,
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var analysisJob: Job? = null

    /** The last photo handed to the review, for decisions that arrive after the store was already cleared. */
    @Volatile private var lastPhoto: Uri? = null

    /** The still-analysis job for the latest capture (tests join it). */
    val currentAnalysis: Job? get() = analysisJob

    fun onCaptured(shot: CapturedShot) {
        lastPhoto = shot.uri
        val liveReport = AfterActionReport.build(shot.liveResult)
        reviewStore.put(
            ReviewEntry(
                photoUri = shot.uri,
                result = shot.liveResult,
                diagnostics = shot.diagnostics,
                processing = shot.levelling,
                report = liveReport,
                analysing = true,
                outcome = shot.outcome,
                originalPhotoUri = shot.companionUri,
            ),
        )
        analysisJob?.cancel()
        analysisJob = scope.launch(analysisDispatcher) {
            attempt("journal append") { journal.append(journalRecordFor(shot, liveReport)) }
            val self = coroutineContext.job
            // Cancel the moment the review shows another photo or closes; launched outside this job so it never
            // keeps it alive.
            val watcher = scope.launch {
                reviewStore.current.first { it?.photoUri != shot.uri }
                self.cancel()
            }
            try {
                analyse(shot)
            } finally {
                watcher.cancel()
                reviewStore.updateIfCurrent(shot.uri) { it.copy(analysing = false) }
            }
        }
    }

    private suspend fun analyse(shot: CapturedShot) {
        val uri = shot.uri
        // 1. Auto-level rewrites the file in place; analyse the final pixels.
        reviewStore.current.first { it == null || it.photoUri != uri || !it.processing }
        val levelled = reviewStore.current.value?.takeIf { it.photoUri == uri }?.diagnostics?.autoLevelAppliedDegrees != null

        // 2. The captured photo through the detectors.
        val still = attempt("still analysis") { stillAnalysis.analyze(uri, shot.isFrontCamera) }
        if (still != null) reviewStore.updateIfCurrent(uri) { it.copy(stillAnalysis = still) }

        val frame = still ?: shot.liveFrame?.takeIf { !levelled }
        val result = when {
            still != null -> attempt("still evaluation") { coach.evaluateOnce(still, GuidanceLevel.COACH, shot.intent, shot.weights) }
            else -> shot.liveResult
        }

        // 3. Report and what-if from the best evaluation available.
        if (result != null) {
            val report = AfterActionReport.build(result)
            val whatIf = if (still != null || !levelled) result.optimization?.best?.asCrop() else null
            reviewStore.updateIfCurrent(uri) {
                it.copy(stillResult = if (still != null) result else it.stillResult, report = report, whatIf = whatIf)
            }
            attempt("journal update") {
                journal.update(uri.toString()) { r ->
                    r.copy(
                        score = result.score,
                        verdict = report.verdict.name,
                        scene = result.scene.type.name,
                        tryNextTimeCategory = report.tryNextTime?.category?.name,
                    )
                }
            }
        }

        // 4. Better crops.
        if (frame != null && result != null) {
            val crops = attempt("crop suggestions") {
                cropSuggester.suggest(frame, result, shot.intent, GuidanceLevel.COACH, shot.weights)
            }.orEmpty()
            reviewStore.updateIfCurrent(uri) { it.copy(crops = crops) }
        }

        // 5. Background separation for portrait outcomes.
        if (still != null && shot.outcome.spec.postProcess == PostProcess.BOKEH_MASK && hasUsableMask(still)) {
            val processed = attempt("background separation") { stillAnalysis.separateBackground(uri, still) }
            if (processed != null) reviewStore.updateIfCurrent(uri) { it.copy(processedPreviewUri = processed) }
        }
    }

    // --- Review decisions -------------------------------------------------------------------------------

    /** Keep (true) or Retake (false) on the photo under review. Counts once per photo. */
    fun onReviewDecision(kept: Boolean) {
        val uri = currentPhoto() ?: return
        scope.launch {
            var cue: MetricCategory? = null
            var firstDecision = false
            journal.update(uri.toString()) { r ->
                firstDecision = r.kept == null
                cue = r.cueCategoryAtCapture?.let(::categoryOf)
                if (firstDecision) r.copy(kept = kept, retaken = !kept) else r
            }
            if (firstDecision) FeedbackTracker.reviewDecision(cue, kept)?.let { applyFeedback(it) }
        }
    }

    /** "Not helpful" on the card's Try-next-time. Counts once per photo. */
    fun onNotHelpful() {
        val entry = reviewStore.current.value ?: return
        val event = FeedbackTracker.notHelpful(entry.report) ?: return
        scope.launch {
            var first = false
            val known = journal.update(entry.photoUri.toString()) { r ->
                first = !r.notHelpful
                r.copy(notHelpful = true)
            }
            if (first || !known) applyFeedback(event)
        }
    }

    /** The user saved [crop]. */
    fun onCropChosen(crop: CropSuggestion) {
        val uri = currentPhoto()
        scope.launch {
            applyFeedback(FeedbackTracker.cropChosen(crop))
            if (uri != null) journal.update(uri.toString()) { it.copy(cropAspect = crop.aspect.name) }
        }
    }

    private fun currentPhoto(): Uri? = reviewStore.current.value?.photoUri ?: lastPhoto

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w(TAG, "After-action $what failed; the card keeps what it has", t)
        null
    }

    companion object {
        private const val TAG = "ReviewFlow"
        const val MIN_MASK_COVERAGE = 0.08f
        const val MAX_MASK_COVERAGE = 0.70f

        fun hasUsableMask(analysis: FrameAnalysis): Boolean =
            analysis.subjectMask?.coverage()?.let { it in MIN_MASK_COVERAGE..MAX_MASK_COVERAGE } == true

        fun journalRecordFor(shot: CapturedShot, report: AfterActionReport): JournalRecord = JournalRecord(
            id = shot.uri.toString(),
            timestampMs = shot.timestampMs,
            score = shot.liveResult.score,
            verdict = report.verdict.name,
            scene = shot.liveResult.scene.type.name,
            outcome = shot.outcome.name,
            cueCategoryAtCapture = shot.cueAtCapture?.category?.name,
            cueIdAtCapture = shot.cueAtCapture?.id,
            tryNextTimeCategory = report.tryNextTime?.category?.name,
            followedCategories = shot.followedSinceLastCapture.map { it.name },
        )

        private fun categoryOf(name: String): MetricCategory? = MetricCategory.entries.firstOrNull { it.name == name }
    }
}

/**
 * The re-shoot ghost for [entry]: the still photo's subject-mask bounds, else its primary subject's box, else
 * the live result's. Null when nothing was found.
 */
fun ghostRectFor(entry: ReviewEntry): NormalizedRect? =
    entry.stillAnalysis?.subjectMask?.bounds()
        ?: entry.stillResult?.primarySubject?.bounds
        ?: entry.result.primarySubject?.bounds
