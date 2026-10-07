import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// editor-syntax: the native editor's syntax layer. tree-sitter v0.25.10 behind an owned `ses_*` C ABI
// (native/include/supermux_syntax.h), every grammar's CODE compiled into one native library per
// platform and its parse TABLES moved into compressed blobs (native/README.md). JNI on Android and
// the desktop JVM (jvmAndAndroidMain), cinterop on iOS (src/nativeInterop/cinterop/syntax.def), and
// on the web the same C compiled to one wasm32 module behind a small JS loader (wasmJsMain).
// Everything is UTF-16 code units end to end: no offset conversion anywhere on the Kotlin side.
// Spec: docs/superpowers/specs/2026-09-25-native-editor-design.md §5.
//
// Native artifacts are NOT built by Gradle: `native/build.sh all` (on the Mac) writes them to
// build/natives/<target>/lib/ with build/natives/manifest.json (sha256 + size of each). Gradle
// packages whichever targets exist, checked against the manifest; a missing target only warns.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

// `build/` is shared with the native scripts (fetched sources, generated grammars, natives), so
// Gradle gets its own subdirectory and `clean` cannot wipe them.
layout.buildDirectory = layout.projectDirectory.dir("build/gradle")

val nativeBuildDir: File = layout.projectDirectory.dir("build/natives").asFile
// Desktop JVM: build.sh target (== resource key <os>-<arch>) -> JNI library file name.
val jvmNativeTargets = linkedMapOf(
    "linux-x64" to "libsupermux_syntax_jni.so",
    "linux-arm64" to "libsupermux_syntax_jni.so",
    "macos-x64" to "libsupermux_syntax_jni.dylib",
    "macos-arm64" to "libsupermux_syntax_jni.dylib",
    "windows-x64" to "supermux_syntax_jni.dll",
)
// Android: build.sh target -> jniLibs ABI directory.
val androidNativeTargets = linkedMapOf("android-arm64" to "arm64-v8a", "android-x64" to "x86_64")

/**
 * `build/natives/<target>/lib/<lib>` checked against build/natives/manifest.json: the file and its
 * sha256, null if the target was not built here; throws if the file is stale.
 */
fun verifiedNativeLib(root: File, target: String, lib: String): Pair<File, String>? {
    val file = File(root, "$target/lib/$lib")
    val manifestFile = File(root, "manifest.json")
    if (!file.isFile || !manifestFile.isFile) return null
    @Suppress("UNCHECKED_CAST")
    val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val entry = (manifest["libraries"] as List<Map<String, Any?>>).firstOrNull { it["target"] == target && it["file"] == lib }
        ?: throw GradleException("build/natives/manifest.json does not list $target/$lib: rerun native/build.sh $target")
    val bytes = file.readBytes()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    if (sha != entry["sha256"] || bytes.size.toLong() != (entry["size"] as Number).toLong()) {
        throw GradleException("$target/lib/$lib does not match build/natives/manifest.json (stale): rerun native/build.sh $target")
    }
    return file to sha
}

