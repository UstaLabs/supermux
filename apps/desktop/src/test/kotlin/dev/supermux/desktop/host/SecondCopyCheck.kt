package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories

/**
 * Card #77 on a real computer that already hosts with an installed copy of the app: this runs a
 * SECOND copy's supervisor (fresh app data, the same ~/.mux/state, the real service manager) through
 * what its user can do. Nothing it may touch is real: every command that could end, delete, register,
 * restart or kill something is refused and printed, and the env file it reads is a copy.
 *
 *   java -cp <desktop classes + test classes + runtime jars> dev.supermux.desktop.host.SecondCopyCheckKt <workDir> [port]
 *
 * The verdict is the last line: OK when nothing was refused (the second copy never tried).
 */
fun main(args: Array<String>) = runBlocking {
    val work = Path.of(args.getOrNull(0) ?: error("usage: SecondCopyCheck <workDir> [port]")).createDirectories()
    val port = args.getOrNull(1)?.toInt() ?: HostingPrefs.DEFAULT_PORT
    val real = SystemOsEnv
    // The first copy's definition files, read from copies: a removal (which must not happen) deletes a copy.
    val home = work.resolve("home")
    for (f in listOf("Library/LaunchAgents/${BrokerService.LAUNCHD_LABEL}.plist", ".config/systemd/user/${BrokerService.SYSTEMD_UNIT}",
        ".config/autostart/${BrokerService.XDG_AUTOSTART_FILE}", ".config/${BrokerService.XDG_ENV_FILE}")) copyTree(real.home.resolve(f), home.resolve(f))
    val appData = work.resolve("localappdata").also { copyTree(real.localAppData.resolve("Supermux"), it.resolve("Supermux")) }
    val guarded = GuardedOsEnv(real, home, appData)
    val stateDir = BrokerPaths.defaultStateDir()
    // An interrupted takeover's journal would be finished or undone by the copy's launch: not here.
    check(!Files.exists(stateDir.resolve("takeover-backup/pending.json"))) { "a takeover journal is pending in $stateDir; not running" }
    println("state dir ${stateDir}, port $port, ${real.os}")
    println("before: ${firstCopy(real)}")

    val scenarios = listOf(
        "the wizard's \"Connect to a different broker instead\", hosting already off" to HostingPrefs(hosting = false, port = port),
        "a first run (hosting and background on), then tray background off, then hosting off" to HostingPrefs(port = port),
        "a launch with background off, then hosting off" to HostingPrefs(background = false, port = port),
    )
    for ((i, s) in scenarios.withIndex()) {
        val (what, prefs) = s
        println("\n== $what")
        var saved = prefs
        val log = mutableListOf<String>()
        val sup = HostSupervisor(
            stateDir = stateDir,
            loadPrefs = { saved },
            savePrefs = { saved = it },
            osEnv = guarded,
            materialize = { error("a second copy must not copy a broker over the first copy's") },
            startChild = { error("no broker child here") },
            bundledBuild = { null },
            lateBundledBuild = { null },
            repoDir = { null },
            hostName = "second-copy-check",
            log = { log += it },
            identity = InstallIdentity(work.resolve("appdata-$i/install.json")),
        )
        if (prefs.hosting) {
            val first = launch { sup.ensure() }
            withTimeoutOrNull(60_000) { sup.status.first { it is HostingStatus.Running || it is HostingStatus.AskTakeover || it is HostingStatus.CantStart } }
            println("   launch: ${sup.status.value}")
            if (sup.status.value is HostingStatus.AskTakeover) sup.answerTakeover(false) // "Leave it alone"
            first.join()
            println("   then: ${sup.status.value}")
            if (prefs.background) {
                sup.setBackground(false)
                println("   tray background off: ${sup.status.value}")
            }
        }
        sup.setHosting(false)
        println("   hosting off: ${sup.status.value}")
        sup.quit()
        log.forEach { println("   log: $it") }
    }
    println("\nafter: ${firstCopy(real)}")
    println("broker on $port: ${HostProber.probe(port)}")
    if (guarded.refused.isEmpty()) println("OK: the second copy never tried to change the first copy's service")
    else guarded.refused.forEach { println("REFUSED: $it") }
}

/** What the service manager says about the first copy's service (read only). */
private fun firstCopy(env: OsEnv): String = when (env.os) {
    DesktopOs.WINDOWS -> env.runCapture(listOf("schtasks", "/Query", "/TN", BrokerService.WINDOWS_TASK_NAME, "/FO", "LIST", "/V"))
        ?.lines()?.filter { it.startsWith("Status:") || it.startsWith("Last Run Time:") || it.startsWith("Last Result:") }
        ?.joinToString(" | ") { it.replace(Regex("\\s+"), " ").trim() } ?: "no task"
    DesktopOs.MACOS -> env.runResult(listOf("launchctl", "print", "gui/${env.uid}/${BrokerService.LAUNCHD_LABEL}")).let { r ->
        if (r.exit != 0) "not loaded" else r.out.lines().filter { "state =" in it || "pid =" in it }.joinToString(" | ") { it.trim() }
    }
    else -> env.runCapture(listOf("systemctl", "--user", "is-active", BrokerService.SYSTEMD_NAME))?.trim() ?: "inactive"
}

/** The real OS for reading; every command that could change a service or a process is refused and kept. */
private class GuardedOsEnv(private val real: OsEnv, override val home: Path, override val localAppData: Path) : OsEnv by real {
    val refused: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
    override fun run(argv: List<String>): Boolean = runResult(argv).exit == 0
    override fun runResult(argv: List<String>): OsEnv.RunResult = runResult(argv, 30_000)
    override fun runResult(argv: List<String>, timeoutMs: Long): OsEnv.RunResult {
        if (changes(argv)) { refused += argv; return OsEnv.RunResult(1, "", "refused by the #77 check") }
        return real.runResult(argv, timeoutMs)
    }
    override fun runCapture(argv: List<String>): String? = runResult(argv).takeIf { it.exit == 0 }?.out

    private fun changes(argv: List<String>): Boolean {
        val cmd = argv.firstOrNull()?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase()?.removeSuffix(".exe") ?: return false
        val verb = argv.drop(1).firstOrNull { !it.startsWith("--") }?.lowercase()
        return when (cmd) {
            "schtasks" -> verb != "/query"
            "launchctl" -> verb !in setOf("print", "list")
            "systemctl" -> verb !in setOf("is-active", "is-enabled", "show", "status", "cat")
            "tasklist", "where", "which" -> false
            "powershell" -> argv.any { "RunAs" in it || "Stop-Process" in it || "Register" in it }
            else -> true
        }
    }
}

private fun copyTree(from: Path, to: Path) {
    if (Files.isRegularFile(from)) { to.parent.createDirectories(); from.copyTo(to, overwrite = true); return }
    if (!Files.isDirectory(from)) return
    Files.walk(from).use { s ->
        s.forEach { p ->
            val t = to.resolve(from.relativize(p).toString())
            if (Files.isDirectory(p)) t.createDirectories() else p.copyTo(t, overwrite = true)
        }
    }
}
