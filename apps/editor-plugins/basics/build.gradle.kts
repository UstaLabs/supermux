plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/basics: the editing basics every code editor has, as a plugin on the public API
// (spec §7, "our own features are plugins"): closing brackets, and Enter between braces.
//
// Depends on :editor-core (state, transactions, facets, keymaps) and on :editor-compose ONLY for
// three editor-level facets that live there (inputHandlerFacet, indentUnitFacet) and for
// DefaultCommands.insertNewline. It uses no Compose UI type: it produces transactions and key
// bindings, data only, which is the sandbox-ready plugin contract. (Moving those facets into
// :editor-core would let a plugin like this depend on the core alone; left for M4, when the other
// plugins show which facets belong there.)
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
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The soft-keyboard path: typing through the real hidden field of a composed Editor.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.basics"
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
        events("passed", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
