import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-wpetest: the Linux WPE feasibility test for :webview-compose's Linux engine
// (docs/superpowers/plans/2026-10-30-webview-linux-wpe.md). A throwaway desktop app: WPE WebKit 2.54 rendering off
// screen through WPEPlatform objects of its own, its shared-memory frames drawn by Compose, and scripted scenarios that
// measure it. Compose and kotlinx only, like the library. The native half (native/) builds in the lab container.
//
//   ./gradlew :webview-wpetest:jvmTest   # the pure parts (stats, keys, timelines), on any host
//   ./gradlew :webview-wpetest:stageLab  # build/lab/lib: the app jar and its runtime classpath, skiko for linux-x64
group = "dev.supermux.webview.wpetest"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            // Dispatchers.Main on desktop.
            implementation(libs.coroutines.swing)
            implementation(libs.serialization.json)
            // The lab is Linux x64: the staged classpath carries that skiko runtime, whatever host builds it.
            implementation(compose.desktop.linux_x64)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val stageLab by tasks.registering(Sync::class) {
    group = "wpetest"
    description = "Copies the app jar and its runtime classpath (skiko for linux-x64) to build/lab/lib."
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("lab/lib"))
}

tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(5))
}
