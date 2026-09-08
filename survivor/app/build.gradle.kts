import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// --- Versioning: SV_VERSION_CODE / SV_VERSION_NAME env vars, else commit count / "1.0.<code>" ---------
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
val svVersionCode = System.getenv("SV_VERSION_CODE")?.toIntOrNull() ?: gitCommitCount()
val svVersionName = System.getenv("SV_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.$svVersionCode"

// --- Release signing: real upload key from env (CI secrets), else the checked-in debug keystore -------
val releaseKeystoreBase64 = System.getenv("SV_RELEASE_KEYSTORE_BASE64")
val releaseKeystorePassword = System.getenv("SV_RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("SV_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SV_RELEASE_KEY_PASSWORD")
val hasRealReleaseSigning = !releaseKeystoreBase64.isNullOrBlank() && !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()
val decodedReleaseKeystore = layout.buildDirectory.file("release.keystore").get().asFile
if (hasRealReleaseSigning) {
    decodedReleaseKeystore.parentFile.mkdirs()
    decodedReleaseKeystore.writeBytes(Base64.getDecoder().decode(releaseKeystoreBase64))
}

android {
    namespace = "com.survivor.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.survivor.app"
        minSdk = 26
        targetSdk = 36
        versionCode = svVersionCode
        versionName = svVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
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
                storeFile = rootProject.file("keystore/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true; warningsAsErrors = false }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
