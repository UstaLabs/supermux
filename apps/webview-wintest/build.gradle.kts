import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-wintest: the Windows capture + focus feasibility test for :webview-compose's Windows engine
// (docs/superpowers/plans/2026-10-29-webview-windows-capture-test.md). A throwaway desktop app: one WebView2 in
// visual-hosting mode, captured with Windows.Graphics.Capture and drawn by Compose, and scripted scenarios that
// measure it. Compose and kotlinx only, like the library. The native half (native/) is built on the Windows VM.
//
//   ./gradlew :webview-wintest:jvmTest   # the pure parts (stats, cursors, bounds debounce, timelines), on any host
//   ./gradlew :webview-wintest:stageVm   # build/vm/lib: the app jar and its runtime classpath, skiko for Windows x64
group = "dev.supermux.webview.wintest"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(libs.coroutines.core)
            // Dispatchers.Main on desktop.
            implementation(libs.coroutines.swing)
            implementation(libs.serialization.json)
            // The VM is Windows 11 on x64: the staged classpath carries that skiko runtime, whatever host builds it.
            implementation(compose.desktop.windows_x64)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val stageVm by tasks.registering(Sync::class) {
    group = "wintest"
    description = "Copies the app jar and its runtime classpath (skiko for Windows x64) to build/vm/lib."
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("vm/lib"))
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(5))
}
