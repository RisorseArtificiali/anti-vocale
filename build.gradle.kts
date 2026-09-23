// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // AGP 8.10 ships R8 that only reads Kotlin metadata 2.1; Kotlin 2.4
        // classes (incl. our own stdlib) need R8 9.1.29+ (compatible with
        // AGP 8.5.2+, see developer.android.com/studio/build/kotlin-d8-r8-versions).
        classpath("com.android.tools:r8:9.1.43")
    }
}

plugins {
    // TASK-252: versions live in gradle/libs.versions.toml; this block only
    // declares which plugins the root classpath carries for the modules.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.hilt) apply false
}
