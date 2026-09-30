package dev.supermux.desktop.host

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * How the supervisor starts and stops the broker: the service install (with its child fallbacks),
 * the child spawn, the health wait, and the checks that keep a second broker off our state dir.
 * All of it runs under [HostSupervisor.lock].
 */

/**
 * Start the broker the way [prefs] says. Returns null once `/host` is healthy, else the reason.
 * Background mode installs the OS service; the dev checkout (no packaged broker) and, when
 * [allowChildFallback], a failed install (after removing its leftovers) run it as a child instead.
 */
internal suspend fun HostSupervisor.launchLocked(
    prefs: HostingPrefs,
    bins: HostBinaries.SidecarBinaries,
    carried: Map<String, String>,
    allowChildFallback: Boolean,
    healthTimeoutMs: Long = timing.healthTimeoutMs,
): String? {
    _backgroundError.value = null
    if (!prefs.background) return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
    val broker = bins.brokerPath
    if (broker == null) {
        _backgroundError.value = HostSupervisor.DEV_BACKGROUND
        log(HostSupervisor.DEV_BACKGROUND)
        return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
    }
    // Installing over OUR service replaces its broker (restart), so only a fresh install can add a second one.
    val replacingOurs = ourServiceInstalled()
    if (!replacingOurs) secondBrokerReason(prefs.port)?.let { return it }
    val spec = BrokerService.Spec(broker, brokerEnvFor(prefs, bins, carried), logFile)
    val failure = when (val result = withContext(io) { BrokerService.install(spec, osEnv) }) {
        is BrokerService.Result.Installed -> when {
            // Linux XDG autostart: nothing runs until the next login, so run it now ourselves.
            // Still background mode for the prefs and UI; the child outlives the app.
            result.path == BrokerService.xdgAutostartPath(osEnv) ->
                return launchChildLocked(prefs, bins, carried, healthTimeoutMs, detached = true)
            result.enabled -> {
                mode = HostSupervisor.Mode.SERVICE
                childDetached = false
                return awaitHealthy(prefs.port, null, healthTimeoutMs)
            }
            else -> {
                // systemd: `enable --now` failed, though the restart may still have started it.
                mode = HostSupervisor.Mode.SERVICE
                if (awaitHealthy(prefs.port, null, minOf(timing.systemdHealthMs, healthTimeoutMs)) == null) return null
                "the service didn't start"
            }
        }
        is BrokerService.Result.Failed -> result.message
        BrokerService.Result.Unsupported -> "not supported on this system"
        is BrokerService.Result.Removed -> result.toString()
    }
    if (!allowChildFallback) return failure
    // Never leave a definition that could start a second broker; if it can't be removed, no child either.
    removeServiceLocked(prefs.port)?.let { return it }
    mode = null
    _backgroundError.value = "Couldn't keep supermux running in the background: $failure"
    log(_backgroundError.value!!)
    val childWhy = launchChildLocked(prefs, bins, carried, healthTimeoutMs) ?: return null
    if (!replacingOurs) return childWhy
    // We replaced our own running service, removed it, and the child was refused too. Never end with
    // nothing running and our definition gone: put the service back (the old job is gone by now).
    log("the child fallback failed too ($childWhy); reinstalling the background service")
    return when (val again = withContext(io) { BrokerService.install(spec, osEnv) }) {
        is BrokerService.Result.Installed -> {
            mode = HostSupervisor.Mode.SERVICE
            childDetached = false
            _backgroundError.value = null
            awaitHealthy(prefs.port, null, healthTimeoutMs)
        }
        is BrokerService.Result.Failed -> "Couldn't restart the background service: ${again.message}"
        else -> "Couldn't restart the background service: $failure"
    }
}

/**
 * Copy the bundled binaries out of the app. In a packaged app whose copy failed, fall back to the
 * broker a previous version installed (and flag [HostSupervisor.lastCopyFailed]).
 */
