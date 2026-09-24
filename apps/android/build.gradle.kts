import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
    alias(libs.plugins.google.services)
}

// Release signing is driven by a gitignored keystore.properties (never committed).
// When it's absent (fresh checkout / CI without the key) the release build falls
// back to the debug key so it still assembles — see buildTypes.release below.
val keystorePropsFile = project.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

// Local/dev defaults. CI release builds override both via Gradle properties
// (-PsupermuxVersionCode / -PsupermuxVersionName) so every published APK gets a
// strictly higher versionCode without a manual bump — see release.yml build-android.
val defaultVersionCode = 32
val defaultVersionName = "0.9.8"
val supermuxVersionCode = (findProperty("supermuxVersionCode") as String?)
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: defaultVersionCode
val supermuxVersionName = (findProperty("supermuxVersionName") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: defaultVersionName

// The ABIs the shared terminal engine ships, from apps/gradle.properties. ONE source of truth with
// `:terminal-core`'s `androidNativeTargets` — the module that stages the JNI libraries reads the
// same line, so "which ABIs does the APK claim" and "which ABIs is the engine built for" are a
// single decision rather than two lists that agree by review.
val androidTerminalAbis: Set<String> =
    (providers.gradleProperty("supermux.terminal.androidAbis").orNull
        ?: error("supermux.terminal.androidAbis is not set (apps/gradle.properties)"))
        .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { it.substringBefore('=').trim() }
        .toSet()

android {
    namespace = "dev.supermux.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.supermux.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = supermuxVersionCode
        versionName = supermuxVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The APK claims exactly the ABIs the terminal engine actually ships — read from the
            // SAME property `:terminal-core` stages its JNI libraries from, so the two cannot
            // drift. `:terminal-core` FAILS the build when one of them was never built, which is
            // what stops an APK being packaged with an ABI it has no engine for.
            //
            // Why this line exists at all: before Plan 4 the Android terminal was a library whose
            // native part shipped for armeabi-v7a and x86 as well, so the APK was genuinely
            // installable on a 32-bit device. The shared engine has no such targets, and without
            // a filter the APK would keep advertising them and then have nothing to load. The
            // reasoning for the two we DO ship is recorded next to the property itself.
            abiFilters += androidTerminalAbis
        }
    }
    buildFeatures { compose = true }
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        // When upload-keystore.jks is present, BOTH debug and release sign with the same
        // key as GitHub/Play (CN=Supermux). That way `installDebug` / local deploy can
        // upgrade over a sideloaded release APK without INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        // Without the keystore (fresh CI checkout), both fall back to the Android debug key.
        getByName("debug") {
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
        getByName("release") {
            // Minification is intentionally OFF. R8 renamed/stripped code that several
            // subsystems resolve BY NAME at runtime and that static analysis can't see:
            //  - JNI callbacks into a native terminal library -> native crash opening the
            //    Terminal (this was ConnectBot termlib; `:terminal-core`'s JNI is reached the
            //    same way, so the reason outlived the library)
            //  - Google Tink behind EncryptedSharedPreferences -> SecureTokenStore lost the
            //    pairing across restarts
            //  - the cm6 editor @JavascriptInterface bridge (onChange/onSave/onReady/lspOut)
            // Curating exhaustive keep-rules + re-verifying every subsystem isn't worth the
            // ~22MB; an unminified release == the already-verified debug build. proguard-rules.pro
            // keeps the known-required rules documented if minify is ever re-enabled.
            isMinifyEnabled = false
            isShrinkResources = false
            // Real release key when keystore.properties is present; debug key otherwise
            // (so the build still produces an installable APK for testing).
            signingConfig = if (keystorePropsFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":ui"))
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.security.crypto)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.activity.compose)
    // Not used directly: a transitive dependency drags in a pre-1.3.0 androidx.fragment, whose
    // FragmentActivity breaks the ActivityResult APIs (PushPermission) — lintVitalRelease treats
    // that as fatal (InvalidFragmentVersionForActivityResult). Pin a current one.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.splashscreen)
    // (Navigation 3 replaced navigation-compose in cluster G8: the shared `SupermuxApp` root
    //  drives one `NavDisplay` back stack on BOTH hosts, and it arrives transitively from :ui.)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.zxing.android.embedded)
    // Inline markdown images (async load + cache). ktor3 backend reuses our ktor stack.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    // MockEngine: drive PairingHolder's probe client without a socket (PairingHolderTest).
    testImplementation(libs.ktor.client.mock)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
