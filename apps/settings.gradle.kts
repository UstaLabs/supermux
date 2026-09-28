pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// Toolchain auto-provisioning. Needed by Compose Hot Reload (:desktop:hotRun): its run tasks demand
// a JetBrains Runtime — the JBR's enhanced class redefinition IS the reload mechanism — and no
// stock JDK satisfies that, so without a download resolver the task fails with "No matching
// toolchains found for JVM_VENDOR=JETBRAINS". Only affects toolchain requests that can't be
// satisfied locally; the ordinary jvmToolchain(17)/(21) builds keep using the installed JDKs.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "supermux-apps"
include(":shared")
include(":ui")
include(":android")
include(":desktop")
// SupermuxKit.framework: the ONE Kotlin binary the iOS app links (it re-exports :ui and :shared).
// iOS-only targets, so on this Linux host every one of its tasks is disabled and it costs nothing.
include(":ios")
// The browser client: :ui compiled to Kotlin/Wasm, staged into src/channels/web/static for the
// broker to serve. Replaces the Vue PWA (see docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md).
include(":web")
// The shared terminal engine (libghostty-vt + Kotlin contract). Self-contained: depends on no other
// module here, so it can be published on its own later (dev.supermux.terminal:terminal-core).
include(":terminal-core")
// The shared Compose terminal surface (grid painting, geometry, input) on top of :terminal-core.
// Depends on nothing else here either, so the pair can be published together
// (dev.supermux.terminal:terminal-compose).
include(":terminal-compose")
// The standalone terminal sample + benchmark harness. Depends on :terminal-compose (and through it
// :terminal-core) and on NOTHING else here — that independence IS the check, and it is what lets
// the pair be published on its own.
include(":terminal-sample")
// The native editor's pure-Kotlin core (rope, transactions, extensions). Depends on nothing else here,
// like :terminal-core (docs/superpowers/specs/2026-09-25-native-editor-design.md).
include(":editor-core")
// The native editor's syntax layer: tree-sitter behind an owned ses_* C ABI, grammars as code + table blobs.
include(":editor-syntax")
// The native editor's one Compose surface (layout, drawing, scrolling, pointer, hidden-field IME) on :editor-core.
include(":editor-compose")
// Try the native editor (desktop window + web page) and measure it: depends on :editor-compose and :editor-syntax only.
include(":editor-sample")
// The native editor's first plugin: closing brackets and Enter between braces (spec: editor-plugins/*).
include(":editor-plugins:basics")
// The native editor's M4a plugins: undo/redo, highlighting (the syntax host), folding, view settings.
include(":editor-plugins:history")
include(":editor-plugins:highlight")
include(":editor-plugins:fold")
include(":editor-plugins:view")
// The native editor's M4b plugin: search & replace (engine + panel).
include(":editor-plugins:search")
// The native editor's M4c plugins: autocompletion, lint (diagnostics), the LSP client.
include(":editor-plugins:autocomplete")
