package dev.supermux.webview.engine.linux

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.webkit.WebKitUrls
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The WPE runtime the Linux engine needs: WPE WebKit 2.54 and the engine's glue, built by runtime/linux and shipped by
 * the app (about 45 MB compressed), never by this library's jar.
 *
 * Where it is looked for, in order:
 * 1. the system property [PROPERTY];
 * 2. the environment variable [ENV];
 * 3. `wpe-runtime` in Compose Desktop's packaged resources (`compose.application.resources.dir`);
 * 4. `~/.local/share/dev.supermux.webview/wpe-runtime`.
 *
 * The first folder with a `manifest.properties` is used, and must be this library's: runtime, ABI, version and
 * architecture, and every listed file at its size. The glue's sha256 is checked before it is loaded.
 *
 * WebKit runs pages in bubblewrap's sandbox. A system where `bwrap` can't make one gets no engine, unless the app opts
 * out with `-Ddev.supermux.webview.wpe.sandbox=off`. Every failure is [State.Unavailable], never a crash (spec §7).
 */
internal object WpeRuntime {
    const val ABI: String = "1"
    const val VERSION_PREFIX: String = "2.54."
    const val PROPERTY: String = "dev.supermux.webview.wpe.runtime"
    const val ENV: String = "WEBVIEW_WPE_RUNTIME"
    const val SANDBOX_PROPERTY: String = "dev.supermux.webview.wpe.sandbox"
    const val GLUE: String = "lib/libwebview-wpe.so"

    sealed interface State {
        data class Ready(val dir: File, val version: String) : State
        data class Unavailable(val reason: UnavailableReason, val detail: String) : State
    }

    @Volatile
    private var state: State? = null

    /** Starts WebKit once per process, the first time an engine is asked for; later calls say how that went. */
    @Synchronized
    fun start(): State = state ?: load().also { state = it }

    fun candidates(property: String?, env: String?, resourcesDir: String?, home: String?): List<File> = buildList {
        property?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        env?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        resourcesDir?.takeIf { it.isNotBlank() }?.let { add(File(it, "wpe-runtime")) }
        home?.takeIf { it.isNotBlank() }?.let { add(File(it, ".local/share/dev.supermux.webview/wpe-runtime")) }
    }

    fun archOf(osArch: String): String? = when (osArch.lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> null
    }

    /** What is wrong with the runtime in [dir] for [arch]; null if it is this library's and whole. */
    fun verify(dir: File, arch: String): String? {
        val file = File(dir, "manifest.properties")
        if (!file.isFile) return "no manifest.properties in $dir"
        val manifest = Properties().apply { file.inputStream().use { load(it) } }
        if (manifest.getProperty("runtime") != "wpe") return "$dir is not a WPE runtime"
        if (manifest.getProperty("abi") != ABI) return "runtime ABI ${manifest.getProperty("abi")}, this library speaks $ABI"
        val version = manifest.getProperty("version").orEmpty()
        if (!version.startsWith(VERSION_PREFIX)) return "WPE $version, this library needs $VERSION_PREFIX*"
        if (manifest.getProperty("arch") != arch) return "a runtime for ${manifest.getProperty("arch")}, this JVM is $arch"
        val files = manifest.stringPropertyNames().filter { it.startsWith("file.") }
        if (files.isEmpty()) return "the manifest lists no files"
        for (key in files) {
            val path = key.removePrefix("file.")
            val parts = manifest.getProperty(key).trim().split(' ')
            val size = parts.getOrNull(0)?.toLongOrNull() ?: -1L
            val f = File(dir, path)
            if (!f.isFile || f.length() != size) return "$path is missing or not $size bytes"
            if (path == GLUE && sha256(f) != parts.getOrNull(1)) return "$path doesn't match its sha256"
        }
        return null
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun load(): State {
        if (!System.getProperty("os.name").orEmpty().startsWith("Linux")) return State.Unavailable(UnavailableReason.NotImplemented, "not Linux")
        val osArch = System.getProperty("os.arch").orEmpty()
        val arch = archOf(osArch) ?: return State.Unavailable(UnavailableReason.NoWpe, "no WPE runtime is built for $osArch")
        val home = System.getProperty("user.home")
        val dirs = candidates(System.getProperty(PROPERTY), System.getenv(ENV), System.getProperty("compose.application.resources.dir"), home)
        val dir = dirs.firstOrNull { File(it, "manifest.properties").isFile }
            ?: return State.Unavailable(UnavailableReason.NoWpe, "no WPE runtime in ${dirs.joinToString()}")
        verify(dir, arch)?.let { return State.Unavailable(UnavailableReason.NoWpe, it) }
        val sandbox = System.getProperty(SANDBOX_PROPERTY) != "off"
        if (sandbox) sandboxProblem()?.let { return State.Unavailable(UnavailableReason.NoWpe, it) }
        try {
            System.load(File(dir, GLUE).absolutePath)
        } catch (e: Throwable) {
            return State.Unavailable(UnavailableReason.NativeLibraryFailed, e.message ?: e.toString())
        }
        WpeNative.setenv("WEBKIT_EXEC_PATH", File(dir, "libexec").absolutePath)
        WpeNative.setenv("WEBKIT_INJECTED_BUNDLE_PATH", File(dir, "lib/wpe-webkit-2.0/injected-bundle").absolutePath)
        WpeHost.attach()
        val flags = (if (sandbox) 0 else WpeNative.INIT_NO_SANDBOX) or (if (WpeDefaults.GLOBAL_CONTEXT) WpeNative.INIT_GLOBAL_CONTEXT else 0)
        val data = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "dev.supermux.webview/wpe")
        val cache = File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "dev.supermux.webview/wpe")
        if (WpeNative.init(flags, data.absolutePath, cache.absolutePath) != 0) return State.Unavailable(UnavailableReason.NoWpe, "WebKit didn't start")
        val version = WpeNative.version()
        if (!version.startsWith(VERSION_PREFIX)) return State.Unavailable(UnavailableReason.NoWpe, "WPE $version")
        WpeNative.registerScheme(WebKitUrls.BUNDLE_SCHEME, true)
        WpeNative.registerScheme(WebKitUrls.FLOOR_SCHEME, true)
        return State.Ready(dir, version)
    }

    /** Why bubblewrap can't sandbox pages here; null if it can. */
    private fun sandboxProblem(): String? {
        if (!File("/usr/bin/bwrap").canExecute() || !File("/usr/bin/xdg-dbus-proxy").canExecute()) {
            return "bubblewrap or xdg-dbus-proxy is missing (WebKit's page sandbox); -D$SANDBOX_PROPERTY=off runs pages without it"
        }
        return try {
            val process = ProcessBuilder("/usr/bin/bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--unshare-all", "true")
                .redirectErrorStream(true).start()
            when {
                !process.waitFor(5, TimeUnit.SECONDS) -> {
                    process.destroyForcibly()
                    "bubblewrap didn't answer in 5 s"
                }
                process.exitValue() != 0 -> "bubblewrap can't make a sandbox here: " + process.inputStream.bufferedReader().readText().trim().take(200)
                else -> null
            }
        } catch (e: Exception) {
            "bubblewrap: ${e.message}"
        }
    }
}
