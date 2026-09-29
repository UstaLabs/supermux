plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// editor-core: the native editor's pure-Kotlin heart: rope, change sets, selection, state,
// transactions and the extension system (docs/superpowers/specs/2026-09-25-native-editor-design.md §4).
//
// Depends on NOTHING in this repository and on no UI toolkit: no Compose, no :shared, no :ui.
// Every client runs the same code, and the whole contract is testable in milliseconds on the JVM.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: compiled on the Mac (kotlin.native.ignoreDisabledTargets elsewhere).
    iosArm64()
    iosSimulatorArm64()
    // Browser; its test task is off: commonTest runs on the JVM, and nothing here is browser-specific.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "dev.supermux.editor.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
