package com.compositioncoach.app.ui.about

import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.app.ui.review.AfterActionLogic
import com.compositioncoach.app.ui.review.DiagnosticsFormatter

/**
 * The plain text "Send feedback" shares: the app version, then the last after-action card (verdict, score,
 * outcome, what worked, try next time, crops) and the capture diagnostics table. English-only on purpose: it is
 * read by the developers, not the user. No photo, no location, nothing the card does not already show. Pure
 * (`FeedbackTextTest`).
 */
object FeedbackText {
    fun build(version: String, entry: ReviewEntry?, noCardLine: String): String = buildString {
        appendLine("Version: $version")
        appendLine()
        if (entry == null) {
            appendLine(noCardLine)
            return@buildString
        }
        val result = AfterActionLogic.resultOf(entry)
        val verdict = AfterActionLogic.verdict(entry.report, result)
        appendLine("Last card: ${verdict.label} · ${result.score}")
        appendLine("Outcome: ${entry.outcome.label}")
        AfterActionLogic.worked(entry.report, result).forEach { appendLine("Worked: $it") }
        AfterActionLogic.tryNext(entry.report)?.let { appendLine("Try next time: ${it.text} (+${it.gain})") }
        if (entry.crops.isNotEmpty()) {
            appendLine("Crops: " + entry.crops.joinToString { "${it.aspect.label} ${it.predictedScore}" })
        }
        if (entry.whatIf != null) appendLine("What if: offered")
        val rows = DiagnosticsFormatter.details(entry.diagnostics)
        if (rows.isNotEmpty()) {
            appendLine()
            appendLine("Capture:")
            rows.forEach { appendLine("${it.label}: ${it.value}") }
        }
    }.trimEnd()
}
