// M3 editor risk gate: the single, init-once holder for KCEF (embedded Chromium / JCEF) that every
// editor panel shares. KCEF.init downloads a pinned JetBrainsRuntime+CEF bundle (~100-150MB) on the
// very first run into a persistent installDir under the app config dir, then boots one CefApp for
// the whole process. This holder surfaces that lifecycle as a [KcefState] StateFlow the UI observes
// (download progress / ready / error / restart-required) and hands out browsers once ready.
//
// WHY init-once + init-after-window: CefApp is a process-global singleton — a second KCEF.init
// throws. And initializing CEF BEFORE the Compose/AWT window exists freezes the app (a known CMP
// issue), so callers invoke [ensureInit] from a LaunchedEffect once the window is up, off the main
// thread (Dispatchers.IO — the download + native load are blocking).
package dev.supermux.desktop.editor

import com.jetbrains.cef.JCefAppConfig
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFBrowser
import dev.datlag.kcef.KCEFClient
import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString

/** Lifecycle of the shared KCEF runtime, surfaced to the editor UI. */
sealed interface KcefState {
    /** Not started yet (no [KcefRuntime.ensureInit] call has run). */
    data object Idle : KcefState

    /** Downloading the CEF/JBR bundle. [pct] is 0..100 (KCEF reports a percentage). */
    data class Downloading(val pct: Float) : KcefState

    /** CefApp is up; [KcefRuntime.newBrowser] will return a live browser. */
    data object Ready : KcefState

    /**
     * Init failed. [msg] is the underlying throwable's message (surface it; do NOT swallow).
     *
     * ⚠️ TERMINAL / DEAD END: this state never recovers within the process. [started] is a
     * compare-and-set that only flips once, so [ensureInit] is a no-op after the first attempt, and
     * CefApp is a process-global singleton that cannot be re-initialized after a failed load. So a
     * "Retry" affordance would do nothing but re-render Error. UI MUST treat Error like
     * [RestartRequired] — show the message + a "restart the app" path, NOT a retry button.
     */
    data class Error(val msg: String) : KcefState

    /**
     * CEF requires a JVM restart to finish (fresh native install on some platforms). Like [Error],
     * this is terminal for the current process — surface a restart path, not a retry.
     */
    data object RestartRequired : KcefState
}

/**
 * Process-global KCEF holder. Thread-safe, init-once.
 *
 * T2 (the editor bridge) builds on this: it takes a [newBrowser] result, adds a
 * [org.cef.browser.CefMessageRouter] to the browser's [dev.datlag.kcef.KCEFClient] for JS→Kotlin
 * (`cefQuery`), and drives JS via `browser.executeJavaScript` / `browser.evaluateJavaScript`.
 */
object KcefRuntime {

    /**
     * Pinned JetBrainsRuntime release the CEF natives ship in. Matched to JDK-17 hosts (this build
     * targets JVM 17). Bumping the JDK toolchain means bumping this tag. See the M3 plan.
     */
    const val JBR_RELEASE: String = "jbr-release-17.0.10b1087.23"

