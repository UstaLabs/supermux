plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

repositories {
    mavenCentral()
    google()
}

// The pane layer: splits, tab strips, drag, drop zones. It knows nothing about what a pane
// contains — no view kinds, no sessions, no broker, no fonts of its own. Everything content-shaped
// arrives through a slot (see PaneHost's tabSlot / addSlot / emptyGroupSlot).
//
// This is a MATERIAL 3 pane library, deliberately: both consumers already ship material3, so
// staying toolkit-neutral would cost a colour-token abstraction that nothing would use.
//
// jvm only for now. Android gets a target when a caller needs one — declaring androidTarget()
// early would add an Android variant to every build and give nothing back.
dependencies {
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

    testImplementation(kotlin("test"))
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(17) }
