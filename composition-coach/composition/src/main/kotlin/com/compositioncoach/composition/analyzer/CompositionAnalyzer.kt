package com.compositioncoach.composition.analyzer

import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneIntent

/**
 * Everything an analyzer may look at for one frame. Built once per frame by the engine and shared by all analyzers.
 *
 * @param subjects all subjects the engine resolved, most salient first.
 * @param primarySubject the subject guidance is centred on (null for subject-less scenes such as landscapes).
 */
data class AnalysisContext(
    val frame: FrameAnalysis,
    val scene: SceneClassification,
    val subjects: List<DetectedSubject>,
    val primarySubject: DetectedSubject?,
    val guidanceLevel: GuidanceLevel = GuidanceLevel.BALANCED,
    /** The photographer's declared shooting mode; [SceneIntent.AUTO] when they left it to detection. */
    val intent: SceneIntent = SceneIntent.AUTO,
) {
    val hasPeople: Boolean get() = frame.faces.isNotEmpty() || frame.bodies.isNotEmpty()
}

/**
 * One composition concept (horizon, headroom, ...). Analyzers are pure functions of [AnalysisContext]:
 * no state, no Android types, so they can be unit-tested with synthetic geometry.
 *
 * Return `null` (or a metric with `applicable = false`) when the concept does not apply to the frame.
 */
interface CompositionAnalyzer {
    val name: String
    val category: MetricCategory
    fun analyze(context: AnalysisContext): CompositionMetric?
}
