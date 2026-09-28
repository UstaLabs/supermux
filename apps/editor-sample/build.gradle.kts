plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-sample: try the native editor (desktop window, web page, Android app, iOS app) and measure it.
//
// It depends on :editor-compose and :editor-syntax and on NOTHING else of supermux, like
// :terminal-sample: it opens a bundled real Kotlin file (apps/shared/.../HostStore.kt, 2,231
// lines), a Markdown file (the editor's own spec) and generated 10k-line and 10 MB files, with
// syntax colours from a SyntaxWorker.
//
//   ./gradlew :editor-sample:run                       # the desktop window
//   ./gradlew :editor-sample:jvmTest                   # the performance targets (spec §6.6), asserted
//   ./gradlew :editor-sample:wasmJsBrowserDistribution # the web page (build/dist/wasmJs/productionExecutable)
//   node web-bench/run.mjs <dist dir>                  # web frame times + the cold-start ceiling (headless Chrome)
//   ./gradlew :editor-sample:assembleDebug             # the Android app (dev.supermux.editor.sample)
//   iosApp/device.sh <device id>                       # the iOS app, built, signed and installed ON THE MAC
group = "dev.supermux.editor.sample"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // The iOS app (iosApp/project.yml) links EditorSample.framework: dynamic, like the main app's
    // SupermuxKit, so the syntax binding's static archive is linked into it once, and embedded and
    // signed by :editor-sample:embedAndSignAppleFrameworkForXcode (which also copies the Compose
    // resources, the fonts and sample files, into the app bundle).
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "EditorSample"
            isStatic = false
        }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "editor-sample.js" }
            testTask { enabled = false }
        }
        binaries.executable()
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":editor-compose"))
            implementation(project(":editor-syntax"))
            implementation(project(":editor-plugins:basics"))
            implementation(project(":editor-plugins:highlight"))
            implementation(project(":editor-plugins:history"))
            implementation(project(":editor-plugins:fold"))
            implementation(project(":editor-plugins:view"))
            implementation(project(":editor-plugins:search"))
            implementation(project(":editor-plugins:autocomplete"))
            implementation(project(":editor-plugins:lint"))
            implementation(project(":editor-plugins:lsp"))
            // The sample's documents talk to the in-process toy language server (M4c; M5's host uses the broker).
            implementation(project(":editor-plugins:lsp-fake"))
            implementation(project(":editor-plugins:diff"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies { implementation(libs.kotlinx.browser) }
        }
    }
}

compose.resources {
    packageOfResClass = "dev.supermux.editor.sample.resources"
    generateResClass = always
}

android {
    namespace = "dev.supermux.editor.sample"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        // Its own id: installing the sample never touches another dev.supermux app.
        applicationId = "dev.supermux.editor.sample"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Debug-signed: this APK is never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ---------------------------------------------------------------- browser assets --------------
//
// The Kotlin/Wasm toolchain does not copy a dependency klib's resources next to the consumer's
// module (editor-syntax/native/README.md, "Serving it"), so this page re-exports editor-syntax's
// loader and verified wasm binary as its OWN wasmJs resources, plus the code-only grammars'
// tables at the default path next to the module (editor-syntax/tables/<lang>.sesz).
val syntaxProject = project(":editor-syntax")
val stageSyntaxWasmAssets by tasks.registering(Copy::class) {
    description = "Re-export syntax-loader.mjs, supermux-syntax.wasm and the grammar tables as this page's resources."
    dependsOn(":editor-syntax:stageWasmResources", ":editor-syntax:stageTables")
    from(syntaxProject.layout.buildDirectory.dir("generated/wasmResources"))
    from(syntaxProject.file("src/wasmJsMain/resources/syntax-loader.mjs"))
    from(syntaxProject.layout.buildDirectory.dir("generated/tables"))
    into(layout.buildDirectory.dir("generated/syntaxWasmAssets"))
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageSyntaxWasmAssets)

// ---------------------------------------------------------------- desktop run + perf ----------

val jvmMainCompilation = kotlin.jvm().compilations.getByName("main")
val jvmRuntimeClasspath: FileCollection = files(jvmMainCompilation.output.allOutputs, configurations.named("jvmRuntimeClasspath"))

/** `:editor-sample:run`: the desktop window. `-Psample.bench=true` runs the in-window benchmark and prints it. */
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the desktop editor sample"
    dependsOn("jvmMainClasses")
    classpath = jvmRuntimeClasspath
    mainClass.set("dev.supermux.editor.sample.MainKt")
    jvmArgs("-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
    providers.gradleProperty("sample.bench").orNull?.let { jvmArgs("-Deditor.sample.bench=$it") }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    // The perf test is the measurement: release-like JVM (no assertions), a fixed heap.
    jvmArgs("-da", "-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
    systemProperty("java.awt.headless", "true")
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------- web measurements -----------
//
// Both drive Google Chrome (CHROME_BIN, default: the Mac's) over the DevTools protocol with Node
// (>= 22, no packages): web-bench/run.mjs.
val webDist = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")

/**
 * The web cold start: five fresh headless Chrome profiles each load the page once, and index.html's
 * main-thread monitor reports the longest hold from the page's first script to the first frame with
 * syntax colours. Ceilings picked from the measurement (Mac, 2026-09-26: ~200 ms overall, Compose's
 * own first frame; ~130 ms after the syntax backend loaded, the Kotlin highlights query compile,
 * now done before the first parse) with headroom, so a regression fails.
 */
tasks.register<Exec>("webColdStartTest") {
    group = "verification"
    description = "Assert the web page's longest main-thread hold until its first coloured frame (headless Chrome)"
    dependsOn("wasmJsBrowserDistribution")
    workingDir = projectDir
    commandLine("node", "web-bench/run.mjs", "cold", webDist.get().asFile.absolutePath, "--runs", "5", "--ceiling", "300", "--syntax-ceiling", "200")
}

/** The web text-input path (TEXTAREA on focus, insertText/IME at the caret, paste, one a11y text box), in Chrome. */
tasks.register<Exec>("webInputTest") {
    group = "verification"
    description = "Assert the web text input: the DOM field, IME/insertText at the caret, paste, the accessibility tree (headless Chrome)"
    dependsOn("wasmJsBrowserDistribution")
    workingDir = projectDir
    commandLine("node", "web-bench/run.mjs", "input", webDist.get().asFile.absolutePath)
    // Then two editors and a plain <input> on one page, a text field inside a block widget, the search panel.
    doLast {
        project.providers.exec { commandLine("node", "web-bench/run.mjs", "two", webDist.get().asFile.absolutePath); workingDir = projectDir }.result.get().assertNormalExitValue()
        project.providers.exec { commandLine("node", "web-bench/run.mjs", "widget", webDist.get().asFile.absolutePath); workingDir = projectDir }.result.get().assertNormalExitValue()
        project.providers.exec { commandLine("node", "web-bench/run.mjs", "search", webDist.get().asFile.absolutePath); workingDir = projectDir }.result.get().assertNormalExitValue()
    }
}

/** The in-page benchmark (keystrokes and wheel scrolling on the 10k-line file) in Chrome. */
tasks.register<Exec>("webBench") {
    group = "verification"
    description = "Web frame times: keystrokes and wheel scrolling on the 10k-line file (Chrome)"
    dependsOn("wasmJsBrowserDistribution")
    workingDir = projectDir
    commandLine("node", "web-bench/run.mjs", "bench", webDist.get().asFile.absolutePath)
}
