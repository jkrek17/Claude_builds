package com.compositioncoach.composition

import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ReframeVector
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryTest {
    @Test
    fun `rect center and area`() {
        val r = NormalizedRect(0.2f, 0.2f, 0.6f, 0.4f)
        assertEquals(NormalizedPoint(0.4f, 0.3f), r.center)
        assertEquals(0.08f, r.area, 1e-5f)
    }

    @Test
    fun `reframe vector to move subject right of thirds line says move right`() {
        // subject at x=0.85 should land on x=0.66 -> camera pans right
        val v = ReframeVector.toMoveSubject(NormalizedPoint(0.85f, 0.4f), NormalizedPoint(0.66f, 0.4f))
        assertEquals(Direction.RIGHT, v.primaryDirection())
    }

    @Test
    fun `small vector is a no-op`() {
        assertEquals(Direction.NONE, ReframeVector(0.005f, 0.01f).primaryDirection())
    }
}
