plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// editor-plugins/lsp-fake: an in-process language server for a toy language, behind the LSP client's
// LspTransport: what the LSP plugin's tests and the editor sample run against, so M4c is testable end
// to end without the broker. NOT for production hosts (they use the broker's channel, M5).
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
            api(project(":editor-plugins:lsp"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.lsp.fake"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
