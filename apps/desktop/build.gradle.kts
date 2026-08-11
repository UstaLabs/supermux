import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.reload.gradle.ComposeHotRun
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    // Dev-time only: adds :desktop:hotRun / :desktop:reload. See the ComposeHotRun block below for
    // why this does NOT leak into the packaged app.
    alias(libs.plugins.compose.hot.reload)
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") // JediTerm (M2)
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.jediterm.core) // both jediterm modules: dual LGPLv3/Apache-2.0 — used under Apache-2.0
    implementation(libs.jediterm.ui)
    // macOS chrome: JBR custom-title-bar API (MacWindowChrome.kt). Safe no-op facade on non-JBR JVMs.
    implementation(libs.jbr.api)
    // Direct JetBrains JCEF compile API. At runtime the matching JBR's built-in `jcef` module wins
    // class loading (verified by smokeJcefEditor), keeping its Java classes and native Chromium an
    // exact build pair. That JBR image is prepared below for :run/:hotRun and every distribution.
    implementation(libs.jcef)
    implementation(libs.zxing.core) // Plan 3 Task 3: pure-Java QR encoder for the first-run host wizard's pairing QR
    // Native LazyList reorder (elevates, auto-scrolls, animates neighbors) — same library Android uses.
    // Custom detectDragGestures step-math was jumpy on desktop trackpads; Calvin is production-proven.
    implementation(libs.reorderable)
    // Navigation 3 — user-owned back stack for full-pane workspace overlays (Settings hub,
    // Archived, AppUpdate). Workspace shell stays outside the stack so chat/editor/term keep state.
    implementation(libs.jetbrains.navigation3.ui)

    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock) // seed BrokerApi responses (e.g. terminal-tab list) in UI tests
    // Real WS reconnect tests for System restart (local stub broker; not shipped).
    testImplementation(libs.ktor.server.cio)
    testImplementation(libs.ktor.server.websockets)
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(17) }

val hostOs = System.getProperty("os.name").orEmpty().lowercase()
val hostArch = System.getProperty("os.arch").orEmpty().lowercase()
val macBuildHost = hostOs.let { it.contains("mac") || it.contains("darwin") }

// JCEF's Java classes and native Chromium payload must come from the same JetBrains build family.
// Unit tests only need the Maven API jar; live run/package tasks lazily fetch this pinned official
// JBR-with-JCEF image, verify its SHA-512, and either launch with it or hand it to jpackage as the
// complete runtime image. This replaces the old wrapper's first-editor-use runtime download and ensures a
// freshly installed app has the rich editor available offline on first launch.
val jbrJcefVersion = "21.0.11"
val jbrJcefBuild = "1163.116"
val jbrJcefPlatform = when {
    macBuildHost -> "osx"
    hostOs.contains("win") -> "windows"
    hostOs.contains("linux") -> "linux"
    else -> error("JBR with JCEF is not available for os.name=${System.getProperty("os.name")}")
}
val jbrJcefArch = when (hostArch) {
    "amd64", "x86_64", "x64" -> "x64"
    "arm64", "aarch64" -> "aarch64"
    else -> error("JBR with JCEF is not available for os.arch=${System.getProperty("os.arch")}")
}
val jbrJcefSha512 = mapOf(
    "linux-x64" to "9affe2dd70e26c5aa76e25860ba89cad0ae266558ca679e0178b22acc025ea61adb78c770e6965e61c050e45784819eee22f9e4d98e187a14f401f8a12d2118a",
    "linux-aarch64" to "c39251cbafdc7a8433ed4b5136500a3530845191bc5568cfc64e199b68c6e457cf973644116dc9beb172a95ef9214d3f3a19ba1bb9dd6f0af2207c2373995d0f",
    "windows-x64" to "92a2b1271ade034c338a35ff4a0f81b865100a24f3e507b733933985203ce8d8a40304ac298eb62d6d4320dc034ba6bb14023b987edd2c002077456b0039246a",
    "windows-aarch64" to "5f8239730d4f40248f71c5dc2abfb8640727461e3f3dc851abaa216d8a9b80f1791861d51ac1cbd6634cafc26d7f4b2da350b433460ad391b14488d28acb74bd",
    "osx-x64" to "19c9e078ffa85cdc4e98fa90e62719913f8227d887d47adbb4372fc77fc23d69e8b55bb99d352e7c806ac5c3062b89ef71a1036170ebe7c4277061430e0c0674",
    "osx-aarch64" to "1ff18d478d47e0b98940546e0845da3289695872dabc27aa0337d9228cf9876bea8386e44d1a366bd2c115e0236dde5d8b3f96f39d2206aa155d382bcca382d7",
).getValue("$jbrJcefPlatform-$jbrJcefArch")
val jbrJcefArchiveName = "jbr_jcef-$jbrJcefVersion-$jbrJcefPlatform-$jbrJcefArch-b$jbrJcefBuild.tar.gz"
val jbrJcefRootName = jbrJcefArchiveName.removeSuffix(".tar.gz")
val jbrJcefArchive = layout.buildDirectory.file("jbr-jcef/$jbrJcefArchiveName")
val jbrJcefExtractDir = layout.buildDirectory.dir("jbr-jcef/runtime")
val jbrJcefImage = jbrJcefExtractDir.map { it.dir(jbrJcefRootName) }
val jbrJcefHome = jbrJcefImage.map { image -> if (macBuildHost) image.dir("Contents/Home") else image }
val jbrJcefPackageImage = jbrJcefHome
// JDK 21 jpackage's --app-content merges this `runtime` directory into App.app/Contents/runtime.
// macOS needs it because --runtime-image intentionally copies Contents/Home only, while JCEF's
// Chromium framework and helper apps live in the JBR bundle's sibling Contents/Frameworks.
val jbrJcefMacAppContent = layout.buildDirectory.dir("jbr-jcef/mac-app-content/runtime")
val jbrJcefLauncher = providers.provider<org.gradle.jvm.toolchain.JavaLauncher> {
    object : org.gradle.jvm.toolchain.JavaLauncher {
        override fun getExecutablePath(): org.gradle.api.file.RegularFile =
            jbrJcefHome.get().file("bin/java")

        override fun getMetadata(): org.gradle.jvm.toolchain.JavaInstallationMetadata =
            object : org.gradle.jvm.toolchain.JavaInstallationMetadata {
                override fun getLanguageVersion() = org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)
                override fun getJavaRuntimeVersion() = "$jbrJcefVersion+$jbrJcefBuild"
                override fun getJvmVersion() = jbrJcefVersion
                override fun getVendor() = "JetBrains s.r.o."
                override fun getInstallationPath(): org.gradle.api.file.Directory = jbrJcefHome.get()
                override fun isCurrentJvm() = false
            }
    }
}

