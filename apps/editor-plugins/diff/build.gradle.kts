plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/diff: the diff views (spec §7 item 7) on the public API, CM6's @codemirror/merge in
// Kotlin: a line diff with a character diff inside changed lines (pure, sliced recompute), an inline
// mode (one column: the working copy with the deleted lines as block widgets; today's walkthrough),
// a side-by-side mode on two linked views (M3c's LinkedScroll + lineMappingFacet), folded unchanged
// runs, revert-hunk, next/previous hunk, and review threads (block widgets, the comment composer).
//
// Data in (base and working text, threads, a composer), events out (DiffHost): M5 maps :ui's
// walkthrough and diff callbacks onto it.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    // The engine's tests (and its slicing: a real macrotask between slices) also run in headless Chrome.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core"))
            api(project(":editor-compose"))
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // Revert is one undo step.
            implementation(project(":editor-plugins:history"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        wasmJsTest { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // Both modes in composed Editors: alignment, folds, threads, the composer and its focus.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.diff"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    val mac = File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    if (System.getenv("CHROME_BIN") == null && mac.canExecute()) environment("CHROME_BIN", mac.absolutePath)
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// No Compose UI in the browser tests (the engine and the plugin's state only).
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
