import java.time.Duration

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// webview-sample: the standalone sample and perf lab for :webview-compose. It depends on :webview-compose and on
// NOTHING else of supermux, like :terminal-sample: if the library can only be used from inside this repository, this
// module stops building. The desktop runs the library's fake engine until a desktop engine is built; the browser
// (wasmJs) runs the real web engine, an iframe per live page; Android runs the system WebView (or the fake engine, to
// compare); iOS runs the real WKWebView engine, and with `-engine-checks` the engine's conformance checks. Each engine
// plan adds its platform's target and engine.
//
//   ./gradlew :webview-sample:run             # the desktop window: the perf lab, the overlay demo, the checks page
//   ./gradlew :webview-sample:jvmTest         # the perf lab's invariants under scripted scrolling, its numbers printed as
//                                             # `perf-lab` lines, and the screens rendered to build/sample-screens/*.png
//   ./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the browser sample, served on localhost only
//   ./gradlew :webview-sample:wasmJsBrowserDistribution     # build/dist/wasmJs/productionExecutable, which
//                                             # web-check/run.mjs drives in a headless Chrome (modes lab and overlay)
//   ./gradlew :webview-sample:assembleDebug   # the Android app (dev.supermux.webview.sample);
//                                             # device-checks/android-sample.sh installs and runs it on a phone
//   device-checks/ios-engine-checks.sh        # ON THE MAC: the iOS engine's conformance checks, in the simulator
//   device-checks/ios-sim.sh                  # ON THE MAC: the iOS app's UI evidence (Maestro, screenshots)
//   device-checks/linux-engine-checks.sh      # ON THIS BOX: the Linux engine's checks, on desktop, in a plain Ubuntu 24.04
group = "dev.supermux.webview.sample"
version = "0.1.0-dev.1"

// device-checks/linux-engine-checks.sh builds the sample on the Mac for Linux x64: -Pwebview.sample.linux puts Linux's
// skiko on the classpath instead of the build host's.
val linuxStage = providers.gradleProperty("webview.sample.linux").isPresent

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig { outputFileName = "webview-sample.js" }
            // The sample's tests run on the desktop (jvmTest). Its browser build is checked in a real Chrome by
            // web-check/run.mjs, which can do what Karma can't: trusted input, process memory, screenshots.
            testTask { enabled = false }
        }
        binaries.executable()
    }
    // The iOS host (iosApp/project.yml) links WebViewSample.framework: the perf lab on real WKWebView engines, and the
    // engine's conformance checks (device-checks/ios-engine-checks.sh). Dynamic, like the other samples' frameworks.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "WebViewSample"
            isStatic = false
        }
    }
    applyDefaultHierarchyTemplate()

    sourceSets {
        // Kotlin/Native interop (the process's arguments, exit) in the iOS host; every iOS source set names it.
        matching { it.name.startsWith("ios") }.configureEach {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            implementation(project(":webview-compose"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            // Material3 dresses the sample's own controls. The web views use nothing of it.
            implementation(compose.material3)
            implementation(libs.coroutines.core)
        }
        // The engines' conformance checks (plan Task 12, D11): shared by the iOS app and the macOS desktop app, which run
        // them in a real window on their platform's engine. Compose, Skia and the library, and asserts of their own:
        // kotlin-test is a test library.
        val checksMain by creating { dependsOn(commonMain.get()) }
        iosMain.get().dependsOn(checksMain)
        jvmMain.get().dependsOn(checksMain)
        jvmMain.dependencies {
            implementation(if (linuxStage) compose.desktop.linux_x64 else compose.desktop.currentOs)
            // The Linux engine checks (linux/LinuxChecks.kt) assert with kotlin.test, in the app itself, as iOS's do.
            implementation(kotlin("test"))
            // Dispatchers.Main on desktop, where the runtime's scope runs.
            implementation(libs.coroutines.swing)
        }
        androidMain.dependencies {
            // ComponentActivity.setContent, and the document picker's activity-result contract.
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
        }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            // kotlinx.browser / org.w3c, which the wasm stdlib no longer has.
            dependencies { implementation(libs.kotlinx.browser) }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.supermux.webview.sample.MainKt"
        jvmArgs += listOf("-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
        // `./gradlew :webview-sample:run -Dwebview.sample.engine=wpe`: the pages on WPE WebKit (Linux, its runtime installed).
        System.getProperty("webview.sample.engine")?.let { jvmArgs += "-Dwebview.sample.engine=$it" }
        // A release build is shrunk by ProGuard, which reads no rules from library jars: the library's are added here, as
        // any app's build adds them (its README, macOS). device-checks/mac-release-check.sh runs the packaged app.
        buildTypes.release.proguard {
            configurationFiles.from(project(":webview-compose").file("src/jvmMain/resources/META-INF/proguard/webview-compose-desktop.pro"))
        }
        // jpackage takes one to three integers, and macOS a major version of 1 or more: not 0.1.0-dev.1.
        nativeDistributions { packageVersion = "1.0.0" }
    }
}

// The sample's jar and its runtime classpath in build/linux/lib, for device-checks/linux-engine-checks.sh. Run it with
// -Pwebview.sample.linux, so the classpath carries Linux x64's skiko whatever host builds it.
val stageLinux by tasks.registering(Sync::class) {
    group = "webview-sample"
    description = "Copies the sample's jar and runtime classpath to build/linux/lib (with -Pwebview.sample.linux)."
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("linux/lib"))
}

// The desktop sample as the Mac's scripts start it (plan Task 13): its jar and runtime classpath in build/mac-app/lib,
// started as `java -cp 'lib/*' dev.supermux.webview.sample.MainKt` by device-checks/mac-*.sh in a transient Aqua
// LaunchAgent, never through Gradle: an SSH session has no window server. The library's jar carries the macOS glue.
val installMacApp by tasks.registering(Sync::class) {
    description = "Copies the desktop sample's jar and runtime classpath to build/mac-app/lib."
    from(tasks.named("jvmJar"))
    from(configurations.named("jvmRuntimeClasspath"))
    into(layout.buildDirectory.dir("mac-app/lib"))
}

android {
    namespace = "dev.supermux.webview.sample"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        // Its own id: installing the sample never touches another dev.supermux app.
        applicationId = "dev.supermux.webview.sample"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = version.toString()
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // Debug-signed: this APK is never distributed.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    // Compose UI tests drive one scene at a time.
    maxParallelForks = 1
    // A fixed heap, so a run's GC doesn't depend on the machine's free memory.
    jvmArgs("-XX:+UseG1GC", "-Xms512m", "-Xmx2g")
    // The scripted scrolls take a while; a hung test fails the task instead of holding the build.
    timeout.set(Duration.ofMinutes(15))
    // Never up to date: the perf lab's numbers come from a run that happened.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed")
        // The `perf-lab` lines are the measurements.
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
