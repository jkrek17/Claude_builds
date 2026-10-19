package com.compositioncoach.app.ui.youreye

import android.net.Uri
import com.compositioncoach.app.di.BestShot
import com.compositioncoach.app.di.FollowRate
import com.compositioncoach.app.di.YourEyeSnapshot
import com.compositioncoach.composition.model.MetricCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The journal's [YourEyeSnapshot] as the screen's [YourEyeData], and the reset wiring. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class YourEyeMapperTest {

    private val snapshot = YourEyeSnapshot(
        tendencies = listOf("You like tight, centred portraits"),
        keepersLast30Days = 7,
        followRates = mapOf(
            MetricCategory.HORIZON to FollowRate(followed = 8, shown = 10),
            MetricCategory.HEADROOM to FollowRate(followed = 1, shown = 4),
            // Shown too rarely to say anything about the user.
            MetricCategory.LIGHT to FollowRate(followed = 2, shown = 2),
        ),
        bestThree = listOf(BestShot("content://media/1", 91, "KEEPER", 1L), BestShot("content://media/2", 84, "KEEPER", 2L)),
        totalPhotos = 12,
    )

    @Test
    fun `fields map one to one, follow rates need enough showings and get plain-word labels`() {
        val data = YourEyeMapper.map(snapshot) { it.name.lowercase() }
        assertEquals(listOf("You like tight, centred portraits"), data.tendencies)
        assertEquals(7, data.keepers30d)
        assertEquals(listOf("horizon" to 0.8f, "headroom" to 0.25f), data.followRates)
        assertEquals(listOf(Uri.parse("content://media/1") to 91, Uri.parse("content://media/2") to 84), data.best)
        assertEquals("horizon" to 8, YourEyeFormatter.followedMost(data))
        assertEquals("headroom" to 3, YourEyeFormatter.followedLeast(data))
    }

    @Test
    fun `every kind of advice has a label in strings xml`() {
        val label = AdviceLabels.from(RuntimeEnvironment.getApplication().resources)
        MetricCategory.entries.forEach { assertTrue(it.name, label(it).isNotBlank()) }
        assertEquals("Level horizon", label(MetricCategory.HORIZON))
    }

    @Test
    fun `the source maps the flow and its reset is the camera's reset`() = runBlocking {
        var resets = 0
        val source = JournalYourEyeSource(flowOf(snapshot), { it.name }, { resets++ })
        assertEquals(7, source.data.first().keepers30d)
        source.resetCoachingPreferences()
        assertEquals(1, resets)
    }
}