internal suspend fun HostSupervisor.binaries(): HostBinaries.SidecarBinaries {
    val bins = withContext(io) { materialize() }
    lastCopyFailed = bins.brokerPath == null && packaged()
    if (!lastCopyFailed) return bins
    val os = when (osEnv.os) {
        OsEnv.Os.MAC -> HostBinaries.Os.MAC
        OsEnv.Os.LINUX -> HostBinaries.Os.LINUX
        OsEnv.Os.WINDOWS -> HostBinaries.Os.WINDOWS
        OsEnv.Os.OTHER -> HostBinaries.Os.OTHER
    }
    val previous = stateDir.resolve(HostBinaries.BIN_SUBDIR).resolve(HostBinaries.fileName(HostBinaries.Binary.Broker, os))
    if (!Files.isRegularFile(previous)) {
        log("couldn't copy the broker out of the app")
        return bins
    }
    log("couldn't copy the update out of the app; using the broker a previous version installed")
    return bins.copy(brokerPath = previous, binDir = bins.binDir ?: previous.parent)
}

/**
 * Remove our service, then wait (up to [HostSupervisor.Timing.portFreeMs]) for its broker to let go of
 * [port]. Null when done; else why not, and then no child may be started.
 */
internal suspend fun HostSupervisor.removeServiceLocked(port: Int): String? {
    val r = withContext(io) { BrokerService.remove(osEnv) }
    if (r is BrokerService.Result.Failed) return "Couldn't stop the background service: ${r.message}"
    awaitPortFree(port)
    return null
}

/** True once nothing answers on [port]; false after [timeoutMs]. */
internal suspend fun HostSupervisor.awaitPortFree(port: Int, timeoutMs: Long = timing.portFreeMs): Boolean {
    val deadline = now() + timeoutMs
    while (true) {
        if (probe(port) == HostProbeResult.PortFree) return true
        if (now() >= deadline) return false
        delay(timing.healthPollMs)
    }
}

internal suspend fun HostSupervisor.launchChildLocked(
    prefs: HostingPrefs,
    bins: HostBinaries.SidecarBinaries,
    carried: Map<String, String>,
    healthTimeoutMs: Long = timing.healthTimeoutMs,
    detached: Boolean = false,
): String? {
    if (quitting) return HostSupervisor.QUITTING
    stopChildLocked()
    secondBrokerReason(prefs.port)?.let { return it }
    val repo = if (bins.brokerPath == null) repoDir() else null
    val argv = bins.brokerPath?.let { listOf(it.toString()) }
        ?: repo?.let { listOf(bunPath(), it.resolve("src/main.ts").toString()) }
        ?: return "the supermux broker isn't bundled with this app"
    val env = childEnv(prefs, bins, carried)
    // Spawn and record atomically: a cancel here must never leave an untracked broker running.
    val c = withContext(NonCancellable) {
        runCatching { Files.createDirectories(stateDir) }
        val c = withContext(io) { startChild(ChildLaunch(argv, env, repo, logFile)) }
        if (quitting) {
            c.destroy()
            null
        } else {
            child = c
            childDetached = detached
            mode = HostSupervisor.Mode.CHILD
            c.pid?.let { pidFile.write(it, c.startMillis) }
            c
        }
    } ?: return HostSupervisor.QUITTING
    val why = awaitHealthy(prefs.port, c, healthTimeoutMs)
    if (why != null) stopChildLocked()
    return why
}

private fun HostSupervisor.brokerEnvFor(prefs: HostingPrefs, bins: HostBinaries.SidecarBinaries, carried: Map<String, String>) =
    brokerEnv(prefs, bins, carried, stateDir, hostName, existingPath, userHome, osEnv.os)

/** The app's env minus every inherited `MUX_*` key, plus ours. */
private fun HostSupervisor.childEnv(prefs: HostingPrefs, bins: HostBinaries.SidecarBinaries, carried: Map<String, String>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((k, v) in baseEnv()) if (!k.uppercase().startsWith("MUX_")) out[k] = v
    out.putAll(brokerEnvFor(prefs, bins, carried))
    return out
}

