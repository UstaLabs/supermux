plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The pane layer: splits, tab strips, drag, drop zones. It knows nothing about what a pane
// contains — no view kinds, no sessions, no broker, no fonts of its own. Everything content-shaped
// arrives through a slot (see PaneHost's tabSlot / addSlot / emptyGroupSlot).
//
// This is a MATERIAL 3 pane library, deliberately: both consumers already ship material3, so
// staying toolkit-neutral would cost a colour-token abstraction that nothing would use.
//
// jvm() + androidTarget(). Desktop consumes the JVM artifact; Android wires :ui in a later phase.
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: these types are in this module's OWN public signatures —
            // PaneHost takes a LayoutNode, PaneStripChrome returns a Modifier, PaneDragController exposes
            // Rect/Offset, Motion returns a FiniteAnimationSpec. Under `implementation` a second consumer
            // gets unresolved references on our own API and only :desktop compiles, by accident of
            // declaring the same dependencies itself.
            api(project(":shared"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // Icons.Filled.Close, used by DefaultTabChip's close affordance.
            api(compose.materialIconsExtended)
            // Motion's FiniteAnimationSpec — used directly, so declare it rather than leaning on
            // compose.foundation's transitive.
            implementation(compose.animation)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.ui"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