kotlin {
    jvmToolchain(17)

    // Default hierarchy plus `nativeBacked` (everything backed by the ses_* library: jvm, android,
    // ios, and since M2c the web, where the same C runs as wasm) and inside it `jvmAndAndroid` (the
    // shared JNI binding). commonMain holds only the platform-free types.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nativeBacked") {
                group("jvmAndAndroid") {
                    withJvm()
                    withAndroidTarget()
                }
                group("ios") { withIos() }
                withWasmJs()
            }
        }
    }

    jvm()
    // The instrumented (device) tests run the common test tree, so nativeBackedTest runs on Android.
    androidTarget {
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }
    // Apple targets: compiled on the Mac (kotlin.native.ignoreDisabledTargets elsewhere), against
    // the static libsupermux_syntax.a that `native/build.sh ios-*` builds there.
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, dir) ->
        val interop = target.compilations.getByName("main").cinterops.create("syntax") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/syntax.def"))
            includeDirs(project.file("native/include"))
            extraOpts("-libraryPath", File(nativeBuildDir, "$dir/lib").absolutePath)
        }
        // cinterop copies the archive into the klib but does not track it: a rebuilt .a must rerun it.
        tasks.named(interop.interopProcessingTaskName) { inputs.files(File(nativeBuildDir, "$dir/lib/libsupermux_syntax.a")) }
        target.binaries.all { linkerOpts("-lz") }
    }
    // An optimised test binary for the simulator's performance numbers (the default test binary is a
    // debug build): link with linkPerfReleaseTestIosSimulatorArm64, run it with `xcrun simctl spawn`.
    iosSimulatorArm64().binaries.test("perf", listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE))
    // The web: build/natives/wasm32/lib/supermux-syntax.wasm (native/wasm/build.sh) + syntax-loader.mjs.
    // wasmJsBrowserTest runs the nativeBacked tests in headless Chrome through Karma against the real
    // module (karma.config.d/syntax-wasm.js serves the goldens and tables); CHROME_BIN must point at
    // a WasmGC-capable Chrome (the Mac's Google Chrome is used when present).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { useKarma { useChromeHeadless() } }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":editor-core")) // the syntax plugin is an editor-core extension
            implementation(libs.coroutines.core) // the background syntax worker
        }
        commonTest.dependencies {
            // NO kotlinx-coroutines-test: its 1.9.0 wasm-js klib does not link against the Kotlin
            // 2.4.10 stdlib; the suspending tests use runSuspendTest (TestSupport.kt).
            implementation(kotlin("test"))
        }
        wasmJsMain { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        wasmJsTest { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "dev.supermux.editor.syntax"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// ---------------------------------------------------------------- bundled queries -----------

// The query files (src/commonMain/resources/queries/<lang>/<kind>.scm, written by
// tools/fetch-queries.py and committed) compiled into the library as Kotlin strings, so every
// platform, the web included, reads the same text without a resource lookup.
abstract class GenerateBundledQueries : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val queriesDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun generate() {
        val root = queriesDir.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val pkg = File(out, "dev/supermux/editor/syntax").apply { mkdirs() }
        val files = root.walkTopDown().filter { it.isFile && it.extension == "scm" }.sortedBy { it.relativeTo(root).path }.toList()
        fun lit(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '$' -> append("\\$")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        val sb = StringBuilder()
        sb.appendLine("// GENERATED by :editor-syntax:generateBundledQueries from src/commonMain/resources/queries. Do not edit.")
        sb.appendLine("package dev.supermux.editor.syntax").appendLine()
        sb.appendLine("internal object BundledQueries {")
        sb.appendLine("    /** Every bundled query, as \"<lang>/<kind>\". */")
        sb.appendLine("    val keys: List<String> = listOf(" + files.joinToString(", ") { lit(it.relativeTo(root).path.removeSuffix(".scm")) } + ")")
        sb.appendLine()
        sb.appendLine("    fun get(language: String, kind: QueryKind): String? = when (language + \"/\" + kind.file) {")
        files.forEachIndexed { i, f -> sb.appendLine("        ${lit(f.relativeTo(root).path.removeSuffix(".scm"))} -> Q$i.text") }
        sb.appendLine("        else -> null")
        sb.appendLine("    }")
        sb.appendLine("}")
        files.forEachIndexed { i, f ->
            // Chunks stay far below the JVM's 64 KB constant limit; joined at run time, never folded.
            val chunks = f.readText().chunked(8000)
            sb.appendLine().appendLine("private object Q$i {")
            sb.appendLine("    val text: String = arrayOf(")
            for (c in chunks) sb.appendLine("        ${lit(c)},")
            sb.appendLine("    ).joinToString(\"\")")
            sb.appendLine("}")
        }
        File(pkg, "BundledQueries.kt").writeText(sb.toString())
    }
}

val generateBundledQueries by tasks.registering(GenerateBundledQueries::class) {
    description = "Compile src/commonMain/resources/queries/**.scm into BundledQueries.kt."
    queriesDir.set(layout.projectDirectory.dir("src/commonMain/resources/queries"))
    outputDir.set(layout.buildDirectory.dir("generated/bundledQueries"))
}
kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(generateBundledQueries)
// The .scm files are compiled in: do not ship them twice as resources as well.
tasks.withType<ProcessResources>().configureEach { exclude("queries/**") }

// ---------------------------------------------------------------- native packaging ----------

// Desktop JVM: /dev/supermux/editor/syntax/natives/<target>/{<lib>, native.properties} in the jar,
// read by SesNativeLoader (sha256 + size checked before extraction and loading).
val stageJvmNativeResources by tasks.registering {
    description = "Stage the verified desktop JNI libraries of every locally built target as jvmMain resources."
    val root = nativeBuildDir
    val targets = jvmNativeTargets
    val outDir = layout.buildDirectory.dir("generated/jvmNativeResources")
    inputs.files(targets.map { (t, lib) -> File(root, "$t/lib/$lib") } + File(root, "manifest.json"))
    // The release gate is an input: a cached "natives missing, staged nothing" result must not
    // satisfy a later SUPERMUX_REQUIRE_EDITOR_SYNTAX=1 build.
    inputs.property("requireEditorSyntax", System.getenv("SUPERMUX_REQUIRE_EDITOR_SYNTAX") == "1")
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        val missing = mutableListOf<String>()
        for ((target, lib) in targets) {
            val found = verifiedNativeLib(root, target, lib)
            if (found == null) { missing += target; continue }
            val (file, sha) = found
            val dir = File(out, "dev/supermux/editor/syntax/natives/$target").apply { mkdirs() }
            file.copyTo(File(dir, lib), overwrite = true)
            File(dir, "native.properties").writeText(
                buildString {
                    appendLine("# generated by :editor-syntax:stageJvmNativeResources from build/natives/manifest.json")
                    appendLine("target=$target")
                    appendLine("library=$lib")
                    appendLine("sha256=$sha")
                    appendLine("size=${file.length()}")
                },
            )
        }
        if (missing.isNotEmpty()) {
            val message = "editor-syntax: no desktop JNI library for ${missing.joinToString()} (build with native/build.sh <target>)"
            // A shipped desktop app must not fall back to plain text on those OSes (release.yml sets it).
            if (System.getenv("SUPERMUX_REQUIRE_EDITOR_SYNTAX") == "1") throw GradleException("$message; required by SUPERMUX_REQUIRE_EDITOR_SYNTAX=1")
            logger.warn(message)
        }
    }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(stageJvmNativeResources)

// Android: jniLibs/<abi>/libsupermux_syntax_jni.so, added to every variant as a generated jniLibs
// directory (AGP variant API; the legacy sourceSets DSL is unusable under AGP 9 + KMP).
abstract class StageAndroidJniLibs : DefaultTask() {
    @get:Internal abstract val nativeRoot: DirectoryProperty
    @get:Input abstract val abis: MapProperty<String, String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val nativeInputs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Internal abstract val verifier: Property<(File, String, String) -> Pair<File, String>?>

    /** False only under `-Peditor.allowMissingAndroidNative=true`. See [stage]. */
    @get:Input abstract val required: Property<Boolean>

    @TaskAction fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val missing = mutableListOf<String>()
        for ((target, abi) in abis.get()) {
            val found = verifier.get()(nativeRoot.get().asFile, target, "libsupermux_syntax_jni.so")
            if (found == null) { missing += target; continue }
            found.first.copyTo(File(out, "$abi/libsupermux_syntax_jni.so"), overwrite = true)
        }
        if (missing.isEmpty()) return
        // FAIL, not warn (M5, terminal-core's rule): AGP merges an empty jniLibs directory without a
        // word, so an APK built now would install and run with every file as plain text on those
        // ABIs, and a warning in a long Gradle log is not a signal.
        val message =
            "editor-syntax: no Android JNI library for ${missing.joinToString()}.\n" +
                "  An APK packaged now would claim these ABIs and have no syntax highlighting in them.\n" +
                "  Build them:  ${missing.joinToString("\n               ") { "bash apps/editor-syntax/native/build.sh $it" }}\n" +
                "  Or, to deliberately produce an APK with plain-text editors on those ABIs, pass\n" +
                "  -Peditor.allowMissingAndroidNative=true."
        if (required.get()) throw GradleException(message)
        logger.warn("$message\n  (allowed by -Peditor.allowMissingAndroidNative)")
    }
}

val stageAndroidJniLibs by tasks.registering(StageAndroidJniLibs::class) {
    description = "Stage the verified Android JNI libraries of every locally built ABI as jniLibs."
    nativeRoot.set(layout.projectDirectory.dir("build/natives"))
    abis.set(androidNativeTargets)
    nativeInputs.from(androidNativeTargets.keys.map { t -> File(nativeBuildDir, "$t/lib/libsupermux_syntax_jni.so") })
    nativeInputs.from(File(nativeBuildDir, "manifest.json"))
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    verifier.set { root, target, lib -> verifiedNativeLib(root, target, lib) }
    required.set(providers.gradleProperty("editor.allowMissingAndroidNative").orNull?.toBoolean() != true)
}
// ---------------------------------------------------------------- tables resources ----------

// Every code-only grammar's tables blob, as resource editor-syntax/tables/<lang>.sesz: jvmMain
// resources (desktop jar) and Android Java resources (the AAR; read through the class loader, so
// no Context is needed). iOS reads the same directory from the app bundle: see native/README.md.
// Each blob is checked against build/natives/manifest.json's "tables" (sha256 + size); the native
// loader checks it again against the hash compiled into the grammar's code.
abstract class StageTables : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val blobs: ConfigurableFileCollection
    @get:Internal abstract val genDir: DirectoryProperty
    @get:Internal abstract val manifest: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dir = File(out, "editor-syntax/tables").apply { mkdirs() }
        val mf = manifest.get().asFile
        if (!mf.isFile) {
            logger.warn("editor-syntax: no build/natives/manifest.json (run native/build.sh all): no tables resources")
            return
        }
        @Suppress("UNCHECKED_CAST")
        val tables = (groovy.json.JsonSlurper().parse(mf) as Map<String, Any?>)["tables"] as List<Map<String, Any?>>?
            ?: throw GradleException("build/natives/manifest.json has no tables: rerun native/build.sh manifest")
        for (t in tables) {
            val lang = t["lang"] as String
            val src = File(genDir.get().asFile, "$lang/$lang.sesz")
            val bytes = src.readBytes()
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (sha != t["sha256"] || bytes.size.toLong() != (t["size"] as Number).toLong()) {
                throw GradleException("$src does not match build/natives/manifest.json (stale): rerun native/build.sh manifest")
            }
            File(dir, "$lang.sesz").writeBytes(bytes)
        }
    }
}

