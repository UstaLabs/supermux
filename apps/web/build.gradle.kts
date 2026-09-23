import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

// The iframe bridge shim, copied where the Karma test can FETCH it. `EditorBridgeIframeTest` builds
// its stub editor page out of the real file, so the shim and the engine can never drift apart
// unnoticed — an inlined copy in the test would assert against itself. Karma serves the test
// compilation's processed resources under the run's base path; `karma.config.d/editor-shim.js`
// registers this one file and proxies it to `/editor-shim.js`, which is what the test fetches.
val editorShimTestResourceDir = layout.buildDirectory.dir("editorShimTestResource")

// The browser host of the shared Compose app. Thin by design, like apps/ios: entry point,
// WebPlatform + browser actuals, and the packaging that puts the bundle where the broker serves it.
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
            // Browser-only tests (URL sync, localStorage stores, the cookie bootstrap) run in
            // headless Chrome through Karma. CHROME_BIN must point at a WasmGC-capable Chrome;
            // this host has /usr/bin/google-chrome. `:shared`/`:ui` keep their wasm test task off.
            testTask {
                useKarma { useChromeHeadless() }
            }
            // Karma serves the engine `.wasm` as `application/wasm` — see
            // web/karma.config.d/terminal-wasm.js, which is why the streaming compile the broker
            // serves is the one the tests take too.
        }
        // No `applyBinaryen()` here on purpose: on KGP 2.3.x calling it is a hard ERROR
        // ("Binaryen is enabled by default. This call is redundant. Scheduled for removal in
        // Kotlin 2.3."). wasm-opt already runs over the production binary.
        binaries.executable()
    }

    sourceSets {
        wasmJsMain {
            // `js("…")`, external declarations and JsAny are all still behind this opt-in in 2.3.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                implementation(project(":ui"))
                implementation(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                // kotlinx.browser / org.w3c — no longer in the wasm stdlib.
                implementation(libs.kotlinx.browser)
            }
        }
        wasmJsTest {
            // `EditorBridgeIframeTest` mounts the REAL `editor/editor-shim.js` in its stub frame
            // rather than a copy of it — see [editorShimTestResource] below.
            resources.srcDir(editorShimTestResourceDir)
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.coroutines.test)
                // MockEngine: the browser-side bootstrap tests drive BrokerApi without a broker.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

// ── The terminal engine's browser assets ────────────────────────────────────────────────────
//
// MEASURED (terminal-core/consumer-smoke, 2026-09-22) and hit here for real in Plan 4 Task 4: the
// Kotlin/Wasm toolchain does NOT copy a DEPENDENCY klib's resources next to the consumer's
// compiled module, so webpack fails the whole bundle with
//   Module not found: Error: Can't resolve './terminal-loader.mjs'
// — and it fails even though nothing in the app has run yet, because `@file:JsModule` is resolved
// at BUNDLE time. Every browser host of terminal-core therefore re-exports the two files the
// package ships inside its klib as its own wasmJs resources; `:terminal-sample` does the same, and
// this is the line that made `:web` a browser host of it.
//
// It is also what makes the DEFAULT wasm URL correct. Once `supermux-terminal.wasm` sits beside
// the loader, webpack recognises the loader's `new URL("./supermux-terminal.wasm",
// import.meta.url)` and emits the binary as an asset, `stageForBroker` content-hashes it into
// `assets/` with everything else and rewrites the reference — so no host has to pass
// `wasmAssetUrl`, and there is no second place for the hash to go stale.
//
// The bytes come from `:terminal-core:stageWasmResources`, the task that verifies
// `build/wasm/supermux-terminal.wasm` against its manifest (sha256 + size + ABI) before staging.
val terminalCoreWasmResources: File =
    project(":terminal-core").projectDir.resolve("build/gradle/generated/wasmResources")

val stageTerminalWasmAssets by tasks.registering(Copy::class) {
    description = "Re-export terminal-loader.mjs + supermux-terminal.wasm as this app's wasmJs resources."
    dependsOn(":terminal-core:stageWasmResources")
    from(terminalCoreWasmResources)
    into(layout.buildDirectory.dir("generated/terminalWasmAssets"))
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageTerminalWasmAssets)
kotlin.sourceSets.getByName("wasmJsTest").resources.srcDir(stageTerminalWasmAssets)

// `supermux-terminal.wasm` reaches the distribution TWICE, and that is the design working, not a
// mistake: once as the wasmJs resource above (which is what lets webpack RESOLVE the loader's
// `new URL(...)` at bundle time) and once as the asset webpack EMITS from that same URL. Same
// file, same bytes, two producers — so the distribution copy has to be told that a duplicate is
// expected instead of failing the build with "no duplicate handling strategy has been set".
// EXCLUDE rather than INCLUDE: with identical bytes either is correct, and keeping the first
// makes the outcome independent of the order the copy happens to visit its sources in.
tasks.named<Sync>("wasmJsBrowserDistribution") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// The same two environment facts `:terminal-core` and `:terminal-sample` need, now that `:web`'s
// bundle carries the engine and its browser tests load a `.wasm` too. Without CHROME_BIN the task
// fails on a host that has Chrome under a name Karma does not guess; with a D-Bus session bus,
// headless Chrome can stall every http(s) navigation on a headless host (file: URLs still load),
// which hangs Karma's capture.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    if (System.getenv("CHROME_BIN") == null && File("/usr/bin/google-chrome").canExecute()) {
        environment("CHROME_BIN", "/usr/bin/google-chrome")
    }
    environment("DBUS_SESSION_BUS_ADDRESS", "disabled:")
}

