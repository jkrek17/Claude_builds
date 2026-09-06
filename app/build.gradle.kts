import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// --- Versioning -------------------------------------------------------------------------------------
// versionCode: CC_VERSION_CODE env var if set, otherwise the number of commits reachable from HEAD
// (a monotonically increasing, reproducible default for CI), otherwise 1 if git isn't available.
// versionName: CC_VERSION_NAME env var if set, otherwise "0.9.<versionCode>" (see BuildInfoTest /
// VersionInfo for the same "0.9.<code>" formatting rule, unit-tested on the JVM).
// Uses providers.exec (not java.lang.ProcessBuilder) because it is the configuration-cache-compatible
// way to run an external process from a build script — see gradle.properties' configuration-cache flag.
fun gitCommitCount(): Int = try {
    val result = providers.exec {
        workingDir = rootDir
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }
    if (result.result.get().exitValue == 0) result.standardOutput.asText.get().trim().toIntOrNull() ?: 1 else 1
} catch (_: Exception) {
    1
}

fun resolveVersionCode(): Int =
    System.getenv("CC_VERSION_CODE")?.toIntOrNull() ?: gitCommitCount()

fun resolveVersionName(versionCode: Int): String =
    System.getenv("CC_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.9.$versionCode"

val ccVersionCode = resolveVersionCode()
val ccVersionName = resolveVersionName(ccVersionCode)

// --- Release signing ---------------------------------------------------------------------------------
// A real upload key is supplied via four env vars (populated from GitHub secrets in CI; see
// docs/RELEASE.md for how to create one and where to put it). When they are absent — a local build, a
// fork, a PR from an external contributor — assembleRelease must still produce an installable,
// R8-shrunk APK, so it falls back to the same checked-in debug keystore the debug build type uses.
val releaseKeystoreBase64 = System.getenv("CC_RELEASE_KEYSTORE_BASE64")
val releaseKeystorePassword = System.getenv("CC_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("CC_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("CC_RELEASE_KEY_PASSWORD")
val hasRealReleaseSigning = !releaseKeystoreBase64.isNullOrBlank() &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

val decodedReleaseKeystore = layout.buildDirectory.file("release.keystore").get().asFile
if (hasRealReleaseSigning) {
    decodedReleaseKeystore.parentFile.mkdirs()
    decodedReleaseKeystore.writeBytes(Base64.getDecoder().decode(releaseKeystoreBase64))
}

android {
    namespace = "com.compositioncoach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.compositioncoach.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ccVersionCode
        versionName = ccVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        // A fixed, checked-in debug key so every CI build is signed identically and installs update
        // over each other instead of failing with a signature mismatch. Debug-only: never ship with it.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (hasRealReleaseSigning) {
                storeFile = decodedReleaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else {
                // No real upload key configured: fall back to the debug keystore so `assembleRelease` /
                // `bundleRelease` always succeed and produce something installable (e.g. local builds,
                // forked-repo CI runs). Never used for an actual Play Store upload — see docs/RELEASE.md.
                storeFile = rootProject.file("keystore/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            // Phones are arm64 (or 32-bit ARM on older devices). Dropping the x86/x86_64 emulator ABIs
            // from the bundled ML Kit/TFLite native libraries roughly halves the debug APK. The release
            // build type intentionally has no abiFilters: the AAB (bundleRelease) generates per-ABI
            // splits itself, and Play serves each device only what it needs.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    implementation(project(":composition"))
    implementation(project(":vision"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// --- Play policy verification ---------------------------------------------------------------------
// Composition Coach declares no INTERNET permission anywhere (no dependency is allowed to add one
// silently either). This reads the merged *debug* manifest AGP already produces for
// `processDebugMainManifest` and fails the build if that ever changes, wired into `check` so it runs
// in ordinary CI (`./gradlew check`) without needing a device or emulator.
tasks.register("verifyNoInternetPermission") {
    group = "verification"
    description = "Fails if the merged debug manifest declares android.permission.INTERNET."
    dependsOn("processDebugMainManifest")

    val mergedManifestDir = layout.buildDirectory.dir("intermediates/merged_manifest/debug")
    inputs.dir(mergedManifestDir).withPropertyName("mergedManifestDir")
    val outputMarker = layout.buildDirectory.file("verifyNoInternetPermission/result.txt")
    outputs.file(outputMarker)

    doLast {
        val manifestFile = mergedManifestDir.get().asFile.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
            ?: error("Could not find the merged debug manifest under ${mergedManifestDir.get().asFile}; " +
                "run `./gradlew :app:processDebugMainManifest` first.")
        val content = manifestFile.readText()
        check(!content.contains("android.permission.INTERNET")) {
            "Merged manifest at ${manifestFile.path} declares android.permission.INTERNET. " +
                "Composition Coach must never request network access — see README.md and docs/PRIVACY_POLICY.md."
        }
        val marker = outputMarker.get().asFile
        marker.parentFile.mkdirs()
        marker.writeText("no INTERNET permission found in ${manifestFile.path}\n")
    }
}

tasks.named("check") { dependsOn("verifyNoInternetPermission") }
