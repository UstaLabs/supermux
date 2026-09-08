plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.compiler)
    // The `org.jetbrains.compose` plugin, even though this module declares no Compose resources of
    // its own and needs no `compose.` DSL (every artifact arrives through `api(project(":ui"))`).
    //
    // It is here for ONE reason: `syncComposeResourcesForIos`. Compose Multiplatform copies a
    // dependency's `composeResources` — for us `:ui`'s Geist fonts — into the app bundle from a
    // hook it installs on the FRAMEWORK module's `embedAndSignAppleFrameworkForXcode`, and that
    // hook only exists where this plugin is applied. Without it the framework still links and the
    // app still launches; the fonts are simply absent and every screen falls back to the system
    // face, with no error anywhere. H1 left the plugin off and hit exactly that.
    alias(libs.plugins.compose.multiplatform)
    // Swift-friendly Kotlin: sealed classes become enums, suspend functions become async, flows
    // become AsyncSequence. Same version `:shared` uses; the phone links ONE framework, so the
    // SKIE-generated Swift lives in this one.
    alias(libs.plugins.skie)
}

// SupermuxKit — the ONE Kotlin framework the iOS app links (cluster H1).
//
// It re-exports `:ui` and `:shared`, so Swift sees the shared Compose root, the stores and the
// models through a single binary. Linking `Shared.framework` alongside it would embed the `:shared`
// klib TWICE — two Kotlin runtimes, two copies of every object, and state that silently does not
// agree with itself. The watch app is a separate binary and keeps its own `Shared.framework`.
//
// iOS targets only. There is nothing here a JVM or an Android build could use: it is UIKit glue.
kotlin {
    // expect/actual classes are stable-in-practice but flagged Beta; acknowledge it, as `:shared`
    // does — `:ui`'s VncFramebuffer is an expect CLASS and this module compiles against it.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SupermuxKit"
            // Dynamic, like `Shared.framework`: a static framework would have to be linked into
            // every extension that touches it, and the notification extensions must NOT pull the
            // Compose runtime in (APPLICATION_EXTENSION_API_ONLY).
            isStatic = false
            // `export` is what puts the two modules' public API in the framework's headers. Without
            // it Swift can call MainViewController() and nothing else.
            export(project(":ui"))
            export(project(":shared"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: `export` above only works on an `api` dependency.
            api(project(":ui"))
            api(project(":shared"))
        }
    }
}
