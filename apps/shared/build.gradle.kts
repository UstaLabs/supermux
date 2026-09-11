plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
}

kotlin {
    // expect/actual classes are stable-in-practice but flagged Beta; acknowledge it.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    // Applied explicitly so the extra non-Apple intermediate source sets below (which add their own
    // dependsOn edges) coexist with the default apple/native/jvm/android wiring instead of disabling it.
    applyDefaultHierarchyTemplate()

    jvm()
    androidTarget()
    // Browser client (plan 1 of the web→KMP migration). `browser()` only — no Node target. The
    // wasm test task is disabled: every commonTest already runs on the JVM, and headless-Chromium
    // Karma is wired for `:web` alone (its tests are the browser-only ones).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    // Apple targets are declared so the appleMain/iosMain/watchosMain source sets +
    // Apple actuals exist; their compile/link tasks run on a Mac (Spec 2). On this
    // Linux host they are disabled (see kotlin.native.ignoreDisabledTargets in
    // gradle.properties). iOS + watchOS share Darwin code via the default hierarchy's
    // intermediate `appleMain` source set.
    listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosSimulatorArm64(),
    ).forEach { t ->
        t.binaries.framework {
            baseName = "Shared"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.atomicfu)
            implementation(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // MockEngine: capture the exact request shapes BrokerApi produces.
            implementation(libs.ktor.client.mock)
        }
        // Two intermediate source sets, both narrower than commonMain, for two DIFFERENT reasons.
        //
        // nonWatchMain — the GFM markdown parser (org.jetbrains:markdown 0.7.6). It publishes JVM,
        // Android, iOS and macOS but NOT watchosArm64, so it cannot live in commonMain. The set is
        // therefore "everything except the watch": the phone, the desktop and the Mac all render
        // markdown from `ui/Markdown.kt`, and `:ui`'s chat renderer (which is commonMain and now
        // compiles for iOS) needs `parseMarkdownBlocks` to resolve there. Only the watch app's
        // framework stays free of it — the watch shows plain text.
        //
        // nonAppleMain — the JVM/Android-only code: `java.time` labels, `java.util.TimeZone`, and
        // the CIO HTTP engine (Apple targets use Darwin). It sits UNDER nonWatchMain so jvm and
        // android reach the markdown parser through one edge rather than two.
        val nonWatchMain by creating { dependsOn(commonMain.get()) }
        val nonWatchTest by creating { dependsOn(commonTest.get()) }
        nonWatchMain.dependencies { implementation(libs.markdown) }

        val nonAppleMain by creating { dependsOn(nonWatchMain) }
        val nonAppleTest by creating { dependsOn(nonWatchTest) }
        nonAppleMain.dependencies { implementation(libs.ktor.client.cio) }

        // iosMain is the default hierarchy's intermediate over iosArm64 + iosSimulatorArm64; both
        // get the parser. watchosArm64/watchosSimulatorArm64 deliberately do not.
        iosMain { dependsOn(nonWatchMain) }

        // wasmJs sits under nonWatchMain (it renders markdown) but NOT nonAppleMain (java.time,
        // CIO): the browser has its own clock/format/HTTP actuals in wasmJsMain.
        wasmJsMain {
            dependsOn(nonWatchMain)
            // `js("…")`, external declarations and JsAny are all still behind this opt-in in 2.3.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(libs.ktor.client.js)
                // kotlinx.browser / org.w3c / org.khronos.webgl — no longer in the wasm stdlib.
                implementation(libs.kotlinx.browser)
                // zlib for the RFB ZRLE decoder — synchronous, which DecompressionStream is not.
                implementation(npm("pako", "2.1.0"))
            }
        }

        jvmMain { dependsOn(nonAppleMain) }
        jvmTest {
            dependsOn(nonAppleTest)
            // A throwaway local WebSocket server to drive VncClient end-to-end
            // against the captured RFB fixture (JVM-only test harness; not shipped).
            dependencies {
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.websockets)
            }
        }
        appleMain.dependencies { implementation(libs.ktor.client.darwin) }
        // nonIosAppleMain — the Apple targets that are NOT the iPhone app; today that is watchOS
        // alone (the native macOS app that also sat here was retired).
        //
        // It exists for exactly one declaration, `SecureTokenStore`. Cluster H2 gives iOS a real
        // Keychain actual, and an actual in `appleMain` would have to serve watchOS too — but the
        // watch has no Keychain item to read (it is provisioned over WatchConnectivity). So the
        // in-memory stub stays here for the watch, and the Keychain actual lives in `iosMain`.
        val nonIosAppleMain by creating { dependsOn(appleMain.get()) }
        watchosMain { dependsOn(nonIosAppleMain) }
        androidMain {
            dependsOn(nonAppleMain)
            dependencies { implementation(libs.androidx.security.crypto) }
        }
    }
}

android {
    namespace = "dev.supermux.shared"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
