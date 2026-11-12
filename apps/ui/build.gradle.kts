import java.security.MessageDigest

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // ui/nav/Route.kt: @Serializable destinations (Android's type-safe navigation-compose reads the
    // generated serializer's descriptor to build its route strings).
    alias(libs.plugins.serialization)
}

// The pane layer: splits, tab strips, drag, drop zones. It knows nothing about what a pane
// contains — no view kinds, no sessions, no broker, no fonts of its own. Everything content-shaped
// arrives through a slot (see PaneHost's tabSlot / addSlot / emptyGroupSlot).
//
// This is a MATERIAL 3 pane library, deliberately: both consumers already ship material3, so
// staying toolkit-neutral would cost a colour-token abstraction that nothing would use.
//
// jvm() + androidTarget() + the two iOS targets. Desktop consumes the JVM artifact, Android the
// android one, and `:ios` (SupermuxKit.framework) the iOS ones.
// #123: the string catalogs. Compose resources' generated accessors cost ~150 B of wasm per key, and even a key's
// NAME carried as a string literal costs ~5 B gzipped (1,174 names ≈ 6 KB), so the source XML (apps/ui/i18n,
// values/ = English, values-<tag>/) is compiled here: every key gets a small number, `Str.<key>` / `Plurals.<key>`
// are inline getters returning it (no code per key, still checked by the compiler), and each language becomes ONE
// compact file, files/i18n/<lang>.txt, that the app reads once (the web fetches it, cached under VERSION).
val i18nSourceDir = layout.projectDirectory.dir("i18n")
val i18nCatalogOut = layout.projectDirectory.dir("src/commonMain/composeResources/files/i18n")
val generateI18n = tasks.register("generateI18n") {
    val source = i18nSourceDir
    val keysOut = layout.buildDirectory.dir("generated/i18n/kotlin")
    val catalogsOut = i18nCatalogOut
    inputs.dir(source)
    outputs.dir(keysOut)
    outputs.dir(catalogsOut)
    doLast {
        val root = source.asFile
        val comment = Regex("<!--[\\s\\S]*?-->")
        val stringRe = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</string>")
        val pluralsRe = Regex("<plurals\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</plurals>")
        val itemRe = Regex("<item\\s+quantity=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</item>")
        val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
        // As Compose resources read them: XML entities, then \n, \t, \uXXXX, and a backslash before anything else.
        fun unescape(raw: String): String {
            val xml = entity.replace(raw) { m ->
                when (val e = m.groupValues[1]) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                    else -> (if (e.startsWith("#x")) e.drop(2).toInt(16) else e.drop(1).toInt()).toChar().toString()
                }
            }
            val out = StringBuilder()
            var i = 0
            while (i < xml.length) {
                val c = xml[i]
                if (c == '\\' && i + 1 < xml.length) {
                    val n = xml[i + 1]
                    when {
                        n == 'n' -> { out.append('\n'); i += 2 }
                        n == 't' -> { out.append('\t'); i += 2 }
                        n == 'u' && i + 5 < xml.length -> { out.append(xml.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                        else -> { out.append(n); i += 2 }
                    }
                } else { out.append(c); i++ }
            }
            return out.toString()
        }
        // The compact file's own escaping: one entry per line, fields split by tabs.
        fun compact(text: String) = text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
        class Lang(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)
        fun read(dir: java.io.File): Lang {
            val strings = linkedMapOf<String, String>()
            val plurals = linkedMapOf<String, Map<String, String>>()
            dir.listFiles { f -> f.extension == "xml" }!!.sortedBy { it.name }.forEach { f ->
                val xml = comment.replace(f.readText(), "")
                stringRe.findAll(xml).forEach { strings[it.groupValues[1]] = unescape(it.groupValues[2]) }
                pluralsRe.findAll(xml).forEach { p ->
                    plurals[p.groupValues[1]] = itemRe.findAll(p.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
                }
            }
            return Lang(strings, plurals)
        }
        val english = read(root.resolve("values"))
        val stringKeys = english.strings.keys.sorted()
        val pluralKeys = english.plurals.keys.sorted()
        val sIndex = stringKeys.withIndex().associate { it.value to it.index }
        val pIndex = pluralKeys.withIndex().associate { it.value to it.index }
        val langs = linkedMapOf("en" to english)
        root.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!.sortedBy { it.name }
            .forEach { langs[it.name.removePrefix("values-")] = read(it) }
        val outDir = catalogsOut.asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        for ((tag, lang) in langs) {
            val text = buildString {
                lang.strings.forEach { (k, v) -> sIndex[k]?.let { appendLine("s\t$it\t${compact(v)}") } }
                lang.plurals.forEach { (k, forms) ->
                    pIndex[k]?.let { i -> forms.forEach { (q, v) -> appendLine("p\t$i\t$q\t${compact(v)}") } }
                }
            }
            outDir.resolve("$tag.txt").writeText(text)
            digest.update(tag.toByteArray()); digest.update(text.toByteArray())
        }
        val version = digest.digest().joinToString("") { b -> "%02x".format(b) }.take(12)
        fun q(s: String) = "\"" + s + "\""
        val kt = buildString {
            appendLine("// Generated by :ui:generateI18n from apps/ui/i18n/values. Do not edit.")
            appendLine("package dev.supermux.ui.i18n")
            appendLine()
            appendLine("@Suppress(\"ObjectPropertyName\")")
            appendLine("object Str {")
            stringKeys.forEachIndexed { i, k -> appendLine("    inline val `$k`: StringKey get() = StringKey($i)") }
            appendLine("}")
            appendLine()
            appendLine("@Suppress(\"ObjectPropertyName\")")
            appendLine("object Plurals {")
            pluralKeys.forEachIndexed { i, k -> appendLine("    inline val `$k`: PluralKey get() = PluralKey($i)") }
            appendLine("}")
            appendLine()
            appendLine("internal object I18nFiles {")
            appendLine("    const val VERSION = ${q(version)}")
            appendLine("    const val STRINGS = ${stringKeys.size}")
            appendLine("    const val PLURALS = ${pluralKeys.size}")
            appendLine("    val LANGUAGES: List<String> = listOf(${langs.keys.joinToString { q(it) }})")
            appendLine("    /** Settings search finds its rows' labels by name; only `search_` keys can be looked up so. */")
            appendLine("    fun searchKey(name: String): Int = when (name) {")
            stringKeys.filter { it.startsWith("search_") }.forEach { appendLine("        ${q(it)} -> ${sIndex.getValue(it)}") }
            appendLine("        else -> -1")
            appendLine("    }")
            appendLine("}")
        }
        val file = keysOut.get().asFile.resolve("dev/supermux/ui/i18n/Keys.kt")
        file.parentFile.mkdirs()
        file.writeText(kt)
    }
}
// Every resources task (Compose resources, Android assets, the JVM classpath) must see the compact catalogs.
tasks.configureEach { if (name != "generateI18n" && name.contains("Resources", ignoreCase = true)) dependsOn(generateI18n) }

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // iOS (cluster H1). Declared so `iosMain` exists and the 8 expect seams get their Apple
    // actuals; the compile/link tasks are DISABLED on this Linux host
    // (kotlin.native.ignoreDisabledTargets in gradle.properties) and run on the Mac. No framework
    // is declared here — `:ios` owns the single one the phone links (SupermuxKit), which re-exports
    // this module; a second framework would embed the klibs twice.
    iosArm64()
    iosSimulatorArm64()
    // Browser (plan 1 of web→KMP). Same reasoning as :shared: no Node target, and the wasm test
    // task is disabled — this module has no commonTest at all (jvmTest covers commonMain) and
    // :web owns the browser-only tests.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
    // Explicit, matching `:shared`: iosMain sits under the default apple/native hierarchy, and
    // naming the template keeps that wiring stable if an intermediate source set is ever added.
    applyDefaultHierarchyTemplate()

    sourceSets {
        // #123: the catalog keys (Str, Plurals) and file list, generated from the English catalog.
        commonMain { kotlin.srcDir(generateI18n.map { layout.buildDirectory.dir("generated/i18n/kotlin").get() }) }
        commonMain.dependencies {
            // Native Mermaid rendering for ```mermaid fences (vendored cmp-mermaid, see mermaid-compose/README.md).
            implementation(project(":mermaid-compose"))
            // `api`, not `implementation`: these types are in this module's OWN public signatures —
            // PaneHost takes a LayoutNode, PaneStripChrome returns a Modifier, PaneDragController exposes
            // Rect/Offset, Motion returns a FiniteAnimationSpec. Under `implementation` a second consumer
            // gets unresolved references on our own API and only :desktop compiles, by accident of
            // declaring the same dependencies itself.
            api(project(":shared"))
            // The ONE terminal renderer (Plan 4 Task 3). `:terminal-compose` and `:terminal-core`
            // are a standalone, publishable pair that knows nothing about supermux — the arrow
            // points THIS WAY ONLY, and must keep doing so: `GhosttyTerminalViewFactory` adapts
            // their API to this module's `TerminalViewFactory` seam, and nothing over there names
            // a type from here. `api`, because TerminalSession/TerminalTheme appear in that
            // factory's own public signatures.
            api(project(":terminal-compose"))
            // The native editor (M5): the same one-way arrow as the terminal pair. editor-core,
            // editor-compose and editor-syntax know nothing about supermux; this module adapts them
            // (the document views, the broker's LSP channel, the review callbacks). `api` for the
            // modules whose types sit in this module's own signatures (EditorView on a Document,
            // SyntaxBackend on Platform, LspTransport, DiffHost); the other plugins are plain
            // implementation details of the panes.
            api(project(":editor-core"))
            api(project(":editor-compose"))
            api(project(":editor-syntax"))
            api(project(":editor-plugins:lsp"))
            api(project(":editor-plugins:diff"))
            api(project(":editor-plugins:view"))
            implementation(project(":editor-plugins:basics"))
            implementation(project(":editor-plugins:history"))
            implementation(project(":editor-plugins:highlight"))
            implementation(project(":editor-plugins:fold"))
            implementation(project(":editor-plugins:search"))
            implementation(project(":editor-plugins:autocomplete"))
            implementation(project(":editor-plugins:lint"))
            // The Browser tab (#105): each platform's own web engine inside Compose. `implementation`:
            // no webview type is in this module's public signatures (BrowserActions speaks the
            // broker's DTOs; the pane converts).
            implementation(project(":webview-compose"))
            api(compose.runtime)
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            // Icons.Filled.Close, used by DefaultTabChip's close affordance.
            api(compose.materialIconsExtended)
            // Motion's FiniteAnimationSpec, and SharedTransitionScope/AnimatedVisibilityScope in
            // SessionAvatar's own signature — hence `api`, not `implementation`.
            api(compose.animation)
            // Geist + Geist Mono ship from here as Compose Multiplatform resources (Res.font.*),
            // replacing Android's R.font and desktop's classpath lookup. `api`, because the font
            // families in theme/Type.kt are this module's public surface.
            api(compose.components.resources)
            // Compose Multiplatform's own BackHandler (androidx.compose.ui.backhandler), used by
            // EditorPanel's search-overlay / tree-drawer back contract. It is a SEPARATE artifact
            // from compose.ui and there is no `compose.` accessor for it, hence the coordinates;
            // the version is the one the Compose plugin resolves for every other compose artifact.
            api("org.jetbrains.compose.ui:ui-backhandler:${libs.versions.composeMultiplatform.get()}")
            // Coil 3 — the ONE image loader for both apps (markdown images in D2, attachment
            // thumbnails, avatars). Multiplatform, and its Ktor-3 network backend shares the
            // engine :shared already ships. `api`, because AsyncImage/ImageRequest appear in the
            // shared composables' own signatures (the loadImage test seams take them).
            api(libs.coil.compose)
            api(libs.coil.network.ktor3)
            // Inline video in the shared chat timeline (D2). Moved here from :desktop; Android's
            // media3/ExoPlayer player is gone (D2 removed the dependency) and :desktop had no other
            // player at all. Renders frames into a Compose Canvas, so nothing AWT-heavyweight.
            api(libs.compose.media.player)
            // The inline-image fetch policy (https gate, byte cap, bounded redirects) is Ktor here
            // rather than `java.net`, so it compiles for iOS too. The engine comes from :shared's
            // runtime classpath.
            api(libs.ktor.client.core)
            // MessageTts's generation counter — a plain Int would be a data race between the
            // Compose frame that toggles and the coroutine that streams audio chunks.
            implementation(libs.atomicfu)
            // Navigation 3 — the ONE navigation renderer for both hosts (cluster G8). Desktop
            // already drove `NavDisplay` + a back stack; Android's `NavHost`/`composable<Route.X>`
            // (androidx.navigation:navigation-compose) is gone. `api`, because `ShellUiState`
            // exposes the Nav3 back stack and `FullPaneOverlaySceneStrategy` is a `SceneStrategy`
            // in this module's own public signatures.
            api(libs.jetbrains.navigation3.ui)
        }
        wasmJsMain {
            // `js("…")`, external declarations and JsAny are all still behind this opt-in in 2.3.
            languageSettings.optIn("kotlin.js.ExperimentalWasmJsInterop")
            dependencies {
                // kotlinx.browser / org.w3c — no longer in the wasm stdlib.
                implementation(libs.kotlinx.browser)
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.desktop.uiTestJUnit4)
            implementation(compose.desktop.currentOs)
            // The library's fake engine, so the Browser pane's tests run where Compose UI tests run.
            implementation(project(":webview-compose-testing"))
            // ChatHeaderTest drives the real ChatPanel over a HostStore, which needs an HTTP
            // engine; the mock engine answers "{}" and no socket is opened (connectOnInit = false).
            implementation(libs.ktor.client.mock)
            // SettingsHubTest fires a REAL back gesture (NavigationEventInput) at the hub's
            // BackHandler. The artifact is already on the runtime classpath via ui-backhandler;
            // naming it here only puts it on the test COMPILE classpath.
            implementation(libs.jetbrains.navigationevent.compose)
            // ZXing lives HERE ONLY, and only as a DECODER. `widgets/qr/QrEncoder.kt` is a
            // hand-written encoder (ZXing core is a JVM-only jar, so it stopped resolving when
            // this module gained iOS targets, and this phase adds no libraries) — the one honest
            // proof that it is spec-correct is a round trip through an INDEPENDENT reader, which
            // is what QrEncoderTest / QrCodeTest / DevicesSettingsScreenTest do. Nothing in
            // commonMain, and therefore nothing shipped, references it.
            implementation(libs.zxing.core)
        }
    }
}

// #123: the UI follows the system language, so pin the tests' JVM to English wherever they run.
tasks.withType<Test>().configureEach {
    systemProperty("user.language", "en")
    systemProperty("user.country", "US")
}

compose.resources {
    // Generated accessor: dev.supermux.ui.resources.Res — public so app modules could reach it too.
    packageOfResClass = "dev.supermux.ui.resources"
    publicResClass = true
    generateResClass = always
}

android {
    namespace = "dev.supermux.ui"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
