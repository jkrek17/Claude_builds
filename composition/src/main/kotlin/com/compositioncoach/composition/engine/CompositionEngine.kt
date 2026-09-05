package com.compositioncoach.composition.engine

import com.compositioncoach.composition.analyzer.CompositionAnalyzer
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel

/**
 * Stateless per-frame evaluation: classify scene -> resolve subjects -> run analyzers -> weight -> rank advice.
 *
 * STATUS: STUB. To be implemented by the :composition owner. Keep the public API.
 */
class CompositionEngine(
    private val analyzers: List<CompositionAnalyzer>,
) {
    fun evaluate(frame: FrameAnalysis, level: GuidanceLevel = GuidanceLevel.BALANCED): CompositionResult {
        return CompositionResult.empty(frame.timestampNanos)
    }

    companion object {
        fun default(): CompositionEngine = CompositionEngine(emptyList())
    }
}
