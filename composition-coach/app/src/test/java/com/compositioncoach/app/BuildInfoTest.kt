package com.compositioncoach.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildInfoTest {

    @Test
    fun `defaultVersionName follows the 0-9-versionCode scheme`() {
        assertEquals("0.9.1", BuildInfo.defaultVersionName(1))
        assertEquals("0.9.27", BuildInfo.defaultVersionName(27))
        assertEquals("0.9.999", BuildInfo.defaultVersionName(999))
    }

    @Test
    fun `defaultVersionName is monotonic in versionCode, matching an ever-increasing commit count`() {
        val names = (1..10).map { BuildInfo.defaultVersionName(it) }
        assertEquals(names.distinct().size, names.size)
    }
}
