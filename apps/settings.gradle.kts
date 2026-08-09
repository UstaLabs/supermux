pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// Toolchain auto-provisioning. Needed by Compose Hot Reload (:desktop:hotRun): its run tasks demand
// a JetBrains Runtime — the JBR's enhanced class redefinition IS the reload mechanism — and no
// stock JDK satisfies that, so without a download resolver the task fails with "No matching
// toolchains found for JVM_VENDOR=JETBRAINS". Only affects toolchain requests that can't be
// satisfied locally; the ordinary jvmToolchain(17)/(21) builds keep using the installed JDKs.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "supermux-apps"
include(":shared")
include(":ui")
include(":android")
include(":desktop")
