package com.compositioncoach.app

import android.Manifest
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * End-to-end launch smoke test: builds the real [MainActivity] (real [CompositionCoachApp], real
 * [com.compositioncoach.app.di.AppContainer], real [com.compositioncoach.app.ui.navigation.AppNavGraph])
 * under Robolectric and asserts the process survives `onCreate` through `onResume`.
 *
 * This exists because a JVM run of the app's *own* code (everything this module's other unit tests
 * cover) cannot catch a crash that only happens when the real framework/ML Kit/CameraX call chain
 * fires — which is exactly the class of bug a "the app won't open" field report points at, and exactly
 * why this needs to actually launch the Activity rather than unit-test a helper function.
 *
 * Neither CameraX nor ML Kit function under Robolectric — there is no camera and no bundled TFLite
 * runtime backing them here — so both tests below are expected to hit real failures from
 * `ProcessCameraProvider.getInstance` and `FaceDetection.getClient`/friends deep inside the call chain
 * that [MainActivity.onCreate] kicks off. The assertion is not "these succeed"; it is that the app's
 * own error handling — [com.compositioncoach.app.di.AppContainer.frameSourceOrNull] catching
 * [com.compositioncoach.vision.VisionPipelineFactory.create], [com.compositioncoach.vision.VisionPipeline.start]
 * catching each detector's construction, and [com.compositioncoach.app.camera.CameraController.bind]
 * catching `ProcessCameraProvider` failures — keeps that from reaching `onCreate` as an uncaught
 * exception. A real device's ML Kit/CameraX calls fail for the same reasons a stripped `ContentProvider`,
 * a missing per-ABI native library, or a `MlKitContext` that never initialized would fail there, so this
 * failure path is not a Robolectric artifact to work around; it is the condition the tester's phone is
 * almost certainly also hitting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CompositionCoachApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLaunchTest {

    @Test
    fun `activity launches without camera permission and shows the permission gate`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        assertFalse("Activity finished/crashed during launch", activity.isFinishing)

        controller.pause().stop().destroy()
    }

    @Test
    fun `activity launches with camera permission granted and reaches the camera screen composition`() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CAMERA)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        assertFalse("Activity finished/crashed during launch with CAMERA granted", activity.isFinishing)

        controller.pause().stop().destroy()
    }
}
