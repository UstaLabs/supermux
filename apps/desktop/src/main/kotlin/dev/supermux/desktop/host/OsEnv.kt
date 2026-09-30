package dev.supermux.desktop.host

import java.nio.file.Path

/**
 * Injectable OS seam: every launchctl / systemctl / schtasks / process call goes through here so
 * unit tests use a fake and never touch a real service. [SystemOsEnv] is the real one.
 */
interface OsEnv {
    enum class Os { MAC, LINUX, WINDOWS, OTHER }

    val os: Os
    val home: Path
    val localAppData: Path
    val uid: Long?
    val xdgRuntimeDir: String?
    fun hasCommand(name: String): Boolean

    /** Run [argv] best-effort; true iff it exited 0. Never throws. */
    fun run(argv: List<String>): Boolean

    data class RunResult(val exit: Int, val out: String, val err: String)

    /** Run [argv], capturing exit code, stdout and stderr. Never throws (failure to start = exit -1). */
    fun runResult(argv: List<String>): RunResult

    /** Sleep [ms] (a seam so retry loops do not slow tests). */
    fun sleep(ms: Long)

    /** Run [argv] and return its stdout, or null on failure. Never throws. */
    fun runCapture(argv: List<String>): String?
}

/** The real environment: OS from `os.name`, uid via UnixSystem (guarded), PATH command probing. */
object SystemOsEnv : OsEnv {
    override val os: OsEnv.Os = run {
        val name = System.getProperty("os.name")?.lowercase() ?: ""
        when {
            name.contains("mac") || name.contains("darwin") -> OsEnv.Os.MAC
            name.contains("win") -> OsEnv.Os.WINDOWS
            name.contains("nux") || name.contains("nix") -> OsEnv.Os.LINUX
            else -> OsEnv.Os.OTHER
        }
    }

    override val home: Path = Path.of(System.getProperty("user.home") ?: ".")
    override val localAppData: Path = Path.of(
        System.getenv("LOCALAPPDATA") ?: home.resolve("AppData/Local").toString(),
    )

    override val uid: Long? by lazy {
        // getuid is macOS/Linux-only; guarded so it never runs on Windows.
        if (os != OsEnv.Os.MAC && os != OsEnv.Os.LINUX) null
        else runCatching { com.sun.security.auth.module.UnixSystem().uid }.getOrNull()
    }

    override val xdgRuntimeDir: String? get() = System.getenv("XDG_RUNTIME_DIR")

    override fun hasCommand(name: String): Boolean =
        runCatching {
            val which = if (os == OsEnv.Os.WINDOWS) "where" else "which"
            ProcessBuilder(which, name)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)

    override fun run(argv: List<String>): Boolean = runResult(argv).exit == 0

    override fun runResult(argv: List<String>): OsEnv.RunResult = try {
        val p = ProcessBuilder(argv).start()
        p.outputStream.close()
        var err = ""
        val t = Thread { err = runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("") }
        t.isDaemon = true
        t.start()
        val out = p.inputStream.bufferedReader().readText()
        val exit = p.waitFor()
        t.join(2000)
        OsEnv.RunResult(exit, out, err)
    } catch (e: Exception) {
        OsEnv.RunResult(-1, "", e.message ?: e.toString())
    }

    override fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    override fun runCapture(argv: List<String>): String? = runCatching {
        val p = ProcessBuilder(argv).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    }.getOrNull()
}
