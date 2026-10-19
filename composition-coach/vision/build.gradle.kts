// Android library: turns CameraX frames + device sensors into a FrameAnalysis
// (faces, bodies, image statistics, orientation) consumed by :composition.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.compositioncoach.vision"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":composition"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.camera.core)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.pose.detection)
    implementation(libs.mlkit.objects)
    implementation(libs.mlkit.segmentation.selfie)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
