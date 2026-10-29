import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-compose: each platform's own web engine inside Compose (Android WebView, WKWebView, WebView2,
// WPE, an iframe on the web), with hundreds of logical web views pooled onto a few live engines.
// Self-contained like :terminal-compose: Compose and kotlinx only, NOTHING from :shared or :ui, so it can
// be published and reused on its own.
group = "dev.supermux.webview"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget { publishLibraryVariants("release") }
    // Apple targets: their compile/link tasks are disabled on Linux (kotlin.native.ignoreDisabledTargets)
    // and run on the Mac.
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Runs commonTest and wasmJsTest (the bridge shim, executed by a real browser) in headless Chrome.
            testTask { useKarma { useChromeHeadless() } }
        }
        // Required by the Compose plugin's wasm test check (CMP-4906); this module ships no app of its own.
        binaries.executable()
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            implementation(compose.foundation)
            api(compose.ui)
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        commonTest {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
            }
        }
        // The compiler takes opt-ins only from a compilation's own source set (jvmTest, wasmJsTest, …), never from the
        // commonTest they depend on. Without this the commonTest opt-in above does nothing: every test source set
        // names it too.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies { implementation(libs.kotlinx.browser) }
        }
        wasmJsTest {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
}

// runTest's own timeout can't interrupt a wait inside a non-cancellable section, so a hung test fails the task instead
// of holding the build.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}

android {
    namespace = "dev.supermux.webview"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
