package com.compositioncoach.app.ui.camera

import android.view.Surface
import com.compositioncoach.app.camera.CaptureRotation
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.vision.DeviceRotationQuantizer
import com.compositioncoach.vision.FrameCoordinateMapper
import com.compositioncoach.vision.UprightRotation
import com.compositioncoach.vision.gravityToRollDegrees
import com.compositioncoach.vision.referenceRollToDeviceRotation
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The rotation chain, end to end, from **gravity** to **pixels on the portrait-locked screen**, for each
 * of the four physical holds x rear/front camera.
 *
 * Every expected value below is derived from physical first principles in the comments, never from what
 * the code currently returns. Two shipped builds got signs wrong here; the point of this file is that a
 * reviewer can check the derivation by reading, and that no two sign errors can cancel each other again
 * (each stage is asserted separately, not just the composition of all of them).
 *
 * ## The conventions everything below is built from
 *
 * **Device axes** (`SensorEvent`): `+x` out the screen's RIGHT edge, `+y` out its TOP edge, `+z` out of
 * the screen face towards the viewer. A stationary device's accelerometer / the third row of the fused
 * rotation matrix both report the direction of **up** in those axes; call it `U`.
 *
 * **Holds and gravity.** `Surface.ROTATION_90` means the device is turned 90 degrees *counter-clockwise*
 * from natural, as the person looking at the screen sees it — so its RIGHT edge points up.
 * ```
 * hold                       up in device axes (g = 9.81)
 * portrait (natural)         ( 0,  g,  0)
 * right edge up   (ROT_90)   ( g,  0,  0)
 * upside down     (ROT_180)  ( 0, -g,  0)
 * left edge up    (ROT_270)  (-g,  0,  0)
 * ```
 *
 * **Screen coordinates**: x right, y down. In that (y-down) space a *visually clockwise* rotation by
 * `phi` is the matrix `[[cos phi, -sin phi], [sin phi, cos phi]]` — e.g. it sends "right" `(1, 0)` to
 * "down" `(0, 1)`, which is what clockwise means.
 *
 * **Where the world lands on the portrait-locked screen.** With the device rotated `theta` CCW, its own
 * axes are `x_hat = cos(theta) * right + sin(theta) * up` and `y_hat = -sin(theta) * right + cos(theta) * up`
 * (`right` = the photographer's right, `up` = world up). The preview draws the scene in device axes, so a
 * world direction `w` appears on screen at `(w . x_hat, -w . y_hat)` for the rear camera:
 * ```
 * world up    -> ( sin theta, -cos theta)      theta=90  -> ( 1,  0)  the screen's RIGHT
 * world right -> ( cos theta,  sin theta)      theta=90  -> ( 0,  1)  the screen's BOTTOM
 * ```
 * Both are the unrotated vector turned **clockwise by theta**, so:
 * ```
 * displayFrame = rotate_clockwise(theta) . physicalUpFrame
 * ```
 * That single identity is what [OverlayMapper.rotatePointToDisplay] / [OverlayMapper.rotateVectorToDisplay]
 * must implement, and it is what the field report pins: at `theta = 90` the advice "move right" has to
 * draw an arrow toward the screen's bottom, and the top of the scene is visible at the screen's right.
 *
 * **The front camera differs inside the buffer, but not on screen.** A camera looking along `f` with up
 * `u` has image-right `r = f x u`. Rear looks along `-z` so `r = +x` (device-right is image-right); front
 * looks along `+z` so `r = -x` (device-right is image-*left*, which is why a front-camera photo shows your
 * right hand on the image's left). World up therefore lands at `(-sin theta, -cos theta)` in the front
 * camera's *un-mirrored* image — rotated counter-**clockwise** by theta. The preview mirrors that image,
 * and mirroring negates a rotation, so the user still sees world-up at `(sin theta, -cos theta)`: the
 * on-screen mapping above is camera-agnostic, while the buffer correction is not.
 */
class RotationTruthTableTest {

    // --- The four holds --------------------------------------------------------------------------------

    private data class Hold(
        val name: String,
        /** Degrees the phone is turned counter-clockwise from natural portrait. */
        val theta: Int,
        /** `Surface.ROTATION_*` constant for this hold, i.e. what an upright JPEG needs as targetRotation. */
        val surfaceRotation: Int,
        /** Where the physical top-centre of the scene is drawn on the portrait-locked screen. */
        val topCentreOnScreen: NormalizedPoint,
        /** Where the physical-right unit vector points on screen (x right, y down). */
        val physicalRightOnScreen: Pair<Float, Float>,
        /** Clockwise Compose degrees a glyph needs so its own "up" points at physical up. */
        val chromeAngle: Float,
    )

    private val holds = listOf(
        // theta = 0. Nothing is rotated; this is the trusted anchor (verified correct on a Pixel).
        Hold(
            name = "portrait (natural)",
            theta = 0,
            surfaceRotation = Surface.ROTATION_0,
            topCentreOnScreen = NormalizedPoint(0.5f, 0f),
            physicalRightOnScreen = 1f to 0f,
            chromeAngle = 0f,
        ),
        // theta = 90, right edge up. World up lands at (sin 90, -cos 90) = (1, 0) = the screen's RIGHT, so
        // the top-centre of the scene is drawn at the screen's right-centre. World right lands at
        // (cos 90, sin 90) = (0, 1) = the screen's BOTTOM -- the arrow the tester photographed pointing at
        // the top was 180 degrees out. A glyph rotated +90 (clockwise) has its up at (sin 90, -cos 90) =
        // (1, 0), matching world up, so it reads correctly to a photographer whose head is off to the
        // screen's right.
        Hold(
            name = "right edge up (Surface.ROTATION_90)",
            theta = 90,
            surfaceRotation = Surface.ROTATION_90,
            topCentreOnScreen = NormalizedPoint(1f, 0.5f),
            physicalRightOnScreen = 0f to 1f,
            chromeAngle = 90f,
        ),
        // theta = 180, upside down. Both axes flip: world up -> (0, +1) (screen bottom), world right ->
        // (-1, 0) (screen left).
        Hold(
            name = "upside down (Surface.ROTATION_180)",
            theta = 180,
            surfaceRotation = Surface.ROTATION_180,
            topCentreOnScreen = NormalizedPoint(0.5f, 1f),
            physicalRightOnScreen = -1f to 0f,
            chromeAngle = 180f,
        ),
        // theta = 270, left edge up. World up -> (sin 270, -cos 270) = (-1, 0) = the screen's LEFT; world
        // right -> (cos 270, sin 270) = (0, -1) = the screen's TOP. Chrome needs 270 clockwise, which
        // atan2 reports as the equivalent -90 (and which is also the shorter way to animate to).
        Hold(
            name = "left edge up (Surface.ROTATION_270)",
            theta = 270,
            surfaceRotation = Surface.ROTATION_270,
            topCentreOnScreen = NormalizedPoint(0f, 0.5f),
            physicalRightOnScreen = 0f to -1f,
            chromeAngle = -90f,
        ),
    )

    /** `imageInfo.rotationDegrees` for a typical Pixel-style mount. Constant for a portrait-locked app. */
    private val rearR0 = 90
    private val frontR0 = 270

    // A deliberately non-square analysis buffer, so a wrong axis swap cannot hide.
    private val sensorWidth = 640
    private val sensorHeight = 480

    private val eps = 1e-4f

    // --- Stage 1: gravity -> deviceRotationDegrees -----------------------------------------------------

    @Test
    fun `gravity for each hold quantizes to that hold's Surface_ROTATION constant`() {
        for (hold in holds) {
            val (gx, gy, gz) = gravityFor(hold.theta, tiltDegrees = 0f)
            // Sanity: the vector really is the textbook one for this hold before any maths runs on it.
            assertEquals("${hold.name}: gz", 0f, gz, eps)
            val roll = gravityToRollDegrees(gx, gy)
            assertEquals(
                "${hold.name}: quantized device rotation",
                hold.theta,
                settleQuantizer(roll),
            )
        }
    }

    @Test
    fun `raw roll from gravity is the phone's own rotation from natural portrait`() {
        // portrait -> 0, right edge up -> +90, upside down -> 180 (reported as -180 by atan2, the same
        // angle), left edge up -> -90 (== 270). This is the identity DeviceRotationQuantizer relies on.
        assertEquals(0f, gravityToRollDegrees(0f, G), eps)
        assertEquals(90f, gravityToRollDegrees(G, 0f), eps)
        assertEquals(180f, kotlin.math.abs(gravityToRollDegrees(0f, -G)), eps)
        assertEquals(-90f, gravityToRollDegrees(-G, 0f), eps)
    }

    // --- Stage 2: deviceRotation -> analysis (upright) rotation, then a marked pixel through the mapper --

    @Test
    fun `the physical top-centre of the scene lands at the top-centre of the analysis frame, every hold`() {
        for (hold in holds) {
            for (isFront in booleanArrayOf(false, true)) {
                val recovered = markerThroughTheBuffer(hold.theta, isFront, MARKER_TOP_CENTRE)
                val label = "${hold.name}, ${facing(isFront)}"
                assertEquals("$label: marker x", 0.5f, recovered.x, 2e-3f)
                // "~0": the marker was placed a hair inside the top edge so it is unambiguously inside
                // the buffer; it must come back at that same small y, not at 1 - y (upside down) or on
                // the x axis (rotated by 90).
                assertEquals("$label: marker y", MARKER_TOP_CENTRE.y, recovered.y, 2e-3f)
            }
        }
    }

    @Test
    fun `an off-centre marker survives the buffer round trip too, which is what pins mirroring`() {
        // MARKER_UPPER_LEFT is left of centre in the analysis frame -- i.e. left as the photographer sees
        // it, front camera included (Geometry.kt: x = 0 is always the left edge on screen). A dropped or
        // doubled mirror shows up here and nowhere else.
        for (hold in holds) {
            for (isFront in booleanArrayOf(false, true)) {
                val recovered = markerThroughTheBuffer(hold.theta, isFront, MARKER_UPPER_LEFT)
                val label = "${hold.name}, ${facing(isFront)}"
                assertEquals("$label: marker x", MARKER_UPPER_LEFT.x, recovered.x, 2e-3f)
                assertEquals("$label: marker y", MARKER_UPPER_LEFT.y, recovered.y, 2e-3f)
            }
        }
    }

    @Test
    fun `the upright rotation is R0 minus theta for the rear camera and R0 plus theta for the front`() {
        // Spelled out as a table so a reader does not have to run the formula. The two agree at theta 0
        // and theta 180 and differ by 180 degrees at 90 and 270 -- which is exactly the front-camera bug
        // this file's round-trip tests catch: an aspect-ratio check cannot see a 180-degree error.
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(rearR0, 0, isFrontCamera = false))
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(rearR0, 90, isFrontCamera = false))
        assertEquals(270, UprightRotation.computeUprightRotationDegrees(rearR0, 180, isFrontCamera = false))
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(rearR0, 270, isFrontCamera = false))

        assertEquals(270, UprightRotation.computeUprightRotationDegrees(frontR0, 0, isFrontCamera = true))
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(frontR0, 90, isFrontCamera = true))
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(frontR0, 180, isFrontCamera = true))
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(frontR0, 270, isFrontCamera = true))
    }

    @Test
    fun `a landscape hold produces a landscape analysis frame`() {
        // Not a substitute for the round trip above (it cannot see a 180-degree error), but it does pin
        // that the frame the engine reasons about is genuinely re-oriented rather than left portrait.
        for (hold in holds) {
            for (isFront in booleanArrayOf(false, true)) {
                val mapper = mapperFor(hold.theta, isFront)
                val landscapeHold = hold.theta == 90 || hold.theta == 270
                val landscapeFrame = mapper.outputWidth > mapper.outputHeight
                assertEquals(
                    "${hold.name}, ${facing(isFront)}: frame orientation",
                    landscapeHold,
                    landscapeFrame,
                )
            }
        }
    }

    // --- Stage 3: analysis frame -> the portrait-locked screen -----------------------------------------

    @Test
    fun `the physical top-centre of the scene is drawn where the preview actually shows it`() {
        for (hold in holds) {
            val onScreen = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0.5f, 0f), hold.theta)
            assertEquals("${hold.name}: screen x", hold.topCentreOnScreen.x, onScreen.x, eps)
            assertEquals("${hold.name}: screen y", hold.topCentreOnScreen.y, onScreen.y, eps)
        }
    }

    @Test
    fun `the physical-right direction is drawn where physical right actually is`() {
        for (hold in holds) {
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(1f, 0f, hold.theta)
            assertEquals("${hold.name}: arrow dx", hold.physicalRightOnScreen.first, dx, eps)
            assertEquals("${hold.name}: arrow dy", hold.physicalRightOnScreen.second, dy, eps)
        }
    }

    @Test
    fun `the physical-up direction is drawn where physical up actually is`() {
        for (hold in holds) {
            // rotate_clockwise(theta) . (0, -1) = (sin theta, -cos theta).
            val rad = Math.toRadians(hold.theta.toDouble())
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(0f, -1f, hold.theta)
            assertEquals("${hold.name}: up dx", sin(rad).toFloat(), dx, eps)
            assertEquals("${hold.name}: up dy", (-cos(rad)).toFloat(), dy, eps)
        }
    }

    @Test
    fun `points and direction vectors use the same rotation, so an arrow always lands on its target`() {
        // Independent cross-check: the difference of two mapped points must equal the mapped difference.
        for (hold in holds) {
            val a = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0.4f, 0.6f), hold.theta)
            val b = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0.7f, 0.2f), hold.theta)
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(0.3f, -0.4f, hold.theta)
            assertEquals("${hold.name}: dx", b.x - a.x, dx, eps)
            assertEquals("${hold.name}: dy", b.y - a.y, dy, eps)
        }
    }

    // --- Stage 4: chrome counter-rotation ---------------------------------------------------------------

    @Test
    fun `chrome rotates so a glyph's own up points at where physical up appears on screen`() {
        for (hold in holds) {
            val angle = OverlayMapper.uprightChromeAngleDegrees(hold.theta)
            // Compared as an angle, not a float: atan2 reports in (-180, 180], so the 180-degree hold can
            // legitimately come back as -180 (and 270 as -90). Both are the same rotation, and both are
            // what animateFloatAsState should take the short way round to.
            assertEquals(
                "${hold.name}: chrome angle (was $angle)",
                0f,
                RotationAnimation.shortestSignedDelta(hold.chromeAngle, angle),
                1e-3f,
            )

            // ... and check that claim directly rather than trusting the table: a glyph rotated clockwise
            // by `angle` has its up at (sin angle, -cos angle); physical up appears at (sin theta,
            // -cos theta). This is the assertion the two shipped builds would have failed (-90 instead of
            // +90 at ROTATION_90: text reading bottom-to-top).
            val glyph = Math.toRadians(angle.toDouble())
            val physical = Math.toRadians(hold.theta.toDouble())
            assertEquals("${hold.name}: glyph up x", sin(physical).toFloat(), sin(glyph).toFloat(), 1e-3f)
            assertEquals("${hold.name}: glyph up y", (-cos(physical)).toFloat(), (-cos(glyph)).toFloat(), 1e-3f)
        }
    }

    @Test
    fun `chrome is not the negation of the arrow mapping, which is how the last two sign errors cancelled`() {
        // The shipped build paired an inverted rotateVectorToDisplay with a negated atan2 here; the two
        // errors cancelled, so chrome looked right while every arrow and box was 180 degrees out, and
        // "fixing" the obvious half alone flipped chrome straight back to the original field bug. Pin the
        // relationship explicitly: chrome must equal +theta, never -theta, at 90 and 270.
        for (hold in holds.filter { it.theta == 90 || it.theta == 270 }) {
            val angle = OverlayMapper.uprightChromeAngleDegrees(hold.theta)
            val wrongSign = RotationAnimation.shortestSignedDelta(0f, -hold.theta.toFloat())
            assertEquals(
                "${hold.name}: chrome must not be -theta",
                false,
                kotlin.math.abs(RotationAnimation.shortestSignedDelta(angle, wrongSign)) < 1f,
            )
        }
    }

    // --- Stage 5: capture EXIF --------------------------------------------------------------------------

    @Test
    fun `capture rotation is the Surface constant that makes the saved JPEG upright`() {
        for (hold in holds) {
            assertEquals(
                "${hold.name}: ImageCapture.targetRotation",
                hold.surfaceRotation,
                CaptureRotation.surfaceRotationFor(hold.theta),
            )
        }
    }

    // --- Stage 6: roll re-referencing for the horizon analyzer -------------------------------------------

    @Test
    fun `a level hold reads roll ~0 in every orientation`() {
        for (hold in holds) {
            val (gx, gy, _) = gravityFor(hold.theta, tiltDegrees = 0f)
            val raw = gravityToRollDegrees(gx, gy)
            val quantized = settleQuantizer(raw)
            assertEquals(
                "${hold.name}: re-referenced roll",
                0f,
                referenceRollToDeviceRotation(raw, quantized),
                1e-3f,
            )
        }
    }

    @Test
    fun `a 5 degree clockwise horizon tilt reads +5 in every orientation`() {
        // "The horizon appears rotated 5 degrees clockwise in the analysis frame" means the phone has been
        // turned 5 degrees further counter-clockwise than the nominal hold (turning the camera CCW sweeps
        // a fixed scene CW inside the frame). So gravity is the hold's vector rotated by theta + 5, and
        // the residual after re-referencing must be exactly +5 -- in all four holds, including the two
        // where the raw roll wraps past +/-180.
        for (hold in holds) {
            val (gx, gy, _) = gravityFor(hold.theta, tiltDegrees = 5f)
            val raw = gravityToRollDegrees(gx, gy)
            val quantized = settleQuantizer(raw)
            assertEquals("${hold.name}: still quantizes to this hold", hold.theta, quantized)
            assertEquals(
                "${hold.name}: re-referenced roll",
                5f,
                referenceRollToDeviceRotation(raw, quantized),
                1e-3f,
            )
        }
    }

    @Test
    fun `a 5 degree counter-clockwise horizon tilt reads -5 in every orientation`() {
        for (hold in holds) {
            val (gx, gy, _) = gravityFor(hold.theta, tiltDegrees = -5f)
            val raw = gravityToRollDegrees(gx, gy)
            val quantized = settleQuantizer(raw)
            assertEquals("${hold.name}: still quantizes to this hold", hold.theta, quantized)
            assertEquals(
                "${hold.name}: re-referenced roll",
                -5f,
                referenceRollToDeviceRotation(raw, quantized),
                1e-3f,
            )
        }
    }

    // --- Helpers ----------------------------------------------------------------------------------------

    /**
     * "Up in device axes" for a phone held at [theta] degrees counter-clockwise from natural portrait, with
     * an extra [tiltDegrees] of counter-clockwise turn on top (which is what makes the horizon appear
     * rotated `tiltDegrees` *clockwise* inside the frame).
     *
     * Derived, not tabulated: rotating the device CCW by `a` moves world-up to `(sin a, cos a)` in device
     * axes -- check against the table in the class KDoc, e.g. `a = 90` gives `(g, 0)`, right edge up. ✓
     */
    private fun gravityFor(theta: Int, tiltDegrees: Float): Triple<Float, Float, Float> {
        val a = Math.toRadians(theta.toDouble() + tiltDegrees)
        return Triple((G * sin(a)).toFloat(), (G * cos(a)).toFloat(), 0f)
    }

    /** Feeds one roll reading through [DeviceRotationQuantizer] until its debounce window has elapsed. */
    private fun settleQuantizer(rollDegrees: Float): Int {
        val quantizer = DeviceRotationQuantizer()
        quantizer.update(rollDegrees, isReliable = true, nowMs = 0L)
        return quantizer.update(rollDegrees, isReliable = true, nowMs = DeviceRotationQuantizer.DEBOUNCE_MS + 1)
    }

    private fun facing(isFront: Boolean) = if (isFront) "front camera" else "rear camera"

    private fun mapperFor(theta: Int, isFront: Boolean): FrameCoordinateMapper {
        val r0 = if (isFront) frontR0 else rearR0
        return FrameCoordinateMapper(
            sensorWidth = sensorWidth,
            sensorHeight = sensorHeight,
            rotationDegrees = UprightRotation.computeUprightRotationDegrees(r0, theta, isFront),
            cropLeft = 0,
            cropTop = 0,
            cropRight = sensorWidth,
            cropBottom = sensorHeight,
            mirror = isFront,
        )
    }

    /**
     * The whole buffer model in one place. Places [marker] (given in the physical-up **analysis** frame we
     * want to end up with) into the raw sensor buffer by working *backwards* from the display-upright
     * image, then runs the real [FrameCoordinateMapper] forwards and returns where it says the marker is.
     * A correct chain returns [marker] unchanged.
     *
     * Step by step, for a `sensorWidth x sensorHeight` buffer:
     *  1. The **display-upright image** `DU` is by definition the buffer rotated clockwise by
     *     `R0 = imageInfo.rotationDegrees`. Its size is the buffer's, swapped at 90/270.
     *  2. Where is the marker in `DU`? From the class KDoc's physics, *not* from any of the code under
     *     test:
     *      - Rear: `DU` is literally what the portrait-locked preview shows, and the preview shows the
     *        physical-up frame turned clockwise by `theta`, so `du = rotate_cw(theta) . marker`.
     *      - Front: `DU` is the un-mirrored front image, in which world-up lands rotated *counter*-
     *        clockwise by `theta` (reversed optical axis). Its un-mirrored physically-upright twin is
     *        `mirror(marker)` (the analysis frame is mirrored; undo that first), so
     *        `du = rotate_cw(-theta) . mirror(marker)`.
     *  3. Scale `du` by `DU`'s pixel size, then rotate *back* by `R0` (counter-clockwise) to land on the
     *     raw sensor pixel the camera would have delivered.
     *  4. Hand that sensor pixel to the mapper built with [UprightRotation]'s corrected rotation and the
     *     camera's mirror flag -- the exact object `VisionPipeline` builds per frame.
     */
    private fun markerThroughTheBuffer(theta: Int, isFront: Boolean, marker: NormalizedPoint): NormalizedPoint {
        val r0 = if (isFront) frontR0 else rearR0

        // Step 2.
        val du = if (isFront) {
            rotateUnitSquareCw(NormalizedPoint(1f - marker.x, marker.y), (360 - theta) % 360)
        } else {
            rotateUnitSquareCw(marker, theta)
        }

        // Step 1 + 3: DU's pixel size, the marker's pixel position in it, then back to sensor pixels.
        val duWidth = if (r0 == 90 || r0 == 270) sensorHeight else sensorWidth
        val duHeight = if (r0 == 90 || r0 == 270) sensorWidth else sensorHeight
        val duX = du.x * duWidth
        val duY = du.y * duHeight
        val (sensorX, sensorY) = rotateBufferPointCcw(duX, duY, sensorWidth, sensorHeight, r0)

        // Step 4.
        return mapperFor(theta, isFront).sensorPointToNormalized(sensorX, sensorY)
    }

    /**
     * Rotate a point of the unit square clockwise by [degrees]. Checked at 90: the top-left corner
     * `(0, 0)` goes to `(1, 0)`, the top-**right** — where the top-left corner of a photo ends up when
     * you physically turn the photo clockwise.
     */
    private fun rotateUnitSquareCw(p: NormalizedPoint, degrees: Int): NormalizedPoint =
        when (((degrees % 360) + 360) % 360) {
            0 -> p
            90 -> NormalizedPoint(1f - p.y, p.x)
            180 -> NormalizedPoint(1f - p.x, 1f - p.y)
            270 -> NormalizedPoint(p.y, 1f - p.x)
            else -> error("degrees must be a multiple of 90")
        }

    /**
     * Inverse of "rotate a `w x h` buffer clockwise by [degrees]": takes a point in the rotated image and
     * returns the pixel it came from in the original `w x h` buffer. The forward map at 90 is
     * `(x, y) -> (h - y, x)`, so the inverse is `(rx, ry) -> (ry, h - rx)`.
     */
    private fun rotateBufferPointCcw(rx: Float, ry: Float, w: Int, h: Int, degrees: Int): Pair<Float, Float> =
        when (((degrees % 360) + 360) % 360) {
            0 -> rx to ry
            90 -> ry to (h - rx)
            180 -> (w - rx) to (h - ry)
            270 -> (w - ry) to rx
            else -> error("degrees must be a multiple of 90")
        }

    private companion object {
        const val G = 9.81f

        /** The physical top-centre of the scene, a hair inside the frame so it is unambiguously in it. */
        val MARKER_TOP_CENTRE = NormalizedPoint(0.5f, 0.05f)

        /** Physically above centre and to the (photographer's) left: the one that can see a mirror error. */
        val MARKER_UPPER_LEFT = NormalizedPoint(0.2f, 0.1f)
    }
}