val prepareJbrJcefRuntime by tasks.registering {
    group = "compose desktop"
    description = "Download and verify the pinned JetBrains Runtime with JCEF"
    inputs.property("archiveName", jbrJcefArchiveName)
    inputs.property("sha512", jbrJcefSha512)
    outputs.dir(jbrJcefHome)
    if (macBuildHost) outputs.dir(jbrJcefMacAppContent)

    doLast {
        val archive = jbrJcefArchive.get().asFile.toPath()
        val runtimeHome = jbrJcefHome.get().asFile.toPath()
        if (!Files.exists(runtimeHome.resolve("release"))) {
            Files.createDirectories(archive.parent)
            if (!Files.exists(archive)) {
                val partial = archive.resolveSibling("${archive.fileName}.part")
                val url = "https://cache-redirector.jetbrains.com/intellij-jbr/$jbrJcefArchiveName"
                URI(url).toURL().openStream().use { input ->
                    Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING)
                }
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING)
            }

            val actualSha512 = Files.newInputStream(archive).use { input ->
                val digest = MessageDigest.getInstance("SHA-512")
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            check(actualSha512 == jbrJcefSha512) {
                "SHA-512 mismatch for $jbrJcefArchiveName: expected $jbrJcefSha512, got $actualSha512"
            }

            delete(jbrJcefExtractDir)
            copy {
                from(tarTree(resources.gzip(archive.toFile())))
                into(jbrJcefExtractDir)
            }
            check(Files.exists(runtimeHome.resolve("release"))) {
                "JBR archive did not contain the expected runtime at $runtimeHome"
            }
        }

        if (macBuildHost) {
            val sourceFrameworks = jbrJcefImage.get().dir("Contents/Frameworks").asFile.toPath()
            val contentRoot = jbrJcefMacAppContent.get().asFile.toPath()
            val targetFrameworks = contentRoot.resolve("Contents/Frameworks")
            val chromium = targetFrameworks
                .resolve("Chromium Embedded Framework.framework")
                .resolve("Chromium Embedded Framework")
            if (!Files.isRegularFile(chromium)) {
                delete(contentRoot.toFile())
                copy {
                    from(sourceFrameworks)
                    into(targetFrameworks)
                }
            }
            check(Files.isRegularFile(chromium)) {
                "JBR archive did not contain the expected macOS JCEF framework at $chromium"
            }
        }
    }
}

