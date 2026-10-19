package com.compositioncoach.composition

import com.compositioncoach.composition.engine.AfterActionReport
import com.compositioncoach.composition.engine.CompositionEngine
import com.compositioncoach.composition.engine.CropSuggester
import com.compositioncoach.composition.fixtures.SyntheticFrames
import com.compositioncoach.composition.fixtures.SyntheticFrames.face
import com.compositioncoach.composition.fixtures.SyntheticFrames.frameWith
import com.compositioncoach.composition.fixtures.SyntheticFrames.maskFromRect
import com.compositioncoach.composition.fixtures.SyntheticFrames.objectAt
import com.compositioncoach.composition.fixtures.SyntheticFrames.standingBody
import com.compositioncoach.composition.geometry.FrameTransform
import com.compositioncoach.composition.model.CropAspect
import com.compositioncoach.composition.model.DetectedLine
import com.compositioncoach.composition.model.FramingCandidate
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.ImageStatistics
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.ReframeVector
import com.compositioncoach.composition.model.SceneIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CropSuggesterTest {
    private val engine = CompositionEngine.default()
    private val suggester = CropSuggester(engine)

    // ---- FramingCandidate.asCrop -----------------------------------------------------------------------

    @Test
    fun `the current framing and stepping back have no crop`() {
        assertNull(FramingCandidate("current", ReframeVector.ZERO, 70f).asCrop())
        assertNull(FramingCandidate("wider", ReframeVector(zoom = -0.1f), 70f).asCrop())
    }

    @Test
    fun `a zoom is a centred window of size 1 over 1 plus zoom`() {
        val crop = FramingCandidate("closer", ReframeVector(zoom = 0.25f), 80f).asCrop()!!
        assertEquals(0.8f, crop.width, 1e-5f)
        assertEquals(0.8f, crop.height, 1e-5f)
        assertEquals(0.5f, crop.center.x, 1e-5f)
        assertEquals(0.5f, crop.center.y, 1e-5f)
    }

    @Test
    fun `a pan is a window shifted the same way, shrunk to fit`() {
        // Pan right by 7 %: content moves left, so the window moves right by 7 % and shrinks to 86 %.
        val right = FramingCandidate("right", ReframeVector(dx = 0.07f), 80f).asCrop()!!
        assertEquals(0.57f, right.center.x, 1e-5f)
        assertEquals(0.86f, right.width, 1e-5f)
        assertEquals(1f, right.right, 1e-5f)
        // Raise the camera by 7 %: content moves down, so the window moves up.
        val up = FramingCandidate("up", ReframeVector(dy = 0.07f), 80f).asCrop()!!
        assertEquals(0.43f, up.center.y, 1e-5f)
        assertEquals(0f, up.top, 1e-5f)
        // The subject lands where the simulated move would put it (up to the documented shrink).
        val subject = NormalizedPoint(0.8f, 0.5f)
        val simulated = FrameTransform.transformPoint(subject, ReframeVector(dx = 0.07f))
        val cropped = FrameTransform.cropPoint(subject, right)
        assertTrue(cropped.x < subject.x && simulated.x < subject.x)
        assertEquals(simulated.x, cropped.x, 0.05f)
    }

    // ---- FrameTransform.crop ---------------------------------------------------------------------------

    @Test
    fun `cropping maps detections into the window and drops what falls outside`() {
        val inside = face(cx = 0.6f, cy = 0.4f, size = 0.1f, id = 1)
        val outside = face(cx = 0.1f, cy = 0.1f, size = 0.05f, id = 2)
        val frame = frameWith(
            faces = listOf(inside, outside),
            bodies = listOf(standingBody(cx = 0.6f, headTop = 0.35f, feetY = 0.95f)),
            objects = listOf(objectAt(0.6f, 0.7f, 0.1f)),
            frameWidth = 1000, frameHeight = 2000,
        )
        val window = NormalizedRect(0.4f, 0.2f, 0.9f, 0.7f)
        val cropped = FrameTransform.crop(frame, window)
        assertEquals(listOf(1), cropped.faces.map { it.id })
        assertEquals(0.4f, cropped.faces[0].center.x, 1e-5f) // (0.6 - 0.4) / 0.5
        assertEquals(0.4f, cropped.faces[0].center.y, 1e-5f) // (0.4 - 0.2) / 0.5
        assertEquals(500, cropped.frameWidth)
        assertEquals(1000, cropped.frameHeight)
        assertEquals(1, cropped.objects.size)
        // Ankles at y = 0.95 are far below the crop (y = 1.5): reported as out of frame, not "cut at the joint".
        val ankle = cropped.bodies[0].landmarks.values.first { it.type.name.endsWith("ANKLE") }
        assertEquals(0f, ankle.inFrameLikelihood, 0f)
    }

    @Test
    fun `stats and mask are resampled into same-size grids, and an identity crop changes nothing`() {
        val stats = SyntheticFrames.statsWithLuminancePatches(0.2f, listOf(NormalizedRect(0.5f, 0f, 1f, 1f) to 0.8f), 16, 16)
            .copy(dominantLines = listOf(DetectedLine(NormalizedPoint(0f, 0.5f), NormalizedPoint(1f, 0.5f), 0.9f)))
        val mask = maskFromRect(NormalizedRect(0.5f, 0.5f, 1f, 1f), 16, 16)
        val frame = frameWith(stats = stats, subjectMask = mask)
        assertSame(frame, FrameTransform.crop(frame, NormalizedRect.FULL))

        val right = FrameTransform.crop(frame, NormalizedRect(0.5f, 0f, 1f, 1f))
        val s: ImageStatistics = right.stats!!
        assertEquals(16, s.gridWidth)
        assertEquals(16, s.gridHeight)
        assertTrue(s.luminance.all { it == 0.8f })
        assertEquals(0.8f, s.meanLuminance, 0.02f)
        assertEquals(1f, right.subjectMask!!.at(0, 15), 0f)
        assertEquals(0f, right.subjectMask!!.at(0, 0), 0f)
        val line = s.dominantLines.single()
        assertEquals(0f, line.start.x, 1e-5f)
        assertEquals(1f, line.end.x, 1e-5f)
    }

    // ---- CropSuggester ---------------------------------------------------------------------------------

    @Test
    fun `a subject at the right edge gets a crop that beats the original`() {
        val frame = frameWith(faces = listOf(face(cx = 0.88f, cy = 0.4f, size = 0.15f)))
        val current = engine.evaluate(frame, GuidanceLevel.COACH)
        val crops = suggester.suggest(frame, SceneIntent.AUTO)
        assertTrue("expected a crop, got none", crops.isNotEmpty())
        assertTrue(crops.size <= CropSuggester.MAX_SUGGESTIONS)
        assertTrue(crops.all { it.predictedScore >= current.score + CropSuggester.MIN_GAIN })
        assertEquals(crops.sortedByDescending { it.predictedScore }, crops)
        // The best crop really does score that well when evaluated on its own.
        val best = crops.first()
        assertEquals(best.predictedScore, engine.evaluate(FrameTransform.crop(frame, best.rect), GuidanceLevel.COACH).score)
    }

    @Test
    fun `an object at the right edge gets a thirds crop`() {
        val frame = frameWith(objects = listOf(objectAt(0.88f, 0.5f, 0.2f, ObjectCategory.FOOD, 0.9f)))
        val crops = suggester.suggest(frame, SceneIntent.AUTO)
        assertTrue(crops.any { it.headline == CropSuggester.HEADLINE_THIRDS_SUBJECT })
    }

    @Test
    fun `a centred symmetric scene gets no thirds crop`() {
        val frames = listOf(
            frameWith(stats = SyntheticFrames.offCenterSalientRegion(cx = 0.5f, hotEdge = 0.6f)),
            frameWith(faces = listOf(face(cx = 0.5f, cy = 0.35f, size = 0.2f)), stats = SyntheticFrames.symmetricStats()),
        )
        for (frame in frames) {
            val crops = suggester.suggest(frame, SceneIntent.AUTO)
            assertTrue("thirds crop offered for a symmetric scene: $crops", crops.none { it.headline.contains("third") })
        }
    }

    @Test
    fun `a single face gets a 4 by 5 portrait crop`() {
        val frame = frameWith(faces = listOf(face(cx = 0.5f, cy = 0.55f, size = 0.12f)))
        val crops = suggester.suggest(frame, SceneIntent.AUTO)
        val fourFive = crops.firstOrNull { it.aspect == CropAspect.FOUR_FIVE }
        assertNotNull("expected a 4:5 crop, got $crops", fourFive)
        val pixelAspect = fourFive!!.rect.width * frame.frameWidth / (fourFive.rect.height * frame.frameHeight)
        assertEquals(0.8f, pixelAspect, 0.01f)
    }

    @Test
    fun `a group never gets a crop that cuts someone out`() {
        val frame = frameWith(
            faces = listOf(
                face(cx = 0.25f, cy = 0.4f, size = 0.12f, id = 0),
                face(cx = 0.5f, cy = 0.4f, size = 0.12f, id = 1),
                face(cx = 0.97f, cy = 0.4f, size = 0.12f, id = 2),
            ),
        )
        for (crop in suggester.suggest(frame, SceneIntent.AUTO)) {
            assertTrue(crop.aspect == CropAspect.ORIGINAL)
            assertTrue(crop.rect.left <= 0.19f && crop.rect.right >= 0.999f)
        }
    }

    @Test
    fun `crops never exceed the frame and the card is always well-formed`() {
        val random = Random(7)
        repeat(60) {
            val faces = List(random.nextInt(0, 3)) { i ->
                face(cx = random.nextFloat(), cy = random.nextFloat(), size = 0.05f + random.nextFloat() * 0.4f, id = i)
            }
            val objects = if (random.nextBoolean()) listOf(objectAt(random.nextFloat(), random.nextFloat(), 0.1f + random.nextFloat() * 0.3f)) else emptyList()
            val frame = frameWith(
                faces = faces, objects = objects,
                frameWidth = 600 + random.nextInt(1400), frameHeight = 600 + random.nextInt(1400),
                stats = if (random.nextBoolean()) SyntheticFrames.symmetricStats() else null,
            )
            val intent = SceneIntent.entries[random.nextInt(SceneIntent.entries.size)]
            // The after-action card for the same photo is always well-formed too.
            val report = AfterActionReport.build(engine.evaluate(frame, GuidanceLevel.COACH, intent))
            assertTrue(report.worked.size <= 3)
            report.tryNextTime?.let { assertTrue(it.sentence, it.sentence.length <= AfterActionReport.MAX_SENTENCE_LENGTH && it.predictedGain >= 1) }
            for (crop in suggester.suggest(frame, intent)) {
                val r = crop.rect
                assertTrue("crop outside the frame: $r", r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f)
                assertTrue(r.width > 0f && r.height > 0f)
                assertTrue(crop.predictedScore in 0..100)
            }
        }
    }
}
