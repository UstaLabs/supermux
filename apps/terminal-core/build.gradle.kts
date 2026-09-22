import java.security.MessageDigest

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
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
// `-Pterminal.version=` overrides it (a release build stamps the release number; the publish-guard
// check uses it to exercise the release path without editing this file).
version = providers.gradleProperty("terminal.version").orNull?.takeIf { it.isNotBlank() } ?: "0.1.0-dev.1"

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
    // publishLibraryVariants: the Maven publication carries the RELEASE aar only (the debug aar
    // holds the same natives and nothing consumes it).
    androidTarget { publishLibraryVariants("release") }
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
        commonMain.dependencies {
            // The ONE runtime dependency of this package: TerminalSession is a coroutine + channels
            // + StateFlow, and its StateFlow is part of the API every host consumes. Pinned to the
            // version the rest of the app already resolves (gradle/libs.versions.toml).
            api(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // NO kotlinx-coroutines-test: its 1.9.0 wasm-js klib does not link against the Kotlin
            // 2.4.10 stdlib (IR linker: "Key kotlin.text/substring ... is missing in the map"), and
            // coroutines is pinned app-wide. VirtualClock (commonTest) is the deterministic
            // scheduler + clock the session tests need, and it works on every target.
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

// ---------------------------------------------------------------- verification gates --------

/** Every native artifact the package can carry: target -> the library file that must exist. */
val allNativeTargets: Map<String, String> = buildMap {
    putAll(jvmNativeTargets)
    androidNativeTargets.keys.forEach { put(it, "libsupermux_terminal_jni.so") }
    iosNativeTargets.keys.forEach { put(it, "libsupermux_terminal.a") }
}

/** The host that must have built a target (which host can produce its library at all). */
val targetBuildHost: Map<String, String> = mapOf(
    "linux-x64" to "linux", "linux-arm64" to "linux", "windows-x64" to "linux",
    "android-arm64" to "linux", "android-x64" to "linux",
    // macOS dylibs are linked by ld64 and the iOS archives need Xcode's SDK: Mac only.
    "macos-x64" to "macos", "macos-arm64" to "macos",
    "ios-arm64" to "macos", "ios-simulator-arm64" to "macos",
)

val buildHostKind: String = System.getProperty("os.name").orEmpty().lowercase().let {
    when {
        it.startsWith("mac") || it.startsWith("darwin") -> "macos"
        it.startsWith("windows") -> "windows"
        else -> "linux"
    }
}

/** A version that is explicitly not final: only such a version may be published incomplete. */
val versionIsPrerelease: Boolean = Regex("-(dev|snapshot)", RegexOption.IGNORE_CASE)
    .containsMatchIn(version.toString())

/** `-Pterminal.allowIncompleteReleasePublish=true`: the loud, deliberate escape hatch (see below). */
val allowIncompleteReleasePublish: Boolean =
    providers.gradleProperty("terminal.allowIncompleteReleasePublish").orNull?.toBoolean() == true

/**
 * Publication profile. `release` demands **every** target — which no single machine can produce,
 * so a release jar is assembled from a Linux build tree plus the Mac-built `macos-*` dylibs and
 * `ios-*` archives copied into it (see VERIFICATION.md). `dev` (the default for a `-dev` /
 * `-SNAPSHOT` version) demands everything THIS host can build and lets the rest be absent; the
 * artifacts then carry an ABI manifest that names exactly which targets are inside.
 * Override: `-Pterminal.publishProfile=dev|release`.
 *
 * **A release NUMBER may not quietly get the lenient gate.** Asking for `dev` on a version without
 * a `-dev`/`-SNAPSHOT` qualifier fails the build, because it would mint real release coordinates
 * whose missing targets are only visible inside abi-manifest.json. The escape hatch
 * `-Pterminal.allowIncompleteReleasePublish=true` exists for a deliberate partial rebuild, and it
 * is not quiet: a banner goes into the build log, the POM gets
 * `<terminal.incompleteTargets>` + a description suffix, and the ABI manifest gets
 * `incomplete_release: true` with the missing targets — so the ARTIFACT identifies itself.
 */
val publishProfile: String = run {
    val requested = providers.gradleProperty("terminal.publishProfile").orNull?.lowercase()
    if (requested != null && requested != "dev" && requested != "release") {
        throw GradleException("terminal.publishProfile must be 'dev' or 'release', not '$requested'")
    }
    val profile = requested ?: if (versionIsPrerelease) "dev" else "release"
    if (profile == "dev" && !versionIsPrerelease && !allowIncompleteReleasePublish) {
        throw GradleException(
            "terminal-core: refusing the 'dev' publication profile for version '$version', which has no " +
                "-dev/-SNAPSHOT qualifier. The dev profile only requires the targets THIS host can build, so " +
                "it would publish release coordinates with missing native libraries.\n" +
                "  * publish a release: drop -Pterminal.publishProfile (or pass =release) and have every " +
                "target present (see verifyReleaseArtifacts)\n" +
                "  * publish a pre-release: use a -dev / -SNAPSHOT version (-Pterminal.version=...)\n" +
                "  * really want an incomplete build under this number: add " +
                "-Pterminal.allowIncompleteReleasePublish=true, which stamps the POM and the ABI manifest " +
                "so the artifact says so itself",
        )
    }
    profile
}

/** True when the escape hatch above is actually in use (release number + lenient gate). */
val incompleteReleasePublish: Boolean = publishProfile == "dev" && !versionIsPrerelease

/**
 * Targets with no staged library, by file existence only (no hashing) — enough to stamp the POM at
 * configuration time. The authoritative, hash-checked list is in the ABI manifest.
 */
fun absentTargets(): List<String> {
    val missing = allNativeTargets.filterNot { (t, lib) -> File(nativeBuildDir, "$t/lib/$lib").isFile }.keys.toMutableList()
    if (!File(wasmBuildDir, wasmModuleName).isFile) missing += "wasm32"
    return missing.sorted()
}

if (incompleteReleasePublish) {
    logger.warn(
        "\n" + "=".repeat(100) +
            "\nterminal-core: INCOMPLETE RELEASE PUBLISH. Version '$version' has no -dev/-SNAPSHOT qualifier, " +
            "but -Pterminal.allowIncompleteReleasePublish=true selected the host-only gate." +
            "\n  Targets with no native library in this build: ${absentTargets().joinToString().ifEmpty { "(none)" }}" +
            "\n  The POM and dev/supermux/terminal/abi-manifest.json are stamped accordingly." +
            "\n" + "=".repeat(100),
    )
}

/** Targets a publish of [profile] must contain; wasm32 is always required (it is host-independent). */
fun requiredTargets(profile: String): Map<String, String> =
    if (profile == "release") allNativeTargets
    else allNativeTargets.filterKeys { targetBuildHost[it] == buildHostKind }

fun registerVerifyTask(name: String, profile: String, extraDescription: String) =
    tasks.register(name) {
        group = "verification"
        description = extraDescription
        val root = nativeBuildDir
        val required = requiredTargets(profile)
        val optional = allNativeTargets - required.keys
        val wasmDir = wasmBuildDir
        val wasmName = wasmModuleName
        doLast {
            val problems = mutableListOf<String>()
            fun check(target: String, lib: String, mandatory: Boolean) {
                try {
                    if (verifiedNativeLib(root, target, lib) == null && mandatory) {
                        problems += "$target: lib/$lib or manifest.json missing"
                    }
                } catch (e: GradleException) {
                    // A stale/ABI-wrong artifact is a problem even when the target is optional.
                    problems += e.message.orEmpty()
                }
            }
            required.forEach { (t, lib) -> check(t, lib, mandatory = true) }
            optional.forEach { (t, lib) -> check(t, lib, mandatory = false) }
            try {
                if (verifiedWasmModule(wasmDir, wasmName) == null) problems += "wasm32: build/wasm/$wasmName or manifest.json missing"
            } catch (e: GradleException) {
                problems += e.message.orEmpty()
            }
            if (problems.isNotEmpty()) {
                throw GradleException(
                    "terminal-core native artifacts incomplete for the '$profile' profile " +
                        "(run native/build.sh <target> on the right host):\n  " + problems.joinToString("\n  "),
                )
            }
            logger.lifecycle(
                "terminal-core: '$profile' profile satisfied — ${required.size + 1} required targets " +
                    "(${required.keys.sorted().joinToString()}, wasm32) present and verified",
            )
        }
    }

// Release gate: every target present and matching its manifest. Local builds skip missing ones.
val verifyNativeArtifacts = registerVerifyTask(
    "verifyNativeArtifacts", "release",
    "Fail unless EVERY release target's native library is built and matches its manifest.",
)

// Dev gate: everything this host can build (plus wasm32); Mac-only targets may be absent, but any
// artifact that IS present must still match its manifest.
val verifyNativeArtifactsForHost = registerVerifyTask(
    "verifyNativeArtifactsForHost", "dev",
    "Fail unless every native library THIS host can build is present and matches its manifest.",
)

/**
 * THE entry point for release packaging (Plan 4 and any hand-assembled artifact tree must call it).
 *
 * Why it exists: the gates below hang off the `publish*` tasks, so anyone who runs `jvmJar` /
 * `bundleReleaseAar` / `assemble` and copies the outputs by hand gets NO verification at all —
 * plain `assemble` happily produces a jar with three of five desktop targets in it. This task
 * verifies the full release set FIRST and only then builds the artifacts, so "assemble the release"
 * and "prove the release is complete" are one command.
 */
val verifyReleaseArtifacts = tasks.register("verifyReleaseArtifacts") {
    group = "verification"
    description = "Release packaging entry point: verify ALL targets, then assemble the artifacts."
    dependsOn(verifyNativeArtifacts)
    dependsOn("assemble")
    val profile = publishProfile
    val pkgVersion = version.toString()
    doLast {
        // The artifacts just built were stamped with whatever profile is in effect; a release tree
        // must not be stamped `dev`.
        if (profile != "release") {
            throw GradleException(
                "terminal-core: verifyReleaseArtifacts built artifacts stamped with the '$profile' profile " +
                    "(version '$pkgVersion'). Release packaging must run with the release profile: use a " +
                    "release version number, or pass -Pterminal.publishProfile=release.",
            )
        }
        logger.lifecycle("terminal-core: release artifacts assembled from a fully verified native tree.")
    }
}

// ---------------------------------------------------------------- publishing ----------------

// The ABI manifest that ships inside the artifacts: which st_* ABI they speak, which Ghostty/Zig
// built them, and exactly which targets are inside THIS build. Derived only from the per-target
// manifest.json files (no timestamps or host paths), so it is reproducible.
val abiManifestFile = layout.buildDirectory.file("generated/packageMetadata/dev/supermux/terminal/abi-manifest.json")
val generateAbiManifest by tasks.registering {
    group = "documentation"
    description = "Generate the ABI/pin manifest packaged as dev/supermux/terminal/abi-manifest.json."
    val root = nativeBuildDir
    val wasmDir = wasmBuildDir
    val wasmName = wasmModuleName
    val targets = allNativeTargets
    val pkgVersion = project.version.toString()
    val pkgGroup = project.group.toString()
    val pkgName = project.name
    val abiVersion = nativeAbiVersion
    val profile = publishProfile
    val incompleteRelease = incompleteReleasePublish
    val required = requiredTargets(publishProfile).keys
    val out = abiManifestFile
    inputs.files(targets.flatMap { (t, lib) -> listOf(File(root, "$t/lib/$lib"), File(root, "$t/manifest.json")) })
    inputs.files(File(wasmDir, wasmName), File(wasmDir, "manifest.json"))
    inputs.property("version", pkgVersion)
    inputs.property("profile", profile)
    outputs.file(out)
    doLast {
        fun manifestOf(file: File): Map<String, Any?>? {
            if (!file.isFile) return null
            @Suppress("UNCHECKED_CAST")
            return groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        }
        val entries = mutableListOf<Map<String, Any?>>()
        val missing = mutableListOf<String>()
        for ((target, lib) in targets) {
            val manifest = manifestOf(File(root, "$target/manifest.json"))
            val verified = verifiedNativeLib(root, target, lib)
            if (manifest == null || verified == null) { missing += target; continue }
            entries += linkedMapOf(
                "target" to target,
                "library" to lib,
                "sha256" to verified.second,
                "size" to verified.first.length(),
                "zig_target" to manifest["zig_target"],
                "build_host" to manifest["build_host"],
                "runtime_tested" to manifest["runtime_tested"],
                "test_host" to manifest["test_host"],
            )
        }
        val wasmManifest = manifestOf(File(wasmDir, "manifest.json"))
        val wasmModule = verifiedWasmModule(wasmDir, wasmName)
        if (wasmManifest != null && wasmModule != null) {
            @Suppress("UNCHECKED_CAST")
            val file = (wasmManifest["files"] as List<Map<String, Any?>>).first { it["path"] == wasmName }
            entries += linkedMapOf(
                "target" to "wasm32",
                "library" to wasmName,
                "sha256" to file["sha256"],
                "size" to file["size"],
                "zig_target" to wasmManifest["zig_target"],
                "build_host" to wasmManifest["build_host"],
                "runtime_tested" to wasmManifest["runtime_tested"],
                "test_host" to wasmManifest["test_runtimes"],
            )
        } else {
            missing += "wasm32"
        }
        val reference = entries.firstOrNull()?.let { manifestOf(File(root, "${it["target"]}/manifest.json")) }
            ?: wasmManifest
        val doc = linkedMapOf(
            "schema" to 1,
            "package" to "$pkgGroup:$pkgName",
            "version" to pkgVersion,
            "abi_version" to abiVersion,
            "profile" to profile,
            "ghostty_commit" to reference?.get("ghostty_commit"),
            "libghostty_vt_version" to reference?.get("libghostty_vt_version"),
            "zig_version" to reference?.get("zig_version"),
            "required_targets" to (required.sorted() + "wasm32"),
            "targets" to entries.sortedBy { it["target"].toString() },
            // `complete` = every target this package can carry is inside this build.
            "complete" to missing.isEmpty(),
            "missing_targets" to missing.sorted(),
            // Only true when a release-numbered version was published with the host-only gate via
            // -Pterminal.allowIncompleteReleasePublish: the artifact says so about itself.
            "incomplete_release" to incompleteRelease,
            "incomplete_targets" to if (incompleteRelease) missing.sorted() else emptyList(),
            "licenses" to listOf("META-INF/dev.supermux.terminal/LICENSE", "META-INF/dev.supermux.terminal/THIRD-PARTY-NOTICES.md"),
        )
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(doc)) + "\n")
    }
}
// The manifest is a resource of the two targets whose artifacts a consumer can read it from at
// run time: the desktop jar and the wasm klib. (The AAR carries the licence files below but not
// the manifest — an Android app reads the natives it got through jniLibs, not through resources.)
val packageMetadataDir = layout.buildDirectory.dir("generated/packageMetadata")
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(packageMetadataDir).builtBy(generateAbiManifest))
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(files(packageMetadataDir).builtBy(generateAbiManifest))