    private val _state = MutableStateFlow<KcefState>(KcefState.Idle)
    val state: StateFlow<KcefState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)

    /** App config dir (`<XDG_CONFIG_HOME|APPDATA>/supermux-desktop`) — shared with the token store. */
    private fun configDir(): Path = DesktopTokenStore.defaultPath().parent

    /**
     * Persistent CEF/JBR install dir. Sanctioned to survive between runs — later tasks (and every
     * relaunch) reuse the downloaded bundle instead of re-fetching ~100-150MB.
     */
    fun installDir(): Path = configDir().resolve("kcef-bundle")

    /** CEF disk cache (GPU shader cache, http cache) — kept beside the bundle, under config. */
    private fun cacheDir(): Path = configDir().resolve("kcef-cache")

    /**
     * Idempotent. First call kicks off KCEF.init on [scope] + Dispatchers.IO; later calls are no-ops
     * (CefApp is a process singleton). Progress/terminal states land on [state].
     */
    fun ensureInit(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        // Off-by-default verification hook (M3): SMX_KCEF_FORCE_ERROR=1 short-circuits init straight
        // to the TERMINAL Error state WITHOUT booting Chromium, so the editor's native BasicTextField
        // fallback (WebCodeEditor.NativeCodeEditor) can be exercised headlessly. Harmless in
        // production (unset by default); mirrors the SMX_KCEF_EXTRA_ARGS headless seam above.
        if (System.getenv("SMX_KCEF_FORCE_ERROR") == "1") {
            _state.value = KcefState.Error("forced via SMX_KCEF_FORCE_ERROR (verification)")
            return
        }
        val install = installDir()
        val cache = cacheDir()
        val mac = isMacOs()
        scope.launch(Dispatchers.IO) {
            try {
                Files.createDirectories(install)
                Files.createDirectories(cache)
                // macOS DEFENSE IN DEPTH: a bundle KCEF considers installed (install.lock present)
                // but whose CEF framework binary is missing would take the native path straight into
                // a failed dlopen + a null-pointer jump that SIGSEGVs the WHOLE JVM — and because the
                // editor pane is restored from ui-state.json, that turns into a crash LOOP the user
                // can't click their way out of. Fail into the terminal Error state (native fallback
                // editor) instead. A fresh dir is fine — init is what downloads the bundle.
                if (mac && macBundleIncomplete(install)) {
                    _state.value = KcefState.Error(
                        "CEF bundle at $install is incomplete (no ${macFrameworkBinary(install).fileName}) — " +
                            "delete that directory to re-download",
                    )
                    return@launch
                }
                _state.value = KcefState.Downloading(0f)
                KCEF.init(
                    builder = {
                        installDir(install.toFile())
                        progress {
                            onDownloading { pct -> _state.value = KcefState.Downloading(pct.coerceAtLeast(0f)) }
                            onInitialized { _state.value = KcefState.Ready }
                        }
                        settings {
                            cachePath = cache.absolutePathString()
                            // Pin CEF's helper + resource dirs to the extracted bundle. KCEF's
                            // CefInitializer only fills these when blank and otherwise CEF defaults
                            // them to `java.home/lib`, where jcef_helper / the .pak files do NOT
                            // live — so the GPU/renderer subprocess launch fails with
                            // "failed to execvp .../lib/jcef_helper" and Chromium aborts. Setting
                            // them explicitly to the install dir fixes both the subprocess exec and
                            // the "Could not load resources.pak" errors. (Observed on this JDK-17
                            // host during the M3-T1 probe.)
                            //
                            // On macOS the shapes are different: the helper is an .app bundle inside
                            // `Frameworks/` (not a bare `jcef_helper`) — leave browser_subprocess_path
                            // blank and CefInitializer's `getBrowserPath` override fills it correctly —
                            // and the resources live inside the framework, with the locale .pak files
                            // under `Resources/<locale>.lproj` rather than a `locales/` dir (so
                            // locales_dir_path stays unset; CEF derives it).
                            if (mac) {
                                resourcesDirPath = macResourcesDir(install).absolutePathString()
                            } else {
                                browserSubProcessPath = install.resolve("jcef_helper").absolutePathString()
                                resourcesDirPath = install.absolutePathString()
                                localesDirPath = install.resolve("locales").absolutePathString()
                            }
                        }
                        // Pin the JBR/CEF bundle release (JetBrainsRuntime GitHub releases is the
                        // default owner/repo) so init is reproducible across hosts.
                        download {
                            github { release(JBR_RELEASE) }
                        }
                        // Xvfb/headless + no display server sandbox: Chromium won't boot under Xvfb
                        // without these. --disable-gpu forces software GL; --disable-gpu-compositing
                        // keeps the compositor off the (absent) GPU. On a headless box with no GPU,
                        // Chromium still tries to spawn a GPU *process*, which fails to launch and —
                        // after a few retries — hard-aborts the whole app ("GPU process isn't
                        // usable. Goodbye."). SMX_KCEF_EXTRA_ARGS lets headless/CI runs add the
                        // switches that stop that (e.g. --in-process-gpu --disable-gpu-sandbox)
                        // WITHOUT baking headless-only flags into the shipped app, which runs on a
                        // real GPU. See KcefRuntime KDoc / the M3-T1 report.
                        val extra = parseExtraArgs(System.getenv("SMX_KCEF_EXTRA_ARGS"))
                        val ours = listOf("--no-sandbox", "--disable-gpu", "--disable-gpu-compositing") + extra
                        if (mac) {
                            // ⭐ macOS: CEF's own command line is a SECOND channel that must carry
                            // the bundle paths — see [macCefLaunchArgs]. KCEF's default AppHandler
                            // carries an empty arg array, so CefApp starts up with no framework
                            // location and dies on ICU; handing it these args fixes that. Subclassing
                            // KCEF's own AppHandler keeps its file-scheme registration intact.
                            val macArgs = macCefLaunchArgs(install, jcefAppArgs(), ours)
                            appHandler(KCEF.AppHandler(macArgs.toTypedArray()))
                            // ⭐ macOS: REPLACE the builder args instead of appending to them.
                            // KCEFBuilder seeds `args` from `JCefAppConfig.getInstance().appArgsAsList`,
                            // which on mac carries `--framework-dir-path=` / `--main-bundle-path=` /
                            // `--browser-subprocess-path=` computed from **java.home** — the
                            // JetBrainsRuntime layout. Under jpackage's runtime (Corretto) that is
                            // `<app>/Contents/runtime/Contents/Frameworks`, which holds no CEF. KCEF
                            // *prepends* the correct paths (Platform.OS.MACOSX.getFixedArgs), but
                            // `CefApp.startup` scans the whole array and keeps the **last**
                            // `--framework-dir-path=` it sees, so the stale one wins → the framework
                            // dlopen fails → CEF calls through a null pointer → SIGSEGV kills the JVM.
                            // Dropping just those three switches (keeping --use-mock-keychain,
                            // --force-device-scale-factor, …) leaves KCEF's prepended paths unopposed.
                            args(*macArgs.toTypedArray())
                        } else {
                            addArgs(*ours.toTypedArray())
                        }
                    },
                    onError = { t -> _state.value = KcefState.Error(t?.message ?: t.toString()) },
                    onRestartRequired = { _state.value = KcefState.RestartRequired },
                )
                // KCEF.init returns only after CefApp is up; onInitialized already set Ready, but
                // set it defensively in case the callback shape changes across KCEF versions.
                if (_state.value is KcefState.Downloading) _state.value = KcefState.Ready
            } catch (t: Throwable) {
                _state.value = KcefState.Error(t.message ?: t.toString())
            }
        }
    }

    /**
     * A fresh [KCEFClient] the caller fully owns (client-per-engine — see [DesktopEditorEngine] for
     * why: KCEFClient exposes only a SINGLE load/display handler, so one client per browser keeps an
     * engine's handlers from stomping another's). Returns null unless [state] is [KcefState.Ready] —
     * NEVER call the KCEF client factory optimistically, since it throws (NotInitialized) before init
     * completes. Blocking; cheap once ready. The caller adds its message router / handlers, then
     * calls `client.createBrowser(url)`, and disposes the client in its own teardown.
     */
    fun newClient(): KCEFClient? {
        if (_state.value != KcefState.Ready) return null
        return KCEF.newClientOrNullBlocking()
    }

    /**
     * A fresh browser pointed at [url] on its own client. Returns null unless [state] is
     * [KcefState.Ready] (gated — no optimistic creation). Blocking — call off the main thread, or
     * from the SwingPanel factory on the EDT (cheap once ready). The returned
     * [KCEFBrowser.getUIComponent] is the AWT child to embed in a SwingPanel. Prefer [newClient] when
     * you need to attach a message router / load handler before the page loads (the editor bridge).
     */
    fun newBrowser(url: String): KCEFBrowser? {
        val client = newClient() ?: return null
        return client.createBrowser(url)
    }

    /**
     * Tear down the whole CEF runtime (all browsers, the CefApp, and the native subprocesses).
     * Idempotent-ish: safe to call even if init never ran (KCEF guards internally). Wired into the
     * app's `onCloseRequest` (Main.kt) — NOT a JVM shutdown hook, because CEF wants an orderly
     * shutdown on the AWT/main thread before the process exits, and onCloseRequest runs there while
     * the Compose window still exists. Without this, closing the window orphans the Chromium
     * subprocess (helper + GPU + renderer) — it lingers until the OS reaps it. Blocking.
     *
     * MID-DOWNLOAD GUARD: if init is still Downloading, log + SKIP disposeBlocking rather than block
     * window-close behind (or race) a ~360MB in-flight download. A torn download is harmless: the
     * init-once CAS is per-PROCESS, so the next launch re-runs KCEF.init, which re-verifies/re-heals
     * the installDir. Killing the process mid-download was already the pre-M3 reality.
     */
    fun dispose() {
        if (!started.get()) return
        if (_state.value is KcefState.Downloading) {
            println("[KcefRuntime] dispose() skipped mid-download — next launch re-heals the installDir")
            return
        }
        KCEF.disposeBlocking()
    }
}

