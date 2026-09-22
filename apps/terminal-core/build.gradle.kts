import java.security.MessageDigest

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// terminal-core: the ONE shared terminal engine (upstream Ghostty's libghostty-vt behind an owned
// `st_*` C ABI) for Android, desktop JVM, iOS and the browser. Self-contained on purpose — it
// depends on nothing in :shared or :ui so it can later be published on its own
// (dev.supermux.terminal:terminal-core, MIT).
//
// Bindings: JNI (jvmAndAndroidMain + native/src/terminal_jni.c) on Android and the desktop JVM,
// cinterop (src/nativeInterop/cinterop/terminal.def) on iOS, and in the browser wasm/terminal-loader.mjs
// + build/wasm/supermux-terminal.wasm (both staged as wasmJsMain resources) behind @JsModule externals.
//
// Native artifacts are NOT built by Gradle: `native/build.sh <target>` stages them to
// build/native/<target>/ (with a manifest.json of sha256s). Gradle packages whatever targets exist
// locally — a missing target only logs a warning so local dev never needs every toolchain — and
// `:terminal-core:verifyNativeArtifacts` fails unless every release target is present and matches
// its manifest (publishing depends on it).
group = "dev.supermux.terminal"
version = "0.1.0-dev.1"

// `build/` is shared with the native scripts (native/build.sh, wasm/build.sh), which keep the
// pinned Ghostty checkout, Zig caches and staged libraries there. Gradle gets its own
// subdirectory so `clean` cannot wipe those (multi-minute) native caches.
layout.buildDirectory = layout.projectDirectory.dir("build/gradle")

val nativeBuildDir: File = layout.projectDirectory.dir("build/native").asFile
val nativeAbiVersion = 1
// Desktop JVM: build.sh target (== resource key <os>-<arch>) -> JNI library file name.
val jvmNativeTargets = linkedMapOf(
    "linux-x64" to "libsupermux_terminal_jni.so",
    "linux-arm64" to "libsupermux_terminal_jni.so",
    "macos-x64" to "libsupermux_terminal_jni.dylib",
    "macos-arm64" to "libsupermux_terminal_jni.dylib",
    "windows-x64" to "supermux_terminal_jni.dll",
)
// Android: build.sh target -> jniLibs ABI directory.
val androidNativeTargets = linkedMapOf("android-arm64" to "arm64-v8a", "android-x64" to "x86_64")
// iOS: build.sh target -> Kotlin target name (static archive linked through cinterop).
val iosNativeTargets = linkedMapOf("ios-arm64" to "iosArm64", "ios-simulator-arm64" to "iosSimulatorArm64")
// Browser: wasm/build.sh output (supermux-terminal.wasm + manifest.json) and the loader it ships with.
val wasmBuildDir: File = layout.projectDirectory.dir("build/wasm").asFile
val wasmModuleName = "supermux-terminal.wasm"
val wasmLoaderFile: File = layout.projectDirectory.file("wasm/terminal-loader.mjs").asFile

/**
 * `build/native/<target>/lib/<lib>` verified against `build/native/<target>/manifest.json`:
 * returns the file and its sha256, null if the target was not built here, and throws if the file
 * is stale (sha256/size differ from the manifest) or built for another ABI.
 */
fun verifiedNativeLib(root: File, target: String, lib: String): Pair<File, String>? {
    val file = File(root, "$target/lib/$lib")
    val manifestFile = File(root, "$target/manifest.json")
    if (!file.isFile || !manifestFile.isFile) return null
    @Suppress("UNCHECKED_CAST")
    val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
    val abi = (manifest["abi_version"] as Number?)?.toInt()
    if (abi != nativeAbiVersion) throw GradleException("$target/$lib: manifest abi_version $abi, expected $nativeAbiVersion")
    @Suppress("UNCHECKED_CAST")
    val entry = (manifest["files"] as List<Map<String, Any?>>).firstOrNull { it["path"] == "lib/$lib" }
        ?: throw GradleException("$target/manifest.json does not list lib/$lib: rerun native/build.sh $target")
    val bytes = file.readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (sha != entry["sha256"] || bytes.size.toLong() != (entry["size"] as Number).toLong()) {
        throw GradleException("$target/lib/$lib does not match $target/manifest.json (stale): rerun native/build.sh $target")
    }
    return file to sha
}