val licenseFiles = files(layout.projectDirectory.file("LICENSE"), layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"))
// Every jar (jvm, sources, javadoc/empty) carries our MIT licence and the third-party notices for
// the Ghostty + deps object code inside the native libraries. Namespaced under META-INF so it can
// never collide with another dependency's META-INF/LICENSE.
tasks.withType<Jar>().configureEach {
    from(licenseFiles) { into("META-INF/dev.supermux.terminal") }
    // Reproducible archives: without these two, Gradle walks the input directories in whatever
    // order the file system hands back, so two publishes of the identical tree produce jars with
    // different sha256s (measured). The native libraries inside them are byte-identical across
    // rebuilds; the packaging must be too, or "does this artifact match that build?" is unanswerable.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
// The AAR is a zip: put the same two files at META-INF/ inside it (AGP does not merge library
// java-resources into the aar's classes.jar in a way we can rely on).
tasks.matching { it.name.startsWith("bundle") && it.name.endsWith("Aar") }.configureEach {
    if (this is Zip) {
        from(licenseFiles) { into("META-INF/dev.supermux.terminal") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

publishing {
    repositories {
        // LOCAL ONLY. Nothing here uploads anywhere: the one repository is a directory inside
        // build/ (ignored by git) that the consumer-smoke build resolves from.
        maven {
            name = "localTest"
            url = uri(layout.projectDirectory.dir("build/test-repository"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("supermux terminal-core")
            val baseDescription =
                "The shared supermux terminal engine: upstream Ghostty's libghostty-vt behind an owned " +
                    "st_* C ABI, with Kotlin Multiplatform bindings for Android, the desktop JVM, iOS and " +
                    "the browser. Ships prebuilt native libraries; see META-INF/dev.supermux.terminal/ " +
                    "THIRD-PARTY-NOTICES.md for the licences of the bundled object code."
            // Self-identifying artifacts: the profile is always stamped, and an incomplete release
            // build (the escape hatch) also names its missing targets in the POM itself, not only
            // inside abi-manifest.json.
            properties.put("terminal.publishProfile", publishProfile)
            properties.put("terminal.abiVersion", nativeAbiVersion.toString())
            if (incompleteReleasePublish) {
                val absent = absentTargets()
                properties.put("terminal.incompleteTargets", absent.joinToString(","))
                description.set(
                    "$baseDescription INCOMPLETE BUILD: this release-numbered artifact was published with " +
                        "-Pterminal.allowIncompleteReleasePublish and carries NO native library for " +
                        "${absent.joinToString()} — see dev/supermux/terminal/abi-manifest.json.",
                )
            } else {
                description.set(baseDescription)
            }
            url.set("https://github.com/UstaLabs/supermux")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/UstaLabs/supermux/blob/main/LICENSE")
                    distribution.set("repo")
                }
                license {
                    name.set("MIT License (bundled Ghostty / libghostty-vt)")
                    url.set("https://github.com/ghostty-org/ghostty/blob/main/LICENSE")
                    distribution.set("repo")
                    comments.set(
                        "The published artifacts contain compiled Ghostty code and its dependencies " +
                            "(uucode, Wuffs, simdutf, Google Highway, Zig compiler_rt). Full notices: " +
                            "META-INF/dev.supermux.terminal/THIRD-PARTY-NOTICES.md",
                    )
                }
            }
            developers {
                developer {
                    id.set("ahmethuseyindok")
                    name.set("Ahmet Hüseyin Dok")
                }
            }
            scm {
                url.set("https://github.com/UstaLabs/supermux")
                connection.set("scm:git:https://github.com/UstaLabs/supermux.git")
                developerConnection.set("scm:git:git@github.com:UstaLabs/supermux.git")
            }
        }
    }
}

// Publishing NEVER produces a package whose native artifacts are missing or stale: every publish
// task runs the gate of its profile first (release = all targets, dev = everything this host can
// build). The ABI manifest inside the artifacts then names exactly what is in them.
val publicationGate = if (publishProfile == "release") verifyNativeArtifacts else verifyNativeArtifactsForHost
tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(publicationGate) }
tasks.withType<GenerateModuleMetadata>().configureEach { dependsOn(publicationGate) }
