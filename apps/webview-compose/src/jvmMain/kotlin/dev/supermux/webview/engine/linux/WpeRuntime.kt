package dev.supermux.webview.engine.linux

import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.desktop.hostDesktop
import dev.supermux.webview.engine.webkit.WebKitUrls
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.jetbrains.skiko.OS

/**
 * The WPE runtime the Linux engine needs: WPE WebKit 2.54 and the engine's glue, built by runtime/linux and shipped by
 * the app (about 45 MB compressed), never by this library's jar.
 *
 * Where it is looked for, in order:
 * 1. a folder: the system property [PROPERTY], the environment variable [ENV], then `wpe-runtime` in Compose Desktop's
 *    packaged resources (`compose.application.resources.dir`): the first with a `manifest.properties`;
 * 2. else an archive ([archives]): [ARCHIVE_PROPERTY], [ARCHIVE_ENV], then `wpe-runtime.tar.gz` (`.tar`, `.tar.zst`) in
 *    the packaged resources, unpacked once by [WpeExtractor] into
 *    `$XDG_DATA_HOME/dev.supermux.webview/wpe-runtime/<version>-<hash>`, a folder of its own per runtime, verified file
 *    by file. No folder shared by every app and version is looked in.
 *
 * The runtime must be this library's: runtime, ABI, version and architecture, every listed file at its size, and
 * WebKit's helper processes executable. The glue's sha256 is checked before it is loaded.
 *
 * WebKit runs pages in bubblewrap's sandbox, with the runtime's folder mounted in it (the glue asks for that). WebKit
 * aborts the process where it can't start a sandboxed page, so the engine looks first: a system where `bwrap` can't
 * make a sandbox, or where WebKit would need an `xdg-dbus-proxy` it hasn't, gets no engine, unless the app opts out with
 * `-Ddev.supermux.webview.wpe.sandbox=off`. Every failure is [State.Unavailable], never a crash (spec §7).
 */
internal object WpeRuntime {
    /** The glue's natives as this library calls them; the runtime's manifest says which it was built with. */
    const val ABI: String = "2"
    const val VERSION_PREFIX: String = "2.54."
    const val PROPERTY: String = "dev.supermux.webview.wpe.runtime"
    const val ENV: String = "WEBVIEW_WPE_RUNTIME"
    const val ARCHIVE_PROPERTY: String = "dev.supermux.webview.wpe.archive"
    const val ARCHIVE_ENV: String = "WEBVIEW_WPE_ARCHIVE"
    const val SANDBOX_PROPERTY: String = "dev.supermux.webview.wpe.sandbox"
    const val GLUE: String = "lib/libwebview-wpe.so"

    /** WebKit's helper processes: it aborts the process where it can't start one, so they are checked first. */
    val HELPERS: List<String> = listOf("libexec/WPEWebProcess", "libexec/WPENetworkProcess")

    /** Where WebKit 2.54 looks for bubblewrap and xdg-dbus-proxy: fixed when it was built (`BWRAP_EXECUTABLE`…). */
    private const val BWRAP = "/usr/bin/bwrap"
    private const val DBUS_PROXY = "/usr/bin/xdg-dbus-proxy"

    sealed interface State {
        /** [data]: the app's own folder for its profiles ([WpeProfiles]). */
        data class Ready(val dir: File, val version: String, val data: File) : State
        data class Unavailable(val reason: UnavailableReason, val detail: String) : State
    }

    @Volatile
    private var state: State? = null

    /** Starts WebKit once per process, the first time an engine is asked for; later calls say how that went. */
    @Synchronized
    fun start(): State = state ?: load().also { state = it }

    fun candidates(property: String?, env: String?, resourcesDir: String?): List<File> = buildList {
        property?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        env?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        resourcesDir?.takeIf { it.isNotBlank() }?.let { add(File(it, "wpe-runtime")) }
    }

    /** The archives a runtime may come in, in order: [property], [env], then the packaged resources' `wpe-runtime.tar.*`. */
    fun archives(property: String?, env: String?, resourcesDir: String?): List<File> = buildList {
        property?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        env?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        resourcesDir?.takeIf { it.isNotBlank() }?.let { dir -> for (ext in listOf("tar.gz", "tar", "tar.zst")) add(File(dir, "wpe-runtime.$ext")) }
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
        for (helper in HELPERS) {
            val f = File(dir, helper)
            if (!f.isFile || !f.canExecute()) return "$helper is missing or not executable"
        }
        return null
    }

    /**
     * Why WebKit's page sandbox can't run here; null if it can. [bwrap] and [dbusProxy]: whether WebKit's bubblewrap and
     * xdg-dbus-proxy are there. [probe] makes a sandbox as WebKit tests one.
     *
     * WebKit starts xdg-dbus-proxy for a page whenever it finds a D-Bus session bus or an accessibility bus, and aborts
     * the process without it. GLib finds a session bus even with `DBUS_SESSION_BUS_ADDRESS` unset (at
     * `$XDG_RUNTIME_DIR/bus`, seen on `desktop`, 2026-10-30), so the proxy is required whatever the environment says.
     */
    fun sandboxProblem(bwrap: Boolean, dbusProxy: Boolean, probe: () -> String?): String? = when {
        !bwrap -> "bubblewrap ($BWRAP) is missing: WebKit's page sandbox; -D$SANDBOX_PROPERTY=off runs pages without it"
        !dbusProxy -> "xdg-dbus-proxy ($DBUS_PROXY) is missing, which WebKit's page sandbox needs; -D$SANDBOX_PROPERTY=off runs pages without it"
        else -> probe()
    }

