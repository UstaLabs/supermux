import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// THROWAWAY (native-editor M0 risk checks). Nothing may depend on this module.

// Where native/build-grammars.sh stages the grammar libraries (one dir per target).
val grammarsDir = layout.projectDirectory.dir("native/out")

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget {
        // Run commonTest ON THE DEVICE as instrumented tests: JNI grammars need a real Android runtime.
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        // ...and keep the host unit-test variant OFF the shared test tree (it cannot load the .so).
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.compilations.getByName("main").cinterops.create("grammars") {
            definitionFile.set(file("src/nativeInterop/cinterop/grammars.def"))
            includeDirs(file("native/include"))
            extraOpts("-libraryPath", grammarsDir.dir(t.konanTarget.name).asFile.absolutePath)
        }
        t.binaries.framework { baseName = "EditorSpike"; isStatic = true }
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        // jvm + android + ios share the ktreesitter implementation.
        val treeSitterNative by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(treeSitterNative)
        androidMain.get().dependsOn(treeSitterNative)
        iosMain.get().dependsOn(treeSitterNative)

        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
            implementation(compose.material3)
        }
        treeSitterNative.dependencies { implementation("io.github.tree-sitter:ktreesitter:0.25.1") }
        commonTest.dependencies { implementation(kotlin("test")) }
        wasmJsMain {
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies { implementation(npm("web-tree-sitter", "0.25.10")) }
        }
        wasmJsTest { languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop") }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "dev.supermux.editor.spike"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The legacy `sourceSets["main"].jniLibs` DSL is unusable under AGP 9 + KMP (see :terminal-core),
// so the grammar .so files go in through the variant API.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(grammarsDir.dir("jniLibs").asFile.absolutePath)
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty(
        "editor.grammars.lib",
        grammarsDir.file("macos_arm64/libeditorgrammars.dylib").asFile.absolutePath,
    )
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    // Headless Chrome can stall every http(s) navigation when it finds a D-Bus session bus.
    environment("DBUS_SESSION_BUS_ADDRESS", "disabled:")
}
