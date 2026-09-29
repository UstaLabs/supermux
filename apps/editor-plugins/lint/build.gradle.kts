plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/lint: CM6's @codemirror/lint in Kotlin on the public API: diagnostics as data (a
// RangeSet mapped through edits), squiggle marks, gutter markers, a hover tooltip through
// editor-compose's tooltip layer, next / previous, and the diagnostics panel. The LSP plugin feeds it.
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
            api(project(":editor-compose"))
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // A code action is one undo step; a diagnostic inside a fold opens it.
            implementation(project(":editor-plugins:history"))
            implementation(project(":editor-plugins:fold"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The tooltip and the panel in a composed Editor.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.lint"
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
