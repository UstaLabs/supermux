plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // ui/nav/Route.kt: @Serializable destinations (Android's type-safe navigation-compose reads the
    // generated serializer's descriptor to build its route strings).
    alias(libs.plugins.serialization)
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
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // Icons.Filled.Close, used by DefaultTabChip's close affordance.
            api(compose.materialIconsExtended)
            // Motion's FiniteAnimationSpec, and SharedTransitionScope/AnimatedVisibilityScope in
            // SessionAvatar's own signature — hence `api`, not `implementation`.
            api(compose.animation)
            // Geist + Geist Mono ship from here as Compose Multiplatform resources (Res.font.*),
            // replacing Android's R.font and desktop's classpath lookup. `api`, because the font
            // families in theme/Type.kt are this module's public surface.
            api(compose.components.resources)
            // Compose Multiplatform's own BackHandler (androidx.compose.ui.backhandler), used by
            // EditorPanel's search-overlay / tree-drawer back contract. It is a SEPARATE artifact
            // from compose.ui and there is no `compose.` accessor for it, hence the coordinates;
            // the version is the one the Compose plugin resolves for every other compose artifact.
            api("org.jetbrains.compose.ui:ui-backhandler:${libs.versions.composeMultiplatform.get()}")
            // Coil 3 — the ONE image loader for both apps (markdown images in D2, attachment
            // thumbnails, avatars). Multiplatform, and its Ktor-3 network backend shares the
            // engine :shared already ships. `api`, because AsyncImage/ImageRequest appear in the
            // shared composables' own signatures (the loadImage test seams take them).
            api(libs.coil.compose)
            api(libs.coil.network.ktor3)
            // Inline video in the shared chat timeline (D2). Moved here from :desktop; Android's
            // media3/ExoPlayer player is gone (D2 removed the dependency) and :desktop had no other
            // player at all. Renders frames into a Compose Canvas, so nothing AWT-heavyweight.
            api(libs.compose.media.player)
            // The inline-image fetch policy (https gate, byte cap, bounded redirects) is Ktor here
            // rather than `java.net`, so it compiles for iOS too. The engine comes from :shared's
            // runtime classpath.
            api(libs.ktor.client.core)
            // MessageTts's generation counter — a plain Int would be a data race between the
            // Compose frame that toggles and the coroutine that streams audio chunks.
            implementation(libs.atomicfu)
            // The ONE QR encoder (widgets/QrCode.kt). Pure-Java ZXing core — NOT the Android
            // `zxing-android-embedded` scanner Android's copy pulled in just to draw a bitmap.
            // Only the module matrix comes from it; the raster is a Compose Canvas, so this stays
            // `implementation`: no ZXing type appears in `:ui`'s own public API.
            implementation(libs.zxing.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // ChatHeaderTest drives the real ChatPanel over a HostStore, which needs an HTTP
            // engine; the mock engine answers "{}" and no socket is opened (connectOnInit = false).
            implementation(libs.ktor.client.mock)
            // SettingsHubTest fires a REAL back gesture (NavigationEventInput) at the hub's
            // BackHandler. The artifact is already on the runtime classpath via ui-backhandler;
            // naming it here only puts it on the test COMPILE classpath.
            implementation(libs.jetbrains.navigationevent.compose)
            // QrCodeTest decodes what widgets/QrCode.kt encoded, through ZXing's own reader.
            // (commonMain declares zxing as `implementation`, which a KMP test source set does
            // not inherit.)
            implementation(libs.zxing.core)
        }
    }
}

compose.resources {
    // Generated accessor: dev.supermux.ui.resources.Res — public so app modules could reach it too.
    packageOfResClass = "dev.supermux.ui.resources"
    publicResClass = true
    generateResClass = always
}

android {
    namespace = "dev.supermux.ui"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
