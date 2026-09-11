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
                // The terminal pane. KGP resolves these through its own yarn workspace
                // (build/wasm/node_modules) and webpack bundles them into app.js; the stylesheet is
                // NOT bundled, so `stageForBroker` copies xterm.css to the root of the served tree.
                implementation(npm("@xterm/xterm", "5.5.0"))
                implementation(npm("@xterm/addon-fit", "0.10.0"))
                implementation(npm("@xterm/addon-webgl", "0.18.0"))
            }
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            // MockEngine: the browser-side bootstrap tests drive BrokerApi without a broker.
            implementation(libs.ktor.client.mock)
        }
    }
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
// of `:ui` + `:shared` — 2.58 MiB, the webpack loader app.js 0.20 MiB; 5.94 MiB (6085 KiB) total,
// of which xterm.js is 0.10 MiB — the same tree measured 5981 KiB before task 3 bundled it.
// 8 MiB is a bloat catch with roughly 2 MiB of headroom for the
// remaining panes of this plan — NOT a target to grow into. The staged `editor/` bundle
// (CodeMirror, 1.3 MB raw) sits outside assets/ and is deliberately not counted: it is a separate,
// lazily-loaded page.
val maxGzipBytes = 8L * 1024 * 1024

fun sha8(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)

fun gzipSize(bytes: ByteArray): Long {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.size().toLong()
}

// Extra sources `stageForBroker` copies in beside the webpack dist. Hoisted out of the task action
// so they can be declared as INPUTS: editing the CodeMirror bundle or bumping xterm must re-run the
// task, not leave a stale copy published under an up-to-date check.
//
// xterm.js's stylesheet is a real npm file that webpack never bundles, so it is lifted straight out
// of KGP's yarn workspace. The editor bundle's single source of truth is the committed android
// assets dir (desktop reads the same files).
val xtermCssFile: File = rootProject.layout.buildDirectory
    .file("wasm/node_modules/@xterm/xterm/css/xterm.css").get().asFile
val editorSrcDir: File = rootProject.projectDir.resolve("android/src/main/assets/editor")

// NOT under `src/wasmJsMain/resources/`: everything there is copied to the webpack dist root, where
// the hashing pass below would rename it into `assets/editor-shim-<hash>.js` and rewrite its bare
// name inside app.js — the iframe page would then ask for a file that no longer exists at that name.
// This lives outside the Kotlin source set precisely so the build treats it as a plain data file.
val editorShimFile: File = layout.projectDirectory.file("editor/editor-shim.js").asFile

val stageForBroker by tasks.registering {
    group = "distribution"
    description = "Build the wasm bundle and stage it (content-hashed) into src/channels/web/static"
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.dir(distDir)
    // `files(...).optional()` rather than `file(...)`: a missing input must fail in the task action
    // with its own explanatory message, not as an opaque Gradle snapshotting error. The shim is
    // genuinely optional until task 4 creates it.
    inputs.files(xtermCssFile).withPropertyName("xtermCss").optional()
    inputs.dir(editorSrcDir).withPropertyName("editorBundle")
    inputs.files(editorShimFile).withPropertyName("editorShim").optional()
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

        // xterm.js's stylesheet, at the root of the served tree (no-cache, ~5 KB); index.html links
        // it by name. Deliberately unhashed: cheap to revalidate, and one fewer rewrite rule. Fail
        // loudly if the dependency moved rather than shipping a terminal with no CSS.
        check(xtermCssFile.isFile) { "xterm.css not found at $xtermCssFile — did the npm dependency change?" }
        xtermCssFile.copyTo(staging.resolve("xterm.css"), overwrite = true)

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

        // Guards, BEFORE anything is published.
        val gz = assets.listFiles()!!.filter { it.extension == "wasm" || it.extension == "js" || it.extension == "mjs" }
            .sumOf { gzipSize(it.readBytes()) }
        check(gz <= maxGzipBytes) { "web bundle gzip total ${gz / 1024} KB exceeds the ${maxGzipBytes / 1024} KB ceiling" }
        check(staging.resolve("index.html").exists()) { "index.html missing from the staged bundle" }
        check(staging.resolve("editor/index.html").exists()) { "editor bundle missing from the staged tree" }

        // Publish: wipe whatever the previous build (Vite or ours) left — the broker reads disk-first.
        out.deleteRecursively()
        out.mkdirs()
        staging.copyRecursively(out, overwrite = true)
        println("stageForBroker: ${renames.size} hashed assets, gzip total ${gz / 1024} KB → $out")
    }
}
