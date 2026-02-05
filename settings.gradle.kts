// Settings file for Screen Time Tracker project
// Configures plugin repositories and includes modules

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // For MPAndroidChart
    }
}

// Project name
rootProject.name = "ScreenTimeTracker"

// Include the app module
include(":app")