val stageTables by tasks.registering(StageTables::class) {
    description = "Stage every code-only grammar's verified tables blob as resource editor-syntax/tables/<lang>.sesz."
    genDir.set(layout.projectDirectory.dir("build/gen"))
    manifest.set(layout.projectDirectory.file("build/natives/manifest.json"))
    blobs.from(layout.projectDirectory.file("build/natives/manifest.json"))
    blobs.from(layout.projectDirectory.dir("build/gen").asFileTree.matching { include("*/*.sesz") })
    outputDir.set(layout.buildDirectory.dir("generated/tables"))
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(stageTables)

// ---------------------------------------------------------------- test tables ---------------

// A code-only grammar's tables, provided at run time by the tests: build/gen/fsharp/fsharp.sesz as
// test resource sesz/fsharp.sesz, plus sesz/fsharp-tampered.sesz, the same blob with one payload
// byte changed and recompressed (valid header, valid zlib), which the SHA-256 check must refuse.
abstract class StageTestTables : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val blob: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun stage() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val dir = File(out, "sesz").apply { mkdirs() }
        val src = blob.singleFile
        if (!src.isFile) {
            logger.warn("editor-syntax: no $src (run native/build.sh gen): the provided-tables tests will fail")
            return
        }
        val sesz = src.readBytes()
        File(dir, "fsharp.sesz").writeBytes(sesz)
        fun u32(at: Int) = (0..3).fold(0L) { v, i -> v or ((sesz[at + i].toLong() and 0xFF) shl (8 * i)) }
        val raw = ByteArray(u32(8).toInt())
        Inflater().run {
            setInput(sesz, 24, sesz.size - 24)
            check(inflate(raw) == raw.size) { "$src: short inflate" }
            end()
        }
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x5A).toByte()
        val z = ByteArrayOutputStream().also { o ->
            DeflaterOutputStream(o, Deflater(9)).use { it.write(raw) }
        }.toByteArray()
        val tampered = sesz.copyOfRange(0, 24) + z
        for (i in 0..3) tampered[12 + i] = (z.size ushr (8 * i)).toByte()
        File(dir, "fsharp-tampered.sesz").writeBytes(tampered)
    }
}

