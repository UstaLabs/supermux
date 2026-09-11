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
// jvm() + androidTarget() + the two iOS targets. Desktop consumes the JVM artifact, Android the
// android one, and `:ios` (SupermuxKit.framework) the iOS ones.
kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // iOS (cluster H1). Declared so `iosMain` exists and the 8 expect seams get their Apple
    // actuals; the compile/link tasks are DISABLED on this Linux host
    // (kotlin.native.ignoreDisabledTargets in gradle.properties) and run on the Mac. No framework
    // is declared here — `:ios` owns the single one the phone links (SupermuxKit), which re-exports
    // this module; a second framework would embed the klibs twice.
    iosArm64()
    iosSimulatorArm64()
    // Browser (plan 1 of web→KMP). Same reasoning as :shared: no Node target, and the wasm test
    // task is disabled — this module has no commonTest at all (jvmTest covers commonMain) and
    // :web owns the browser-only tests.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    // Explicit, matching `:shared`: iosMain sits under the default apple/native hierarchy, and
    // naming the template keeps that wiring stable if an intermediate source set is ever added.
    applyDefaultHierarchyTemplate()

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
            // Navigation 3 — the ONE navigation renderer for both hosts (cluster G8). Desktop
            // already drove `NavDisplay` + a back stack; Android's `NavHost`/`composable<Route.X>`
            // (androidx.navigation:navigation-compose) is gone. `api`, because `ShellUiState`
            // exposes the Nav3 back stack and `FullPaneOverlaySceneStrategy` is a `SceneStrategy`
            // in this module's own public signatures.
            api(libs.jetbrains.navigation3.ui)
        }
        wasmJsMain {
            // `js("…")`, external declarations and JsAny are all still behind this opt-in in 2.3.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                // kotlinx.browser / org.w3c — no longer in the wasm stdlib.
                implementation(libs.kotlinx.browser)
            }
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
            // ZXing lives HERE ONLY, and only as a DECODER. `widgets/qr/QrEncoder.kt` is a
            // hand-written encoder (ZXing core is a JVM-only jar, so it stopped resolving when
            // this module gained iOS targets, and this phase adds no libraries) — the one honest
            // proof that it is spec-correct is a round trip through an INDEPENDENT reader, which
            // is what QrEncoderTest / QrCodeTest / DevicesSettingsScreenTest do. Nothing in
            // commonMain, and therefore nothing shipped, references it.
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