/** Poll `/host` until OUR broker answers. Null = healthy (and [hostId] set), else why not. */
internal suspend fun HostSupervisor.awaitHealthy(port: Int, c: ChildHandle?, timeoutMs: Long): String? {
    val deadline = now() + timeoutMs
    while (true) {
        if (c != null && !c.isAlive) return "supermux stopped while starting." + logTail()
        val r = probe(port)
        if (r is HostProbeResult.Supermux && r.managedBy == "desktop") {
            _hostId.value = r.hostId
            lastHealthyBuild = r.build
            return null
        }
        if (now() >= deadline) return "supermux didn't answer on port $port." + logTail()
        delay(timing.healthPollMs)
    }
}

internal suspend fun HostSupervisor.stopChildLocked() = withContext(NonCancellable) {
    val c = child ?: return@withContext
    child = null
    childDetached = false
    pidFile.delete()
    if (!c.isAlive) return@withContext
    c.destroy()
    if (!awaitExit(c, timing.stopGraceMs)) {
        c.destroyForcibly()
        awaitExit(c, 2_000)
    }
}

internal fun HostSupervisor.adoptOrphanLocked(): Boolean {
    val rec = pidFile.read() ?: return false
    val c = runCatching { adoptFromPidFile(rec, processes) }.getOrNull()?.takeIf { it.isAlive } ?: return false
    child = c
    childDetached = false
    return true
}

/** Our service definition (not the XDG autostart, which is supervised like a child). */
internal suspend fun HostSupervisor.ourServiceInstalled(): Boolean =
    withContext(io) { BrokerService.isOursInstalled(osEnv) && !BrokerService.isOursXdgAutostart(osEnv) }

/**
 * The broker's own guard against a second broker on one state dir reads /proc (Linux only), so
 * check its pid file here: a live process that isn't our child means don't start another.
 */
internal suspend fun HostSupervisor.secondBrokerReason(port: Int): String? {
    val pid = otherLiveBrokerPid() ?: return null
    // It may be exiting (a service job launchd is tearing down, a child we just stopped): a graceful
    // shutdown takes a few seconds. Wait for it to finish and the port to be free; still alive after
    // [HostSupervisor.Timing.exitingBrokerMs] means a real second broker.
    val deadline = now() + timing.exitingBrokerMs
    while (now() < deadline) {
        delay(timing.healthPollMs)
        if (otherLiveBrokerPid() == null && probe(port) == HostProbeResult.PortFree) return null
    }
    if (otherLiveBrokerPid() == null) return null
    return "supermux is already running on this computer (pid $pid) but isn't answering on port $port. Quit it, then try again."
}

/** The live pid in `broker.pid` when it isn't our child (and isn't a reused pid), else null. */
private fun HostSupervisor.otherLiveBrokerPid(): Long? {
    val pid = runCatching { Files.readString(brokerPidFile).trim().toLong() }.getOrNull() ?: return null
    if (pid == child?.pid) return null
    val info = processes.info(pid) ?: return null
    // A process that started after the pid file was written can't be the broker that wrote it (pid reuse).
    val written = runCatching { Files.getLastModifiedTime(brokerPidFile).toMillis() }.getOrNull()
    val started = info.startMillis
    if (written != null && started != null && started > written + 1_000) return null
    return pid
}

internal fun HostSupervisor.logTail(lines: Int = 20): String {
    val tail = runCatching {
        RandomAccessFile(logFile.toFile(), "r").use { f ->
            val len = f.length()
            val start = maxOf(0L, len - 16_384)
            f.seek(start)
            val buf = ByteArray((len - start).toInt())
            f.readFully(buf)
            String(buf, Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(lines)
        }
    }.getOrDefault(emptyList())
    return if (tail.isEmpty()) "" else "\n" + tail.joinToString("\n")
}
