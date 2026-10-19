package com.compositioncoach.app.di

import android.net.Uri
import com.compositioncoach.app.ui.review.reviewImageCacheKey
import com.compositioncoach.composition.model.CompositionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** When a review's pre-level original becomes garbage, and how the review image's cache key moves with revisions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewOriginalsTest {

    private val photo = Uri.parse("content://media/external/images/media/42")
    private val original = Uri.parse("file:///cache/originals/cc_original_1.jpg")
    private val levelled = ReviewEntry(photo, CompositionResult.empty(), revision = 1, originalPhotoUri = original)

    @Test
    fun `Keep or Retake clearing the store frees the original`() {
        assertEquals(original, staleOriginalOf(levelled, null))
    }

    @Test
    fun `a newer capture replacing the review frees the original`() {
        val next = ReviewEntry(Uri.parse("content://media/external/images/media/43"), CompositionResult.empty())
        assertEquals(original, staleOriginalOf(levelled, next))
    }

    @Test
    fun `an update that keeps the original, or an entry without one, frees nothing`() {
        assertNull(staleOriginalOf(levelled, levelled.copy(processing = false)))
        assertNull(staleOriginalOf(levelled.copy(originalPhotoUri = null), null))
        assertNull(staleOriginalOf(null, levelled))
    }

    @Test
    fun `the review image cache key changes with every revision`() {
        val uri = photo.toString()
        assertEquals("$uri#0", reviewImageCacheKey(uri, 0))
        assertNotEquals(reviewImageCacheKey(uri, 0), reviewImageCacheKey(uri, 1))
    }
}
