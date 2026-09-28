import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

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

// ── The native editor's syntax module (M5) ───────────────────────────────────────────────────
//
// Same reason as the terminal's pair above (editor-syntax/native/README.md, "Serving it"): the
// Kotlin/Wasm toolchain does not copy `:editor-syntax`'s klib resources next to this module, so the
// loader and its verified wasm are re-exported as this app's own resources. webpack then emits the
// wasm from the loader's `new URL("./supermux-syntax.wasm", import.meta.url)` and `stageForBroker`
// hashes it into `assets/` like every other binary.
//
// The code-only grammars' tables (29 `.sesz` blobs, 5.4 MB) are fetched BY NAME, so they cannot be
// content-hashed one by one. They are NOT resources (the dist root would hold them under a stable
// path with a no-cache rule): `stageForBroker` copies them straight from `:editor-syntax:stageTables`
// into `assets/editor-syntax/tables/<digest>/`, where the directory changes with the grammar
// versions and the `/assets/` immutable rule is safe. `generateSyntaxAssetsKotlin` writes the same
// directory into the app ([SYNTAX_TABLES_DIR]) for `WasmBackend.load(tablesUrl = …)`.
val syntaxProject = project(":editor-syntax")
val syntaxWasmResources: File = syntaxProject.layout.buildDirectory.dir("generated/wasmResources").get().asFile
val syntaxTablesDir: File = syntaxProject.layout.buildDirectory.dir("generated/tables/editor-syntax/tables").get().asFile

val syntaxWasmAssetsDir = layout.buildDirectory.dir("generated/syntaxWasmAssets")
val stageSyntaxWasmAssets by tasks.registering(Copy::class) {
    description = "Re-export syntax-loader.mjs + supermux-syntax.wasm as this app's wasmJs resources."
    dependsOn(":editor-syntax:stageWasmResources")
    from(syntaxWasmResources)
    from(syntaxProject.file("src/wasmJsMain/resources/syntax-loader.mjs"))
    into(syntaxWasmAssetsDir)
    // A host without the Mac-built module (Linux dev, the CI lanes, the Docker build) still gets a
    // bundle: webpack only has to RESOLVE the loader's `new URL("./supermux-syntax.wasm", …)`, so an
    // empty placeholder stands in; `WasmBackend.load` then fails to compile it and every editor
    // opens files as plain text. `stageForBroker` refuses the placeholder for a release
    // (SUPERMUX_REQUIRE_EDITOR_SYNTAX=1 / -Peditor.requireSyntax=true).
    doLast {
        val wasm = syntaxWasmAssetsDir.get().asFile.resolve("supermux-syntax.wasm")
        if (!wasm.isFile) {
            logger.warn("web: no supermux-syntax.wasm (apps/editor-syntax/native/wasm/build.sh): staging an empty placeholder, the editor will show plain text")
            wasm.writeBytes(ByteArray(0))
        }
    }
}
kotlin.sourceSets.getByName("wasmJsMain").resources.srcDir(stageSyntaxWasmAssets)
kotlin.sourceSets.getByName("wasmJsTest").resources.srcDir(stageSyntaxWasmAssets)

/** The tables' digest: every blob's name and bytes, in name order (the directory changes with any of them). */
fun syntaxTablesDigest(dir: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    dir.listFiles().orEmpty().filter { it.isFile && it.extension == "sesz" }.sortedBy { it.name }.forEach { f ->
        md.update(f.name.toByteArray())
        md.update(0)
        md.update(f.readBytes())
    }
    return md.digest().joinToString("") { "%02x".format(it) }.take(16)
}

val syntaxAssetsKotlinDir = layout.buildDirectory.dir("generated/syntaxAssetsKotlin")
val generateSyntaxAssetsKotlin by tasks.registering {
    description = "Write the grammar tables' served directory (assets/editor-syntax/tables/<digest>/) into the app."
    dependsOn(":editor-syntax:stageTables")
    inputs.dir(syntaxTablesDir).optional()
    outputs.dir(syntaxAssetsKotlinDir)
    doLast {
        val out = syntaxAssetsKotlinDir.get().asFile.resolve("dev/supermux/web/editor").apply { deleteRecursively(); mkdirs() }
        val digest = syntaxTablesDigest(syntaxTablesDir)
        out.resolve("SyntaxAssets.kt").writeText(
            """
            |// Generated by :web:generateSyntaxAssetsKotlin from :editor-syntax:stageTables. Do not edit.
            |package dev.supermux.web.editor
            |
            |/** Where `stageForBroker` puts the code-only grammars' tables (relative to the page). */
            |internal const val SYNTAX_TABLES_DIR: String = "assets/editor-syntax/tables/$digest/"
            |""".trimMargin(),
        )
    }
}
kotlin.sourceSets.getByName("wasmJsMain").kotlin.srcDir(generateSyntaxAssetsKotlin)

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

