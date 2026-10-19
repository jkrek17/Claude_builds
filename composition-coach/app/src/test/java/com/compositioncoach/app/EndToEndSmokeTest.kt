package com.compositioncoach.app

import android.Manifest
import android.os.Looper
import android.util.Range
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CaptureSessionInfo
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.outcome.ExperienceMode
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.ui.camera.CameraViewModel
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GazeDirection
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.vision.FrameAnalysisSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * The app as one product, under Robolectric: the real [MainActivity], navigation graph, `SettingsRepository`,
 * [CameraViewModel], composition engine and camera chrome, with only the two things Robolectric cannot run
 * replaced at their edges: the vision pipeline (a [FakeFrameSource] streaming a centred face, so the real engine
 * classifies a portrait scene and scores it) and the CameraX bind (a fake bind result handed to the camera's own
 * view model, as `CameraScreen` would after a real bind, so the zoom presets exist).
 *
 * 1. Settings seeded to **Easy**: the outcome strip and the shutter compose, and no score number does, even
 *    though frames are being scored.
 * 2. Settings changed to **Coach + Pro** while the screen is up: the score chip and the zoom preset chips appear.
 *
 * [AppLaunchTest] still covers the plain launch with the real (failing under Robolectric) vision pipeline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = SmokeTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EndToEndSmokeTest {

    @get:Rule
    val rule = createEmptyComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as SmokeTestApp

    @Test
    fun `Easy shows the strip and shutter without a number, Coach plus Pro adds the score and zoom chips`() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        seed { it.copy(experienceMode = ExperienceMode.EASY, proControls = false, onboardingSeen = true) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            rule.onNode(hasContentDescription("What you want to shoot")).assertExists()
            rule.onNode(hasText("Anything")).assertExists()
            rule.onNode(hasContentDescription("Shutter", substring = true)).assertExists()
            // Frames are being scored (the arc has a scene), but Easy never shows the number.
            waitFor("the camera view model to see a scene") { cameraViewModel(scenario).uiState.value.composition.scene.confidence > 0f }
            scores().assertCountIs(0)
            zoomChips().assertCountIs(0)

            // What CameraScreen does after a real bind: hand the result to the view model.
            runOnMain(scenario) { cameraViewModel(scenario).onCameraBindResult(fakeBind()) }
            seed { it.copy(experienceMode = ExperienceMode.COACH, proControls = true, showScore = true) }
            waitFor("the score chip") { scores().fetchSemanticsNodes().isNotEmpty() }
            waitFor("the zoom presets") { zoomChips().fetchSemanticsNodes().size >= 2 }
            rule.onNode(hasContentDescription("Shutter", substring = true)).assertExists()
        }
    }

    private fun scores() = rule.onAllNodes(hasContentDescription("Composition score", substring = true))

    private fun zoomChips() = rule.onAllNodes(hasContentDescription("Zoom", substring = true))

    private fun SemanticsNodeInteractionCollection.assertCountIs(expected: Int) {
        val actual = fetchSemanticsNodes().size
        assertTrue("expected $expected nodes, found $actual", actual == expected)
    }

    private fun seed(transform: (CoachSettings) -> CoachSettings) = runBlocking { app.container.settingsRepository.update(transform) }

    /** Lets DataStore, the frame flow (real threads) and the main looper (Robolectric's clock) all move on. */
    private fun settle(rounds: Int = 10) {
        repeat(rounds) {
            Thread.sleep(20)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        }
        rule.waitForIdle()
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(150) {
            if (condition()) return
            settle(rounds = 1)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    private fun runOnMain(scenario: ActivityScenario<MainActivity>, block: () -> Unit) {
        scenario.onActivity { block() }
        settle(rounds = 2)
    }

    /**
     * The camera destination's own [CameraViewModel]: the PreviewView sits in that destination, whose
     * back-stack entry is both its lifecycle owner and the view model's store.
     */
    private fun cameraViewModel(scenario: ActivityScenario<MainActivity>): CameraViewModel {
        var vm: CameraViewModel? = null
        scenario.onActivity { activity ->
            val preview = findPreviewView(activity.window.decorView) ?: error("PreviewView not composed")
            val owner = (preview.findViewTreeLifecycleOwner() as? ViewModelStoreOwner)
                ?: preview.findViewTreeViewModelStoreOwner()
                ?: error("No view model store for the camera destination")
            vm = ViewModelProvider(owner, CameraViewModel.factory(app.container))[CameraViewModel::class.java]
        }
        return vm!!
    }

    private fun findPreviewView(view: View): PreviewView? {
        if (view is PreviewView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findPreviewView(view.getChildAt(i))?.let { return it }
        return null
    }

    /** A successful bind of a back lens with a 0.5x-8x zoom range, as CameraX would report it. */
    private fun fakeBind(): CameraBindResult.Success = CameraBindResult.Success(
        camera = proxy(Camera::class.java) { name ->
            if (name == "getCameraInfo") proxy(CameraInfo::class.java) { info -> if (info == "getZoomState") MutableLiveData<Any>() else null } else null
        },
        hasFlashUnit = false,
        lensFacing = LensFacing.BACK,
        exposureRange = Range(0, 0),
        session = CaptureSessionInfo(
            lensFacing = LensFacing.BACK,
            requestedEnhancement = CaptureEnhancement.AUTO,
            availableEnhancements = setOf(CaptureEnhancement.OFF),
            minZoomRatio = 0.5f,
            maxZoomRatio = 8f,
        ),
        controller = null,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, answer: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
            answer(method.name) ?: when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Float.TYPE -> 0f
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as T
}

/** [CompositionCoachApp] with the vision pipeline replaced by [FakeFrameSource]. */
class SmokeTestApp : CompositionCoachApp() {
    override fun createContainer(): AppContainer = AppContainer(this) { FakeFrameSource() }
}

/** A frame source that streams one well-lit, centred face every 50 ms while started, like a person in frame. */
class FakeFrameSource : FrameAnalysisSource {
    @Volatile private var running = false

    override val frames: Flow<FrameAnalysis> = flow {
        var t = 0L
        while (true) {
            if (running) emit(frame(t++))
            delay(50)
        }
    }

    override val imageAnalyzer: ImageAnalysis.Analyzer = ImageAnalysis.Analyzer { it.close() }

    override fun setFrontCamera(isFront: Boolean) = Unit
    override fun setTargetIntervalMs(intervalMs: Long) = Unit
    override fun setPoseDetectionEnabled(enabled: Boolean) = Unit

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
    }

    private fun frame(n: Long) = FrameAnalysis(
        timestampNanos = n * 50_000_000L,
        frameWidth = 480,
        frameHeight = 640,
        faces = listOf(
            DetectedFace(
                id = 1,
                bounds = NormalizedRect(0.38f, 0.25f, 0.62f, 0.45f),
                leftEye = NormalizedPoint(0.46f, 0.33f),
                rightEye = NormalizedPoint(0.54f, 0.33f),
                gaze = GazeDirection.CENTER,
            ),
        ),
    )
}
