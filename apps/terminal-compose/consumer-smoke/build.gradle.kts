// Consumes dev.supermux.terminal:terminal-compose:0.1.0-dev.1 (which pulls terminal-core with it)
// the way a third-party Compose app would: a plain Kotlin Multiplatform + Compose Multiplatform
// build with ONE supermux dependency, no supermux source on the classpath, and no Zig, no Android
// NDK and no C compiler anywhere near it. The surface must come out of the PUBLISHED artifacts and
// the engine out of the published jar's resources, or these tests fail.
//
//   apps/gradlew -p apps/terminal-compose/consumer-smoke jvmTest
// See README.md for what each check proves.

plugins {
    kotlin("multiplatform") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

group = "dev.supermux.terminal.composeconsumersmoke"
version = "0.0.0-local"

val terminalVersion = "0.1.0-dev.1"

// The same repositories settings.gradle.kts declares, repeated here because the Kotlin and Compose
// plugins add project repositories of their own and project repositories then take precedence over
// the settings ones. The content filters are what guarantees the packages under test can come from
// the two local test repositories and from nowhere else.
repositories {
    maven {
        name = "terminalCoreLocalTest"
        url = uri(
            (providers.gradleProperty("terminalCoreRepo").orNull?.let { File(it) }
                ?: rootDir.resolve("../../terminal-core/build/test-repository")).absoluteFile,
        )
        content { includeGroup("dev.supermux.terminal") }
    }
    maven {
        name = "terminalComposeLocalTest"
        url = uri(
            (providers.gradleProperty("terminalComposeRepo").orNull?.let { File(it) }
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

    sourceSets {
        commonMain.dependencies {
            // THE dependency under test. `terminal-core` arrives transitively, as `api`, which is
            // itself part of what this build proves: a consumer names the surface and gets the
            // engine — it does not have to know the engine's coordinates.
            implementation("dev.supermux.terminal:terminal-compose:$terminalVersion")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

// The JVM check asserts WHERE the native library it loaded came from, so point the extraction cache
// at a directory of THIS build: the loaded path must be inside it, and nowhere near terminal-core's
// build tree.
val jvmNativeCache = layout.buildDirectory.dir("terminal-native-cache")
tasks.named<Test>("jvmTest") {
    val cache = jvmNativeCache.get().asFile
    doFirst { cache.mkdirs() }
    // NOTE what is deliberately NOT set: -Dsupermux.terminal.nativeLibrary, the developer override
    // that loads a library from a path. The packaged one is the whole point, and the test asserts
    // the override is unset.
    systemProperty("supermux.terminal.cacheDir", cache.absolutePath)
    systemProperty("consumerSmoke.expectedVersion", terminalVersion)
    systemProperty("consumerSmoke.nativeCache", cache.absolutePath)
    // Compose UI tests are single-threaded per scene; one fork keeps the gate green and terminating
    // on a loaded host.
    maxParallelForks = 1
    testLogging { showStandardStreams = true }
}
