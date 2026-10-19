package com.compositioncoach.app.ui.settings

import com.compositioncoach.app.camera.CaptureEnhancement.AUTO
import com.compositioncoach.app.camera.CaptureEnhancement.HDR
import com.compositioncoach.app.camera.CaptureEnhancement.NIGHT
import com.compositioncoach.app.camera.CaptureEnhancement.OFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Settings "Enhancement" row: persisted choice against what the bound lens offers. */
class EnhancementRowModelTest {

    @Test
    fun `an available persisted choice is simply selected`() {
        val model = EnhancementRowModel.of(NIGHT, setOf(OFF, AUTO, NIGHT))
        assertEquals(listOf(OFF, AUTO, NIGHT), model.options)
        assertEquals(NIGHT, model.selected)
        assertFalse(model.unavailableOnLens)
        assertTrue(model.isSelectable(NIGHT))
    }

    @Test
    fun `an explicit choice this lens lacks stays selected and is flagged unavailable`() {
        val model = EnhancementRowModel.of(NIGHT, setOf(OFF, HDR))
        assertEquals(listOf(OFF, HDR, NIGHT), model.options)
        assertEquals(NIGHT, model.selected)
        assertTrue(model.unavailableOnLens)
        assertFalse(model.isSelectable(NIGHT))
        assertTrue(model.isSelectable(HDR))
    }

    @Test
    fun `the AUTO default on a lens without AUTO shows Off, which is what runs`() {
        val model = EnhancementRowModel.of(AUTO, setOf(OFF, NIGHT))
        assertEquals(listOf(OFF, NIGHT), model.options)
        assertEquals(OFF, model.selected)
        assertFalse(model.unavailableOnLens)
    }

    @Test
    fun `nothing beyond Off means the disabled row`() {
        val model = EnhancementRowModel.of(NIGHT, setOf(OFF))
        assertTrue(model.options.isEmpty())
        assertNull(model.selected)
        assertEquals(EnhancementRowModel.of(AUTO, emptySet()), model)
    }
}
