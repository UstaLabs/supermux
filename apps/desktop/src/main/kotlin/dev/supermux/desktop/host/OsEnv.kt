package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Injectable OS seam: every launchctl / systemctl / schtasks / process call goes through here so
 * unit tests use a fake and never touch a real service. [SystemOsEnv] is the real one.
 */
interface OsEnv {
    companion object {
        /** A command that waits on the user (a UAC or admin password prompt): long, but not forever. */
        const val PROMPT_TIMEOUT_MS = 10 * 60_000L
    }

    val os: DesktopOs
    val home: Path
    val localAppData: Path
    val uid: Long?
    val xdgRuntimeDir: String?
    fun hasCommand(name: String): Boolean

    /** Run [argv] best-effort; true iff it exited 0. Never throws. */
    fun run(argv: List<String>): Boolean

    data class RunResult(val exit: Int, val out: String, val err: String)

    /**
     * Run [argv], capturing exit code, stdout and stderr. Never throws (failure to start = exit -1).
     * Killed (with its children) after the default timeout ([SystemOsEnv.defaultTimeoutMs]): a hung
     * `launchctl` or `schtasks` must never hang the supervisor's lock.
     */
    fun runResult(argv: List<String>): RunResult

    /** [runResult] with its own [timeoutMs] (e.g. a UAC prompt the user takes a while to answer). */
    fun runResult(argv: List<String>, timeoutMs: Long): RunResult = runResult(argv)

    /** Sleep [ms] (a seam so retry loops do not slow tests). */
    fun sleep(ms: Long)

    /** Run [argv] and return its stdout, or null on failure or after the default timeout. Never throws. */
    fun runCapture(argv: List<String>): String?

    /** This process's environment variable [name]; null when unset. */
    fun getenv(name: String): String? = null

    /** Linux: this process's `/proc/self/cgroup` (which systemd unit runs us); null elsewhere or unreadable. */
    fun selfCgroup(): String? = null

    /**
     * POSTs an empty body to the loopback [url] with [headers]: the status code, or null when nothing answered within
     * a few seconds. The broker's stop request (BrokerService.stop) goes through it.
     */
    fun postLocal(url: String, headers: Map<String, String>): Int? = null
}

/** The real environment: OS from [DesktopOs], uid via UnixSystem (guarded), PATH command probing. */
object SystemOsEnv : OsEnv {
    override val os: DesktopOs = DesktopOs.current

    override val home: Path = Path.of(System.getProperty("user.home") ?: ".")
    override val localAppData: Path = Path.of(
        System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString(),
    )

    override val uid: Long? by lazy {
        // getuid is macOS/Linux-only; guarded so it never runs on Windows.
        if (os != DesktopOs.MACOS && os != DesktopOs.LINUX) null
        else runCatching { com.sun.security.auth.module.UnixSystem().uid }.getOrNull()
    }

    override val xdgRuntimeDir: String? get() = System.getenv("XDG_RUNTIME_DIR")

    /** How long [runResult] / [runCapture] let a command run before killing it. */
    @Volatile var defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS
    const val DEFAULT_TIMEOUT_MS = 30_000L

    // In-process (findOnPath): no `which` (missing on Windows and some Linux images) or `where` process.
    override fun hasCommand(name: String): Boolean =
        dev.supermux.desktop.platform.findOnPath(name, System.getenv("PATH"), os == DesktopOs.WINDOWS, System.getenv("PATHEXT")) != null

    override fun run(argv: List<String>): Boolean = runResult(argv).exit == 0

    override fun postLocal(url: String, headers: Map<String, String>): Int? = try {
        val request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
            .timeout(java.time.Duration.ofSeconds(5))
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build()
            .send(request, java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()
    } catch (_: Exception) {
        null
    }

    override fun runResult(argv: List<String>): OsEnv.RunResult = runResult(argv, defaultTimeoutMs)

    override fun runResult(argv: List<String>, timeoutMs: Long): OsEnv.RunResult = try {
        val p = ProcessBuilder(argv).start()
        p.outputStream.close()
        fun drain(s: java.io.InputStream): () -> String {
            var text = ""
            val t = Thread { text = runCatching { s.bufferedReader().readText() }.getOrDefault("") }
            t.isDaemon = true
            t.start()
            return { t.join(2_000); text }
        }
        val out = drain(p.inputStream)
        val err = drain(p.errorStream)
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            kill(p)
            OsEnv.RunResult(-1, out(), "timed out after $timeoutMs ms: ${argv.firstOrNull()}")
        } else {
            OsEnv.RunResult(p.exitValue(), out(), err())
        }
    } catch (e: Exception) {
        OsEnv.RunResult(-1, "", e.message ?: e.toString())
    }

    /** The command and everything it started (a `Start-Process -Wait`, a shell's children). */
    private fun kill(p: Process) {
        runCatching { p.descendants().forEach { it.destroyForcibly() } }
        p.destroyForcibly()
        runCatching { p.waitFor(2, TimeUnit.SECONDS) }
    }

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    override fun getenv(name: String): String? = System.getenv(name)

    override fun selfCgroup(): String? =
        if (os != DesktopOs.LINUX) null
        else runCatching { java.nio.file.Files.readString(Path.of("/proc/self/cgroup")) }.getOrNull()

    override fun runCapture(argv: List<String>): String? =
        runResult(argv).takeIf { it.exit == 0 }?.out
}
