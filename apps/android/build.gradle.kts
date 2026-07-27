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

android {
    namespace = "dev.supermux.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.supermux.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 32; versionName = "0.9.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        getByName("release") {
            // Minification is intentionally OFF. R8 renamed/stripped code that several
            // subsystems resolve BY NAME at runtime and that static analysis can't see:
            //  - org.connectbot termlib JNI callbacks  -> native crash opening the Terminal
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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.security.crypto)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.reorderable)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.compose.material3.windowsize)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.termlib)
    implementation(libs.zxing.android.embedded)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    // Inline markdown images (async load + cache). ktor3 backend reuses our ktor stack.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.compose.ui.tooling.preview)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
