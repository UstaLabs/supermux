// Consumes dev.supermux.terminal:terminal-core:0.1.0-dev.1 the way a third-party app would: a
// plain Kotlin Multiplatform build with ONE dependency, no supermux source on the classpath, and
// no Zig, no Android NDK, no C compiler anywhere near it. The native engine must come out of the
// PUBLISHED artifacts (the jar's resources / the klib's resources) or these tests fail.
//
//   apps/gradlew -p apps/terminal-core/consumer-smoke jvmTest              # runs here (Linux)
//   apps/gradlew -p apps/terminal-core/consumer-smoke wasmJsBrowserTest    # needs a WasmGC Chrome
//   apps/gradlew -p apps/terminal-core/consumer-smoke iosSimulatorArm64Test  # Mac only
// See README.md for what each check proves and which ones this host can run.

plugins {
    kotlin("multiplatform") version "2.4.10"
}

group = "dev.supermux.terminal.consumersmoke"
version = "0.0.0-local"

val terminalCoreVersion = "0.1.0-dev.1"

// The same three repositories settings.gradle.kts declares, repeated here because the Kotlin/Wasm
// plugin adds project repositories of its own (Node.js + Yarn distributions) and project
// repositories then take precedence over the settings ones. The content filters are what
// guarantees the package under test can come from the local test repository and from nowhere else.
repositories {
    maven {
        name = "terminalCoreLocalTest"
        url = uri(
            (providers.gradleProperty("terminalCoreRepo").orNull?.let { File(it) }
                ?: rootDir.resolve("../build/test-repository")).absoluteFile,
        )
        content { includeGroup("dev.supermux.terminal") }
    }
    mavenCentral { content { excludeGroup("dev.supermux.terminal") } }
    google { content { excludeGroup("dev.supermux.terminal") } }
}

kotlin {
    jvmToolchain(17)

    jvm()
    // Apple targets: configured here so a Mac can run the iOS consumer check (cinterop + the static
    // archive come from the published klib). On Linux their tasks are disabled.
    iosArm64()
    iosSimulatorArm64()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { useKarma { useChromeHeadless() } }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // THE dependency under test. The content filters above (and the identical ones in
            // settings.gradle.kts) let it come from the local test repository and from nowhere
            // else — never from a project, never from a remote.
            implementation("dev.supermux.terminal:terminal-core:$terminalCoreVersion")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        wasmJsTest { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
    }
}

// ---- Browser consumer: stage the engine assets out of the PUBLISHED klib -------------------
//
// Measured here (2026-09-22): the Kotlin/Wasm toolchain does NOT copy a DEPENDENCY klib's
// resources next to the consumer's compiled module, so webpack fails with
//   Module not found: Error: Can't resolve './terminal-loader.mjs'
// A host app therefore has to re-export the two files terminal-core ships in its klib
// (`terminal-loader.mjs` + `supermux-terminal.wasm`) as its OWN wasmJs resources. That is what the
// block below does, and it is the documented consumer recipe (terminal-core/native/README.md,
// "Browser (wasmJs)"). It still proves packaging: the bytes come out of the published klib, never
// out of terminal-core/build/wasm.
val terminalCoreWasmKlib: Configuration = configurations.create("terminalCoreWasmKlib") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    terminalCoreWasmKlib("dev.supermux.terminal:terminal-core-wasm-js:$terminalCoreVersion@klib")
}
val extractTerminalWasmAssets = tasks.register<Copy>("extractTerminalWasmAssets") {
    description = "Unpack terminal-loader.mjs + supermux-terminal.wasm from the published klib."
    from(terminalCoreWasmKlib.map { zipTree(it) }) {
        include("terminal-loader.mjs", "supermux-terminal.wasm")
    }
    into(layout.buildDirectory.dir("generated/terminalWasmAssets"))
    doLast {
        val staged = destinationDir.listFiles().orEmpty().map { "${it.name} (${it.length()} bytes)" }.sorted()
        logger.lifecycle("consumer-smoke: staged from ${terminalCoreWasmKlib.singleFile.name}: $staged")
    }
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(extractTerminalWasmAssets)

// ---- Android consumer: the AAR, resolved from the same published coordinates ----------------
//
// PACKAGING ONLY. Loading the library needs a device or emulator (no adb device here, Plan 3), so
// this build resolves the published AAR and checks what is inside it; nothing Android ever runs.
val terminalCoreAar: Configuration = configurations.create("terminalCoreAar") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    terminalCoreAar("dev.supermux.terminal:terminal-core-android:$terminalCoreVersion@aar")
}

// The JVM consumer check asserts WHERE the library it loaded came from, so point the extraction
// cache at a directory of this build: the loaded path must be inside it (and nowhere near
// terminal-core's build tree).
val jvmNativeCache = layout.buildDirectory.dir("terminal-native-cache")
tasks.named<Test>("jvmTest") {
    val cache = jvmNativeCache.get().asFile
    val aar = terminalCoreAar
    inputs.files(aar)
    doFirst { cache.mkdirs() }
    // NOTE what is deliberately NOT set here: -Dsupermux.terminal.nativeLibrary, the developer
    // override terminal-core's own jvmTest uses to load a library from a path. The packaged one is
    // the whole point, and the test asserts the override is unset.
    systemProperty("supermux.terminal.cacheDir", cache.absolutePath)
    systemProperty("consumerSmoke.expectedVersion", terminalCoreVersion)
    systemProperty("consumerSmoke.nativeCache", cache.absolutePath)
    doFirst { systemProperty("consumerSmoke.androidAar", aar.singleFile.absolutePath) }
    testLogging { showStandardStreams = true }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (System.getenv("CHROME_BIN") == null && File("/usr/bin/google-chrome").canExecute()) {
        environment("CHROME_BIN", "/usr/bin/google-chrome")
    }
    // Same host gotcha as terminal-core: with a D-Bus session bus headless Chrome stalls every
    // http(s) navigation here, which hangs Karma's capture.
    environment("DBUS_SESSION_BUS_ADDRESS", "disabled:")
}
