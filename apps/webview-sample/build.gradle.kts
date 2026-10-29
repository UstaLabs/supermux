import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-sample: the standalone sample and perf lab for :webview-compose. It depends on :webview-compose and on
// NOTHING else of supermux, like :terminal-sample: if the library can only be used from inside this repository, this
// module stops building. The desktop runs the library's fake engine; the browser (wasmJs) runs the real web engine, an
// iframe per live page. Each engine plan adds its platform's target and engine.
//
//   ./gradlew :webview-sample:run       # the desktop window: the perf lab and the overlay demo
//   ./gradlew :webview-sample:jvmTest   # the perf lab's invariants under scripted scrolling, its numbers printed as
//                                       # `perf-lab` lines, and the screens rendered to build/sample-screens/*.png
//   ./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the browser sample, served on localhost only
//   ./gradlew :webview-sample:wasmJsBrowserDistribution     # build/dist/wasmJs/productionExecutable, which
//                                       # web-check/run.mjs drives in a headless Chrome (modes lab and overlay)
group = "dev.supermux.webview.sample"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "webview-sample.js" }
            // The sample's tests run on the desktop (jvmTest). Its browser build is checked in a real Chrome by
            // web-check/run.mjs, which can do what Karma can't: trusted input, process memory, screenshots.
            testTask { enabled = false }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":webview-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // Material3 dresses the sample's own controls. The web views use nothing of it.
            implementation(compose.material3)
            implementation(libs.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            // Dispatchers.Main on desktop, where the runtime's scope runs.
            implementation(libs.coroutines.swing)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            // kotlinx.browser / org.w3c, which the wasm stdlib no longer has.
            dependencies { implementation(libs.kotlinx.browser) }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.supermux.webview.sample.MainKt"
        jvmArgs += listOf("-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
    }
}

tasks.withType<Test>().configureEach {
    // Compose UI tests drive one scene at a time.
    maxParallelForks = 1
    // A fixed heap, so a run's GC doesn't depend on the machine's free memory.
    jvmArgs("-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
    // The scripted scrolls take a while; a hung test fails the task instead of holding the build.
    timeout.set(Duration.ofMinutes(15))
    // Never up to date: the perf lab's numbers come from a run that happened.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        // The `perf-lab` lines are the measurements.
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
