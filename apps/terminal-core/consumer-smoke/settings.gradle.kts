// A SEPARATE Gradle build on purpose: it is NOT part of apps/settings.gradle.kts, shares no
// classpath, no source and no project state with supermux, and owns nothing but a dependency on the
// published coordinates. If terminal-core can only be used from inside this repository, this build
// fails.
//
// Run it from anywhere with the apps wrapper (it only supplies the Gradle distribution):
//   apps/gradlew -p apps/terminal-core/consumer-smoke jvmTest
//
// It resolves `dev.supermux.terminal:*` from ONE place: the local test repository that
// `:terminal-core:publishAllPublicationsToLocalTestRepository` writes. Nothing is downloaded or
// uploaded for that group, and no other repository is allowed to answer for it, so a green run
// cannot silently have used a project dependency or a cached snapshot from elsewhere.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // NOTE: the mode stays the default (PREFER_PROJECT) on purpose. Kotlin/Wasm's browser test
    // toolchain adds its OWN project repositories (the Node.js and Yarn distributions), which
    // FAIL_ON_PROJECT_REPOS rejects outright and PREFER_SETTINGS ignores — either way
    // `wasmJsBrowserTest` cannot run. Exclusivity is enforced by content filters instead, declared
    // identically here and in build.gradle.kts: `dev.supermux.terminal` may come from the local
    // test repository and from nowhere else, and that repository may serve nothing else. The JVM
    // check additionally asserts the jar it actually loaded lives in build/test-repository.
    repositories {
        terminalCoreLocalTest(providers.gradleProperty("terminalCoreRepo").orNull, rootDir)
        mavenCentral { content { excludeGroup("dev.supermux.terminal") } }
        google { content { excludeGroup("dev.supermux.terminal") } }
    }
}

/** The one repository allowed to serve `dev.supermux.terminal`. `-PterminalCoreRepo=<dir>` moves it. */
fun RepositoryHandler.terminalCoreLocalTest(override: String?, rootDir: File) {
    maven {
        name = "terminalCoreLocalTest"
        url = uri((override?.let { File(it) } ?: File(rootDir, "../build/test-repository")).absoluteFile)
        content { includeGroup("dev.supermux.terminal") }
    }
}

rootProject.name = "terminal-core-consumer-smoke"