val stageTestTables by tasks.registering(StageTestTables::class) {
    blob.from(layout.projectDirectory.file("build/gen/fsharp/fsharp.sesz"))
    outputDir.set(layout.buildDirectory.dir("generated/testTables"))
}
kotlin.sourceSets.getByName("jvmTest").resources.srcDir(stageTestTables)

// iOS tests read the staged files by absolute path (the simulator sees the Mac's file system):
// the test resources, and the app's tables resources (standing in for the app bundle).
val iosTestResourcePath by tasks.registering {
    val dir = stageTestTables.flatMap { it.outputDir }
    val tablesDir = stageTables.flatMap { it.outputDir }
    val staticDir = layout.projectDirectory.dir("src/nativeBackedTest/resources")
    val out = layout.buildDirectory.dir("generated/iosTestKotlin")
    inputs.property("dir", dir.map { it.asFile.absolutePath })
    inputs.property("tablesDir", tablesDir.map { it.asFile.absolutePath })
    outputs.dir(out)
    doLast {
        val f = out.get().file("dev/supermux/editor/syntax/TestResourceDir.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package dev.supermux.editor.syntax\n\n// generated by :editor-syntax:iosTestResourcePath\n" +
                "internal const val TEST_RESOURCE_DIR = \"${dir.get().asFile.absolutePath}\"\n" +
                "internal const val TEST_STATIC_RESOURCE_DIR = \"${staticDir.asFile.absolutePath}\"\n" +
                "internal const val TEST_TABLES_RESOURCE_DIR = \"${tablesDir.get().asFile.absolutePath}\"\n",
        )
    }
}
kotlin.sourceSets.getByName("iosTest").kotlin.srcDir(iosTestResourcePath)
tasks.matching { it.name.startsWith("ios") && it.name.endsWith("Test") }.configureEach { dependsOn(stageTestTables, stageTables) }

