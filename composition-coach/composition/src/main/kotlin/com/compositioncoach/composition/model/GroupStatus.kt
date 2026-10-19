package com.compositioncoach.composition.model

/**
 * Is everyone in a group photo ready? Computed by [com.compositioncoach.composition.engine.GroupReadiness].
 *
 * @param ready true when at least two faces are in frame, every face is ready (big enough, not cut at an
 *   edge, eyes open or unknown) and the frame has no HIGH-severity issue.
 * @param facesTotal faces counted (after incidental background faces are dropped).
 * @param facesReady how many of those faces pass every per-face check (drives the per-face dots on the
 *   readiness arc).
 * @param blockers plain chip texts, most urgent first, each at most once: "Someone's cut off",
 *   "Eyes closed", "Someone's too small" (the `GroupReadiness.BLOCKER_*` constants). Empty when ready, and
 *   also when the only problem is that fewer than two faces are in frame (the engine's "find your subject"
 *   recommendation already says so).
 */
data class GroupStatus(
    val ready: Boolean,
    val facesTotal: Int,
    val facesReady: Int,
    val blockers: List<String> = emptyList(),
)