// JCEF reflects into java.desktop AWT internals to embed the heavyweight Chromium child;
// JVM module encapsulation needs these opened or init throws InaccessibleObjectException.
// Applies to every JVM that actually STARTS the app — the jpackage image, :desktop:run, AND
// :desktop:hotRun — but NOT unit tests (which never start CEF). See JcefRuntime.
//
// macOS needs TWO more opens, and the failure mode is silent: JCEF's
// CefBrowserWindowMac.getWindowHandle() reflects into `sun.lwawt.LWComponentPeer`
// (getPlatformWindow) plus `sun.lwawt.macosx.CPlatformWindow` / `CFRetainedResource
// $CFNativeAction` to obtain the NSWindow handle. The pinned JBR can use its direct accessor, while
// these opens preserve the reflection fallback used by this JCEF build.
// Without these it logs "failed to retrieve platform window handle" + IllegalAccessException, never
// creates the native browser window, and the editor pane renders as a BLANK WHITE rectangle (CEF
// itself initializes fine — nothing crashes). Host-conditional because the packages only exist in a
// macOS java.desktop; adding them on Linux/Windows would just print a "package not in java.desktop"
// warning at every start, and the mac app image can only be built on a mac anyway.
val jcefAddOpens: List<String> = buildList {
    addAll(listOf("--add-opens", "java.desktop/sun.awt=ALL-UNNAMED"))
    addAll(listOf("--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED"))
    if (macBuildHost) {
        addAll(listOf("--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED"))
        addAll(listOf("--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED"))
    }
}

// Compose Hot Reload. `./gradlew :desktop:hotRun --auto` runs the app under a JetBrains Runtime and
// re-applies @Composable edits on save without losing app state (broker WS session, open tabs).
// Explicit mode (no --auto) reloads on `./gradlew :desktop:reload` instead.
//
// The plugin only touches these run tasks: it does NOT add anything to `runtimeClasspath`, so the
// distributable built by packageDeb/Msi/Dmg is byte-for-byte what it was before — verified with
// `:desktop:dependencies --configuration runtimeClasspath`.
//
// Two things do NOT hot-reload, both by construction: the editor pane (JCEF — a heavyweight
// native Chromium window behind SwingPanel) and the terminal panes (JediTerm, AWT). Their Kotlin
// reloads fine, but the native widgets keep whatever state they had; changing their setup code
// needs a real restart. Everything drawn by Compose — chat, settings, host wizard, usage, tabs —
// reloads normally.
tasks.withType<ComposeHotRun>().configureEach {
    mainClass.set("dev.supermux.desktop.MainKt")
    jvmArgs(jcefAddOpens)
    dependsOn(prepareJbrJcefRuntime)
    javaLauncher.set(jbrJcefLauncher)
}

// The normal Compose run task must use the same JBR/JCEF pair as packaged builds. Tests deliberately
// stay on the ordinary toolchain because none of them starts the native browser runtime.
tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    dependsOn(prepareJbrJcefRuntime)
    javaLauncher.set(jbrJcefLauncher)
}

// Never launch a real system browser from unit/UI tests (Agent OAuth, timeline links, etc.).
// BrowserLauncher.openInBrowser checks this property and no-ops when set.
tasks.withType<Test>().configureEach {
    systemProperty("supermux.tests", "1")
    // Compose UI tests + MockEngine can wedge a worker under load; one fork keeps the gate
    // green-and-terminating (avoids the historical TerminalTabs hang under parallel workers).
    maxParallelForks = 1
}

// Design-approval window for WorkspaceListPanel (test-source main — never ships).
// Run: ./gradlew :desktop:previewWorkspaceList
tasks.register<JavaExec>("previewWorkspaceList") {
    group = "application"
    description = "Open WorkspaceListPanel fixture window for design screenshot (sidebar proportions)"
    dependsOn("compileTestKotlin")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.shell.WorkspaceListPreviewKt")
}

// M3 editor: ship the SAME committed CodeMirror bundle the mobile apps use (single source of
// truth: apps/android/src/main/assets/editor/) into desktop resources under editor/. JCEF loads
// the page from an extracted file:// path at runtime (see EditorWebAssets), but the bundle rides
// the classpath so it's packaged by jpackage. apps/ is the gradle root → rootProject.projectDir
// resolves to .../apps, so android/... below is correct. `from`+`into` on processResources IS the
// Copy — build/resources/main/editor/{index.html,cm6.js} after the task runs.
tasks.named<Copy>("processResources") {
    from("${rootProject.projectDir}/android/src/main/assets/editor") {
        include("index.html", "cm6.js")
        into("editor")
    }
}

