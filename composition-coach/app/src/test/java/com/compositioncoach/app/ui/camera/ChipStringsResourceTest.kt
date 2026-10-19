package com.compositioncoach.app.ui.camera

import org.robolectric.RuntimeEnvironment
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The chip wording table lives in `strings.xml`; [ChipStrings.English] (the JVM tests' fake and the pre-install
 * fallback) must say exactly the same, and [GuidanceFormatter] must read whichever provider is installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChipStringsResourceTest {

    private val resources = RuntimeEnvironment.getApplication().resources

    @After
    fun restore() {
        GuidanceFormatter.strings = ChipStrings.English
    }

    @Test
    fun `the English table matches values strings_xml word for word`() {
        ChipKey.entries.forEach { key ->
            assertEquals("ChipKey.$key", resources.getString(key.resId), key.english)
        }
    }

    @Test
    fun `GuidanceFormatter reads through the installed provider`() {
        GuidanceFormatter.strings = ChipStrings { "x-${it.name}" }
        val rec = Recommendation("t", MetricCategory.SUBJECT_PLACEMENT, Priority.HIGH, 0.9f, Severity.MEDIUM, "t", "t", direction = Direction.LEFT)
        assertEquals("x-SLIGHTLY_LEFT", GuidanceFormatter.chipText(rec))
        assertEquals("x-DESC_SLIGHTLY_LEFT", GuidanceFormatter.chipDescription(rec))
        assertEquals("x-SHOOT", GuidanceFormatter.SHOOT_TEXT)

        GuidanceFormatter.strings = ResourceChipStrings(resources)
        assertEquals("Slightly left", GuidanceFormatter.chipText(rec))
    }
}
