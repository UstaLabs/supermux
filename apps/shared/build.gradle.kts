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
    // Apple targets are declared so the appleMain/iosMain/watchosMain/macosMain source sets +
    // Apple actuals exist; their compile/link tasks run on a Mac (Spec 2). On this
    // Linux host they are disabled (see kotlin.native.ignoreDisabledTargets in
    // gradle.properties). iOS + watchOS + macOS share Darwin code via the default hierarchy's
    // intermediate `appleMain` source set.
    listOf(
        iosArm64(), iosSimulatorArm64(),
        watchosArm64(), watchosSimulatorArm64(),
        macosArm64(),
    ).forEach { t ->
        t.binaries.framework {
            baseName = "Shared"
            isStatic = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
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
        // The GFM markdown parser (org.jetbrains:markdown) only publishes JVM + Android +
        // a subset of Apple targets — notably NOT watchosArm64 — so it can't live in commonMain.
        // The chat markdown renderer is Android-only anyway; iOS has its own native MarkdownView.
        // A non-Apple (jvm + android) intermediate source set holds the parser and its dependency,
        // keeping the Apple framework free of it entirely.
        val nonAppleMain by creating { dependsOn(commonMain.get()) }
        val nonAppleTest by creating { dependsOn(commonTest.get()) }
        nonAppleMain.dependencies { implementation(libs.markdown) }
        jvmMain { dependsOn(nonAppleMain); dependencies { implementation(libs.ktor.client.cio) } }
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
