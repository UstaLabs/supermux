import java.security.MessageDigest
import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-compose: each platform's own web engine inside Compose (Android WebView, WKWebView, WebView2,
// WPE, an iframe on the web), with hundreds of logical web views pooled onto a few live engines.
// Self-contained like :terminal-compose: Compose and kotlinx only, NOTHING from :shared or :ui, so it can
// be published and reused on its own.
group = "dev.supermux.webview"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    // Every declaration apps can reach says so: `public`, with its type written out. What isn't for apps is `internal`.
    explicitApi()
    jvm()
    androidTarget { publishLibraryVariants("release") }
    // Apple targets: their compile/link tasks are disabled on Linux (kotlin.native.ignoreDisabledTargets)
    // and run on the Mac.
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // Runs commonTest and wasmJsTest (the bridge shim, executed by a real browser) in headless Chrome.
            testTask { useKarma { useChromeHeadless() } }
        }
        // Required by the Compose plugin's wasm test check (CMP-4906); this module ships no app of its own.
        binaries.executable()
    }
    applyDefaultHierarchyTemplate()

    // The public API, as compiled for every target, is checked against the dump in api/: `check` fails on any change to
    // it that the dump doesn't show too. After a deliberate change, `webview-mac-gradle :webview-compose:updateKotlinAbi`
    // writes the dump again (on a Mac: the iOS targets build there only), and its diff is reviewed with the code's.
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            implementation(compose.foundation)
            api(compose.ui)
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        // Dispatchers.Main on desktop: the default runtime's scope, and with it every web view, runs there.
        jvmMain.dependencies { implementation(libs.coroutines.swing) }
        // The Android engine (spec §7). androidx only, nothing of supermux.
        androidMain.dependencies {
            implementation(libs.androidx.webkit)
            implementation(libs.androidx.core.core)
            // BackHandler, while the page has focus and can go back.
            implementation(libs.androidx.activity.compose)
        }
        // Run on the user's phone by device-checks/android-instrumented.sh: the real WebView behind the engine.
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.test.runner)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.test.ext.junit)
                implementation(kotlin("test-junit"))
                implementation(libs.androidx.activity.compose)
            }
        }
        commonTest {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
            }
        }
        // The compiler takes opt-ins only from a compilation's own source set (jvmTest, wasmJsTest, …), never from the
        // commonTest they depend on. Without this the commonTest opt-in above does nothing: every test source set
        // names it too.
        matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
        // The engine SPI (WebEngineSpi): the library's own engines implement it, and its runtime and tests drive it, so
        // every source set opts in, for the same reason as above. Apps don't: it isn't stable.
        configureEach {
            languageSettings.optIn("dev.supermux.webview.engine.WebEngineSpi")
        }
        // The iOS engine is Objective-C interop throughout: pointers, structs and NSObject subclasses. Every iOS source
        // set names the opt-ins, for the same reason as above.
        matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlinx.cinterop.BetaInteropApi")
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies { implementation(libs.kotlinx.browser) }
        }
        wasmJsTest {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
}

// runTest's own timeout can't interrupt a wait inside a non-cancellable section, so a hung test fails the task instead
// of holding the build.
tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
    timeout.set(Duration.ofMinutes(10))
}

// The iOS tests run in a simulator on the Mac: "sm-iPhone" (iOS 26.5) unless -Pwebview.simulator names another, such
// as "iPhone 14" (iOS 16.4). A Kotlin/Native test process isn't an app, so no WKWebView loads a page in it: these tests
// cover what needs none, and the sample app runs the engine's conformance checks (device-checks/ios-engine-checks.sh).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    device.set(providers.gradleProperty("webview.simulator").orElse("sm-iPhone"))
}

// The web engine's feasibility suites (classes named *SpikeTest) measure for minutes, so Karma skips them unless asked:
//   webview-mac-gradle :webview-compose:wasmJsBrowserTest -Pwebview.spike=true --tests 'dev.supermux.webview.engine.web.spike.*'
// The environment is an input of the test task, so switching it reruns the task.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (providers.gradleProperty("webview.spike").orNull == "true") environment("WEBVIEW_SPIKE", "1")
}

// The macOS engine's glue (native/macos, plan Task 6): built by build.sh on a Mac, then staged as jvmMain resources
// under dev/supermux/webview/native/macos/ with a native.properties (library, abi, version) that MacNativeLoader reads.
// Elsewhere nothing is built or staged, and the macOS engine reports Unavailable. build.sh signs it ad hoc, never with
// a team identity.
val isMacHost = System.getProperty("os.name").startsWith("Mac")
val macGlueDir = layout.buildDirectory.dir("native/macos")
val macGlueAbi = Regex("#define WV_ABI_VERSION (\\d+)").find(file("native/macos/WVGlue.h").readText())?.groupValues?.get(1)
    ?: throw GradleException("native/macos/WVGlue.h defines no WV_ABI_VERSION")
