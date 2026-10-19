package com.compositioncoach.composition.geometry

import com.compositioncoach.composition.model.BodyLandmark
import com.compositioncoach.composition.model.DetectedBody
import com.compositioncoach.composition.model.DetectedFace
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ReframeVector

/**
 * Simulates the effect of a camera move (pan/tilt + zoom, described by a [ReframeVector]) on the
 * detected geometry of a [FrameAnalysis], so the [com.compositioncoach.composition.engine.CompositionOptimizer]
 * can ask "what would the score look like if the photographer made this move?" without a new camera frame.
 *
 * Sign convention (matches [ReframeVector]'s own kdoc, applied literally so this stays independent of any
 * analyzer-level interpretation): panning the camera right (`dx > 0`) shifts frame content left
 * (`x -= dx`); raising/tilting the camera up (`dy > 0`) shifts frame content down (`y += dy`); zooming in
 * (`zoom > 0`) magnifies geometry about the frame centre. Roll is not simulated here: the optimizer only
 * searches pan/tilt/zoom candidates.
 *
 * KNOWN APPROXIMATION: only face/body geometry is transformed. [ImageStatistics] (luminance/edge grids,
 * symmetry, horizon angle) is left untouched on the transformed frame — recomputing a downscaled image
 * for a hypothetical crop is exactly the expensive work this optimizer exists to avoid. This means
 * statistics-driven analyzers (background distraction, balance, symmetry, negative space, leading lines,
 * scene-specific) see the *original* frame's stats even for shifted candidates; only face/body-driven
 * analyzers (subject placement, headroom, looking room, edge tension, cropping) meaningfully change
 * across candidates. This is fine for the optimizer's purpose (find the best *directional* move) since
 * those are exactly the categories a small reframe changes fastest.
 */
object FrameTransform {

    /** Transforms a single point as if the camera had moved by [vector]. */
    fun transformPoint(p: NormalizedPoint, vector: ReframeVector): NormalizedPoint {
        val shiftedX = p.x - vector.dx
        val shiftedY = p.y + vector.dy
        val scale = (1f + vector.zoom).coerceAtLeast(0.05f)
        val cx = 0.5f
        val cy = 0.5f
        return NormalizedPoint(cx + (shiftedX - cx) * scale, cy + (shiftedY - cy) * scale)
    }

    /** Transforms an axis-aligned rect the same way; scaling about the frame centre keeps it axis-aligned. */
    fun transformRect(rect: NormalizedRect, vector: ReframeVector): NormalizedRect {
        val a = transformPoint(NormalizedPoint(rect.left, rect.top), vector)
        val b = transformPoint(NormalizedPoint(rect.right, rect.bottom), vector)
        return NormalizedRect(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    private fun transformFace(face: DetectedFace, vector: ReframeVector): DetectedFace = face.copy(
        bounds = transformRect(face.bounds, vector),
        leftEye = face.leftEye?.let { transformPoint(it, vector) },
        rightEye = face.rightEye?.let { transformPoint(it, vector) },
        noseBase = face.noseBase?.let { transformPoint(it, vector) },
    )

    private fun transformBody(body: DetectedBody, vector: ReframeVector): DetectedBody = body.copy(
        bounds = transformRect(body.bounds, vector),
        landmarks = body.landmarks.mapValues { (_, lm) ->
            BodyLandmark(lm.type, transformPoint(lm.position, vector), lm.inFrameLikelihood)
        },
    )

    /** Returns a copy of [frame] with faces/bodies moved as if the photographer applied [vector]. A zero vector is a cheap no-op copy. */
    fun transform(frame: FrameAnalysis, vector: ReframeVector): FrameAnalysis {
        if (vector == ReframeVector.ZERO) return frame
        return frame.copy(
            faces = frame.faces.map { transformFace(it, vector) },
            bodies = frame.bodies.map { transformBody(it, vector) },
        )
    }
}
