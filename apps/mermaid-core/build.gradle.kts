plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// mermaid-core: Mermaid.js 12.0.0 translated to Kotlin — parse (Jison tables), diagram state,
// layout (dagre / ELK ports) and a platform-neutral SceneGraph. Vendored from cmp-mermaid
// (MIT, swithun-liu); see README.md for origin, attribution and local changes. Depends on nothing
// in :shared / :ui, and has no Compose dependency — :mermaid-compose paints the SceneGraph.

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: compile/link on the Mac (disabled on this Linux host, like the other modules).
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kaml)
            implementation(libs.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.swithun.cmpmermaid.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