/**
 * `build/wasm/supermux-terminal.wasm` verified against `build/wasm/manifest.json` (ABI, sha256,
 * size); null if wasm/build.sh has not run here, throws if the module is stale.
 */
fun verifiedWasmModule(dir: File, name: String): File? {
    val file = File(dir, name)
    val manifestFile = File(dir, "manifest.json")
    if (!file.isFile || !manifestFile.isFile) return null
    @Suppress("UNCHECKED_CAST")
    val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
    val abi = (manifest["abi_version"] as Number?)?.toInt()
    if (abi != nativeAbiVersion) throw GradleException("wasm/$name: manifest abi_version $abi, expected $nativeAbiVersion")
    @Suppress("UNCHECKED_CAST")
    val entry = (manifest["files"] as List<Map<String, Any?>>).firstOrNull { it["path"] == name }
        ?: throw GradleException("build/wasm/manifest.json does not list $name: rerun wasm/build.sh")
    val bytes = file.readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (sha != entry["sha256"] || bytes.size.toLong() != (entry["size"] as Number).toLong()) {
        throw GradleException("build/wasm/$name does not match build/wasm/manifest.json (stale): rerun wasm/build.sh")
    }
    return file
}

kotlin {
    jvmToolchain(17)

    // Default hierarchy plus `jvmAndAndroid` (the shared JNI binding: NativeTerminal +
    // NativeTerminalEngine), which both the jvm and android source sets depend on.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    jvm()
    androidTarget()
    // Apple targets: their compile/link/cinterop tasks are disabled on this Linux host
    // (kotlin.native.ignoreDisabledTargets) and run on the Mac, against the static
    // libsupermux_terminal.a that `native/build.sh ios-*` builds there.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val nativeTarget = iosNativeTargets.entries.first { it.value == target.name }.key
        target.compilations.getByName("main").cinterops.create("terminal") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/terminal.def"))
            includeDirs(project.file("native/include"))
            extraOpts("-libraryPath", File(nativeBuildDir, "$nativeTarget/lib").absolutePath)
        }
    }
    // Browser only. `:terminal-core:wasmJsBrowserTest` runs commonTest (EngineContractTest, codec,
    // types) + wasmJsTest in headless Chrome through Karma against the real wasm module; it needs
    // wasm/build.sh to have run and CHROME_BIN pointing at a WasmGC-capable Chrome (this host:
    // /usr/bin/google-chrome, used by default when present). karma.config.d/terminal-wasm.js serves
    // the staged module and the loader to the test page.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain {
            // js("…"), external declarations and JsAny are behind this opt-in.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
        wasmJsTest {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (System.getenv("CHROME_BIN") == null && File("/usr/bin/google-chrome").canExecute()) {
        environment("CHROME_BIN", "/usr/bin/google-chrome")
    }
    // With a D-Bus session bus, headless Chrome can stall every http(s) navigation on a headless
    // host (file: URLs still load), which hangs Karma's capture; Chrome needs no bus here.
    environment("DBUS_SESSION_BUS_ADDRESS", "disabled:")
}

