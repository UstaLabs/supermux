plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/search: find and replace as a plugin on the public API (spec §7, "search:
// find/replace panel with regex, match case and whole word; all matches marked"), CM6's
// @codemirror/search in Kotlin.
//
// The engine (SearchQuery, the cursors over the Rope) is pure Kotlin on :editor-core. The plugin
// uses :editor-compose's facets (EditorViewport, revealFacet) and its panel is Compose content
// registered as `panel:search` (the one sanctioned widget exception, spec §4.4).
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    // The regex golden must hold in the browser too (Kotlin/Wasm's own Regex engine): the common
    // tests run in headless Chrome through Karma (the Mac's Google Chrome, see below).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core"))
            implementation(project(":editor-compose"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Replace all is one undo step; a match inside a fold unfolds it.
            implementation(project(":editor-plugins:history"))
            implementation(project(":editor-plugins:fold"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The panel in a composed Editor: focus, Escape, typing, the buttons.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.search"
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

// No Compose UI in the browser tests (the engine and the plugin's state only): the Compose plugin's
// check for a bundled Skiko runtime does not apply.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
