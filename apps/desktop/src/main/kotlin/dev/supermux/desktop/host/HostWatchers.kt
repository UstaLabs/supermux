package dev.supermux.desktop.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The supervisor's watch loops, one per mode. Each runs as THE single watch job; it takes
 * [HostSupervisor.lock] for every state change and drops out as soon as [HostSupervisor.watchGen]
 * moved on (a mode change cancelled and replaced it).
 */

/** Crash-loop and unhealthy-start counters for child mode. A healthy uptime of [HostSupervisor.Timing.healthyResetMs] resets both. */
internal class Retries(private val t: HostSupervisor.Timing) {
    private val exits = ArrayDeque<Long>()
    private var unhealthy = 0
    private var healthySince: Long? = null

    @Synchronized fun reset() { exits.clear(); unhealthy = 0; healthySince = null }

    @Synchronized fun healthy(now: Long) { unhealthy = 0; healthySince = now }

    /** Records an exit; returns the exits within the crash window. */
    @Synchronized fun exit(now: Long): Int {
        val since = healthySince
        if (since != null && now - since >= t.healthyResetMs) { exits.clear(); unhealthy = 0 }
        healthySince = null
        exits.addLast(now)
        while (exits.isNotEmpty() && now - exits.first() > t.crashWindowMs) exits.removeFirst()
        return exits.size
    }

    /** Records a start that never became healthy; returns how many in a row. */
    @Synchronized fun unhealthyStart(): Int = ++unhealthy

    /** The attempt number that indexes the backoff. */
    @Synchronized fun attempt(): Int = maxOf(exits.size, unhealthy, 1)
}

/** Child mode: restart on exit with backoff; a child alive but not answering for 3 polls is stuck. */
internal suspend fun HostSupervisor.watchChild(gen: Long) {
    var failedPolls = 0
    while (true) {
        val c = child ?: return
        if (!awaitExit(c, timing.watchPollMs)) {
            if (probe(currentPrefs.port).isOurs()) { failedPolls = 0; continue }
            if (++failedPolls < timing.stuckPolls) continue
            failedPolls = 0
            lock.withLock {
                if (gen != watchGen || child !== c) return
                log("the broker is running but not answering; restarting it")
                c.destroy()
            }
            if (!awaitExit(c, timing.stopGraceMs)) c.destroyForcibly()
            continue // the exit path below restarts it (and counts it)
        }
        failedPolls = 0
        if (!restartAfterExit(gen, c)) return
    }
}

/** True once a new child is healthy; false when the loop must end (cap hit, mode changed, superseded). */
private suspend fun HostSupervisor.restartAfterExit(gen: Long, c: ChildHandle): Boolean {
    val detached = lock.withLock {
        if (gen != watchGen || child !== c) return false
        val d = childDetached
        child = null
        childDetached = false
        pidFile.delete()
        if (ourServiceInstalled()) {
            // Our service definition is installed: it restarts the broker. Never start a child next to it.
            log("the broker stopped; our service is installed, so it restarts it")
            mode = HostSupervisor.Mode.SERVICE
            startWatch()
            return false
        }
        if (retries.exit(now()) > timing.maxExits) {
            fail("supermux keeps stopping." + logTail())
            return false
        }
        d
    }
    while (true) {
        val attempt = lock.withLock {
            if (gen != watchGen) return false
            retries.attempt().also { publish(HostingStatus.Restarting(it)) }
        }
        delay(timing.backoffMs[minOf(attempt, timing.backoffMs.size) - 1])
        val started = lock.withLock {
            if (gen != watchGen) return false
            val p = currentPrefs
            val bins = withContext(io) { materialize() }
            val why = launchChildLocked(p, bins, carriedStore.load(), detached = detached)
            if (why == null) {
                retries.healthy(now())
                publish(HostingStatus.Running(p.port, readOnly = false))
                true
            } else {
                log("restart failed: $why")
                if (retries.unhealthyStart() >= timing.maxUnhealthyStarts) {
                    fail("supermux didn't start after ${timing.maxUnhealthyStarts} tries. $why")
                    return false
                }
                false
            }
        }
        if (started) return true
    }
}

/** Service mode: the OS restarts it. Down for 30 s: kick it once; still down 30 s later: CantStart. */
internal suspend fun HostSupervisor.watchService(gen: Long) {
    var downSince: Long? = null
    var kicked = false
    while (true) {
        delay(timing.watchPollMs)
        val port = currentPrefs.port
        val r = probe(port)
        lock.withLock {
            if (gen != watchGen) return
            if (r is HostProbeResult.Supermux && r.managedBy == "desktop") {
                downSince = null
                kicked = false
                publishHostId(r.hostId)
                if (currentStatus !is HostingStatus.Running) publish(HostingStatus.Running(port, readOnly = false))
                return@withLock
            }
            val t = now()
            val since = downSince ?: t.also { downSince = it }
            if (t - since < timing.serviceDownMs) return@withLock
            if (!kicked) {
                kicked = true
                downSince = t
                publish(HostingStatus.Restarting(1))
                log("the broker service has been down for ${timing.serviceDownMs / 1000} s; restarting it")
                withContext(io) { BrokerService.restart(osEnv) }
            } else {
                watchGen++
                fail("supermux stopped and didn't come back after a restart." + logTail())
                return
            }
        }
    }
}

/** Read-only: never stops, restarts or edits the broker; only observes it. */
internal suspend fun HostSupervisor.watchReadOnly(gen: Long) {
    var downSince: Long? = null
    while (true) {
        delay(timing.watchPollMs)
        val port = currentPrefs.port
        val r = probe(port)
        lock.withLock {
            if (gen != watchGen) return
            if (r is HostProbeResult.Supermux) {
                downSince = null
                if (currentStatus !is HostingStatus.Running) publish(HostingStatus.Running(port, readOnly = true))
                return@withLock
            }
            val t = now()
            val since = downSince ?: t.also { downSince = it }
            if (t - since >= timing.readOnlyDownMs) {
                watchGen++
                fail("The broker set up outside the app stopped.")
                return
            }
        }
    }
}

/**
 * Our broker that we can neither re-parent nor stop (a previous app run, or the XDG autostart ran it
 * at login). Once the port has been free for [HostSupervisor.Timing.freeProbes] probes in a row,
 * start ours, unless our service definition is installed: then the service owns it.
 */
internal suspend fun HostSupervisor.watchOrphan(gen: Long) {
    var free = 0
    while (true) {
        delay(if (free > 0) timing.freeProbeMs else timing.watchPollMs)
        val p = currentPrefs
        if (probe(p.port) == HostProbeResult.PortFree) free++ else { free = 0; continue }
        if (free < timing.freeProbes) continue
        lock.withLock {
            if (gen != watchGen) return
            if (ourServiceInstalled()) {
                mode = HostSupervisor.Mode.SERVICE
                startWatch()
                return
            }
            publish(HostingStatus.Restarting(1))
            val bins = withContext(io) { materialize() }
            afterLaunch(p, launchLocked(p, bins, carriedStore.load(), allowChildFallback = true))
            return
        }
    }
}
