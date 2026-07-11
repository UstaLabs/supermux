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
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Msi, TargetFormat.AppImage)
            packageName = "supermux"
            packageVersion = "1.0.0"
            description = "supermux desktop"
            vendor = "UstaLabs"
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
        }
    }
}
