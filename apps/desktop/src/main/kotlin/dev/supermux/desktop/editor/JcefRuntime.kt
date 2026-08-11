// Process-global direct JetBrains JCEF holder for the desktop CodeMirror editor. The matching
// JBR-with-JCEF runtime is bundled by desktop/build.gradle.kts, so initialization is local and
// offline: there is no wrapper-managed download and no second copy of Chromium under app config.
//
// WHY init-once + init-after-window: CefApp is a process-global singleton — a second initialization
// throws. Initializing before the Compose/AWT window exists can freeze the app, so callers invoke
// [ensureInit] from a LaunchedEffect after the window is up and the native work runs off the EDT.
package dev.supermux.desktop.editor

import com.jetbrains.cef.JCefAppConfig
import dev.supermux.desktop.auth.DesktopTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.cef.CefApp
import org.cef.CefClient
import org.cef.SystemBootstrap
import org.cef.handler.CefAppHandlerAdapter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.absolutePathString

/** Lifecycle of the shared direct-JCEF runtime, surfaced to the editor UI. */
sealed interface JcefState {
    /** No [JcefRuntime.ensureInit] call has run. */
    data object Idle : JcefState

    /** The bundled native runtime is starting. */
    data object Initializing : JcefState

    /** CefApp is up; [JcefRuntime.newClient] can create a browser client. */
    data object Ready : JcefState

    /** Initialization failed. This is terminal for the process; the UI uses its native fallback. */
    data class Error(val msg: String) : JcefState
}

/** Process-global direct JetBrains JCEF holder. Thread-safe and init-once. */
object JcefRuntime {
    private val _state = MutableStateFlow<JcefState>(JcefState.Idle)
    val state: StateFlow<JcefState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)
    private var cefApp: CefApp? = null

    /** App config dir (`<XDG_CONFIG_HOME|APPDATA>/supermux-desktop`) — shared with the token store. */
    private fun configDir(): Path = DesktopTokenStore.defaultPath().parent

    /** Persistent Chromium cache (HTTP, GPU shaders, local storage). */
    private fun cacheDir(): Path = configDir().resolve("jcef-cache")

    /** Extraction target for the committed CodeMirror web bundle loaded through `file://`. */
    fun editorWebDir(): Path = configDir().resolve("editor-web")

    /**
     * Idempotent. Starts the JCEF instance supplied by the packaged JBR runtime. The old
     * `SMX_KCEF_*` variables remain accepted as compatibility aliases for existing CI/dev scripts.
     */
    fun ensureInit(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return

        if (envFlag("SMX_JCEF_FORCE_ERROR", "SMX_KCEF_FORCE_ERROR")) {
            _state.value = JcefState.Error("forced via SMX_JCEF_FORCE_ERROR (verification)")
            return
        }

        _state.value = JcefState.Initializing
        scope.launch(Dispatchers.IO) {
            try {
                val runtimeProblem = validateBundledRuntime()
                check(runtimeProblem == null) { runtimeProblem.orEmpty() }

                val cache = cacheDir()
                Files.createDirectories(cache)

                val config = JCefAppConfig.getInstance()
                SystemBootstrap.setLoader(config.loader)
                val args = buildList {
                    addAll(config.appArgsAsList.filterNotNull())
                    addAll(listOf("--no-sandbox", "--disable-gpu", "--disable-gpu-compositing"))
                    addAll(parseJcefExtraArgs(envValue("SMX_JCEF_EXTRA_ARGS", "SMX_KCEF_EXTRA_ARGS")))
                }.toTypedArray()
                val settings = config.cefSettings.apply {
                    cache_path = cache.absolutePathString()
                }

                CefApp.addAppHandler(object : CefAppHandlerAdapter(args) {})
                check(CefApp.startup(args)) { "CefApp.startup returned false" }

                val app = CefApp.getInstance(args, settings)
                cefApp = app
                app.onInitialization { state ->
                    when (state) {
                        CefApp.CefAppState.INITIALIZED -> _state.value = JcefState.Ready
                        CefApp.CefAppState.TERMINATED -> {
                            if (_state.value !is JcefState.Error) {
                                _state.value = JcefState.Error("JCEF terminated during initialization")
                            }
                        }
                        else -> Unit
                    }
                }
            } catch (t: Throwable) {
                _state.value = JcefState.Error(t.message ?: t.toString())
            }
        }
    }

    /** A fresh client owned and disposed by one [DesktopEditorEngine]. */
    fun newClient(): CefClient? {
        if (_state.value != JcefState.Ready) return null
        return cefApp?.createClient()
    }

    /** Dispose the process-global CefApp and its helper processes. Safe if the editor never started. */
    fun dispose() {
        if (!started.get()) return
        cefApp?.dispose()
        cefApp = null
    }
}

/** Split direct-JCEF extra switches on ASCII whitespace. Flag-only values are the intended use. */
internal fun parseJcefExtraArgs(raw: String?): List<String> =
    raw?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

internal fun isMacOs(osName: String? = System.getProperty("os.name")): Boolean =
    osName?.lowercase()?.let { it.contains("mac") || it.contains("darwin") } ?: false

private fun envFlag(primary: String, legacy: String): Boolean = envValue(primary, legacy) == "1"

private fun envValue(primary: String, legacy: String): String? =
    System.getenv(primary) ?: System.getenv(legacy)

/**
 * Produce an actionable fallback error before JCEF attempts to load a missing native library. The
 * packaged app and Gradle run tasks always use the pinned JBR-with-JCEF image; this mainly catches
 * developers launching MainKt directly from a stock JDK.
 */
internal fun validateBundledRuntime(
    javaHome: Path = Path.of(System.getProperty("java.home")),
    osName: String = System.getProperty("os.name").orEmpty(),
): String? {
    val os = osName.lowercase()
    val marker = when {
        os.contains("mac") || os.contains("darwin") -> javaHome.parent
            .resolve("Frameworks")
            .resolve("Chromium Embedded Framework.framework")
            .resolve("Chromium Embedded Framework")
        os.contains("win") -> javaHome.resolve("bin").resolve("jcef.dll")
        else -> javaHome.resolve("lib").resolve("libjcef.so")
    }
    return if (Files.isRegularFile(marker)) null else
        "The rich editor requires the bundled JetBrains Runtime with JCEF; missing $marker"
}
