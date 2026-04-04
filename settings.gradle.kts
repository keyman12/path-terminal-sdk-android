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
    }
}

rootProject.name = "path-terminal-sdk-android"

include(":path-core-models")
include(":path-terminal-sdk")
include(":path-emulator-adapter")
include(":path-mock-adapter")
include(":path-diagnostics")
