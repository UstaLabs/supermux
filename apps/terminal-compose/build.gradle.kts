plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// terminal-compose: the ONE terminal surface (grid painting, geometry, input) for Android, desktop
// JVM, iOS and the browser, drawn with Compose Multiplatform on a plain Canvas — no DOM node, no
// Swing widget, no UITextView anywhere in the rendering path.
//
// Self-contained like :terminal-core, and for the same reason: it depends on :terminal-core and
// Compose and NOTHING in :shared / :ui / the app modules, so it stays publishable on its own
// (dev.supermux.terminal:terminal-compose) and no app theme leaks into it. Publication wiring
// itself lands with Plan 2 Task 6.
group = "dev.supermux.terminal"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: declared so `iosMain` exists; their compile/link tasks are disabled on this
    // Linux host (kotlin.native.ignoreDisabledTargets) and run on the Mac.
    iosArm64()
    iosSimulatorArm64()
    // Browser only, like :ui. The wasm test task is disabled: commonTest is covered by jvmTest and
    // the browser-specific behaviour of this module is its host's (:web) to test.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: TerminalSession, TerminalViewport and the constants are
            // in this module's OWN public signatures (Terminal takes a session, TerminalFrame
            // carries TerminalRows), so every consumer needs them on its compile classpath.
            api(project(":terminal-core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // The surface test drives the REAL composable through Compose's test harness (and a
            // real Skia canvas), which is the only way to prove the geometry contract end to end.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.terminal.compose"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
