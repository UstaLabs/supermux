plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// terminal-sample: the standalone sample app and the benchmark harness for the terminal package.
//
// It depends on `:terminal-compose` (and through it `:terminal-core`) and on NOTHING else of
// supermux — no `:shared`, no `:ui`, no broker, no SSH, no pty. That is not tidiness: it is the
// check. If the pair can only be used from inside this repository, this module stops building, and
// the consumer-smoke builds next to each package resolve the SAME code from published coordinates
// to prove the same thing without a project dependency at all.
//
// Every byte any terminal here ever shows is generated in process (src/commonMain/.../
// SampleFixtures.kt). The diagnostics are SAMPLE-ONLY by construction: they are built on the
// package's public seams, and nothing in `:terminal-compose` knows they exist.
group = "dev.supermux.terminal.sample"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: their compile/link tasks are disabled on this Linux host
    // (kotlin.native.ignoreDisabledTargets) and run on the Mac —
    // `:terminal-sample:linkDebugFrameworkIosSimulatorArm64` is the iOS check.
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "terminal-sample.js" }
            // `:terminal-sample:wasmJsBrowserTest` runs commonTest (the fixtures and the
            // measurement arithmetic) in headless Chrome. CHROME_BIN must point at a WasmGC-capable
            // Chrome; this host has /usr/bin/google-chrome.
            testTask { useKarma { useChromeHeadless() } }
        }
        binaries.executable()
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // THE dependency. `:terminal-compose` re-exports `:terminal-core` as `api`, so the
            // session types arrive with it and the sample names no other supermux module.
            implementation(project(":terminal-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // Material3 dresses the sample's OWN controls. It never reaches the terminal: the grid
            // is painted from `TerminalTheme`, which imports no app theme at all.
            implementation(compose.material3)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                // kotlinx.browser / org.w3c — no longer in the wasm stdlib.
                implementation(libs.kotlinx.browser)
            }
        }
        wasmJsTest {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
}

android {
    namespace = "dev.supermux.terminal.sample"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.supermux.terminal.sample"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
    }
    buildTypes {
        // Both build types are unminified: a sample whose release build strips the very code a
        // reader came to look at is not a sample, and R8 has nothing to shrink here anyway.
        getByName("release") {
            isMinifyEnabled = false
            // Debug-signed on purpose: this APK is never distributed, and a signing config would
            // make `assembleRelease` need a key that does not exist in a fresh checkout.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    // The Kotlin sources live in src/androidMain/kotlin (KMP layout); AGP finds the manifest and
    // the resources there too.
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// ---------------------------------------------------------------- browser assets --------------
//
// MEASURED (terminal-core/consumer-smoke, 2026-09-22): the Kotlin/Wasm toolchain does NOT copy a
// DEPENDENCY klib's resources next to the consumer's compiled module, so webpack fails with
//   Module not found: Error: Can't resolve './terminal-loader.mjs'
// — and it fails even when nothing in the app calls the engine, because the import is resolved at
// bundle time. Every browser host of terminal-core therefore re-exports the two files the package
// ships inside its klib as its OWN wasmJs resources. This module is one, so it does it here, for
// the app bundle AND for the test bundle.
//
// The bytes come from `:terminal-core:stageWasmResources`, which is the task that verifies
// `build/wasm/supermux-terminal.wasm` against its manifest (sha256 + size + ABI) before staging it.
val terminalCoreWasmResources: File =
    project(":terminal-core").projectDir.resolve("build/gradle/generated/wasmResources")

val stageTerminalWasmAssets by tasks.registering(Copy::class) {
    description = "Re-export terminal-loader.mjs + supermux-terminal.wasm as this app's wasmJs resources."
    dependsOn(":terminal-core:stageWasmResources")
    from(terminalCoreWasmResources)
    into(layout.buildDirectory.dir("generated/terminalWasmAssets"))
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageTerminalWasmAssets)
kotlin.sourceSets.getByName("wasmJsTest").resources.srcDir(stageTerminalWasmAssets)

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (System.getenv("CHROME_BIN") == null && File("/usr/bin/google-chrome").canExecute()) {
        environment("CHROME_BIN", "/usr/bin/google-chrome")
    }
    // Same host gotcha as :terminal-core: with a D-Bus session bus, headless Chrome on this Linux
    // box stalls every http(s) navigation, which hangs Karma's capture.
    environment("DBUS_SESSION_BUS_ADDRESS", "disabled:")
}

// ---------------------------------------------------------------- desktop run tasks -----------
//
// The Compose Desktop `application {}` DSL is deliberately NOT used: this module has two JVM-ish
// targets (jvm + android) and that DSL's single-target assumption would make the wiring implicit.
// Two explicit JavaExec tasks say exactly what each run is, which matters because one of them is
// the build a measurement may be quoted from.

val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")

/** Classes + resources of `jvmMain`, plus its resolved runtime dependencies (skiko included). */
val jvmRuntimeClasspath: FileCollection =
    files(jvmMainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))

