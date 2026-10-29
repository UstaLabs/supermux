import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-sample: the standalone sample and perf lab for :webview-compose. It depends on :webview-compose and on
// NOTHING else of supermux, like :terminal-sample: if the library can only be used from inside this repository, this
// module stops building. Desktop only for now, on the library's fake engine; each engine plan adds its platform's
// target and engine.
//
//   ./gradlew :webview-sample:run       # the desktop window: the perf lab and the overlay demo
//   ./gradlew :webview-sample:jvmTest   # the perf lab's invariants under scripted scrolling, its numbers printed as
//                                       # `perf-lab` lines, and the screens rendered to build/sample-screens/*.png
group = "dev.supermux.webview.sample"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()

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
