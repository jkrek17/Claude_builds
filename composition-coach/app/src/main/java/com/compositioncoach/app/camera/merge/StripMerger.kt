package com.compositioncoach.app.camera.merge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.createBitmap
import com.compositioncoach.app.camera.CapturedJpeg
import com.compositioncoach.app.camera.SharpnessScorer
import com.compositioncoach.vision.PixelShift
import com.compositioncoach.vision.merge.BurstAlign
import com.compositioncoach.vision.merge.DetailTransfer
import com.compositioncoach.vision.merge.MotionRejectedAverage
import com.compositioncoach.vision.merge.SubPixelShift
import com.compositioncoach.vision.merge.ToneLift
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Strip orchestration of the burst merge: the Android half that feeds the pure maths in
 * `com.compositioncoach.vision.merge`. Two phases, mirroring `FocusStacker` + `FocusMerge`:
 *
 * 1. [prepare] decodes every JPEG once at 1/[GUIDE_SCALE] size (a DCT-scaled decode, cheap), picks the
 *    sharpest as the reference ([SharpnessScorer]), refines each frame's gyro shift by block matching
 *    ([BurstAlign]), and computes the motion-rejecting weights on that guide ([MotionRejectedAverage]). Frames
 *    that agree with the reference almost nowhere are dropped. Memory: the guides and weight maps, a few MB each.
 * 2. [merge] walks the image in [STRIP_ROWS]-row strips: one `BitmapRegionDecoder` region per kept frame
 *    (decoded in parallel), weighted average, reference detail added back ([DetailTransfer]), optional tone
 *    lift ([ToneLift]), written into the output bitmap. Peak memory: the output bitmap plus one strip per
 *    frame, never a decoded frame.
 *
 * Everything works in the capture's stored orientation; the caller writes the EXIF orientation tag.
 */
class StripMerger {

    /**
     * The result of [prepare]: the frames that will be merged (capture order), which of them is the
     * reference, their full-resolution alignment shifts and normalised guide-grid weights.
     */
    class Prepared(
        val frames: List<CapturedJpeg>,
        val referenceIndex: Int,
        val shifts: List<PixelShift>,
        val weights: List<FloatArray>,
        val guideWidth: Int,
        val guideHeight: Int,
        /** Index in the list given to [prepare] of each kept frame. */
        val sourceIndices: List<Int>,
        /** Frames dropped for disagreeing with the reference almost everywhere (or being a different size). */
        val dropped: Int,
    ) {
        val framesUsed: Int get() = frames.size
    }

    /**
     * Aligns and weights [frames]. [gyroShift] returns the full-resolution, stored-orientation shift of frame
     * `to` relative to frame `from` (`PixelShift.NONE` when unknown); it is asked once the reference is known.
     */
    fun prepare(frames: List<CapturedJpeg>, gyroShift: (from: Int, to: Int) -> PixelShift): Prepared {
        require(frames.isNotEmpty()) { "no frames" }
        val guides = ArrayList<FloatArray>(frames.size)
        val scores = ArrayList<Float>(frames.size)
        var gw = 0
        var gh = 0
        for ((i, frame) in frames.withIndex()) {
            val small = BitmapFactory.decodeByteArray(
                frame.bytes, 0, frame.bytes.size, BitmapFactory.Options().apply { inSampleSize = GUIDE_SCALE },
            ) ?: error("Cannot decode frame $i")
            val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            if (i == 0) {
                gw = small.width
                gh = small.height
            }
            val sameSize = small.width == gw && small.height == gh
            small.recycle()
            guides += if (sameSize) MotionRejectedAverage.luma(pixels) else FloatArray(0)
            scores += if (sameSize) SharpnessScorer.score(SharpnessScorer.lumaFromArgb(pixels), gw, gh) else -1f
        }
        val reference = SharpnessScorer.sharpestIndex(scores)
        val scaleX = frames[reference].width.toFloat() / gw
        val scaleY = frames[reference].height.toFloat() / gh

        // Align and weight every other frame against the reference; drop the ones that do not belong.
        val keptIndices = ArrayList<Int>()
        val keptShifts = ArrayList<SubPixelShift>()
        val keptRaw = ArrayList<FloatArray>()
        for (i in frames.indices) {
            if (i == reference) {
                keptIndices += i
                keptShifts += SubPixelShift.NONE
                keptRaw += FloatArray(gw * gh) { 1f }
                continue
            }
            if (guides[i].size != gw * gh) continue
            val gyro = gyroShift(reference, i)
            val initial = SubPixelShift(gyro.dx / scaleX, gyro.dy / scaleY)
            val shift = BurstAlign.refine(guides[reference], guides[i], gw, gh, initial)
            val raw = MotionRejectedAverage.rawWeights(guides[reference], BurstAlign.shifted(guides[i], gw, gh, shift), gw, gh)
            if (MotionRejectedAverage.meanWeight(raw) < MotionRejectedAverage.MIN_MEAN_WEIGHT) continue
            keptIndices += i
            keptShifts += shift
            keptRaw += raw
        }
        MotionRejectedAverage.normalize(keptRaw)
        return Prepared(
            frames = keptIndices.map { frames[it] },
            referenceIndex = keptIndices.indexOf(reference),
            shifts = keptShifts.map { it.scaled(scaleX, scaleY).roundedToPixels() },
            weights = keptRaw,
            guideWidth = gw,
            guideHeight = gh,
            sourceIndices = keptIndices,
            dropped = frames.size - keptIndices.size,
        )
    }

