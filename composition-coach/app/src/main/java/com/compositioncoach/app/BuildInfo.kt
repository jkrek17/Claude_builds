package com.compositioncoach.app

/**
 * Pure formatting rules mirrored from the versioning logic in app/build.gradle.kts
 * (`resolveVersionCode`/`resolveVersionName`) so the "0.9.<versionCode>" default naming scheme is
 * unit-testable on the plain JVM — Gradle build scripts themselves aren't covered by
 * `:app:testDebugUnitTest`. Keep this in sync with app/build.gradle.kts by hand if that default ever
 * changes; [com.compositioncoach.app.BuildInfoTest] is what would need updating first.
 */
object BuildInfo {

    /** The default `versionName` used when the `CC_VERSION_NAME` env var isn't set for a build. */
    fun defaultVersionName(versionCode: Int): String = "0.9.$versionCode"
}
