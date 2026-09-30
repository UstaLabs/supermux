package dev.supermux.desktop.host

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** A running broker process the app holds: a real [Process], an adopted [ProcessHandle], or a test fake. */
interface ChildHandle {
    val pid: Long?
    val isAlive: Boolean
    /** Null while running, or when unknown (an adopted process). */
    val exitCode: Int?
    fun destroy()
    fun destroyForcibly()
    /** Completes when the process exits. Cancelling the returned future must not affect the process. */
    fun onExit(): CompletableFuture<*>
}

class ProcessChild(private val p: Process) : ChildHandle {
    override val pid: Long? get() = runCatching { p.pid() }.getOrNull()
    override val isAlive: Boolean get() = p.isAlive
    override val exitCode: Int? get() = if (p.isAlive) null else runCatching { p.exitValue() }.getOrNull()
    override fun destroy() = p.destroy()
    override fun destroyForcibly() { p.destroyForcibly() }
    override fun onExit(): CompletableFuture<*> = p.onExit()
}

/** A broker child of a previous app run (the app restarted without stopping it), re-parented by pid. */
class ProcessHandleChild(private val h: ProcessHandle) : ChildHandle {
    override val pid: Long? get() = h.pid()
    override val isAlive: Boolean get() = h.isAlive
    override val exitCode: Int? get() = null
    override fun destroy() { h.destroy() }
    override fun destroyForcibly() { h.destroyForcibly() }
    override fun onExit(): CompletableFuture<*> = h.onExit()
}

/** How to launch the broker as the app's child. */
data class ChildLaunch(val argv: List<String>, val env: Map<String, String>, val workDir: Path?, val log: Path)

fun defaultStartChild(l: ChildLaunch): ChildHandle {
    val pb = ProcessBuilder(l.argv)
    l.workDir?.let { pb.directory(it.toFile()) }
    pb.environment().putAll(l.env)
    l.log.parent?.let { Files.createDirectories(it) }
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(l.log.toFile()))
    pb.redirectError(ProcessBuilder.Redirect.appendTo(l.log.toFile()))
    return ProcessChild(pb.start())
}

/** Only re-parent a live process whose command is a supermux broker (guards against pid reuse). */
fun defaultAdoptChild(pid: Long): ChildHandle? {
    val h = ProcessHandle.of(pid).orElse(null) ?: return null
    if (!h.isAlive) return null
    val cmd = h.info().command().orElse("") + " " + h.info().arguments().map { it.joinToString(" ") }.orElse("")
    val isBroker = "supermux-broker" in cmd || ("bun" in cmd && "src/main.ts" in cmd)
    return if (isBroker) ProcessHandleChild(h) else null
}

internal suspend fun awaitExit(c: ChildHandle, ms: Long): Boolean {
    if (!c.isAlive) return true
    // thenApply: a dependent future, so a timeout cancels it and never the process's own future.
    return withTimeoutOrNull(ms) { c.onExit().thenApply { }.await(); true } ?: !c.isAlive
}
