plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    `maven-publish`
}

// terminal-compose: the ONE terminal surface (grid painting, geometry, input) for Android, desktop
// JVM, iOS and the browser, drawn with Compose Multiplatform on a plain Canvas — no DOM node, no
// Swing widget, no UITextView anywhere in the rendering path.
//
// Self-contained like :terminal-core, and for the same reason: it depends on :terminal-core and
// Compose and NOTHING in :shared / :ui / the app modules, so it stays publishable on its own
// (dev.supermux.terminal:terminal-compose) and no app theme leaks into it.
group = "dev.supermux.terminal"
// The SAME property :terminal-core reads, and deliberately so: this module's POM carries a
// dependency on `dev.supermux.terminal:terminal-core:<its version>`, so publishing the two out of
// step would produce a surface that asks for an engine nobody published. `-Pterminal.version=`
// moves BOTH.
version = providers.gradleProperty("terminal.version").orNull?.takeIf { it.isNotBlank() } ?: "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    // publishLibraryVariants: the Maven publication carries the RELEASE aar only, the same choice
    // :terminal-core makes — the debug aar holds the same classes and nothing consumes it.
    androidTarget { publishLibraryVariants("release") }
    // Apple targets: declared so `iosMain` exists; their compile/link tasks are disabled on this
    // Linux host (kotlin.native.ignoreDisabledTargets) and run on the Mac.
    iosArm64()
    iosSimulatorArm64()
    // Browser only, like :ui. The wasm test task is disabled: commonTest is covered by jvmTest and
    // the browser-specific behaviour of this module is its host's (:web) to test.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: TerminalSession, TerminalViewport and the constants are
            // in this module's OWN public signatures (Terminal takes a session, TerminalFrame
            // carries TerminalRows), so every consumer needs them on its compile classpath.
            api(project(":terminal-core"))
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            // The default terminal face ships INSIDE this artifact as a Compose resource (see
            // TerminalFont.kt for why a renderer carries a font at all). `implementation`, not
            // `api`: no resource type appears in this module's public signatures — the generated
            // `Res` accessor is internal (`publicResClass = false`) and the only thing that leaves
            // here is a `FontFamily`.
            implementation(compose.components.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // The surface test drives the REAL composable through Compose's test harness (and a
            // real Skia canvas), which is the only way to prove the geometry contract end to end.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
        }
    }
}

// The font resources above. INTERNAL accessors: `Res.font.jetbrains_mono_*` is this module's own
// business, and a consumer that wants the family calls `packagedTerminalFontFamily()`.
compose.resources {
    packageOfResClass = "dev.supermux.terminal.compose.resources"
    publicResClass = false
    generateResClass = always
}

