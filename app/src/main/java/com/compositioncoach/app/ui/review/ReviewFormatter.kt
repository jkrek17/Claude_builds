package com.compositioncoach.app.ui.review

import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.SubjectKind

/**
 * Pure text/trimming logic for [ReviewScreen] — kept free of Compose so it is unit-testable on the plain
 * JVM (see ReviewFormatterTest). The review screen is meant to be readable "at a glance" right after the
 * shutter fires, so it never grows past a small, fixed amount of content.
 */
object ReviewFormatter {
    /** Above this many strengths+improvements combined, each list is trimmed down to [TRIMMED_COUNT]. */
    const val TRIM_THRESHOLD = 4
    const val TRIMMED_COUNT = 2

    data class TrimmedFeedback(val strengths: List<String>, val improvements: List<String>)

    /**
     * Keeps [strengths]/[improvements] untouched when there are [TRIM_THRESHOLD] or fewer of them
     * combined; otherwise keeps only the top [TRIMMED_COUNT] of each (lists already arrive ranked by the
     * engine, so "top" just means "first").
     */
    fun trim(strengths: List<String>, improvements: List<String>): TrimmedFeedback =
        if (strengths.size + improvements.size > TRIM_THRESHOLD) {
            TrimmedFeedback(strengths.take(TRIMMED_COUNT), improvements.take(TRIMMED_COUNT))
        } else {
            TrimmedFeedback(strengths, improvements)
        }

    /** "Subject: object" when the shot was coached around a [SubjectKind.OBJECT] primary subject, else null. */
    fun subjectLine(primarySubject: DetectedSubject?): String? =
        "Subject: object".takeIf { primarySubject?.kind == SubjectKind.OBJECT }
}
