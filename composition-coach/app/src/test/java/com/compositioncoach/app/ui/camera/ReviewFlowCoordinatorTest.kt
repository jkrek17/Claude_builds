package com.compositioncoach.app.ui.camera

import android.net.Uri
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.di.PhotoJournal
import com.compositioncoach.app.di.ReviewStore
import com.compositioncoach.app.di.StillAnalysisPort
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.CompositionCoach
import com.compositioncoach.composition.engine.CropSuggester
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFlowCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val photo = Uri.parse("content://media/external/images/media/7")
    private val coach = CompositionCoach.create()

    /** A face near the right edge: the engine has something to say, the crop suggester something to offer. */
    private val frame = FrameAnalysis(
        timestampNanos = 1L,
        frameWidth = 1024,
        frameHeight = 768,
        faces = listOf(
            DetectedFace(
                id = 1,
                bounds = NormalizedRect(0.78f, 0.25f, 0.95f, 0.55f),
                leftEye = NormalizedPoint(0.83f, 0.35f),
                rightEye = NormalizedPoint(0.9f, 0.35f),
            ),
        ),
    )

    private class FakePort(var still: FrameAnalysis?, val gate: CompletableDeferred<Unit>? = null) : StillAnalysisPort {
        var analyzed = 0
        override suspend fun analyze(uri: Uri, isFrontCamera: Boolean): FrameAnalysis? {
            analyzed++
            gate?.await()
            return still
        }
        override suspend fun separateBackground(uri: Uri, analysis: FrameAnalysis): Uri? = null
    }

    private class Harness(
        scope: TestScope,
        port: StillAnalysisPort,
        dir: File,
        coach: CompositionCoach,
    ) {
        val store = ReviewStore()
        val events = mutableListOf<Feedback>()
        val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val journal = PhotoJournal(File(dir, "journal.jsonl"), ioDispatcher = dispatcher)
        val flow = ReviewFlowCoordinator(
            scope = scope,
            reviewStore = store,
            journal = journal,
            stillAnalysis = port,
            coach = coach,
            cropSuggester = CropSuggester(),
            applyFeedback = { events += it },
            analysisDispatcher = dispatcher,
        )
    }

    private fun shot(levelling: Boolean = false, cue: FeedbackTracker.Cue? = null) = CapturedShot(
        uri = photo,
        diagnostics = CaptureDiagnostics(),
        liveResult = coach.evaluateOnce(frame, GuidanceLevel.COACH, SceneIntent.PORTRAIT),
        liveFrame = frame,
        isFrontCamera = false,
        outcome = Outcome.PORTRAIT,
        intent = SceneIntent.PORTRAIT,
        weights = AdaptiveWeights.NEUTRAL,
        cueAtCapture = cue,
        followedSinceLastCapture = listOf(MetricCategory.HORIZON),
        levelling = levelling,
        timestampMs = 1_000L,
    )

    @Test
    fun `the card shows the live report at once, then the still analysis fills it in`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, FakePort(frame, gate), tmp.root, coach)
        h.flow.onCaptured(shot())
        val first = h.store.current.value!!
        assertNotNull(first.report)
        assertTrue(first.analysing)
        assertNull(first.stillResult)
        assertEquals(Outcome.PORTRAIT, first.outcome)

        gate.complete(Unit)
        advanceUntilIdle()
        val done = h.store.current.value!!
        assertEquals(frame, done.stillAnalysis)
        assertNotNull(done.stillResult)
        assertNotNull(done.report)
        assertFalse(done.analysing)
        val record = h.journal.read().single()
        assertEquals(photo.toString(), record.id)
        assertEquals("PORTRAIT", record.outcome)
        assertEquals(listOf("HORIZON"), record.followedCategories)
        assertEquals(done.stillResult!!.score, record.score)
    }

    @Test
    fun `without a still analyser the live frame still gives a complete card`() = runTest {
        val port = FakePort(still = null)
        val h = Harness(this, port, tmp.root, coach)
        h.flow.onCaptured(shot())
        advanceUntilIdle()
        val done = h.store.current.value!!
        assertEquals(1, port.analyzed)
        assertNull(done.stillAnalysis)
        assertNull(done.stillResult)
        assertNotNull(done.report)
        assertFalse(done.analysing)
    }

    @Test
    fun `analysis waits for auto-level to finish rewriting the photo`() = runTest {
        val port = FakePort(frame)
        val h = Harness(this, port, tmp.root, coach)
        h.flow.onCaptured(shot(levelling = true))
        advanceUntilIdle()
        assertEquals(0, port.analyzed)
        h.store.updateIfCurrent(photo) { it.copy(processing = false, revision = 1) }
        advanceUntilIdle()
        assertEquals(1, port.analyzed)
        assertFalse(h.store.current.value!!.analysing)
    }

    @Test
    fun `the work is cancelled when the review moves on`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, FakePort(frame, gate), tmp.root, coach)
        h.flow.onCaptured(shot())
        val job = h.flow.currentAnalysis!!
        h.store.clear()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertNull(h.store.current.value)
        gate.complete(Unit)
    }

    @Test
    fun `keep and retake teach the taste model once per photo, and are journaled`() = runTest {
        val h = Harness(this, FakePort(null), tmp.root, coach)
        h.flow.onCaptured(shot(cue = FeedbackTracker.Cue("horizon.tilt", MetricCategory.HORIZON)))
        advanceUntilIdle()
        h.flow.onReviewDecision(kept = true)
        h.flow.onReviewDecision(kept = false) // a second decision on the same photo does not count
        advanceUntilIdle()
        assertEquals(listOf<Feedback>(Feedback.IGNORED_AND_KEPT(MetricCategory.HORIZON)), h.events)
        val record = h.journal.read().single()
        assertEquals(true, record.kept)
        assertFalse(record.retaken)
        assertEquals("HORIZON", record.cueCategoryAtCapture)
    }

    @Test
    fun `a decision after the store was cleared still applies to the last photo`() = runTest {
        val h = Harness(this, FakePort(null), tmp.root, coach)
        h.flow.onCaptured(shot(cue = FeedbackTracker.Cue("headroom.tight", MetricCategory.HEADROOM)))
        advanceUntilIdle()
        h.store.clear()
        h.flow.onReviewDecision(kept = false)
        advanceUntilIdle()
        assertEquals(listOf<Feedback>(Feedback.IGNORED_AND_RETAKEN(MetricCategory.HEADROOM)), h.events)
        assertTrue(h.journal.read().single().retaken)
    }

    @Test
    fun `no cue at capture means no keep or retake event`() = runTest {
        val h = Harness(this, FakePort(null), tmp.root, coach)
        h.flow.onCaptured(shot(cue = null))
        advanceUntilIdle()
        h.flow.onReviewDecision(kept = true)
        advanceUntilIdle()
        assertTrue(h.events.isEmpty())
        assertEquals(true, h.journal.read().single().kept)
    }

    @Test
    fun `ghost rect prefers the still photo's subject`() {
        val live = CompositionResult.empty()
        val entry = com.compositioncoach.app.di.ReviewEntry(photo, live)
        assertNull(ghostRectFor(entry))
        val evaluated = coach.evaluateOnce(frame, GuidanceLevel.COACH, SceneIntent.PORTRAIT)
        assertEquals(evaluated.primarySubject?.bounds, ghostRectFor(entry.copy(result = evaluated)))
    }
}
