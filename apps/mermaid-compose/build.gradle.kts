plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// mermaid-compose: paints :mermaid-core's SceneGraph on a Compose Canvas (MermaidDiagram), with
// the fonts Mermaid's metrics assume shipped as Compose resources. Vendored from cmp-mermaid
// (MIT, swithun-liu); see README.md. No WebView and no JavaScript anywhere in the path.

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
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
            api(project(":mermaid-core"))
            api(compose.ui)
            implementation(compose.foundation)
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.android.svg)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "com.swithun.cmpmermaid.compose.generated.resources"
}

android {
    namespace = "com.swithun.cmpmermaid.compose"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
