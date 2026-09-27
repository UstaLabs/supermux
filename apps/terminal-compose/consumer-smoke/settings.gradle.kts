// A SEPARATE Gradle build on purpose: it is NOT in apps/settings.gradle.kts, shares no classpath,
// no source and no project state with supermux, and owns nothing but a dependency on the published
// coordinates of the terminal PAIR. If the surface can only be used from inside this repository,
// this build fails.
//
// Run it from anywhere with the apps wrapper (it only supplies the Gradle distribution):
//   apps/gradlew -p apps/terminal-compose/consumer-smoke jvmTest
//
// It resolves `dev.supermux.terminal:*` from TWO places and nowhere else: the local test
// repositories that
//   :terminal-core:publishAllPublicationsToLocalTestRepository      -> terminal-core/build/test-repository
//   :terminal-compose:publishAllPublicationsToLocalTestRepository   -> terminal-compose/build/test-repository
// write. Nothing is downloaded or uploaded for that group and no other repository may answer for
// it, so a green run cannot silently have used a project dependency, `:shared`, `:ui`, or a cached
// snapshot from somewhere else.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // The mode stays the default (PREFER_PROJECT), exactly as in terminal-core/consumer-smoke:
    // Compose and Kotlin plugins add project repositories of their own, and FAIL_ON_PROJECT_REPOS
    // would reject them outright. Exclusivity is enforced by CONTENT FILTERS instead, declared
    // identically here and in build.gradle.kts.
    repositories {
        terminalLocalTest(providers.gradleProperty("terminalCoreRepo").orNull, rootDir, "../../terminal-core/build/test-repository", "terminalCoreLocalTest")
        terminalLocalTest(providers.gradleProperty("terminalComposeRepo").orNull, rootDir, "../build/test-repository", "terminalComposeLocalTest")
        mavenCentral { content { excludeGroup("dev.supermux.terminal") } }
        google { content { excludeGroup("dev.supermux.terminal") } }
    }
}

/** One of the two repositories allowed to serve `dev.supermux.terminal`. */
fun RepositoryHandler.terminalLocalTest(override: String?, rootDir: File, default: String, repoName: String) {
    maven {
        name = repoName
        url = uri((override?.let { File(it) } ?: File(rootDir, default)).absoluteFile)
        content { includeGroup("dev.supermux.terminal") }
    }
}

rootProject.name = "terminal-compose-consumer-smoke"