android {
    namespace = "dev.supermux.terminal"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// ---------------------------------------------------------------- native packaging ----------

// Desktop JVM: /dev/supermux/terminal/native/<target>/{<lib>, native.properties} in the jar,
// read by JvmNativeLoader (sha256 + size + ABI checked before extraction and loading).
val stageJvmNativeResources by tasks.registering {
    description = "Stage the verified desktop JNI libraries of every locally built target as jvmMain resources."
    val root = nativeBuildDir
    val targets = jvmNativeTargets
    val pkgVersion = version.toString()
    val abiVersion = nativeAbiVersion
    val outDir = layout.buildDirectory.dir("generated/jvmNativeResources")
    inputs.files(targets.flatMap { (t, lib) -> listOf(File(root, "$t/lib/$lib"), File(root, "$t/manifest.json")) })
    inputs.property("version", pkgVersion)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val missing = mutableListOf<String>()
        for ((target, lib) in targets) {
            val found = verifiedNativeLib(root, target, lib)
            if (found == null) { missing += target; continue }
            val (file, sha) = found
            val dir = File(out, "dev/supermux/terminal/native/$target").apply { mkdirs() }
            file.copyTo(File(dir, lib), overwrite = true)
            @Suppress("UNCHECKED_CAST")
            val manifest = groovy.json.JsonSlurper().parse(File(root, "$target/manifest.json")) as Map<String, Any?>
            File(dir, "native.properties").writeText(
                buildString {
                    appendLine("# generated by :terminal-core:stageJvmNativeResources from build/native/$target/manifest.json")
                    appendLine("target=$target")
                    appendLine("library=$lib")
                    appendLine("sha256=$sha")
                    appendLine("size=${file.length()}")
                    appendLine("abi=$abiVersion")
                    appendLine("version=$pkgVersion")
                    appendLine("ghostty_commit=${manifest["ghostty_commit"]}")
                    appendLine("runtime_tested=${manifest["runtime_tested"]}")
                },
            )
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                "terminal-core: no desktop JNI library for ${missing.joinToString()} — the jar will not run there " +
                    "(build with native/build.sh <target>; verifyNativeArtifacts fails for releases)",
            )
        }
    }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(stageJvmNativeResources)

// jvmTest loads the -DST_JNI_TEST_HOOKS build of the host's JNI library (made only by
// `native/build.sh <host> --test`, never packaged) so JniBindingTest can use its counters and
// failure injection; without it those tests are skipped and everything else runs against the
// packaged release library.
val hostNativeTarget: String? = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val o = when {
        os.startsWith("linux") -> "linux"
        os.startsWith("mac") -> "macos"
        os.startsWith("windows") -> "windows"
        else -> null
    }
    val a = when (arch) { "amd64", "x86_64" -> "x64"; "aarch64", "arm64" -> "arm64"; else -> null }
    if (o != null && a != null) "$o-$a" else null
}
tasks.named<Test>("jvmTest") {
    val lib = hostNativeTarget?.let { t ->
        jvmNativeTargets[t]?.let { name ->
            val dot = name.lastIndexOf('.')
            File(nativeBuildDir, "$t/test-lib/${name.substring(0, dot)}_test${name.substring(dot)}")
        }
    }
    if (lib != null) inputs.files(lib).optional().withPropertyName("jniTestHookLibrary")
    doFirst {
        if (lib != null && lib.isFile) {
            systemProperty("supermux.terminal.nativeLibrary", lib.absolutePath)
        } else {
            logger.warn("terminal-core: no JNI test-hook library ($lib); JniBindingTest is skipped (run native/build.sh $hostNativeTarget --test)")
        }
    }
}

// Android host unit tests would run commonTest (EngineContractTest) on the desktop JVM through the
// Android actual, whose System.loadLibrary can only find the .so inside an APK: they cannot pass
// there. The engine is covered by jvmTest (same JNI binding) and, later, device tests.
tasks.matching { it.name.matches(Regex("test(Debug|Release)UnitTest")) }.configureEach { enabled = false }

