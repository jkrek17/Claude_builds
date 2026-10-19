package com.compositioncoach.vision

/**
 * Device-health-driven degradation ladder for the whole detector cadence, orthogonal to the user's own
 * per-feature toggles ([VisionFeatureToggles]). Derived from live signals (thermal status, battery
 * saver) by `com.compositioncoach.app.camera.ThermalPolicy` in `:app` and applied here via
 * [VisionFeatureToggles.setPerformanceTier] — see [DetectorSchedule] for exactly how each tier changes
 * per-detector cadence.
 *
 * Ordinal order matters: [ThermalPolicy]-equivalent callers combine a thermal-derived tier and a
 * battery-saver-derived tier by taking whichever is *worse* (`maxOf`, comparing ordinals), so the
 * tiers are declared from least to most degraded — do not reorder them.
 *
 * @param targetIntervalMs the floor this tier imposes on [AdaptiveSampler]'s analysis interval, in
 *   addition to (never instead of) whatever the user's own settings-driven interval already requests —
 *   callers combine the two with `maxOf` (the slower/safer of the two always wins).
 */
enum class PerformanceTier(val targetIntervalMs: Long) {
    /** Every detector at its normal cadence. */
    FULL(100L),

    /** Segmentation off; pose backed off to every 3rd accepted frame. */
    REDUCED(150L),

    /** Segmentation and object detection off; pose off entirely. Faces still run every frame. */
    MINIMAL(250L),
}
