package com.compositioncoach.app.ui.review

import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.SubjectKind

/**
 * Pure text/trimming logic for [ReviewScreen] — kept free of Compose so it is unit-testable on the plain
 * JVM (see ReviewFormatterTest). The review screen is meant to be readable "at a glance" right after the
 * shutter fires, so it never grows past a small, fixed amount of content.
 */
object ReviewFormatter {
    /** The review card never shows more than this many strengths, or this many improvements. */
    const val TRIMMED_COUNT = 2

    data class TrimmedFeedback(val strengths: List<String>, val improvements: List<String>)

    /**
     * Keeps the first [TRIMMED_COUNT] of [strengths] and of [improvements] (lists already arrive ranked by
     * the engine, so "first" just means "top") — the review card is meant to be readable at a glance right
     * after the shutter fires, so it always shows *up to* two of each, never more, regardless of how many
     * the engine found.
     */
    fun trim(strengths: List<String>, improvements: List<String>): TrimmedFeedback =
        TrimmedFeedback(strengths.take(TRIMMED_COUNT), improvements.take(TRIMMED_COUNT))

    /** "Subject: object" when the shot was coached around a [SubjectKind.OBJECT] primary subject, else null. */
    fun subjectLine(primarySubject: DetectedSubject?): String? =
        "Subject: object".takeIf { primarySubject?.kind == SubjectKind.OBJECT }
}