// Ceiling on the gzipped download (spec §8): the wasm/js/mjs files directly under assets/ — which
// is what the guard below actually sums, top level only, so fonts and composeResources are outside
// it.
//
// RE-MEASURED 2026-09-23, after the Ghostty terminal replaced xterm.js (numbers from this task's
// own `stageForBroker` run, which prints the same total it checks):
//
//     skiko.wasm                3.18 MiB   the immovable floor
//     supermux-apps-web.wasm    3.00 MiB   all of `:ui` + `:shared` (was 2.58)
//     supermux-terminal.wasm    0.27 MiB   the engine, in place of ~0.10 MiB of xterm.js
//     app.js                    0.12 MiB   the webpack loader (was 0.20)
//     terminal-loader.mjs      ~0.00 MiB
//     ------------------------------------
//     total                     6.57 MiB   (6724 KiB)
//
// So the headroom under the 8 MiB ceiling is 1.43 MiB, not the ~2 MiB the previous note claimed:
// the cutover cost ~0.6 MiB net, most of it in the app wasm rather than in the engine module. It
// remains a bloat catch, NOT a target to grow into — one more feature the size of this one would
// put the ceiling in reach.
//
// NEITHER ARE THE FONTS, and that is now worth a number rather than a clause. `assets.listFiles()`
// below is top level only, so everything under `assets/composeResources/` is outside this ceiling
// by construction:
//
//     geist * 6 (`:ui`, the app's type scale)        810 KB raw   369 KiB gzip
//     jetbrains_mono * 3 (`:terminal-compose`)       829 KB raw   382 KiB gzip   ← added 2026-09-24
//     -------------------------------------------------------------------------
//     fonts, first load                            1 638 KB raw   751 KiB gzip
//
// The terminal's three faces are NEW (the surface used to ask the platform for
// `FontFamily.Monospace`, which resolves to nothing in the browser — see
// `terminal-compose/TerminalFont.kt`). They are fetched when a terminal first mounts, not with the
// shell, and then served `immutable` like every other hashed asset. They add NOTHING to the sum
// this guard checks; the figures above are measured on the files themselves (`gzip -9`), so the
// headroom under the 8 MiB ceiling is still the 1.43 MiB measured on 2026-09-23 plus whatever the
// Kotlin of that change costs in the app wasm — re-read the `stageForBroker:` line of the next
// staged build for the exact total.
val maxGzipBytes = 8L * 1024 * 1024

// THE NATIVE EDITOR'S SYNTAX MODULE IS OUTSIDE THAT CEILING, with a ceiling of its own (M5).
// `supermux-syntax.wasm` is 7.45 MB raw / 2.69 MB gzipped (editor-syntax/native/README.md): counted
// with the shell it would leave -1.2 MiB of the 1.43 MiB headroom above. It is also not part of the
// shell's download: `WasmBackend.load` fetches it the first time an editor opens a file, never at
// page load. So it is measured on
// its own against [maxSyntaxGzipBytes] (today's 2.56 MiB plus room for a few grammars), and the
// guard fails if it is missing rather than silently counting nothing. The file reaches the dist
// twice (the resource copy that lets webpack resolve the loader's URL, and webpack's emitted
// asset): both copies are identified by CONTENT, only one is fetched, one is counted.
//
// The grammar tables (`assets/editor-syntax/tables/<digest>/`, 29 blobs, 5.4 MB, already
// zlib-compressed) are fetched one by one when a file of that language first opens; they sit in a
// sub-directory, so the top-level sum never saw them, and they get their own raw ceiling.
val maxSyntaxGzipBytes = 3L * 1024 * 1024 + 512 * 1024
/** A release refuses a web client without the syntax module (release.yml sets the variable). */
val requireEditorSyntax: Boolean =
    System.getenv("SUPERMUX_REQUIRE_EDITOR_SYNTAX") == "1" ||
        providers.gradleProperty("editor.requireSyntax").orNull?.toBoolean() == true
val maxSyntaxTablesBytes = 7L * 1024 * 1024

fun sha8(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)

fun gzipSize(bytes: ByteArray): Long {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.size().toLong()
}

// The PWA shell: `sw.js`, `manifest.webmanifest`, `favicon.ico` and `icons/`. Committed (no build
// step) and staged to the ROOT of the served tree, outside `assets/` — and emphatically NOT `src/wasmJsMain/resources/`: the hashing pass above renames every
// .js in the dist root, and a service worker that moves to `assets/sw-<hash>.js` has neither its
// registered URL nor its `/` scope any more.
val pwaDir = layout.projectDirectory.dir("pwa")

