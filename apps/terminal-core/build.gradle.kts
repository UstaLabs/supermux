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
// cinterop (src/nativeInterop/cinterop/terminal.def) on iOS. The browser (wasm) still throws
// TerminalEngineUnavailableException(NOT_LINKED) until its loader lands.
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
    // Browser only; commonTest is compiled for wasm but executed on the JVM.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
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
        if (problems.isNotEmpty()) {
            throw GradleException(
                "terminal-core native artifacts incomplete (run native/build.sh <target> on the right host):\n  " +
                    problems.joinToString("\n  "),
            )
        }
        logger.lifecycle("terminal-core: all ${jvm.size + android.size + ios.size} native targets present and verified")
    }
}
