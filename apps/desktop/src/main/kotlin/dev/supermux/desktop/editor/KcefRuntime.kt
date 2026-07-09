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
        val install = installDir()
        val cache = cacheDir()
        scope.launch(Dispatchers.IO) {
            try {
                Files.createDirectories(install)
                Files.createDirectories(cache)
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
                            browserSubProcessPath = install.resolve("jcef_helper").absolutePathString()
                            resourcesDirPath = install.absolutePathString()
                            localesDirPath = install.resolve("locales").absolutePathString()
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
                        addArgs("--no-sandbox", "--disable-gpu", "--disable-gpu-compositing", *extra.toTypedArray())
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
