plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// editor-plugins/lsp: a Language Server Protocol client on the public API, CM6's @codemirror/lsp-client
// in Kotlin: document sync, diagnostics (-> lint), completion (-> autocomplete), hover, signature help,
// definition, references, rename, formatting, code actions. It talks to an LspTransport (the host's:
// the broker's LSP channel in M5, a process's stdio on the desktop, an in-process fake in the sample).
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    // The common tests (sync, every feature, the 5,000-item parse timing) also run in headless Chrome.
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core"))
            api(project(":editor-compose"))
            api(project(":editor-plugins:autocomplete"))
            api(project(":editor-plugins:lint"))
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // Rename / format / a code action are one undo step each; a definition inside a fold opens it.
            implementation(project(":editor-plugins:history"))
            implementation(project(":editor-plugins:fold"))
            implementation(project(":editor-plugins:lsp-fake"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        jvmTest.dependencies {
            // The tooltips and panels in a composed Editor; a real server over stdio when the machine has one.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "dev.supermux.editor.plugins.lsp"
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
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    val mac = File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    if (System.getenv("CHROME_BIN") == null && mac.canExecute()) environment("CHROME_BIN", mac.absolutePath)
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// No Compose UI in the browser tests: the Compose plugin's check for a bundled Skiko runtime does not apply.
tasks.matching { it.name == "checkComposeUiTestConfigurationForWasmJs" }.configureEach { enabled = false }