/**
 * The RELEASE definition for this sample, spelled out so a number can be traced to it:
 *
 * - **not debuggable**: no JDWP agent (`debugOptions.enabled = false`), so nothing can attach and
 *   deoptimise a hot method under the measurement,
 * - **assertions off** (`-da -dsa`): Kotlin's `require`/`check` still run (they are plain code),
 *   but the JDK's own `assert` statements and the `-ea` the developer run enables do not,
 * - **full tiered JIT** — explicitly NOT `-XX:TieredStopAtLevel=1`, which is the single biggest
 *   difference between a "debug" and a "release" JVM run,
 * - **G1 with a fixed heap**, so a run's GC behaviour does not depend on how much RAM the machine
 *   happened to have free at the time,
 * - **nothing instrumented**: no Compose hot reload agent, no tooling agent, no profiler.
 *
 * There is no ProGuard/R8 step because a desktop JVM app has none: "release" on the JVM means the
 * runtime is unhobbled, not that the bytecode was rewritten. Skiko's backend is left to skiko
 * (`SKIKO_RENDER_API=SOFTWARE` in the environment forces software rendering on a GPU-less box;
 * forcing it here would understate the package everywhere else).
 */
val releaseJvmArgs = listOf(
    "-da",
    "-dsa",
    "-XX:+UseG1GC",
    "-Xms512m",
    "-Xmx2g",
    "-Dsupermux.sample.buildType=release",
)

/**
 * `:terminal-sample:run` — the ordinary developer run. Assertions ON and no optimisation flags:
 * fine for looking at the sample, NOT a build to quote a frame time from.
 *
 * `-Psample.terminals=4` mounts four terminals at start (the benchmark's shape).
 */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the desktop terminal sample (debug: assertions on, no optimisation flags)"
    dependsOn("jvmMainClasses")
    classpath = jvmRuntimeClasspath
    mainClass.set("dev.supermux.terminal.sample.MainKt")
    jvmArgs("-ea", "-Dsupermux.sample.buildType=debug")
    providers.gradleProperty("sample.terminals").orNull?.let {
        jvmArgs("-Dsupermux.sample.terminals=$it")
    }
}

/** The same app on the release JVM; see [releaseJvmArgs] for exactly what that means. */
tasks.register<JavaExec>("runRelease") {
    group = "application"
    description = "Run the desktop terminal sample as a release build (no debug agent, assertions off, full JIT)"
    dependsOn("jvmMainClasses")
    classpath = jvmRuntimeClasspath
    mainClass.set("dev.supermux.terminal.sample.MainKt")
    debugOptions { enabled.set(false) }
    jvmArgs(releaseJvmArgs)
    providers.gradleProperty("sample.terminals").orNull?.let {
        jvmArgs("-Dsupermux.sample.terminals=$it")
    }
}

/**
 * `:terminal-sample:benchmark` — the repeatable run, always on the release JVM.
 *
 * ```
 * ./gradlew :terminal-sample:benchmark -Pbenchmark.args="--minutes=10 --fixture=ansi --out=run.md"
 * ```
 * On a headless host it needs a display:
 * `xvfb-run -s "-screen 0 1600x1200x24" ./gradlew :terminal-sample:benchmark -Pbenchmark.args="…"`.
 */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Run the repeatable terminal benchmark (release JVM). -Pbenchmark.args=\"…\""
    dependsOn("jvmMainClasses")
    classpath = jvmRuntimeClasspath
    mainClass.set("dev.supermux.terminal.sample.BenchmarkKt")
    debugOptions { enabled.set(false) }
    jvmArgs(releaseJvmArgs)
    // Never up to date: a benchmark that Gradle skips because "nothing changed" is a benchmark
    // that did not run.
    outputs.upToDateWhen { false }
    args = (providers.gradleProperty("benchmark.args").orNull ?: "")
        .split(' ')
        .filter { it.isNotBlank() }
}
