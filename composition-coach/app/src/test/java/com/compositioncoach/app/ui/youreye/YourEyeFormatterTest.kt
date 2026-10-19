package com.compositioncoach.app.ui.youreye

import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Your eye" formatting: three finished sentences, follow rates in tenths, best three, the empty state, reset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YourEyeFormatterTest {

    private fun data(
        tendencies: List<String> = emptyList(),
        keepers: Int = 0,
        rates: List<Pair<String, Float>> = emptyList(),
        best: List<Pair<Uri, Int>> = emptyList(),
    ) = YourEyeData(tendencies, keepers, rates, best)

    @Test
    fun `nothing at all is the empty state`() {
        assertTrue(YourEyeFormatter.isEmpty(YourEyeData.EMPTY))
        assertFalse(YourEyeFormatter.isEmpty(data(keepers = 1)))
    }

    @Test
    fun `three tendencies at most, each a finished sentence, no repeats`() {
        val shown = YourEyeFormatter.tendencies(data(listOf("You like tight crops", "You like tight crops", "Level.", " ", "A", "B")))
        assertEquals(listOf("You like tight crops.", "Level.", "A."), shown)
    }

    @Test
    fun `follow rates read as N in 10, most and least differ`() {
        val d = data(rates = listOf("Closer" to 0.91f, "Level" to 0.5f, "Give looking room" to 0.14f))
        assertEquals("Closer" to 9, YourEyeFormatter.followedMost(d))
        assertEquals("Give looking room" to 1, YourEyeFormatter.followedLeast(d))
        assertNull(YourEyeFormatter.followedLeast(data(rates = listOf("Closer" to 0.9f))))
        assertNull(YourEyeFormatter.followedMost(YourEyeData.EMPTY))
        assertEquals(10, YourEyeFormatter.inTen(1.4f))
    }

    @Test
    fun `best three, highest first`() {
        val uris = (1..4).map { Uri.parse("content://p/$it") }
        val best = YourEyeFormatter.best(data(best = listOf(uris[0] to 80, uris[1] to 95, uris[2] to 70, uris[3] to 90)))
        assertEquals(listOf(95, 90, 80), best.map { it.second })
    }

    @Test
    fun `the fake source resets to empty and counts resets`() = runTest {
        val source = FakeYourEyeSource(data(keepers = 12))
        assertEquals(12, source.data.first().keepers30d)
        source.resetCoachingPreferences()
        assertEquals(YourEyeData.EMPTY, source.data.first())
        assertEquals(1, source.resets)
    }
}