/**
 * Split a raw `SMX_KCEF_EXTRA_ARGS` value into CEF switches on ASCII whitespace. Blank/absent → no
 * args. LIMITATION: a plain whitespace split — NO quoting or escaping, so a switch value that itself
 * contains a space can't be expressed (fine for the flag-only headless/CI switches this seam exists
 * for, e.g. `--in-process-gpu --disable-gpu-sandbox`). Pure + total for [parseExtraArgs] tests.
 */
internal fun parseExtraArgs(raw: String?): List<String> =
    raw?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

// ── macOS CEF bring-up ────────────────────────────────────────────────────────────────────────
// See the `if (mac)` branches in [KcefRuntime.ensureInit] for WHY each of these exists. Kept as
// pure/file-level functions so they unit-test on any host (KcefMacInitTest) — the mac behaviour
// itself can only be verified by running the packaged .app on a Mac.

/** CEF path switches JCefAppConfig derives from `java.home`; stale under a non-JBR runtime. */
private val JBR_PATH_SWITCHES = listOf(
    "--framework-dir-path=",
    "--main-bundle-path=",
    "--browser-subprocess-path=",
)

internal fun isMacOs(osName: String? = System.getProperty("os.name")): Boolean =
    osName?.lowercase()?.let { it.contains("mac") || it.contains("darwin") } ?: false

