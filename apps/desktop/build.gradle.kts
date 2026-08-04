import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

repositories {
    mavenCentral()
    google()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") // JediTerm (M2)
    maven("https://jogamp.org/deployment/maven")                              // KCEF transitive (M3)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.jediterm.core) // both jediterm modules: dual LGPLv3/Apache-2.0 — used under Apache-2.0
    implementation(libs.jediterm.ui)
    implementation(libs.kcef) // M3 editor: embedded Chromium (JCEF) hosting the shared cm6 bundle (jogamp repo above)
    implementation(libs.zxing.core) // Plan 3 Task 3: pure-Java QR encoder for the first-run host wizard's pairing QR

    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock) // seed BrokerApi responses (e.g. terminal-tab list) in UI tests
    @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
}

kotlin { jvmToolchain(17) }

// Never launch a real system browser from unit/UI tests (Agent OAuth, timeline links, etc.).
// BrowserLauncher.openInBrowser checks this property and no-ops when set.
tasks.withType<Test>().configureEach {
    systemProperty("supermux.tests", "1")
    // Compose UI tests + MockEngine can wedge a worker under load; one fork keeps the gate
    // green-and-terminating (avoids the historical TerminalTabs hang under parallel workers).
    maxParallelForks = 1
    // Fail a wedged test rather than hanging the whole suite indefinitely.
    // (JUnit platform respects junit.jupiter.execution.timeout if used; kotlin.test does not.
    //  The suite still relies on per-waitUntil timeouts inside compose tests.)
}

// M3 editor: ship the SAME committed CodeMirror bundle the mobile apps use (single source of
// truth: apps/android/src/main/assets/editor/) into desktop resources under editor/. KCEF loads
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
        // KCEF (JCEF) reflects into java.desktop AWT internals to embed the heavyweight Chromium
        // child; JDK-17 module encapsulation needs these opened or init throws
        // InaccessibleObjectException. Applies to :desktop:run AND the jpackage image — NOT unit
        // tests (which never start CEF). See KcefRuntime.
        jvmArgs += listOf(
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED",
        )
        // macOS needs TWO more opens, and the failure mode is silent: JCEF's
        // CefBrowserWindowMac.getWindowHandle() reflects into `sun.lwawt.LWComponentPeer`
        // (getPlatformWindow) plus `sun.lwawt.macosx.CPlatformWindow` / `CFRetainedResource
        // $CFNativeAction` to obtain the NSWindow handle — a path a JetBrainsRuntime skips via
        // JdkEx.WindowHandleAccessor, but jpackage's runtime here is Corretto, so the reflection is
        // live. Without these it logs "failed to retrieve platform window handle" +
        // IllegalAccessException, never creates the native browser window, and the editor pane
        // renders as a BLANK WHITE rectangle (CEF itself initializes fine — nothing crashes).
        // Host-conditional because the packages only exist in a macOS java.desktop; adding them on
        // Linux/Windows would just print a "package not in java.desktop" warning at every start,
        // and the mac app image can only be built on a mac anyway.
        val macBuildHost = System.getProperty("os.name").orEmpty().lowercase()
            .let { it.contains("mac") || it.contains("darwin") }
        if (macBuildHost) {
            jvmArgs += listOf(
                "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            )
        }
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
