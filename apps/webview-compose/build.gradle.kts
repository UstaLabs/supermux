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
        // Dispatchers.Main on desktop: the default runtime's scope, and with it every web view, runs there.
        jvmMain.dependencies { implementation(libs.coroutines.swing) }
        // The Android engine (spec §7). androidx only, nothing of supermux.
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.core.core)
            // BackHandler, while the page has focus and can go back.
            implementation(libs.androidx.activity.compose)
        }
        // Run on the user's phone by device-checks/android-instrumented.sh: the real WebView behind the engine.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.ext.junit)
                implementation(kotlin("test-junit"))
                implementation(libs.androidx.activity.compose)
            }
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
        // The iOS engine is Objective-C interop throughout: pointers, structs and NSObject subclasses. Every iOS source
        // set names the opt-ins, for the same reason as above.
        matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
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

// The iOS tests run in a simulator on the Mac: "sm-iPhone" (iOS 26.5) unless -Pwebview.simulator names another, such
// as "iPhone 14" (iOS 16.4). A Kotlin/Native test process isn't an app, so no WKWebView loads a page in it: these tests
// cover what needs none, and the sample app runs the engine's conformance checks (device-checks/ios-engine-checks.sh).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("webview.simulator").orElse("sm-iPhone"))
}

// The web engine's feasibility suites (classes named *SpikeTest) measure for minutes, so Karma skips them unless asked:
//   webview-mac-gradle :webview-compose:wasmJsBrowserTest -Pwebview.spike=true --tests 'dev.supermux.webview.engine.web.spike.*'
// The environment is an input of the test task, so switching it reruns the task.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (providers.gradleProperty("webview.spike").orNull == "true") environment("WEBVIEW_SPIKE", "1")
}

android {
    namespace = "dev.supermux.webview"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The test APK is the app under test: it targets what apps target, so WebView behaves as it does for them
    // (prefers-color-scheme from isLightTheme needs targetSdk 33+; edge-to-edge is enforced from 35).
    testOptions { targetSdk = libs.versions.androidTargetSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
