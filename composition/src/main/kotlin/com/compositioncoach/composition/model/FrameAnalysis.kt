package com.compositioncoach.composition.model

/**
 * Device attitude from the rotation-vector / accelerometer, expressed relative to the *upright preview*.
 *
 * @param rollDegrees camera tilt around the viewing axis, re-referenced to [deviceRotationDegrees] (see
 *   `FrameAnalysis.deviceRotationDegrees`'s KDoc) so a level hold reads ~0 in *every* physical orientation,
 *   not just natural portrait. Positive = the horizon appears rotated clockwise on screen (the user needs
 *   to rotate the phone counter-clockwise to level it). 0 = level.
 * @param pitchDegrees camera tilt up/down. 0 = pointing at the horizon, positive = pointing up.
 * @param isReliable false when the sensor is unavailable or the device is pointing straight up/down.
 * @param deviceRotationDegrees additive convenience duplicate of `FrameAnalysis.deviceRotationDegrees`
 *   (0/90/180/270) at the time this reading was taken; see that field's KDoc for the full contract.
 */
data class DeviceOrientation(
    val rollDegrees: Float,
    val pitchDegrees: Float,
    val isReliable: Boolean = true,
    val deviceRotationDegrees: Int = 0,
)

/**
 * Everything the vision layer knows about one sampled camera frame. This is the *only* input to the
 * composition engine, which keeps the engine free of Android/camera types and unit-testable.
 *
 * Geometry is normalized to the upright, display-oriented (and front-camera-mirrored) frame; see [NormalizedPoint].
 *
 * @param frameWidth / frameHeight size of the upright analysis frame in pixels (used for aspect ratio only).
 * @param detectorTimings per-detector wall time in ms, for the debug overlay.
 */
data class FrameAnalysis(
    val timestampNanos: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val faces: List<DetectedFace> = emptyList(),
    val bodies: List<DetectedBody> = emptyList(),
    val stats: ImageStatistics? = null,
    /** Prominent non-person objects from an object detector (may be empty when the detector is off). */
    val objects: List<DetectedObject> = emptyList(),
    /** Foreground/person probability mask from a segmentation model, when it ran for this frame. */
    val subjectMask: SubjectMask? = null,
    val orientation: DeviceOrientation? = null,
    val isFrontCamera: Boolean = false,
    val analysisLatencyMs: Long = 0L,
    val detectorTimings: Map<String, Long> = emptyMap(),
    /**
     * Physical rotation of the phone from natural portrait at the time of this frame (0/90/180/270).
     * All geometry in this object is already in physical-up coordinates; the UI uses this value to map
     * them back onto the portrait-locked preview and to rotate controls.
     */
    val deviceRotationDegrees: Int = 0,
) {
    val aspectRatio: Float get() = if (frameHeight == 0) 1f else frameWidth.toFloat() / frameHeight
}
