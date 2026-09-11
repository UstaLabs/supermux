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

// Ceiling on the gzipped download (spec §8): app + skiko wasm + loader js. Catches accidental bloat.
val maxGzipBytes = 6L * 1024 * 1024

fun sha8(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)

fun gzipSize(bytes: ByteArray): Long {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.size().toLong()
}

val stageForBroker by tasks.registering {
    group = "distribution"
    description = "Build the wasm bundle and stage it (content-hashed) into src/channels/web/static"
    dependsOn(tasks.named("wasmJsBrowserDistribution"))
    inputs.dir(distDir)
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

        // Guards, BEFORE anything is published.
        val gz = assets.listFiles()!!.filter { it.extension == "wasm" || it.extension == "js" || it.extension == "mjs" }
            .sumOf { gzipSize(it.readBytes()) }
        check(gz <= maxGzipBytes) { "web bundle gzip total ${gz / 1024} KB exceeds the ${maxGzipBytes / 1024} KB ceiling" }
        check(staging.resolve("index.html").exists()) { "index.html missing from the staged bundle" }

        // Publish: wipe whatever the previous build (Vite or ours) left — the broker reads disk-first.
        out.deleteRecursively()
        out.mkdirs()
        staging.copyRecursively(out, overwrite = true)
        println("stageForBroker: ${renames.size} hashed assets, gzip total ${gz / 1024} KB → $out")
    }
}
