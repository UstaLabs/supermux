plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// terminal-core: the ONE shared terminal engine (upstream Ghostty's libghostty-vt behind an owned
// `st_*` C ABI) for Android, desktop JVM, iOS and the browser. Self-contained on purpose — it
// depends on nothing in :shared or :ui so it can later be published on its own
// (dev.supermux.terminal:terminal-core, MIT).
//
// Status: Task 2 of the terminal plan — the shared Kotlin contract only. Every platform's
// `createTerminalEngine` throws `TerminalEngineUnavailableException` until the native bindings
// (JNI / cinterop / wasm) land, so the EngineContractTest suite is red by design.
group = "dev.supermux.terminal"
version = "0.1.0-dev.1"

// `build/` is shared with the native scripts (native/build.sh, wasm/build.sh), which keep the
// pinned Ghostty checkout, Zig caches and staged libraries there. Gradle gets its own
// subdirectory so `clean` cannot wipe those (multi-minute) native caches.
layout.buildDirectory = layout.projectDirectory.dir("build/gradle")

kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget()
    // Apple targets: declared so iosMain exists; their compile/link tasks are disabled on this
    // Linux host (kotlin.native.ignoreDisabledTargets) and run on the Mac.
    iosArm64()
    iosSimulatorArm64()
    // Browser only; commonTest is compiled for wasm but executed on the JVM.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.supermux.terminal"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
