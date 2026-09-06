package com.compositioncoach.app.ui.review

import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReviewFormatterTest {

    private val bounds = NormalizedRect(0.2f, 0.2f, 0.6f, 0.6f)

    @Test
    fun `four or fewer items combined are shown untouched`() {
        val trimmed = ReviewFormatter.trim(strengths = listOf("a", "b"), improvements = listOf("c", "d"))
        assertEquals(listOf("a", "b"), trimmed.strengths)
        assertEquals(listOf("c", "d"), trimmed.improvements)
    }

    @Test
    fun `exactly four combined is the untouched boundary, five trims both lists`() {
        val untouched = ReviewFormatter.trim(strengths = listOf("a", "b", "c"), improvements = listOf("d"))
        assertEquals(listOf("a", "b", "c"), untouched.strengths)
        assertEquals(listOf("d"), untouched.improvements)

        val trimmed = ReviewFormatter.trim(strengths = listOf("a", "b", "c"), improvements = listOf("d", "e"))
        assertEquals(listOf("a", "b"), trimmed.strengths)
        assertEquals(listOf("d", "e"), trimmed.improvements)
    }

    @Test
    fun `trimming keeps the first two of each list, already-ranked order`() {
        val trimmed = ReviewFormatter.trim(
            strengths = listOf("s1", "s2", "s3"),
            improvements = listOf("i1", "i2", "i3"),
        )
        assertEquals(listOf("s1", "s2"), trimmed.strengths)
        assertEquals(listOf("i1", "i2"), trimmed.improvements)
    }

    @Test
    fun `a short list stays shorter than TRIMMED_COUNT even when the combined total is trimmed`() {
        val trimmed = ReviewFormatter.trim(strengths = listOf("only one"), improvements = listOf("i1", "i2", "i3", "i4"))
        assertEquals(listOf("only one"), trimmed.strengths)
        assertEquals(listOf("i1", "i2"), trimmed.improvements)
    }

    @Test
    fun `subjectLine is only shown for an OBJECT-kind primary subject`() {
        val objectSubject = DetectedSubject(id = 1, kind = SubjectKind.OBJECT, bounds = bounds)
        val faceSubject = DetectedSubject(id = 2, kind = SubjectKind.FACE, bounds = bounds)

        assertEquals("Subject: object", ReviewFormatter.subjectLine(objectSubject))
        assertNull(ReviewFormatter.subjectLine(faceSubject))
        assertNull(ReviewFormatter.subjectLine(null))
    }
}
