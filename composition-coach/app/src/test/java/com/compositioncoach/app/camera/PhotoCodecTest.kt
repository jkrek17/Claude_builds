package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCodecTest {

    private fun isRed(c: Int) = Color.red(c) > 180 && Color.blue(c) < 80
    private fun isBlue(c: Int) = Color.blue(c) > 180 && Color.red(c) < 80

    /** A 40x20 JPEG, left half red, right half blue, tagged with [orientation]. */
    private fun jpeg(orientation: Int): Uri {
        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until 20) for (x in 0 until 40) bitmap.setPixel(x, y, if (x < 20) Color.RED else Color.BLUE)
        val file = File.createTempFile("codec", ".jpg", RuntimeEnvironment.getApplication().cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    @Test
    fun `decode applies the EXIF rotation`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val upright = PhotoCodec.decodeUpright(resolver, jpeg(ExifInterface.ORIENTATION_ROTATE_90), null)
        assertNotNull(upright)
        assertEquals(20, upright!!.width)
        assertEquals(40, upright.height)
        // Turned clockwise: the stored left half (red) is now on top.
        assertTrue(isRed(upright.getPixel(10, 5)))
        assertTrue(isBlue(upright.getPixel(10, 35)))
    }

    @Test
    fun `decode applies the selfie mirror and downscales for analysis`() {
        val resolver = RuntimeEnvironment.getApplication().contentResolver
        val mirrored = PhotoCodec.decodeUpright(resolver, jpeg(ExifInterface.ORIENTATION_FLIP_HORIZONTAL), 20)!!
        assertEquals(20, mirrored.width)
        assertEquals(10, mirrored.height)
        assertTrue(isBlue(mirrored.getPixel(3, 5)))
        assertTrue(isRed(mirrored.getPixel(16, 5)))
    }

    @Test
    fun `crop rectangle, names and sampling`() {
        val r = PhotoCodec.cropRectPx(4000, 3000, NormalizedRect(0.1f, 0.2f, 0.6f, 1.2f))
        assertEquals(400, r.left)
        assertEquals(600, r.top)
        assertEquals(2400, r.right)
        assertEquals(3000, r.bottom)
        assertEquals(1, PhotoCodec.cropRectPx(10, 10, NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f)).width())
        assertEquals("CC_20260101_120000_000_crop.jpg", PhotoCodec.derivedName("CC_20260101_120000_000.jpg", "_crop", "x.jpg"))
        assertEquals("CC_1_silk.jpg", PhotoCodec.derivedName(null, "_silk", "CC_1.jpg"))
        assertEquals(2, PhotoCodec.sampleSizeFor(4000, 1024))
        assertEquals(1, PhotoCodec.sampleSizeFor(1500, 1024))
        assertEquals(1024 to 768, PhotoCodec.fitWithin(2000, 1500, 1024))
    }

    @Test
    fun `rotation by quarter turns is exact`() {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).apply { setPixel(0, 0, Color.GREEN) }
        val turned = PhotoCodec.rotateClockwise(bitmap, 90)
        assertEquals(2, turned.width)
        assertEquals(3, turned.height)
        assertEquals(Color.GREEN, turned.getPixel(1, 0))
    }

    @Test
    fun `background separator bitmap wrapper keeps the subject`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 100, 50)) }
        val mask = SubjectMask(32, 32, FloatArray(32 * 32) { i -> if (i % 32 in 8..23 && i / 32 in 8..23) 1f else 0f })
        val out = BackgroundSeparator.apply(bitmap, mask)
        assertEquals(bitmap.getPixel(16, 16), out.getPixel(16, 16))
        assertTrue(Color.red(out.getPixel(1, 1)) < 160)
    }
}
