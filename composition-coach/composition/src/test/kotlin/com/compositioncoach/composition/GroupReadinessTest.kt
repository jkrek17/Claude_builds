package com.compositioncoach.composition

import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.GroupReadiness
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupReadinessTest {
    private val engine = CompositionEngine.default()

    private val twoGoodFaces = listOf(
        face(cx = 0.35f, cy = 0.4f, size = 0.12f, id = 0),
        face(cx = 0.65f, cy = 0.4f, size = 0.12f, id = 1),
    )

    @Test
    fun `everyone big enough, inside the frame, eyes open is ready`() {
        val status = GroupReadiness.evaluate(twoGoodFaces.map { it.copy(eyesOpenProbability = 0.9f) }, emptyList())
        assertTrue(status.ready)
        assertEquals(2, status.facesTotal)
        assertEquals(2, status.facesReady)
        assertTrue(status.blockers.isEmpty())
    }

    @Test
    fun `unknown eye state counts as open`() {
        assertTrue(GroupReadiness.evaluate(twoGoodFaces, emptyList()).ready)
    }

    @Test
    fun `closed eyes block`() {
        val faces = listOf(twoGoodFaces[0].copy(eyesOpenProbability = 0.2f), twoGoodFaces[1].copy(eyesOpenProbability = 0.95f))
        val status = GroupReadiness.evaluate(faces, emptyList())
        assertFalse(status.ready)
        assertEquals(1, status.facesReady)
        assertEquals(listOf(GroupReadiness.BLOCKER_EYES_CLOSED), status.blockers)
    }

    @Test
    fun `a face within 2 percent of an edge is cut off`() {
        val faces = twoGoodFaces + face(cx = 0.95f, cy = 0.4f, size = 0.12f, id = 2) // right side at 1.01
        val status = GroupReadiness.evaluate(faces, emptyList())
        assertFalse(status.ready)
        assertEquals(3, status.facesTotal)
        assertEquals(2, status.facesReady)
        assertEquals(listOf(GroupReadiness.BLOCKER_CUT_OFF), status.blockers)
        // 3 % from the edge is fine.
        assertFalse(GroupReadiness.isCutAtEdge(face(cx = 0.91f, cy = 0.4f, size = 0.12f)))
    }

    @Test
    fun `a face under 6 percent of the frame height is too small, and blockers come in urgency order`() {
        val faces = listOf(
            face(cx = 0.3f, cy = 0.4f, size = 0.05f, id = 0),
            face(cx = 0.5f, cy = 0.4f, size = 0.12f, id = 1).copy(eyesOpenProbability = 0.1f),
            face(cx = 0.99f, cy = 0.4f, size = 0.12f, id = 2),
        )
        val status = GroupReadiness.evaluate(faces, emptyList())
        assertEquals(0, status.facesReady)
        assertEquals(
            listOf(GroupReadiness.BLOCKER_CUT_OFF, GroupReadiness.BLOCKER_EYES_CLOSED, GroupReadiness.BLOCKER_TOO_SMALL),
            status.blockers,
        )
    }

    @Test
    fun `a HIGH severity problem anywhere in the frame blocks`() {
        val pole = Recommendation(
            id = "background.pole", category = MetricCategory.BACKGROUND_DISTRACTION, priority = Priority.HIGH,
            confidence = 1f, severity = Severity.HIGH, title = "Pole", instruction = "Move so the pole is not behind a head",
        )
        val metrics = listOf(CompositionMetric(MetricCategory.BACKGROUND_DISTRACTION, "b", 0.1f, 1f, Severity.HIGH, recommendation = pole))
        val status = GroupReadiness.evaluate(twoGoodFaces, metrics)
        assertFalse(status.ready)
        assertEquals(2, status.facesReady)
        assertEquals(listOf("Move so the pole is not behind a head"), status.blockers)
    }

    @Test
    fun `the engine fills group status for detected and declared groups only`() {
        val group = engine.evaluate(frameWith(faces = twoGoodFaces))
        assertEquals(SceneType.GROUP_PORTRAIT, group.scene.type)
        assertNotNull(group.groupStatus)
        assertEquals(group.groupStatus!!.ready, group.groupReady)

        val cut = engine.evaluate(
            frameWith(faces = twoGoodFaces + face(cx = 0.97f, cy = 0.4f, size = 0.12f, id = 2)),
            intent = SceneIntent.GROUP_PORTRAIT,
        )
        assertEquals(false, cut.groupReady)
        assertTrue(GroupReadiness.BLOCKER_CUT_OFF in cut.groupStatus!!.blockers)

        val awaiting = engine.evaluate(frameWith(faces = listOf(twoGoodFaces[0])), intent = SceneIntent.GROUP_PORTRAIT)
        assertTrue(awaiting.awaitingSubject)
        assertEquals(false, awaiting.groupReady)

        val single = engine.evaluate(frameWith(faces = listOf(twoGoodFaces[0])))
        assertNull(single.groupStatus)
        assertNull(single.groupReady)
    }
}