compose.desktop {
    application {
        mainClass = "dev.supermux.desktop.MainKt"
        // See jcefAddOpens above for why these are required and why the mac pair is host-gated.
        // Shared with :desktop:hotRun so the two run paths can't drift.
        jvmArgs += jcefAddOpens
        nativeDistributions {
            // Host-scoped: jpackage can only ever build the formats of the OS it runs on, AND on
            // macOS Compose eagerly creates a `notarize<Format>` task per declared format —
            // `notarizeAppImage` then hard-fails configuration with "AppImage cannot be notarized!".
            if (macBuildHost) {
                targetFormats(TargetFormat.Dmg)
            } else {
                targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.AppImage)
            }
            // macOS gets a DISTINCT name: the shipping native SwiftUI client installs as
            // `Supermux.app`, and on a case-insensitive volume `/Applications/supermux.app` is the
            // SAME PATH — dragging this DMG's app over would silently replace it. (Setting
            // `macOS { packageName }` alone does NOT rename the bundle in Compose 1.11.1 — the app
            // image and DMG both keep this outer name — so scope it here instead.)
            packageName = if (macBuildHost) "Supermux Desktop" else "supermux"
            packageVersion = "1.0.0"
            description = "supermux desktop"
            vendor = "UstaLabs"
            // Plan 3 Task 5 (desktop-as-host): bundle the host helper binaries — the compiled Bun
            // broker (`supermux-broker`), a static `tmux` (Linux/macOS), and `frpc` (all platforms) —
            // as app resources. jpackage merges resources/{common,<os>,<os>-<arch>}/ into the image
            // and exposes the dir at runtime via -Dcompose.application.resources.dir (see
            // HostBinaries). The big natives are NOT committed; scripts/stage-desktop-binaries.sh
            // fetches/builds them into resources/<os>-<arch>/ in CI before packageDeb/packageMsi
            // (the dirs carry a .gitignore so they exist but stay empty in git). An unstaged dir
            // just bundles nothing — the app then falls back to an already-running/system broker.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            // jdeps auto-detection only sees STATIC bytecode edges, so it misses the JDK modules
            // pulled in by reflection at runtime — JCEF reflects into java.desktop, and
            // ktor/coroutines reach java.naming / java.management / jdk.unsupported. Those gaps
            // surface ONLY in the jlinked installer runtime (a missing-module / NoClassDefFoundError
            // crash), never in :desktop:run. Bundling every module trades image size for correctness.
            includeAllModules = true
            linux {
                debMaintainer = "supermux"
                menuGroup = "Development"
                appCategory = "Development"
            }
            // macOS DMG. Deliberately a DIFFERENT app name + bundle id from the shipping native
            // SwiftUI mac client (`Supermux.app` / `dev.supermux.app`): both would land in
            // /Applications, and on a case-insensitive volume `supermux.app` and `Supermux.app` are
            // the SAME path — installing this would silently replace the native app. `packageName`
            // here is mac-only; Linux/Windows keep "supermux" from the block above.
            macOS {
                bundleID = "dev.supermux.desktop"
                dockName = "Supermux Desktop"
                appCategory = "public.app-category.developer-tools"
                // Hardened runtime is mandatory for notarization; see the plist for why each
                // entitlement is needed (JIT, and library validation for the downloaded CEF).
                entitlementsFile.set(project.file("entitlements.mac.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.mac.plist"))
                // Signing is OPT-IN so unsigned local/CI dry-run builds keep working untouched:
                // pass -PsmMacSignIdentity=<identity-or-sha1> (plus -PsmMacSignKeychain=<path> when
                // the identity lives outside the login keychain). ⚠️ Use the SHA-1 fingerprint from
                // `security find-identity -v <keychain>`, not the display label — codesign fails to
                // resolve a Developer ID by label out of a non-default keychain (release v0.11.11
                // burned a whole tag on exactly that).
                val signIdentity = project.findProperty("smMacSignIdentity") as String?
                if (!signIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(signIdentity)
                        (project.findProperty("smMacSignKeychain") as String?)
                            ?.takeIf { it.isNotBlank() }
                            ?.let { keychain.set(it) }
                    }
                }
                // NOTE: notarization is NOT declared here. Compose's notarization block only speaks
                // Apple-ID + app-specific-password, and this project's credential is an App Store
                // Connect API key (the same one release CI uses), so the DMG is submitted with
                // `xcrun notarytool submit --key/--key-id/--issuer` and stapled afterwards.
            }
        }
    }
}

