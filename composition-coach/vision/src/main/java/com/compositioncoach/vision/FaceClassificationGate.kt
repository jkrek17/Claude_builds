package com.compositioncoach.vision

/**
 * Decides, per accepted frame, whether the live face detector should run ML Kit classification (eyes open,
 * smiling). Classification roughly doubles face-detector time, so it only runs when it is useful: while
 * group mode is on (the Group outcome, see [VisionFeatureToggles.setGroupModeEnabled]), or once two or more
 * faces have been in view for [dwellMs]. It then stays on until fewer than two faces have been seen for
 * [releaseMs], so a face briefly lost behind a hand does not flap the detector. Pure; driven with the previous
 * frame's face count (this frame's is not known until the detector it configures has run).
 */
class FaceClassificationGate(
    private val dwellMs: Long = DEFAULT_DWELL_MS,
    private val releaseMs: Long = DEFAULT_RELEASE_MS,
) {
    private var multiFaceSinceMs: Long? = null
    private var lastMultiFaceMs: Long = Long.MIN_VALUE
    private var latched = false

    /** @return whether to classify the next frame. */
    fun update(previousFaceCount: Int, nowMs: Long, groupMode: Boolean): Boolean {
        if (previousFaceCount >= 2) {
            val since = multiFaceSinceMs ?: nowMs.also { multiFaceSinceMs = it }
            lastMultiFaceMs = nowMs
            if (nowMs - since >= dwellMs) latched = true
        } else {
            multiFaceSinceMs = null
            if (latched && nowMs - lastMultiFaceMs >= releaseMs) latched = false
        }
        return groupMode || latched
    }

    fun reset() {
        multiFaceSinceMs = null
        lastMultiFaceMs = Long.MIN_VALUE
        latched = false
    }

    companion object {
        const val DEFAULT_DWELL_MS = 1_000L
        const val DEFAULT_RELEASE_MS = 2_000L
    }
}
