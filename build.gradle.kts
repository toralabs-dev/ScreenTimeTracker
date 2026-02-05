// Top-level build file for Screen Time Tracker
// This file configures build settings that apply to all modules in the project

plugins {
    // Android application plugin - don't apply here, just declare version
    id("com.android.application") version "8.13.2" apply false

    // Kotlin Android plugin
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false

    // KSP for Room annotation processing
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}

// Clean task to delete the build directory
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
