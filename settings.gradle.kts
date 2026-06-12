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
        // Verifone PSDK — committed local Maven repo (aar + its real pom, so
        // transitive deps resolve). The PSDK download is login-gated at
        // Verifone; partner distribution is pending a licensing answer.
        maven { url = uri("third-party/verifone/m2") }
        // usb-serial-for-android (PSDK transitive dep) lives on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "path-terminal-sdk-android"

include(":path-core-models")
include(":path-terminal-sdk")
include(":path-emulator-adapter")
include(":path-mock-adapter")
include(":path-diagnostics")
include(":path-psdk-adapter")