    /**
     * Why WebKit can't mount the runtime folder [dir] in the page sandbox (`webkit_web_context_add_path_to_sandbox`
     * refuses it, and the page's process then can't start); null if it can. [home] is the user's home folder.
     */
    fun sandboxPathProblem(dir: File, home: File?): String? {
        val path = dir.path
        // platform-ok: a POSIX path, for the Linux engine's WebKit sandbox
        val first = path.removePrefix("/").substringBefore('/')
        return when {
            !dir.isAbsolute -> "the runtime's path $path isn't absolute"
            first.isEmpty() || first == "sys" || first == "proc" || first == "dev" || path == "/home" || path == home?.path ->
                "WebKit can't mount $path in its page sandbox: move the runtime into a folder of its own"
            else -> null
        }
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
        if (hostDesktop != OS.Linux) return State.Unavailable(UnavailableReason.NotImplemented, "not Linux")
        val osArch = System.getProperty("os.arch").orEmpty()
        val arch = archOf(osArch) ?: return State.Unavailable(UnavailableReason.NoWpe, "no WPE runtime is built for $osArch")
        val home = System.getProperty("user.home")
        val resources = System.getProperty("compose.application.resources.dir")
        val dirs = candidates(System.getProperty(PROPERTY), System.getenv(ENV), resources)
        val archives = archives(System.getProperty(ARCHIVE_PROPERTY), System.getenv(ARCHIVE_ENV), resources)
        val found = dirs.firstOrNull { File(it, "manifest.properties").isFile } ?: archives.firstOrNull { it.isFile }?.let { archive ->
            val unpacked = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "dev.supermux.webview/wpe-runtime")
            WpeExtractor.unpack(archive, unpacked).getOrElse { return State.Unavailable(UnavailableReason.NoWpe, "the runtime in $archive: ${it.message}") }
        } ?: return State.Unavailable(UnavailableReason.NoWpe, "no WPE runtime in ${(dirs + archives).joinToString()}")
        // Its real path: what WebKit mounts in the page sandbox, and finds its helpers in.
        val dir = found.canonicalFile
        verify(dir, arch)?.let { return State.Unavailable(UnavailableReason.NoWpe, it) }
        val sandbox = System.getProperty(SANDBOX_PROPERTY) != "off"
        if (sandbox) {
            val problem = sandboxPathProblem(dir, home?.let { File(it).canonicalFile })
                ?: sandboxProblem(File(BWRAP).canExecute(), File(DBUS_PROXY).canExecute(), ::probeSandbox)
            if (problem != null) return State.Unavailable(UnavailableReason.NoWpe, problem)
        }
        try {
            System.load(File(dir, GLUE).path)
        } catch (e: Throwable) {
            return State.Unavailable(UnavailableReason.NativeLibraryFailed, e.message ?: e.toString())
        }
        WpeNative.setenv("WEBKIT_EXEC_PATH", File(dir, "libexec").path)
        WpeNative.setenv("WEBKIT_INJECTED_BUNDLE_PATH", File(dir, "lib/wpe-webkit-2.0/injected-bundle").path)
        WpeHost.processListener = { event -> if (event is WpeEvent.Clipboard) WpeClipboard.System.onPageCopied(event.text) }
        WpeHost.attach()
        val flags = (if (sandbox) 0 else WpeNative.INIT_NO_SANDBOX) or (if (WpeDefaults.GLOBAL_CONTEXT) WpeNative.INIT_GLOBAL_CONTEXT else 0)
        // The app's own folders: another app's profiles are another's (WpeProfiles).
        val data = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "dev.supermux.webview/wpe/${WpeProfiles.app}")
        val cache = File(System.getenv("XDG_CACHE_HOME") ?: "$home/.cache", "dev.supermux.webview/wpe/${WpeProfiles.app}")
        if (WpeNative.init(flags, data.absolutePath, cache.absolutePath, dir.path) != 0) return State.Unavailable(UnavailableReason.NoWpe, "WebKit didn't start")
        val version = WpeNative.version()
        if (!version.startsWith(VERSION_PREFIX)) return State.Unavailable(UnavailableReason.NoWpe, "WPE $version")
        WpeNative.registerScheme(WebKitUrls.BUNDLE_SCHEME, true)
        WpeNative.registerScheme(WebKitUrls.FLOOR_SCHEME, true)
        return State.Ready(dir, version, data)
    }

    /** Why bubblewrap can't make a sandbox here, made as WebKit tests one (WTF's `isInsideUnsupportedContainer`); null if it can. */
    private fun probeSandbox(): String? {
        return try {
            val process = ProcessBuilder(BWRAP, "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--unshare-all", "true")
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
