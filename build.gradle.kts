// Top-level build file where you can add configuration options common to all sub-projects/modules.
// TASK-710: the former buildscript r8 classpath override (9.1.43, added by
// TASK-252 because AGP 8.10's stock R8 only read Kotlin metadata 2.1) is
// GONE: it is older than the R8 AGP 9 drives and broke minify*Release on a
// missing API. AGP 9's bundled R8 reads Kotlin 2.4 metadata natively.

plugins {
    // TASK-252: versions live in gradle/libs.versions.toml; this block only
    // declares which plugins the root classpath carries for the modules.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.crashlytics) apply false
    alias(libs.plugins.hilt) apply false
}