android {
    namespace = "dev.supermux.terminal.compose"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ---------------------------------------------------------------- publishing ----------------
//
// `dev.supermux.terminal:terminal-compose`, published LOCALLY ONLY: the one repository declared is
// a directory inside build/ (git-ignored) that terminal-compose/consumer-smoke resolves from.
// Nothing here uploads anywhere.
//
//   ./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository
//   ./gradlew :terminal-compose:publishAllPublicationsToLocalTestRepository
//   ./gradlew -p terminal-compose/consumer-smoke jvmTest
//
// The pair is published together on purpose — see `version` above and the gate below.

// The MIT licence, in every jar and inside the AAR, namespaced under META-INF so it can never
// collide with another dependency's META-INF/LICENSE.
//
// THIRD-PARTY-NOTICES.md travels with it, for ONE reason: this module compiles no third-party
// object code (unlike :terminal-core) but it does ship a third-party DATA file — JetBrains Mono,
// the default terminal face, as a Compose resource inside every artifact. OFL-1.1 asks for the
// notice to accompany the font, so it is in the archive next to it rather than in the repository
// only.
val licenseFiles = files(
    layout.projectDirectory.file("LICENSE"),
    layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"),
)
tasks.withType<Jar>().configureEach {
    from(licenseFiles) { into("META-INF/dev.supermux.terminal") }
    // Reproducible archives, for the same reason :terminal-core has them: two publishes of the
    // identical tree must produce byte-identical artifacts, or "does this artifact match that
    // build?" is unanswerable.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.matching { it.name.startsWith("bundle") && it.name.endsWith("Aar") }.configureEach {
    if (this is Zip) {
        from(licenseFiles) { into("META-INF/dev.supermux.terminal") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

publishing {
    repositories {
        maven {
            name = "localTest"
            url = uri(layout.projectDirectory.dir("build/test-repository"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("supermux terminal-compose")
            description.set(
                "The shared supermux terminal SURFACE: one Compose Multiplatform composable that " +
                    "draws a dev.supermux.terminal:terminal-core session on a plain Canvas — grid " +
                    "painting, geometry, smooth local scrolling, input routing, IME, selection, " +
                    "clipboard, links and accessibility — for Android, the desktop JVM, iOS and the " +
                    "browser. It imports no app theme and no host framework.",
            )
            url.set("https://github.com/UstaLabs/supermux")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://github.com/UstaLabs/supermux/blob/main/LICENSE")
                    distribution.set("repo")
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

// A Mac is the only host that publishes the Apple targets; both the gate below and the Apple
// publication guard at the end of this file turn on it.
val macHost = System.getProperty("os.name").orEmpty().lowercase()
    .let { it.startsWith("mac") || it.startsWith("darwin") }

/**
 * The publication gate.
 *
 * This module carries no native artifacts of its own, so there is nothing here to verify — but a
 * `terminal-compose` whose `terminal-core` cannot start an engine is a surface that draws nothing,
 * and a consumer resolving the pair would discover that at RUNTIME. So publishing this module runs
 * :terminal-core's OWN gate (release profile = every target, dev profile = everything this host can
 * build) before it writes anything, exactly as publishing :terminal-core does.
 *
 * It also fails if the two versions have drifted, which they cannot while both read
 * `-Pterminal.version` — the check is here so that stops being true loudly rather than silently.
 */
val corePublishGate = if (Regex("-(dev|snapshot)", RegexOption.IGNORE_CASE).containsMatchIn(version.toString())) {
    ":terminal-core:verifyNativeArtifactsForHost"
} else {
    ":terminal-core:verifyNativeArtifacts"
}
val verifyPairedVersion = tasks.register("verifyPairedVersion") {
    group = "verification"
    description = "Fail unless :terminal-core is CONFIGURED at the same version as this module. " +
        "Whether it was actually published is verifyPairedCoreArtifacts' job."
    val own = version.toString()
    val core = project(":terminal-core").version.toString()
    doLast {
        if (own != core) {
            throw GradleException(
                "terminal-compose $own would publish a dependency on terminal-core $core. The two are " +
                    "released as a pair; move both with -Pterminal.version=<version>.",
            )
        }
        logger.lifecycle("terminal-compose: publishing as a pair with terminal-core $core")
    }
}

/**
 * The other half of the pair rule: terminal-core must actually BE in the repository this publish
 * writes to, at this exact version.
 *
 * [verifyPairedVersion] compares two version strings inside one Gradle invocation, which says
 * nothing about what is on disk — publishing this module alone passed it happily and produced a POM
 * naming `dev.supermux.terminal:terminal-core:<version>` that nothing in the repository answers.
 * The consumer-smoke build then fails to RESOLVE, several steps away from the cause, and a real
 * consumer would fail the same way.
 *
 * So the artifacts are checked: the root coordinate's POM and module metadata, and every
 * per-target module the root metadata redirects to — following the same `available-at` links a
 * consumer's dependency resolution follows, so the check adapts to whatever targets that publish
 * contained instead of hard-coding a list. The Apple redirects are exempt off a Mac, which is the
 * documented shape of a Linux publish (terminal-core/VERIFICATION.md §4) and not a drift.
 *
 * It is wired to the `localTest` publish tasks only, because a directory is the only repository
 * this build has; `-PterminalCoreRepo=<dir>` points it elsewhere, exactly as it does for
 * consumer-smoke (and is how the failure mode itself is exercised — see terminal-compose/README.md).
 */
val coreRepositoryDir = providers.gradleProperty("terminalCoreRepo").orNull
    ?.let { file(it) }
    ?: project(":terminal-core").layout.projectDirectory.dir("build/test-repository").asFile
val verifyPairedCoreArtifacts = tasks.register("verifyPairedCoreArtifacts") {
    group = "verification"
    description = "Fail unless terminal-core's artifacts for this version are already in the local test repository."
    val coreVersion = project(":terminal-core").version.toString()
    val repository = coreRepositoryDir
    val appleHost = macHost
    doLast {
        val moduleDir = repository.resolve("dev/supermux/terminal/terminal-core/$coreVersion")
        val root = moduleDir.resolve("terminal-core-$coreVersion.module")
        val pom = moduleDir.resolve("terminal-core-$coreVersion.pom")
        val missing = mutableListOf<String>()
        for (required in listOf(pom, root)) {
            if (!required.isFile || required.length() == 0L) missing += required.path
        }
        if (missing.isEmpty()) {
            // Every `available-at` redirect in the root metadata is a file a consumer will ask for.
            val redirects = Regex("\"url\"\\s*:\\s*\"([^\"]+\\.module)\"")
                .findAll(root.readText())
                .map { it.groupValues[1] }
                .toSet()
            for (redirect in redirects) {
                val target = root.parentFile.resolve(redirect).normalize()
                if (target.isFile) continue
                // A publish made on Linux carries no Apple publications, by design.
                if (!appleHost && Regex("terminal-core-ios", RegexOption.IGNORE_CASE).containsMatchIn(redirect)) continue
                missing += target.path
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "terminal-core $coreVersion is not published in ${repository.path}: " +
                    missing.joinToString(", ") + ". terminal-compose's POM would name a coordinate " +
                    "nothing can resolve. Publish the pair, core first:\n" +
                    "  ./gradlew :terminal-core:publishAllPublicationsToLocalTestRepository\n" +
                    "  ./gradlew :terminal-compose:publishAllPublicationsToLocalTestRepository",
            )
        }
        logger.lifecycle("terminal-compose: terminal-core $coreVersion is in ${repository.path}")
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(corePublishGate)
    dependsOn(verifyPairedVersion)
}
tasks.withType<PublishToMavenRepository>().matching { it.name.endsWith("ToLocalTestRepository") }
    .configureEach { dependsOn(verifyPairedCoreArtifacts) }
tasks.withType<GenerateModuleMetadata>().configureEach {
    dependsOn(corePublishGate)
    dependsOn(verifyPairedVersion)
}

// Apple publications on a non-Mac host: OFF.
//
// `kotlin.native.ignoreDisabledTargets=true` lets the Apple targets be CONFIGURED here so
// `iosMain` exists and common code type-checks, and it disables their compile/link tasks — but not
// their PUBLICATION tasks, which then fail looking for a klib that was never built
// (`terminal-compose-iosArm64Main-<version>.klib (No such file or directory)`).
//
// :terminal-core never hits this only because its Apple targets carry a cinterop, which makes KGP
// disable cross-compilation for them outright; this module has no cinterop, so the guard has to be
// explicit. The effect is the same and it is the documented one: a publish made on Linux contains
// NO Apple publications at all, and the Apple artifacts come from a Mac-made publish
// (terminal-core/VERIFICATION.md §4).
if (!macHost) {
    tasks.matching { task ->
        listOf("IosArm64Publication", "IosSimulatorArm64Publication").any { task.name.contains(it) }
    }.configureEach {
        enabled = false
    }
}