// Compose configures its jpackage task inputs after the nativeDistributions DSL is evaluated. Apply
// the complete verified JBR image afterward so its late default cannot restore the minimized jlink
// runtime (which drops JCEF's non-module Chromium payload). The build JDK still supplies jpackage.
afterEvaluate {
    tasks.withType<AbstractJPackageTask>().configureEach {
        dependsOn(prepareJbrJcefRuntime)
        runtimeImage.set(jbrJcefPackageImage)
        runtimeImage.finalizeValue()
    }
    tasks.named<AbstractJPackageTask>("createDistributable") {
        if (macBuildHost) {
            doFirst {
                check(Runtime.version().feature() >= 21) {
                    "macOS JCEF packaging requires JDK 21+ for jpackage --app-content"
                }
            }
            freeArgs.addAll("--app-content", jbrJcefMacAppContent.get().asFile.absolutePath)
        }
        doLast {
            val appDirectoryName = if (macBuildHost) "${packageName.get()}.app" else packageName.get()
            val appRoot = destinationDir.get().asFile.toPath().resolve(appDirectoryName)
            val nativeMarker = when {
                macBuildHost -> appRoot.resolve(
                    "Contents/runtime/Contents/Frameworks/" +
                        "Chromium Embedded Framework.framework/Chromium Embedded Framework",
                )
                hostOs.contains("win") -> appRoot.resolve("runtime/bin/jcef.dll")
                else -> appRoot.resolve("lib/runtime/lib/libjcef.so")
            }
            check(Files.isRegularFile(nativeMarker)) {
                "Packaged application is missing the JCEF native runtime at $nativeMarker"
            }
        }
    }
}

// Throwaway probe for SwingPanel z-order (test-source main — never ships).
tasks.register<JavaExec>("probeInteropZOrder") {
    group = "application"
    dependsOn("compileTestKotlin")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.shell.InteropZOrderProbeKt")
    jvmArgs(jcefAddOpens)
}

// Probe 2: interop blending on a GPU backend that can actually support it
// (Metal/D3D only — see BlendingProbe.kt). Test-source main — never ships.
tasks.register<JavaExec>("probeBlending") {
    group = "application"
    dependsOn("compileTestKotlin")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.shell.BlendingProbeKt")
    jvmArgs(jcefAddOpens)
}

// Probe 3: the SAME question for the JCEF editor, which blending does NOT rescue
// (native NSView, not Swing content). Keeps the evidence for that split
// reproducible. Test-source main — never ships.
val probeJcefBlending by tasks.registering(JavaExec::class) {
    group = "application"
    dependsOn("compileTestKotlin", prepareJbrJcefRuntime)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.shell.JcefBlendingProbeKt")
    jvmArgs(jcefAddOpens)
    javaLauncher.set(jbrJcefLauncher)
}

// Backward-compatible task alias for existing developer scripts.
tasks.register("probeKcefBlending") {
    group = "application"
    dependsOn(probeJcefBlending)
}

// Full live editor smoke: JCEF loads the extracted CodeMirror bundle and receives cm6's onReady.
tasks.register<JavaExec>("smokeJcefEditor") {
    group = "verification"
    dependsOn("compileTestKotlin", prepareJbrJcefRuntime)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.editor.JcefEditorSmokeKt")
    jvmArgs(jcefAddOpens)
    javaLauncher.set(jbrJcefLauncher)
}

// Probe 4: blending fixes COMPOSITING — does the modal painted over the interop
// child also RECEIVE INPUT? Test-source main — never ships.
tasks.register<JavaExec>("probeInteropInput") {
    group = "application"
    dependsOn("compileTestKotlin")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.supermux.desktop.shell.InteropInputProbeKt")
    jvmArgs(jcefAddOpens)
}

// Writes the test runtime classpath so a probe can be launched with a bare `java`
// from a real GUI terminal — a Gradle daemon started over SSH has no window
// server connection and its forked children inherit that. Test-only helper.
tasks.register("dumpTestClasspath") {
    group = "application"
    dependsOn("compileTestKotlin")
    val cp = sourceSets["test"].runtimeClasspath
    val out = File(System.getProperty("java.io.tmpdir"), "sm-test-classpath.txt")
    doLast {
        out.writeText(cp.joinToString(File.pathSeparator))
        println("dumpTestClasspath -> $out")
    }
}
