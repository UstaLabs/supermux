package dev.supermux.desktop.host

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/** A running broker process the app holds: a real [Process], an adopted [ProcessHandle], or a test fake. */
interface ChildHandle {
    val pid: Long?
    /** When the OS started it (epoch ms), used to tell our child from a reused pid. Null if unknown. */
    val startMillis: Long?
    val isAlive: Boolean
    /** Null while running, or when unknown (an adopted process). */
    val exitCode: Int?
    fun destroy()
    fun destroyForcibly()
    /** Completes when the process exits. Cancelling the returned future must not affect the process. */
    fun onExit(): CompletableFuture<*>
}

private fun ProcessHandle.startMillis(): Long? = runCatching { info().startInstant().map { it.toEpochMilli() }.orElse(null) }.getOrNull()

class ProcessChild(private val p: Process) : ChildHandle {
    override val pid: Long? get() = runCatching { p.pid() }.getOrNull()
    override val startMillis: Long? by lazy { runCatching { p.toHandle().startMillis() }.getOrNull() }
    override val isAlive: Boolean get() = p.isAlive
    override val exitCode: Int? get() = if (p.isAlive) null else runCatching { p.exitValue() }.getOrNull()
    override fun destroy() = p.destroy()
    override fun destroyForcibly() { p.destroyForcibly() }
    override fun onExit(): CompletableFuture<*> = p.onExit()
}

/** A broker child of a previous app run (the app restarted without stopping it), re-parented by pid. */
class ProcessHandleChild(private val h: ProcessHandle) : ChildHandle {
    override val pid: Long? get() = h.pid()
    override val startMillis: Long? get() = h.startMillis()
    override val isAlive: Boolean get() = h.isAlive
    override val exitCode: Int? get() = null
    override fun destroy() { h.destroy() }
    override fun destroyForcibly() { h.destroyForcibly() }
    override fun onExit(): CompletableFuture<*> = h.onExit()
}

/** How to launch the broker as the app's child. [env] is the COMPLETE environment (nothing inherited). */
data class ChildLaunch(val argv: List<String>, val env: Map<String, String>, val workDir: Path?, val log: Path)

fun defaultStartChild(l: ChildLaunch): ChildHandle {
    val pb = ProcessBuilder(l.argv)
    l.workDir?.let { pb.directory(it.toFile()) }
    pb.environment().clear()
    pb.environment().putAll(l.env)
    l.log.parent?.let { Files.createDirectories(it) }
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(l.log.toFile()))
    pb.redirectError(ProcessBuilder.Redirect.appendTo(l.log.toFile()))
    return ProcessChild(pb.start())
}

/** A live process as the OS reports it. */
data class ProcInfo(val startMillis: Long?, val command: String)

/** The OS process table (a seam: tests use a fake). */
interface ProcessTable {
    /** Null when no live process has [pid]. */
    fun info(pid: Long): ProcInfo?
    fun handle(pid: Long): ChildHandle?
}

object SystemProcessTable : ProcessTable {
    override fun info(pid: Long): ProcInfo? {
        val h = ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive } ?: return null
        val i = h.info()
        val cmd = i.command().orElse("") + " " + i.arguments().map { it.joinToString(" ") }.orElse("")
        return ProcInfo(h.startMillis(), cmd.trim())
    }

    override fun handle(pid: Long): ChildHandle? =
        ProcessHandle.of(pid).orElse(null)?.takeIf { it.isAlive }?.let(::ProcessHandleChild)
}

/** The bundled broker, or a dev `bun … src/main.ts`. */
fun isBrokerCommand(cmd: String): Boolean = "supermux-broker" in cmd || ("bun" in cmd && "src/main.ts" in cmd)

/** `<stateDir>/desktop-broker.pid`: `pid:startInstantEpochMillis` of the app's broker child. */
class ChildPidFile(private val file: Path) {
    data class Record(val pid: Long, val startMillis: Long?)

    fun read(): Record? = runCatching {
        val parts = Files.readString(file).trim().split(':')
        Record(parts[0].toLong(), parts.getOrNull(1)?.toLongOrNull())
    }.getOrNull()

    fun write(pid: Long, startMillis: Long?) {
        runCatching {
            file.parent?.let { Files.createDirectories(it) }
            Files.writeString(file, if (startMillis != null) "$pid:$startMillis" else "$pid")
        }
    }

    fun delete() {
        runCatching { Files.deleteIfExists(file) }
    }
}

/**
 * Re-parent the child a previous app run left behind, only if the pid is alive, started at the
 * recorded instant (±1 s: guards against pid reuse) and is still a broker command.
 */
fun adoptFromPidFile(rec: ChildPidFile.Record, table: ProcessTable): ChildHandle? {
    val info = table.info(rec.pid) ?: return null
    val recorded = rec.startMillis ?: return null
    val started = info.startMillis ?: return null
    if (abs(started - recorded) > 1_000) return null
    if (!isBrokerCommand(info.command)) return null
    return table.handle(rec.pid)
}

internal suspend fun awaitExit(c: ChildHandle, ms: Long): Boolean {
    if (!c.isAlive) return true
    // thenApply: a dependent future, so a timeout cancels it and never the process's own future.
    return withTimeoutOrNull(ms) { c.onExit().thenApply { }.await(); true } ?: !c.isAlive
}
