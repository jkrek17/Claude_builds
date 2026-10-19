package com.compositioncoach.app.ui.review

import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.ui.camera.ZoomChipFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Turns [CaptureDiagnostics] into the review card's one quiet line ("12 MP · 1/120 s · ISO 160 · Night ·
 * steady") and its tap-to-expand detail table — what lets a tester compare a shot with the stock camera's
 * on equal terms. Every field is optional: anything the device did not report is simply left out, never
 * shown as "unknown". Pure, unit-tested in `DiagnosticsFormatterTest`.
 */
object DiagnosticsFormatter {

    /** One line of the detail table. */
    data class Row(val label: String, val value: String)

    private const val SEPARATOR = " · "

    /** The one-line summary, or null when the camera reported nothing worth a line. */
    fun summary(d: CaptureDiagnostics): String? = listOfNotNull(
        megapixels(d.widthPx, d.heightPx),
        d.shutterText,
        d.iso?.let { "ISO $it" },
        d.enhancement.takeIf { it != CaptureEnhancement.OFF }?.label,
        d.steadiness?.let { steadinessWord(it) },
    ).takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR)

    /** The expanded two-column table, in the order a photographer reads an EXIF panel. */
    fun details(d: CaptureDiagnostics): List<Row> = listOfNotNull(
        resolution(d.widthPx, d.heightPx)?.let { Row("Resolution", it) },
        d.shutterText?.let { Row("Shutter", it) },
        d.iso?.let { Row("ISO", it.toString()) },
        d.aperture?.let { Row("Aperture", "f/${trimmed(it)}") },
        d.focalLengthMm?.let { Row("Focal length", "${trimmed(it)} mm") },
        d.lensFacing?.let { Row("Lens", if (it == LensFacing.FRONT) "Front" else "Back") },
        Row("Enhancement", d.enhancement.label),
        d.captureMode?.let { Row("Capture mode", sentenceCase(it)) },
        d.jpegQuality?.let { Row("JPEG quality", it.toString()) },
        steadinessDetail(d.steadiness, d.waitedForSteadyMs)?.let { Row("Steadiness", it) },
        d.zoomRatio?.let { Row("Zoom", ZoomChipFormatter.format(it)) },
        d.exposureCompensationIndex?.let { Row("Exposure comp.", exposureSteps(it)) },
        d.captureLatencyMs?.let { Row("Shutter latency", "$it ms") },
    )

    /** "12 MP" from 4000x3000; one decimal under 10 MP ("8.3 MP", "2 MP"). Null without both sides. */
    fun megapixels(widthPx: Int?, heightPx: Int?): String? {
        if (widthPx == null || heightPx == null || widthPx <= 0 || heightPx <= 0) return null
        val mp = widthPx.toLong() * heightPx / 1_000_000.0
        return if (mp >= 10.0) "${Math.round(mp)} MP" else "${trimmed(mp.toFloat())} MP"
    }

    fun resolution(widthPx: Int?, heightPx: Int?): String? {
        val mp = megapixels(widthPx, heightPx) ?: return null
        return "$widthPx × $heightPx$SEPARATOR$mp"
    }

    fun steadinessWord(steadiness: Steadiness): String = when (steadiness) {
        Steadiness.STEADY -> "steady"
        Steadiness.SLIGHT_MOTION -> "slight motion"
        Steadiness.MOVING -> "moving"
    }

    /** "Steady · waited 240 ms"; the wait is left out when the shutter did not have to wait. */
    fun steadinessDetail(steadiness: Steadiness?, waitedMs: Long?): String? {
        val word = steadiness?.let { steadinessWord(it).replaceFirstChar(Char::uppercaseChar) }
        val wait = waitedMs?.takeIf { it > 0 }?.let { "waited $it ms" }
        return when {
            word != null && wait != null -> "$word$SEPARATOR$wait"
            word != null -> word
            wait != null -> wait.replaceFirstChar(Char::uppercaseChar)
            else -> null
        }
    }

    /** "0", "+2 steps", "-1 step": the raw index, since the EV size of a step is per-device. */
    fun exposureSteps(index: Int): String = when {
        index == 0 -> "0"
        else -> "${if (index > 0) "+" else "-"}${abs(index)} step${if (abs(index) == 1) "" else "s"}"
    }

    /** "MAXIMIZE_QUALITY" -> "Maximize quality"; already-readable text passes through with a capital. */
    fun sentenceCase(raw: String): String =
        raw.replace('_', ' ').trim().lowercase(Locale.US).replaceFirstChar(Char::uppercaseChar)

    /** One decimal, no trailing ".0": 1.85 -> "1.9", 2.0 -> "2". */
    private fun trimmed(value: Float): String {
        val text = String.format(Locale.US, "%.1f", value)
        return text.removeSuffix(".0")
    }
}
