plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/fold: folding as a plugin on the public API (spec §7, "fold: gutter arrows,
// fold/unfold/fold-all, placeholder widget"), CM6's @codemirror/language folding in Kotlin.
//
// Depends on :editor-core (state fields, decorations, gutter markers, the fold service) and on
// :editor-compose for its facets (gutter/widget clicks, reveal, atomic delete, EditorViewport): data only.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core"))
            implementation(project(":editor-compose"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Folds and undo together.
            implementation(project(":editor-plugins:history"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // Folding in a composed Editor: gutter clicks, the chip, hidden lines.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.fold"
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
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
