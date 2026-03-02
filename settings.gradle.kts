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
        // AppAuth-Android is published here
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LocalAI Tasker Bridge"
include(":app")