// ── Staging for the broker ──────────────────────────────────────────────────────────────────
//
// The broker serves src/channels/web/static disk-first with `/assets/*` immutable and everything
// else no-cache (src/channels/web/static-serve.ts). So: every .js/.wasm goes under assets/ with a
// content hash in its name and every reference to it is rewritten; index.html stays at the root.

val brokerStaticDir: File = rootProject.projectDir.resolve("../src/channels/web/static")
val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")

// Ceiling on the gzipped download (spec §8): app + skiko wasm + loader js under assets/.
// MEASURED on this branch, gzipped: skiko.wasm 3.18 MiB (the immovable floor), the app wasm — all
// of `:ui` + `:shared` — 2.58 MiB, the webpack loader app.js 0.20 MiB; 5.94 MiB (6085 KiB) total.
// 8 MiB is a bloat catch with roughly 2 MiB of headroom — NOT a target to grow into.
//
// The terminal's weight MOVED in Plan 4 rather than vanishing: the 0.10 MiB of xterm.js counted
// above went with the DOM renderer, and the engine is now `supermux-terminal.wasm`, emitted as its
// own hashed asset under assets/ — so it is inside this ceiling too, and the figures above predate
// it. Re-measure before reading the headroom as spare.
//
// The staged `editor/` bundle (CodeMirror, 1.3 MB raw) sits outside assets/ and is deliberately not
// counted: it is a separate, lazily-loaded page.
val maxGzipBytes = 8L * 1024 * 1024

fun sha8(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)

fun gzipSize(bytes: ByteArray): Long {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.size().toLong()
}

// Extra sources `stageForBroker` copies in beside the webpack dist. Hoisted out of the task action
// so they can be declared as INPUTS: editing the CodeMirror bundle must re-run the task, not leave
// a stale copy published under an up-to-date check.
//
// The editor bundle's single source of truth is the committed android assets dir (desktop reads
// the same files). There is no stylesheet to lift out of KGP's yarn workspace any more — the
// terminal was the only npm package this app had, and the Compose renderer needs no CSS.
val editorSrcDir: File = rootProject.projectDir.resolve("android/src/main/assets/editor")

// NOT under `src/wasmJsMain/resources/`: everything there is copied to the webpack dist root, where
// the hashing pass below would rename it into `assets/editor-shim-<hash>.js` and rewrite its bare
// name inside app.js — the iframe page would then ask for a file that no longer exists at that name.
// This lives outside the Kotlin source set precisely so the build treats it as a plain data file.
val editorShimFile: File = layout.projectDirectory.file("editor/editor-shim.js").asFile

// The PWA shell: `sw.js`, `manifest.webmanifest`, `favicon.ico` and `icons/`. Committed (no build
// step) and staged to the ROOT of the served tree, outside `assets/` — same reason as the editor
// bundle and emphatically NOT `src/wasmJsMain/resources/`: the hashing pass above renames every
// .js in the dist root, and a service worker that moves to `assets/sw-<hash>.js` has neither its
// registered URL nor its `/` scope any more.
val pwaDir = layout.projectDirectory.dir("pwa")

