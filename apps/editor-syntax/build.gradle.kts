import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// editor-syntax: the native editor's syntax layer. tree-sitter v0.25.10 behind an owned `ses_*` C ABI
// (native/include/supermux_syntax.h), every grammar's CODE compiled into one native library per
// platform and its parse TABLES moved into compressed blobs (native/README.md). JNI on Android and
// the desktop JVM (jvmAndAndroidMain), cinterop on iOS (src/nativeInterop/cinterop/syntax.def).
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
    // ios) and inside it `jvmAndAndroid` (the shared JNI binding). commonMain holds only the
    // platform-free types, so a web backend (M2b) can sit beside nativeBacked.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("nativeBacked") {
                group("jvmAndAndroid") {
                    withJvm()
                    withAndroidTarget()
                }
                group("ios") { withIos() }
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

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":editor-core")) // the rope tests feed tree-sitter from a Rope
        }
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

// ---------------------------------------------------------------- native packaging ----------

// Desktop JVM: /dev/supermux/editor/syntax/natives/<target>/{<lib>, native.properties} in the jar,
// read by SesNativeLoader (sha256 + size checked before extraction and loading).
val stageJvmNativeResources by tasks.registering {
    description = "Stage the verified desktop JNI libraries of every locally built target as jvmMain resources."
    val root = nativeBuildDir
    val targets = jvmNativeTargets
    val outDir = layout.buildDirectory.dir("generated/jvmNativeResources")
    inputs.files(targets.map { (t, lib) -> File(root, "$t/lib/$lib") } + File(root, "manifest.json"))
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
            logger.warn("editor-syntax: no desktop JNI library for ${missing.joinToString()} (build with native/build.sh <target>)")
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
        if (missing.isNotEmpty()) {
            logger.warn("editor-syntax: no Android JNI library for ${missing.joinToString()} (build with native/build.sh <target>)")
        }
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
}
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(stageAndroidJniLibs, StageAndroidJniLibs::outputDir)
    }
}

// ---------------------------------------------------------------- tests ---------------------

// jvmTest loads the Mac's freshly built library directly; jvmResourceLoadTest (below) proves the
// packaged-resource path instead.
val jvmTest = tasks.named<Test>("jvmTest") {
    systemProperty("editor.syntax.lib", File(nativeBuildDir, "macos-arm64/lib/libsupermux_syntax_jni.dylib").absolutePath)
    filter { excludeTestsMatching("dev.supermux.editor.syntax.DesktopLoadTest") }
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
