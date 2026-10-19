// Pure Kotlin/JVM module: composition scoring, analyzers, recommendation logic.
// Deliberately has NO Android dependencies so the algorithms are unit-testable on the JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("failed", "skipped") }
}