val stageForBroker by tasks.registering {
    group = "distribution"
    description = "Build the wasm bundle and stage it (content-hashed) into src/channels/web/static"
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.dir(distDir)
    // `.optional()`: a missing `pwa/` must fail in the task action with the explanatory check
    // below, not as an opaque Gradle snapshotting error.
    inputs.dir(pwaDir).withPropertyName("pwa").optional()
    inputs.property("maxGzipBytes", maxGzipBytes)
    inputs.property("maxSyntaxGzipBytes", maxSyntaxGzipBytes)
    inputs.property("requireEditorSyntax", requireEditorSyntax)
    inputs.dir(syntaxTablesDir).withPropertyName("syntaxTables").optional()
    inputs.dir(syntaxWasmResources).withPropertyName("syntaxWasm").optional()
    dependsOn(":editor-syntax:stageTables")
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

        // The native editor's syntax module and grammar tables. Required for a release; elsewhere a
        // host without the Mac-built natives ships the placeholder (plain-text editors), said loudly.
        val requireSyntax = requireEditorSyntax
        val syntaxWasm = syntaxWasmResources.resolve("supermux-syntax.wasm")
        val tables = syntaxTablesDir.listFiles().orEmpty().filter { it.isFile && it.extension == "sesz" }
        val haveSyntax = syntaxWasm.isFile && syntaxWasm.length() > 0 && tables.isNotEmpty()
        if (!haveSyntax) {
            val why = "no verified supermux-syntax.wasm and grammar tables (apps/editor-syntax/native/build.sh all, " +
                "then :editor-syntax:stageWasmResources :editor-syntax:stageTables)"
            check(!requireSyntax) { "$why: a release web client must carry them (SUPERMUX_REQUIRE_EDITOR_SYNTAX)" }
            logger.warn("stageForBroker: $why — the web editor will open every file as plain text")
        }
        // The tables, under a directory named by their digest (the digest `generateSyntaxAssetsKotlin`
        // compiled into the app).
        if (tables.isNotEmpty()) {
            val tablesOut = assets.resolve("editor-syntax/tables/${syntaxTablesDigest(syntaxTablesDir)}").apply { mkdirs() }
            tables.forEach { it.copyTo(tablesOut.resolve(it.name), overwrite = true) }
        }
        val tablesBytes = tables.sumOf { it.length() }
        check(tablesBytes <= maxSyntaxTablesBytes) {
            "grammar tables total ${tablesBytes / 1024} KB exceed their ${maxSyntaxTablesBytes / 1024} KB ceiling"
        }

        // Guards, BEFORE anything is published. The syntax module's copies are recognised by
        // CONTENT (webpack renames its own), the placeholder by being empty.
        val syntaxSha = if (haveSyntax) MessageDigest.getInstance("SHA-256").digest(syntaxWasm.readBytes()) else null
        fun isSyntaxWasm(f: File) = f.extension == "wasm" && (
            f.length() == 0L ||
                (syntaxSha != null && f.length() == syntaxWasm.length() && MessageDigest.getInstance("SHA-256").digest(f.readBytes()).contentEquals(syntaxSha))
            )
        val topLevel = assets.listFiles()!!.filter { it.isFile && (it.extension == "wasm" || it.extension == "js" || it.extension == "mjs") }
        val (syntaxCopies, shell) = topLevel.partition(::isSyntaxWasm)
        check(!haveSyntax || syntaxCopies.any { it.length() > 0 }) {
            "supermux-syntax.wasm is not in the staged assets: the editor would have no syntax module"
        }
        val gz = shell.sumOf { gzipSize(it.readBytes()) }
        check(gz <= maxGzipBytes) { "web bundle gzip total ${gz / 1024} KB exceeds the ${maxGzipBytes / 1024} KB ceiling" }
        val syntaxGz = syntaxCopies.maxOfOrNull { if (it.length() > 0) gzipSize(it.readBytes()) else 0L } ?: 0L
        check(syntaxGz <= maxSyntaxGzipBytes) {
            "supermux-syntax.wasm gzip ${syntaxGz / 1024} KB exceeds its ${maxSyntaxGzipBytes / 1024} KB ceiling"
        }
        check(staging.resolve("index.html").exists()) { "index.html missing from the staged bundle" }
        check(staging.resolve("sw.js").exists() && staging.resolve("icons/icon-192.png").exists()) {
            "PWA shell missing from the staged tree"
        }

        // Publish: wipe whatever the previous build (Vite or ours) left — the broker reads disk-first.
        out.deleteRecursively()
        out.mkdirs()
        staging.copyRecursively(out, overwrite = true)
        println(
            "stageForBroker: ${renames.size} hashed assets, gzip total ${gz / 1024} KB → $out " +
                "(outside it, loaded when an editor opens: syntax module ${syntaxGz / 1024} KB gzip, " +
                "${tables.size} grammar tables ${tablesBytes / 1024} KB)",
        )
    }
}
