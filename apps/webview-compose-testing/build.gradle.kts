import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-compose-testing: what apps, the sample and the library's own tests test web views with, kept out of
// :webview-compose so that its main artifact ships no test hooks:
// - the fake engine (FakeWebEngine, FakeWebEngineFactory, FakeEngineBehavior), on every target the library has;
// - MacEngineTesting (desktop JVM), which drives the real macOS engine in a real window for the sample's checks.
// Depend on it from tests: `testImplementation(project(":webview-compose-testing"))`, or in KMP
// `commonTest.dependencies { implementation(project(":webview-compose-testing")) }`.
group = "dev.supermux.webview"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    // Its API is published as the library's is: every public declaration says so, and the dump in api/ is checked.
    explicitApi()
    jvm()
    androidTarget { publishLibraryVariants("release") }
    // Apple targets: their compile/link tasks are disabled on Linux (kotlin.native.ignoreDisabledTargets) and run on the
    // Mac, as the library's.
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser() }
    applyDefaultHierarchyTemplate()

    // As the library's: `check` fails on a change to the public API that the dump in api/ doesn't show, and
    // `webview-mac-gradle :webview-compose-testing:updateKotlinAbi` writes it again.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    sourceSets {
        commonMain.dependencies {
            api(project(":webview-compose"))
            // The fake's surface: a Box and a Canvas.
            implementation(compose.foundation)
        }
        // The fake's own tests stay in webview-compose's suites, which check it against the library's internal wire
        // codec; MacEngineTesting's decoding of the glue's window copies is tested here.
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
        // The fake is an engine: it implements the engine SPI (WebEngineSpi). Its users opt in only to drive engines
        // themselves (emit an event, read a setup); FakeWebEngineFactory and the contract checks need nothing.
        configureEach {
            languageSettings.optIn("dev.supermux.webview.engine.WebEngineSpi")
        }
    }
}

// MacEngineTesting drives the macOS engine's internals: its glue's test exports (MacGlue.test*), whether the glue is
// loaded, and the motion cover's switch and count. So the JVM compilation, alone, sees webview-compose's internals, as a
// test compilation of that module would: its classes on this compilation's classpath are friends. The fake engine, in
// commonMain, is compiled for every other target (and as common metadata) without them: it uses the public API and the
// engine SPI only. IDEs don't know of the friendship and flag MacEngineTesting's internal calls; the build compiles them.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlinJvm") {
    val classpath = configurations.named("jvmCompileClasspath")
    friendPaths.from(
        classpath.map { configuration ->
            configuration.incoming.artifactView {
                componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":webview-compose" }
            }.files
        },
    )
}

android {
    namespace = "dev.supermux.webview.testing"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
