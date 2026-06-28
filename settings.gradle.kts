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
        // transitive deps resolve). The PSDK is login-gated at Verifone, but Path
        // HAS redistribution rights — the aar is intentionally vendored here and
        // ships to partners via JitPack. See ENVIRONMENTS.md "Verifone PSDK binary".
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