// The iOS test executable's main bundle is its own directory: put the tables there, exactly where
// an app bundle has them (<resources>/editor-syntax/tables/), so the simulator tests load every
// code-only grammar through SyntaxResources' NSBundle lookup, as the app does (iosApp/project.yml
// copies the same directory into Supermux.app).
// One copy per simulator test executable (debugTest, and perfReleaseTest from binaries.test("perf")).
for (binary in listOf("debugTest", "perfReleaseTest")) {
    val copy = tasks.register("bundleTablesFor${binary.replaceFirstChar { it.uppercase() }}IosSimulatorArm64", Copy::class) {
        description = "Put editor-syntax/tables/ next to the $binary iOS simulator executable (its main bundle)."
        from(stageTables.flatMap { it.outputDir })
        into(layout.buildDirectory.dir("bin/iosSimulatorArm64/$binary"))
    }
    tasks.matching { it.name == "iosSimulatorArm64Test" || it.name == "link${binary.replaceFirstChar { it.uppercase() }}IosSimulatorArm64" }
        .configureEach { if (name.startsWith("link")) finalizedBy(copy) else dependsOn(copy) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageAndroidJniLibs, StageAndroidJniLibs::outputDir)
        variant.sources.resources?.addGeneratedSourceDirectory(stageTables, StageTables::outputDir)
        // The device tests read the same resources through the class loader.
        variant.deviceTests.values.forEach { test ->
            test.sources.resources?.addGeneratedSourceDirectory(stageTestTables, StageTestTables::outputDir)
            test.sources.resources?.addStaticSourceDirectory(file("src/nativeBackedTest/resources").absolutePath)
        }
    }
}

// ---------------------------------------------------------------- web (wasm) ----------------

