plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/highlight: syntax highlighting as a plugin (spec §7): editor-syntax's Syntax
// extension, plus the SyntaxHost that owns one SyntaxWorker per EditorView (listener, UI-thread hop,
// viewport, query precompile, "syntax off" panel, disposal). The host side is a Compose helper
// (rememberSyntaxHost); the extension itself is data only.
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
            api(project(":editor-syntax"))
            implementation(project(":editor-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The real syntax worker over the native binding, and bracket matching reading its spans.
            implementation(project(":editor-plugins:basics"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.highlight"
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