val buildMacGlue by tasks.registering(Exec::class) {
    description = "Builds the macOS engine's Objective-C glue (native/macos/build.sh). Mac hosts only."
    onlyIf { isMacHost }
    inputs.dir("native/macos")
    inputs.file("src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacGlue.kt")
    outputs.dir(macGlueDir)
    commandLine("bash", file("native/macos/build.sh").absolutePath, macGlueDir.get().asFile.absolutePath)
}
val stageMacGlue by tasks.registering {
    description = "Stages the macOS glue and its native.properties as jvmMain resources."
    dependsOn(buildMacGlue)
    val library = macGlueDir.map { it.file("libwebview_macos.dylib").asFile }
    val outDir = layout.buildDirectory.dir("generated/macGlueResources")
    val libraryVersion = version.toString()
    val abi = macGlueAbi
    inputs.files(macGlueDir)
    inputs.property("version", libraryVersion)
    inputs.property("abi", abi)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val lib = library.get()
        if (!lib.isFile) {
            logger.warn("webview-compose: no macOS glue in this build (it builds on a Mac only): the macOS engine will be Unavailable")
            return@doLast
        }
        val dir = File(out, "dev/supermux/webview/native/macos").apply { mkdirs() }
        lib.copyTo(File(dir, lib.name), overwrite = true)
        File(dir, "native.properties").writeText(
            "# generated by :webview-compose:stageMacGlue\nlibrary=${lib.name}\nabi=$abi\nversion=$libraryVersion\n",
        )
    }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(stageMacGlue)

// The Windows engine's glue (native/windows): built on the VM by `native/windows/winvm.sh build`, which fetches the DLL
// to build/native/windows-<arch>/. Staged as jvmMain resources under dev/supermux/webview/native/windows-<arch>/ with a
// native.properties (library, abi, version, sha256) that WinNativeLoader checks. Without a fetched DLL nothing is
// staged, and the Windows engine reports Unavailable(NativeLibraryFailed). The ABI is read from native/windows/wv2.h
// when a DLL is staged, so a tree without the glue's sources still builds its jars.
val winGlueHeader = file("native/windows/wv2.h")
val stageWindowsGlue by tasks.registering {
    description = "Stages the Windows glue (fetched from the VM by winvm.sh build) and its native.properties as jvmMain resources."
    val fetched = layout.buildDirectory.dir("native")
    val outDir = layout.buildDirectory.dir("generated/winGlueResources")
    val libraryVersion = version.toString()
    val header = winGlueHeader
    inputs.files(fetched.map { it.asFileTree.matching { include("windows-*/webview_windows.dll") } })
    inputs.files(header)
    inputs.property("version", libraryVersion)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        var staged = 0
        for (arch in listOf("x64", "arm64")) {
            val dll = fetched.get().file("windows-$arch/webview_windows.dll").asFile
            if (!dll.isFile) continue
            val abi = header.takeIf { it.isFile }?.let { Regex("#define WV_ABI_VERSION (\\d+)").find(it.readText())?.groupValues?.get(1) }
                ?: throw GradleException("native/windows/wv2.h defines no WV_ABI_VERSION")
            val dir = File(out, "dev/supermux/webview/native/windows-$arch").apply { mkdirs() }
            dll.copyTo(File(dir, dll.name), overwrite = true)
            val sha = MessageDigest.getInstance("SHA-256").digest(dll.readBytes()).joinToString("") { "%02x".format(it) }
            File(dir, "native.properties").writeText(
                "# generated by :webview-compose:stageWindowsGlue\nlibrary=${dll.name}\nabi=$abi\nversion=$libraryVersion\nsha256=$sha\n",
            )
            staged++
        }
        if (staged == 0) logger.warn("webview-compose: no Windows glue in this build (winvm.sh build fetches it): the Windows engine will be Unavailable")
    }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(stageWindowsGlue)

android {
    namespace = "dev.supermux.webview"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The test APK is the app under test: it targets what apps target, so WebView behaves as it does for them
    // (prefers-color-scheme from isLightTheme needs targetSdk 33+; edge-to-edge is enforced from 35).
    testOptions { targetSdk = libs.versions.androidTargetSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
