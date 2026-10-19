package com.compositioncoach.app.privacy

/**
 * The privacy policy shown in-app (see [com.compositioncoach.app.ui.privacy.PrivacyScreen]), reachable
 * from Settings. Kept as a plain in-app string rather than fetched from a URL: the app has no
 * INTERNET permission and never makes a network call (see [verifyNoInternetPermission] in
 * app/build.gradle.kts), so the policy has to ship with the app to be readable at all.
 *
 * This is the single source of truth for the policy's wording; docs/PRIVACY_POLICY.md is the same
 * text formatted for the repo/Play Console listing — keep the two in sync by hand when either changes.
 */
object PrivacyPolicyText {

    const val TITLE = "Privacy Policy"

    const val LAST_UPDATED = "Last updated: 2026-09-06"

    val TEXT = """
        Composition Coach does not collect, store, or transmit any personal data. It has no network
        permission at all — the app cannot make a network call even if it tried, and this is enforced
        by an automated build check, not just a promise in this document.

        WHAT THE CAMERA IS USED FOR

        The camera preview is analyzed entirely on your device, frame by frame, to compute a live
        composition score and framing suggestions (for example, "move slightly right" or "level the
        horizon"). This analysis uses on-device machine learning (face detection, body-pose detection,
        object detection and image statistics) and never leaves your phone. Composition Coach does not
        recognize who you are, does not attempt facial recognition or identification of any kind, and
        does not keep the analyzed frames — each frame is scored and discarded immediately; nothing
        about it is written to disk or sent anywhere.

        PHOTOS YOU TAKE

        When you press the shutter, the photo is saved using the normal Android system mechanism
        (MediaStore) to your device's own Pictures/CompositionCoach folder, exactly like any other
        camera app. It appears in your gallery like any other photo. The app never uploads it, never
        attaches it to a network request, and does not add any hidden data (like the on-screen score
        overlay) into the saved file — what you see on screen while shooting is a separate layer drawn
        over the live preview, never baked into the picture.

        ON-DEVICE MACHINE LEARNING MODELS

        Composition Coach uses Google's ML Kit for face detection, pose detection, object detection and
        selfie segmentation. These run on your device using Google Play services. The face detection
        model Composition Coach uses is the "unbundled" ML Kit model: it is not packaged inside the
        app's install file. Instead, Google Play services downloads it once, in its own separate
        process, either ahead of time when you install the app or the first time the feature is used.
        That one-time download requires your device to have a network connection at that moment and is
        handled entirely by Google Play services — Composition Coach itself has no network permission
        and plays no part in that download beyond asking Play services to fetch it. Once the model is
        present on the device, every face/pose/object detection Composition Coach performs afterward
        runs fully offline, on-device, using your phone's own processor. No camera frame, photo, or
        detection result is ever sent to Google, to Composition Coach's developer, or to anyone else.

        WHAT COMPOSITION COACH DOES NOT DO

        - No analytics, crash reporting, or advertising SDKs of any kind.
        - No account, sign-in, or user identifier of any kind.
        - No location access.
        - No contacts, microphone, or any permission beyond the camera (and, on Android 9 and below
          only, the storage permission needed to save a photo to the public gallery — Android 10 and
          newer don't need this permission at all).
        - No data is shared with, or sold to, any third party, because no data is collected in the
          first place.

        SETTINGS

        Every setting in the app (guidance level, shooting mode, whether the score is shown, debug mode,
        and so on) is stored only in this app's private storage on your device (Android's DataStore
        Preferences mechanism) and is never transmitted anywhere. Uninstalling the app removes it.

        CHANGES TO THIS POLICY

        If this policy ever changes, the update ships as part of a normal app update and is reflected
        both here and in the version of docs/PRIVACY_POLICY.md published alongside that release.

        CONTACT

        Questions about this policy can be raised through the app's listing on Google Play or the
        project's public source repository.
    """.trimIndent()
}
