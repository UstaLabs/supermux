plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-compose: the native editor's ONE surface (height map, visible-line layout, one Canvas, the
// gutter, scrolling, pointer and the windowed hidden field for soft keyboards / IME) for Android,
// the desktop JVM, iOS and the browser. Spec: docs/superpowers/specs/2026-09-25-native-editor-design.md §6.
//
// Self-contained like :terminal-compose, and for the same reason: it depends on :editor-core and
// Compose and NOTHING in :shared / :ui / the app modules, and not on :editor-syntax either — syntax
// colours arrive as editor-core decorations (`tok-*` mark classes) that the theme resolves.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: compiled on the Mac (kotlin.native.ignoreDisabledTargets elsewhere).
    iosArm64()
    iosSimulatorArm64()
    // Browser; its test task is off: commonTest runs on the JVM, and :editor-sample's web page is
    // where the browser behaviour is exercised.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // `api`: EditorState, TransactionSpec and the command types are in this module's own
            // public signatures.
            api(project(":editor-core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            // The default face (JetBrains Mono) ships inside this artifact as a Compose resource;
            // `implementation`: the generated `Res` is internal, only a FontFamily leaves here.
            implementation(compose.components.resources)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The surface tests drive the REAL composable through Compose's desktop test harness
            // (a real Skia canvas and a real TextMeasurer).
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // Test-only: the theme test reads editor-syntax's token vocabulary, so a class added
            // there cannot go uncoloured here. Main code never depends on :editor-syntax.
            implementation(project(":editor-syntax"))
        }
    }
}

// Internal accessors: `Res.font.jetbrains_mono_*` is this module's business; a consumer that wants
// the family calls `packagedEditorFontFamily()`.
compose.resources {
    packageOfResClass = "dev.supermux.editor.compose.resources"
    publicResClass = false
    generateResClass = always
}

android {
    namespace = "dev.supermux.editor.compose"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Compose UI tests drive one scene at a time; one fork keeps them terminating on a loaded host
// (the same choice :terminal-compose makes).
tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The MIT licence and the font's OFL notice travel inside every archive.
val licenseFiles = files(
    layout.projectDirectory.file("LICENSE"),
    layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"),
)
tasks.withType<Jar>().configureEach { from(licenseFiles) { into("META-INF/dev.supermux.editor") } }