/**
 * The CEF arg list to hand KCEF on macOS: [jbrArgs] (JCefAppConfig's defaults) minus the three
 * java.home-derived path switches, then [ours]. Order is preserved — CEF's own parser takes the
 * last occurrence of a repeated switch, so relative order is load-bearing.
 */
internal fun macCefArgs(jbrArgs: List<String>, ours: List<String>): List<String> =
    jbrArgs.filterNot { arg -> JBR_PATH_SWITCHES.any { arg.trim().startsWith(it) } } + ours

/** JCefAppConfig's default CEF args, or empty if this JVM has no JCEF config (never throws). */
internal fun jcefAppArgs(): List<String> = runCatching {
    JCefAppConfig.getInstance().appArgsAsList.filterNotNull()
}.getOrDefault(emptyList())

/** The CEF framework directory inside a KCEF install dir (macOS bundle layout). */
internal fun macFrameworkDir(install: Path): Path = install
    .resolve("Frameworks")
    .resolve("Chromium Embedded Framework.framework")

/** The CEF framework's Mach-O binary — what the framework dlopen actually resolves to. */
internal fun macFrameworkBinary(install: Path): Path =
    macFrameworkDir(install).resolve("Chromium Embedded Framework")

/**
 * Where `icudtl.dat` and the `*.pak` resources live on macOS: INSIDE the framework. KCEF computes
 * this (Platform.OS.MACOSX.getResourcesPath) but never applies it — its CefInitializer skips
 * `resources_dir_path` on mac, assuming CEF will find the resources bundle-relative. That only holds
 * when the framework sits inside the running app bundle; with the framework in a downloaded install
 * dir, CEF instead resolves against `--main-bundle-path` (which KCEF points at `jcef Helper.app`,
 * a bundle with no Resources) and aborts with "icudtl.dat not found in bundle".
 */
internal fun macResourcesDir(install: Path): Path = macFrameworkDir(install).resolve("Resources")

/** The `jcef Helper.app` bundle CEF launches its GPU/renderer subprocesses from (macOS). */
internal fun macHelperApp(install: Path): Path =
    install.resolve("Frameworks").resolve("jcef Helper.app")

/** The helper bundle's executable — CEF's `browser-subprocess-path` on macOS. */
internal fun macHelperBinary(install: Path): Path =
    macHelperApp(install).resolve("Contents").resolve("MacOS").resolve("jcef Helper")

/**
 * The full CEF arg list for macOS: the three bundle paths (pointing into [install]) followed by
 * [macCefArgs]'s sanitized `jbrArgs` + [ours].
 *
 * ⭐ These have to reach **CEF's own command line**, which is a different channel from the path KCEF
 * uses. KCEF only feeds args to `CefApp.startup(...)` — enough for the java-side framework dlopen —
 * and then calls `CefApp.getInstance(settings)` with NO args. CefApp's args are what
 * `CefAppHandlerAdapter.onBeforeCommandLineProcessing` replays onto the browser process's Chromium
 * command line, where CEF's `PreSandboxStartup` reads `framework-dir-path` / `main-bundle-path` to
 * override the framework bundle *before* ICU initializes. With KCEF's default empty-arg AppHandler,
 * CEF instead resolves relative to the host .app bundle, finds no framework, and aborts with
 * "icudtl.dat not found in bundle". Passing them through a [dev.datlag.kcef.KCEF.AppHandler] (that
 * subclass, NOT a bare adapter — it also registers KCEF's file-scheme handler, which the editor
 * page load depends on) is what closes the gap.
 */
internal fun macCefLaunchArgs(install: Path, jbrArgs: List<String>, ours: List<String>): List<String> =
    listOf(
        "--framework-dir-path=${macFrameworkDir(install)}",
        "--main-bundle-path=${macHelperApp(install)}",
        "--browser-subprocess-path=${macHelperBinary(install)}",
    ) + macCefArgs(jbrArgs, ours)

/**
 * True when KCEF considers [install] installed (`install.lock`) but the CEF framework binary is
 * missing — the shape that SIGSEGVs the JVM instead of failing cleanly. A dir with no `install.lock`
 * is NOT incomplete: that's a fresh install KCEF is about to download.
 */
internal fun macBundleIncomplete(install: Path): Boolean =
    Files.exists(install.resolve("install.lock")) && !Files.exists(macFrameworkBinary(install))