    /**
     * Merges the prepared frames into one stored-orientation bitmap; [toneLut] (from [ToneLift.lut]) is applied
     * last when it is not the identity. The caller recycles the bitmap once it is written.
     */
    suspend fun merge(prepared: Prepared, toneLut: FloatArray? = null): Bitmap = coroutineScope {
        val frames = prepared.frames
        val decoders = frames.map { regionDecoder(it.bytes) }
        try {
            val width = decoders[0].width
            val height = decoders[0].height
            check(decoders.all { it.width == width && it.height == height }) { "Frames differ in size" }
            val ref = prepared.referenceIndex
            val noise = referenceNoise(decoders[ref], width, height)
            val out = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val outRows = IntArray(width * STRIP_ROWS)
            val lut = toneLut?.takeUnless { ToneLift.isIdentity(it) }
            var y0 = 0
            while (y0 < height) {
                val rows = minOf(STRIP_ROWS, height - y0)
                val strips = decoders.mapIndexed { i, decoder ->
                    async {
                        if (i == ref) {
                            // One row of halo each side, for the detail filter.
                            val top = maxOf(0, y0 - 1)
                            val bottom = minOf(height, y0 + rows + 1)
                            MotionRejectedAverage.Strip(decode(decoder, width, top, bottom), top, bottom - top, PixelShift.NONE)
                        } else {
                            val shift = prepared.shifts[i]
                            val top = (y0 + shift.dy).coerceIn(0, height - 1)
                            val bottom = (y0 + rows + shift.dy).coerceIn(top + 1, height)
                            MotionRejectedAverage.Strip(decode(decoder, width, top, bottom), top, bottom - top, shift)
                        }
                    }
                }.awaitAll()
                MotionRejectedAverage.blendStrip(strips, width, height, y0, rows, prepared.weights, prepared.guideWidth, prepared.guideHeight, outRows)
                val refStrip = strips[ref]
                DetailTransfer.apply(outRows, refStrip.pixels, refStrip.top, refStrip.rows, width, height, y0, rows, noise)
                if (lut != null) ToneLift.apply(outRows, width * rows, lut)
                out.setPixels(outRows, 0, width, 0, y0, width, rows)
                y0 += rows
            }
            out
        } finally {
            decoders.forEach { it.recycle() }
        }
    }

    /** Per-pixel noise (luma levels) of the reference, measured on a band through the middle of the frame. */
    private fun referenceNoise(decoder: BitmapRegionDecoder, width: Int, height: Int): Float {
        val rows = minOf(NOISE_BAND_ROWS, height)
        val top = (height - rows) / 2
        val pixels = decode(decoder, width, top, top + rows)
        return DetailTransfer.estimateNoise(IntArray(pixels.size) { DetailTransfer.lumaOf(pixels[it]) }, width, rows)
    }

    /** Rows [top] until [bottom] of the full-width frame as ARGB. */
    private fun decode(decoder: BitmapRegionDecoder, width: Int, top: Int, bottom: Int): IntArray {
        val bitmap = decoder.decodeRegion(
            Rect(0, top, width, bottom), BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("Region decode failed")
        val rows = bottom - top
        val pixels = IntArray(width * rows)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, rows)
        bitmap.recycle()
        return pixels
    }

    private fun regionDecoder(bytes: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } ?: error("No region decoder")

    companion object {
        /** Guide reduction per side (the spec's 1/4). */
        const val GUIDE_SCALE = 4

        /**
         * Rows per strip. Region decoding of a JPEG re-reads everything above the strip, so fewer, taller strips
         * cost less decode time; 384 rows of a 12 MP frame is about 6 MB per frame.
         */
        const val STRIP_ROWS = 384

        /** Rows of the reference used to measure its noise. */
        private const val NOISE_BAND_ROWS = 256
    }
}