val stageForBroker by tasks.registering {
    group = "distribution"
    description = "Build the wasm bundle and stage it (content-hashed) into src/channels/web/static"
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.dir(distDir)
    // `files(...).optional()` rather than `file(...)`: a missing input must fail in the task action
    // with its own explanatory message, not as an opaque Gradle snapshotting error. The shim is
    // genuinely optional until task 4 creates it.
    inputs.dir(editorSrcDir).withPropertyName("editorBundle")
    inputs.files(editorShimFile).withPropertyName("editorShim").optional()
    // `.optional()` like the editor shim above: a missing `pwa/` must fail in the task action
    // with the explanatory check below, not as an opaque Gradle snapshotting error.
    inputs.dir(pwaDir).withPropertyName("pwa").optional()
    inputs.property("maxGzipBytes", maxGzipBytes)
    outputs.dir(brokerStaticDir)

    doLast {
        val src = distDir.get().asFile
        val out = brokerStaticDir
        // The publish below deletes `out` wholesale, and `out` is a relative hop out of the Gradle
        // root into the Bun repo. Prove it still points at the broker's web channel before anyone
        // reorganises either tree and this quietly wipes the wrong directory.
        check(out.parentFile.resolve("static-serve.ts").isFile) {
            "brokerStaticDir resolved to $out, which is not the broker's web channel dir"
        }

        // Stage into build/ first and publish at the end: the size guard must be able to FAIL
        // without having already replaced what the broker is serving.
        val staging = layout.buildDirectory.dir("brokerStage").get().asFile
        staging.deleteRecursively()
        staging.mkdirs()
        val assets = staging.resolve("assets").apply { mkdirs() }

        // Pass 1: hash every binary/script asset, remember old→new names.
        val renames = linkedMapOf<String, String>()
        val hashable = src.walkTopDown()
            .filter { it.isFile && (it.extension == "wasm" || it.extension == "js" || it.extension == "mjs") }
            .toList()
        // wasm first: js files reference wasm names, and the rewrite below changes js content (and thus its hash).
        val wasmFiles = hashable.filter { it.extension == "wasm" }
        val jsFiles = hashable.filter { it.extension != "wasm" }

        fun stage(file: File, content: ByteArray): String {
            val hashed = "${file.nameWithoutExtension}-${sha8(content)}.${file.extension}"
            assets.resolve(hashed).writeBytes(content)
            // `renames` is keyed by BASE name because that is how the bundle references these files.
            // Two dist files sharing one base name would collide here, and the rewrite would silently
            // point every reference at whichever won — fail instead.
            check(renames.put(file.name, "assets/$hashed") == null) { "duplicate asset basename: ${file.name}" }
            return hashed
        }
        wasmFiles.forEach { stage(it, it.readBytes()) }

        fun rewrite(text: String, relativeTo: String): String {
            var t = text
            for ((old, new) in renames) {
                // References are bare file names ("app.wasm", "./skiko.wasm") relative to the script.
                val target = if (relativeTo == "assets") new.removePrefix("assets/") else new
                // Word-boundary-ish guard: never match inside a longer identifier or path segment
                // (so "my-app.wasm" or "vendor/app.wasm" are left alone), and never a name that is
                // already the hashed one. "./" prefixes are consumed so the result stays relative.
                t = t.replace(Regex("(?<![\\w.\\-/])(?:\\./)?" + Regex.escape(old) + "(?![\\w.\\-])")) { target }
            }
            return t
        }
        jsFiles.forEach { f -> stage(f, rewrite(f.readText(), "assets").toByteArray()) }

        // Pass 2: everything else. index.html gets its references rewritten and stays at the root
        // (no-cache, so a redeploy is picked up immediately). Compose's resource tree moves UNDER
        // assets/ to inherit the immutable cache rule — Main.kt's `configureWebResources
        // { resourcePathMapping { "assets/$it" } }` is the other half of that and MUST agree.
        src.walkTopDown().filter { it.isFile && it !in hashable }.forEach { f ->
            val rel = f.relativeTo(src).invariantSeparatorsPath
            val dst = if (rel.startsWith("composeResources/")) staging.resolve("assets/$rel") else staging.resolve(rel)
            dst.parentFile.mkdirs()
            if (f.name == "index.html") dst.writeText(rewrite(f.readText(), "")) else f.copyTo(dst, overwrite = true)
        }

        // The CodeMirror editor bundle, staged at `editor/` in the ROOT, not under assets/: the page
        // references `cm6.js` by a relative bare name, so content-hashing would break it. 1.3 MB
        // revalidated per editor open is acceptable (plan 5 may hash the pair together). Being
        // outside assets/ also keeps it out of the hashing pass AND out of the gzip guard.
        check(editorSrcDir.resolve("index.html").isFile && editorSrcDir.resolve("cm6.js").isFile) {
            "editor bundle missing from $editorSrcDir"
        }
        val editorOut = staging.resolve("editor").apply { mkdirs() }
        editorSrcDir.resolve("cm6.js").copyTo(editorOut.resolve("cm6.js"), overwrite = true)
        // The iframe shim republishes the bundle's `window.AndroidEditor` / webkit hooks as
        // postMessage to the parent frame. It arrives in a later task of this plan; until then the
        // editor page is staged exactly as Android ships it.
        val editorHtml = editorSrcDir.resolve("index.html").readText()
        editorOut.resolve("index.html").writeText(
            if (editorShimFile.isFile) {
                editorShimFile.copyTo(editorOut.resolve("editor-shim.js"), overwrite = true)
                // Must load BEFORE cm6.js: the bundle looks its host objects up at evaluation time.
                val injected = editorHtml.replace(
                    "<script src=\"cm6.js\">",
                    "<script src=\"editor-shim.js\"></script><script src=\"cm6.js\">",
                )
                // A silent no-op here ships an editor whose bridge is never installed, and the only
                // symptom is an iframe that never reports ready. Fail the build instead.
                check(injected != editorHtml) {
                    "editor/index.html no longer contains `<script src=\"cm6.js\">` — the shim injection point moved"
                }
                injected
            } else {
                editorHtml
            }
        )

        // The PWA shell, copied verbatim into the staged root: `sw.js` must be served from `/`
        // (its registration scope), the manifest and favicon are linked by bare name from
        // index.html, and `icons/` is referenced absolutely (`/icons/icon-192.png`) by both the
        // manifest and the push notifications the worker shows. Outside `assets/`, so nothing here
        // is hashed and none of it counts against the gzip ceiling.
        check(pwaDir.asFile.resolve("sw.js").isFile && pwaDir.asFile.resolve("manifest.webmanifest").isFile) {
            "PWA shell missing from ${pwaDir.asFile} (expected sw.js + manifest.webmanifest)"
        }
        // The webpack dist's own index.html is the app shell; a stray one here would silently
        // overwrite it in the copy below and serve a page with no bundle.
        check(!pwaDir.asFile.resolve("index.html").exists()) {
            "${pwaDir.asFile}/index.html would overwrite the staged app shell — remove it"
        }
        pwaDir.asFile.copyRecursively(staging, overwrite = true)

        // Guards, BEFORE anything is published.
        val gz = assets.listFiles()!!.filter { it.extension == "wasm" || it.extension == "js" || it.extension == "mjs" }
            .sumOf { gzipSize(it.readBytes()) }
        check(gz <= maxGzipBytes) { "web bundle gzip total ${gz / 1024} KB exceeds the ${maxGzipBytes / 1024} KB ceiling" }
        check(staging.resolve("index.html").exists()) { "index.html missing from the staged bundle" }
        check(staging.resolve("editor/index.html").exists()) { "editor bundle missing from the staged tree" }
        check(staging.resolve("sw.js").exists() && staging.resolve("icons/icon-192.png").exists()) {
            "PWA shell missing from the staged tree"
        }

        // Publish: wipe whatever the previous build (Vite or ours) left — the broker reads disk-first.
        out.deleteRecursively()
        out.mkdirs()
        staging.copyRecursively(out, overwrite = true)
        println("stageForBroker: ${renames.size} hashed assets, gzip total ${gz / 1024} KB → $out")
    }
}

val editorShimTestResource by tasks.registering(Copy::class) {
    description = "Stage editor/editor-shim.js as a wasmJsTest resource so Karma can serve it"
    from(editorShimFile)
    into(editorShimTestResourceDir)
}

tasks.named("wasmJsTestProcessResources") { dependsOn(editorShimTestResource) }