// build/natives/wasm32/lib/supermux-syntax.wasm, checked against build/natives/manifest.json, as a
// wasmJsMain resource next to syntax-loader.mjs (src/wasmJsMain/resources): Kotlin/Wasm puts both
// beside the compiled module, where the loader's default `new URL("./supermux-syntax.wasm",
// import.meta.url)` resolves and a bundler emits it as an asset. The code-only grammars' tables
// are NOT packed into the klib: a host serves stageTables' editor-syntax/tables/ (native/README.md).
val stageWasmResources by tasks.registering {
    description = "Stage the verified supermux-syntax.wasm as a wasmJsMain resource."
    val root = nativeBuildDir
    val outDir = layout.buildDirectory.dir("generated/wasmResources")
    inputs.files(File(root, "wasm32/lib/supermux-syntax.wasm"), File(root, "manifest.json"))
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val found = verifiedNativeLib(root, "wasm32", "supermux-syntax.wasm")
        if (found == null) logger.warn("editor-syntax: no supermux-syntax.wasm (build with native/wasm/build.sh): the web backend cannot load")
        else found.first.copyTo(File(out, "supermux-syntax.wasm"), overwrite = true)
    }
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageWasmResources)

// The browser tests read their resources synchronously, like the JVM's class loader does: the
// test setup module (syntax-test-setup.mjs) fetches every file listed in syntax-test-resources.json
// before any test runs. They are the goldens (src/nativeBackedTest/resources), the test tables
// (sesz/) and the app's tables (editor-syntax/tables/), served by Karma under /base/kotlin/.
val stageWasmTestResources by tasks.registering {
    description = "Stage the browser tests' resources and their index."
    // nativeBackedTest's own resources reach wasmJsTest through the source-set hierarchy: indexed, not copied.
    val inherited = layout.projectDirectory.dir("src/nativeBackedTest/resources").asFile
    val sources = listOf(stageTestTables.get().outputDir.get().asFile, stageTables.get().outputDir.get().asFile)
    dependsOn(stageTestTables, stageTables)
    inputs.files(sources + inherited)
    val outDir = layout.buildDirectory.dir("generated/wasmTestResources")
    outputs.dir(outDir)
    doLast {
        val out = outDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val paths = sortedSetOf<String>()
        for (src in sources + inherited) {
            if (!src.isDirectory) continue
            src.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(src).invariantSeparatorsPath
                if (src != inherited) f.copyTo(File(out, rel), overwrite = true)
                paths += rel
            }
        }
        File(out, "syntax-test-resources.json").writeText(groovy.json.JsonOutput.toJson(paths.toList()))
    }
}
kotlin.sourceSets.getByName("wasmJsTest").resources.srcDir(stageWasmTestResources)

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    val mac = File("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
    if (System.getenv("CHROME_BIN") == null && mac.canExecute()) environment("CHROME_BIN", mac.absolutePath)
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// ---------------------------------------------------------------- tests ---------------------

// jvmTest loads the Mac's freshly built library directly; jvmResourceLoadTest (below) proves the
// packaged-resource path instead.
val jvmTest = tasks.named<Test>("jvmTest") {
    // One JVM per test class, so ConcurrentLoadTest really races the FIRST use of a grammar.
    forkEvery = 1
    systemProperty("editor.syntax.lib", File(nativeBuildDir, "macos-arm64/lib/libsupermux_syntax_jni.dylib").absolutePath)
    filter { excludeTestsMatching("dev.supermux.editor.syntax.DesktopLoadTest") }
    // -PupdateGolden: the golden tests (re)write src/nativeBackedTest/resources/golden/ instead of comparing.
    if (project.hasProperty("updateGolden")) {
        systemProperty("editor.syntax.updateGolden", file("src/nativeBackedTest/resources/golden").absolutePath)
        outputs.upToDateWhen { false }
    }
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
tasks.register<Test>("jvmResourceLoadTest") {
    group = "verification"
    description = "Load the desktop JNI library the way a consumer does: from the staged jar resources (no editor.syntax.lib)."
    testClassesDirs = jvmTest.get().testClassesDirs
    classpath = jvmTest.get().classpath
    useJUnit()
    filter { includeTestsMatching("dev.supermux.editor.syntax.DesktopLoadTest") }
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Android host unit tests would run on the desktop JVM through System.loadLibrary, which can only
// find the .so inside an APK; the binding is covered by jvmTest and connectedAndroidTest.
tasks.matching { it.name.matches(Regex("test(Debug|Release)UnitTest")) }.configureEach { enabled = false }
