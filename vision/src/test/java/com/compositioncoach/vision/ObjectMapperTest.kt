package com.compositioncoach.vision

import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObjectMapperTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = NormalizedRect(left, top, right, bottom)

    // --- label -> category mapping --------------------------------------------------------------

    @Test
    fun `known ML Kit labels map to their categories`() {
        assertEquals(ObjectCategory.FASHION_GOOD, ObjectMapper.labelToCategory("Fashion good"))
        assertEquals(ObjectCategory.FOOD, ObjectMapper.labelToCategory("Food"))
        assertEquals(ObjectCategory.HOME_GOOD, ObjectMapper.labelToCategory("Home good"))
        assertEquals(ObjectCategory.PLACE, ObjectMapper.labelToCategory("Place"))
        assertEquals(ObjectCategory.PLANT, ObjectMapper.labelToCategory("Plant"))
    }

    @Test
    fun `unknown null and unrecognized labels map to UNKNOWN`() {
        assertEquals(ObjectCategory.UNKNOWN, ObjectMapper.labelToCategory("Unknown"))
        assertEquals(ObjectCategory.UNKNOWN, ObjectMapper.labelToCategory(null))
        assertEquals(ObjectCategory.UNKNOWN, ObjectMapper.labelToCategory("Vehicle"))
        // Case-sensitive on purpose: ML Kit's own label text is a fixed set of exact strings.
        assertEquals(ObjectCategory.UNKNOWN, ObjectMapper.labelToCategory("food"))
    }

    // --- confidence fallback ---------------------------------------------------------------------

    @Test
    fun `confidence comes from the label when present else defaults to 1f`() {
        val labeled = RawDetectedObject(box(0.1f, 0.1f, 0.3f, 0.3f), labelText = "Food", labelConfidence = 0.42f)
        val unlabeled = RawDetectedObject(box(0.1f, 0.1f, 0.3f, 0.3f), labelText = null, labelConfidence = null)

        val mapped = ObjectMapper.map(listOf(labeled, unlabeled), faceBounds = emptyList())

        assertEquals(0.42f, mapped.first { it.category == ObjectCategory.FOOD }.confidence, 1e-6f)
        assertEquals(1f, mapped.first { it.category == ObjectCategory.UNKNOWN }.confidence, 1e-6f)
    }

    // --- frame-covering box is dropped -------------------------------------------------------------

    @Test
    fun `a box covering more than 85 percent of the frame is dropped as the scene`() {
        val sceneBox = RawDetectedObject(box(0f, 0f, 1f, 0.9f)) // area = 0.9 > 0.85
        val realObject = RawDetectedObject(box(0.2f, 0.2f, 0.4f, 0.4f)) // area = 0.04

        val mapped = ObjectMapper.map(listOf(sceneBox, realObject), faceBounds = emptyList())

        assertEquals(1, mapped.size)
        assertEquals(realObject.bounds, mapped.single().bounds)
    }

    @Test
    fun `a box right at the coverage limit is kept`() {
        // area exactly 0.85 should NOT be dropped ("more than" 85%, not "at least").
        val atLimit = RawDetectedObject(box(0f, 0f, 1f, 0.85f))
        val mapped = ObjectMapper.map(listOf(atLimit), faceBounds = emptyList())
        assertEquals(1, mapped.size)
    }

    // --- face-overlapping box is dropped ------------------------------------------------------------

    @Test
    fun `a box overlapping a face by more than 0-3 IoU is dropped as the person`() {
        val face = box(0.1f, 0.1f, 0.5f, 0.5f) // area 0.16
        // Same box as the face -> IoU 1.0, well above the 0.3 limit.
        val onFace = RawDetectedObject(box(0.1f, 0.1f, 0.5f, 0.5f))
        val elsewhere = RawDetectedObject(box(0.6f, 0.6f, 0.8f, 0.8f))

        val mapped = ObjectMapper.map(listOf(onFace, elsewhere), faceBounds = listOf(face))

        assertEquals(1, mapped.size)
        assertEquals(elsewhere.bounds, mapped.single().bounds)
    }

    @Test
    fun `a box with only slight overlap with a face is kept`() {
        val face = box(0.0f, 0.0f, 0.3f, 0.3f) // area 0.09
        // Overlaps the face corner only slightly: intersection much smaller than either box -> low IoU.
        val slightOverlap = RawDetectedObject(box(0.25f, 0.25f, 0.6f, 0.6f))

        val mapped = ObjectMapper.map(listOf(slightOverlap), faceBounds = listOf(face))

        assertEquals(1, mapped.size)
    }

    // --- top-3 by area, largest first ---------------------------------------------------------------

    @Test
    fun `at most 3 objects are kept, largest area first`() {
        val small = RawDetectedObject(box(0f, 0f, 0.1f, 0.1f)) // area 0.01
        val medium = RawDetectedObject(box(0f, 0f, 0.2f, 0.2f)) // area 0.04
        val large = RawDetectedObject(box(0f, 0f, 0.3f, 0.3f)) // area 0.09
        val largest = RawDetectedObject(box(0f, 0f, 0.4f, 0.4f)) // area 0.16
        val huge = RawDetectedObject(box(0f, 0f, 0.5f, 0.5f)) // area 0.25

        val mapped = ObjectMapper.map(listOf(small, medium, large, largest, huge), faceBounds = emptyList())

        assertEquals(3, mapped.size)
        assertEquals(listOf(huge.bounds, largest.bounds, large.bounds), mapped.map { it.bounds })
    }

    // --- id assignment ---------------------------------------------------------------------------

    @Test
    fun `tracking id is used as the DetectedObject id when present, else rank`() {
        val tracked = RawDetectedObject(box(0f, 0f, 0.5f, 0.5f), trackingId = 42)
        val untracked = RawDetectedObject(box(0f, 0f, 0.1f, 0.1f), trackingId = null)

        val mapped = ObjectMapper.map(listOf(tracked, untracked), faceBounds = emptyList())

        assertEquals(42, mapped[0].id)
        assertEquals(42, mapped[0].trackingId)
        assertEquals(1, mapped[1].id) // rank 1 (second, 0-based) in the sorted+capped list
        assertNull(mapped[1].trackingId)
    }

    @Test
    fun `empty input maps to empty output`() {
        assertTrue(ObjectMapper.map(emptyList(), emptyList()).isEmpty())
    }
}