// Browser: supermux-terminal.wasm + terminal-loader.mjs at the root of the wasmJs resources. Kotlin/Wasm
// copies them next to the compiled module, where the loader's `@JsModule("./terminal-loader.mjs")`
// import and its default `new URL("./supermux-terminal.wasm", import.meta.url)` resolve; bundlers
// (webpack, vite) emit the wasm as a content-hashed asset from that URL. README: "Browser (wasmJs)".
val stageWasmResources by tasks.registering {
    description = "Stage the verified supermux-terminal.wasm and wasm/terminal-loader.mjs as wasmJsMain resources."
    val dir = wasmBuildDir
    val name = wasmModuleName
    val loader = wasmLoaderFile
    val outDir = layout.buildDirectory.dir("generated/wasmResources")
    inputs.files(File(dir, name), File(dir, "manifest.json"), loader)
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        loader.copyTo(File(out, loader.name), overwrite = true)
        val module = verifiedWasmModule(dir, name)
        if (module == null) {
            logger.warn(
                "terminal-core: no $name — the wasmJs artifact cannot start an engine " +
                    "(build with wasm/build.sh; verifyNativeArtifacts fails for releases)",
            )
        } else {
            module.copyTo(File(out, name), overwrite = true)
        }
    }
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageWasmResources)

// Android: jniLibs/<abi>/libsupermux_terminal_jni.so, added to every variant as a generated
// jniLibs directory (AGP variant API; the legacy sourceSets DSL is unusable under AGP 9 + KMP).
abstract class StageAndroidJniLibs : DefaultTask() {
    @get:Internal abstract val nativeRoot: DirectoryProperty
    @get:Input abstract val abis: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val verifier: Property<(File, String, String) -> Pair<File, String>?>

    @TaskAction fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val missing = mutableListOf<String>()
        for ((target, abi) in abis.get()) {
            val found = verifier.get()(nativeRoot.get().asFile, target, "libsupermux_terminal_jni.so")
            if (found == null) { missing += target; continue }
            found.first.copyTo(File(out, "$abi/libsupermux_terminal_jni.so"), overwrite = true)
        }
        if (missing.isNotEmpty()) {
            logger.warn(
                "terminal-core: no Android JNI library for ${missing.joinToString()} — the AAR lacks those ABIs " +
                    "(build with native/build.sh <target>; verifyNativeArtifacts fails for releases)",
            )
        }
    }
}

val stageAndroidJniLibs by tasks.registering(StageAndroidJniLibs::class) {
    description = "Stage the verified Android JNI libraries of every locally built ABI as jniLibs."
    nativeRoot.set(layout.projectDirectory.dir("build/native"))
    abis.set(androidNativeTargets)
    nativeInputs.from(androidNativeTargets.keys.flatMap { t ->
        listOf(File(nativeBuildDir, "$t/lib/libsupermux_terminal_jni.so"), File(nativeBuildDir, "$t/manifest.json"))
    })
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    verifier.set { root, target, lib -> verifiedNativeLib(root, target, lib) }
}
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageAndroidJniLibs, StageAndroidJniLibs::outputDir)
    }
}

// Release gate: every target present and matching its manifest. Local builds skip missing ones.
tasks.register("verifyNativeArtifacts") {
    group = "verification"
    description = "Fail unless every release target's native library is built and matches its manifest."
    val root = nativeBuildDir
    val jvm = jvmNativeTargets
    val android = androidNativeTargets
    val ios = iosNativeTargets
    val wasmDir = wasmBuildDir
    val wasmName = wasmModuleName
    doLast {
        val problems = mutableListOf<String>()
        fun check(target: String, lib: String) {
            try {
                if (verifiedNativeLib(root, target, lib) == null) problems += "$target: lib/$lib or manifest.json missing"
            } catch (e: GradleException) {
                problems += e.message.orEmpty()
            }
        }
        jvm.forEach { (t, lib) -> check(t, lib) }
        android.keys.forEach { t -> check(t, "libsupermux_terminal_jni.so") }
        ios.keys.forEach { t -> check(t, "libsupermux_terminal.a") }
        try {
            if (verifiedWasmModule(wasmDir, wasmName) == null) problems += "wasm32: build/wasm/$wasmName or manifest.json missing"
        } catch (e: GradleException) {
            problems += e.message.orEmpty()
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "terminal-core native artifacts incomplete (run native/build.sh <target> on the right host):\n  " +
                    problems.joinToString("\n  "),
            )
        }
        logger.lifecycle("terminal-core: all ${jvm.size + android.size + ios.size + 1} native targets (incl. wasm32) present and verified")
    }
}
