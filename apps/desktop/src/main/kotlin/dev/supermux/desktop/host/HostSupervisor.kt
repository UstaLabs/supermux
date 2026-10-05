package dev.supermux.desktop.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts, updates, supervises and takes over the local broker on every launch (spec §HostSupervisor).
 *
 * Every state transition runs under ONE [Mutex] ([lock]), so the public setters never race [ensure]
 * or the watch loop. The watch loop (HostWatchers.kt) is a single job, cancelled and restarted on
 * every mode change; a generation counter ([watchGen]) makes a stale loop drop out as soon as it
 * next takes the lock. Waiting for the user's takeover answer happens OUTSIDE the lock.
 *
 * Never two brokers on one state dir: the broker refuses a second one itself (`broker.pid`), but only
 * once it runs, so before starting one the supervisor stops its own child and waits on a live
 * `broker.pid` ([secondBrokerCheck]): it adopts one that finishes starting and refuses one that never answers.
 *
 * No public method throws: failures are logged and published as [HostingStatus.CantStart].
 */
class HostSupervisor(
    internal val stateDir: Path = BrokerPaths.defaultStateDir(),
    private val loadPrefs: () -> HostingPrefs,
    private val savePrefs: (HostingPrefs) -> Unit,
    probe: suspend (port: Int) -> HostProbeResult = { port -> withContext(Dispatchers.IO) { HostProber.probe(port) } },
    internal val osEnv: OsEnv = SystemOsEnv,
    /** Copies the bundled broker/tmux/frpc/zmx out of the app image (blocking; run on [io]). */
    internal val materialize: () -> HostBinaries.SidecarBinaries = { HostBinaries.resolve(stateDir) },
    /** True in a packaged app (a resources dir): a null broker path then means the copy failed. */
    internal val packaged: () -> Boolean = { HostBinaries.isPackaged() },
    /** The bundled broker's build ("1.5.0 (abc)"), or null in a dev checkout / on failure. */
    private val bundledBuild: suspend () -> String? = { BrokerVersion.defaultBundledBuild(stateDir) },
    internal val startChild: (ChildLaunch) -> ChildHandle = ::defaultStartChild,
    internal val processes: ProcessTable = SystemProcessTable,
    /** The app's own environment; the child gets it minus [isInheritedServiceKey], plus [brokerEnv]. */
    internal val baseEnv: () -> Map<String, String> = System::getenv,
    internal val repoDir: () -> Path? = { DesktopHostBootstrap.detectRepoDir() },
    internal val bunPath: () -> String = ::defaultBunPath,
    internal val hostName: String = DesktopHostBootstrap.defaultHostName(),
    private val freePort: () -> Int = { ServerSocket(0).use { it.localPort } },
    internal val now: () -> Long = System::currentTimeMillis,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    internal val existingPath: String? = System.getenv("PATH"),
    internal val userHome: String = System.getProperty("user.home") ?: ".",
    internal val timing: Timing = Timing(),
    /** Stderr, plus `<stateDir>/desktop-host.log`: an app launched from Finder has no stdout. */
    internal val log: (String) -> Unit = defaultLog(stateDir),
    internal val carriedStore: CarriedEnvStore = CarriedEnvStore(stateDir.resolve("desktop-carried-env.json"), log),
) {
    data class Timing(
        val waitProbeMs: Long = 2_000,
        val waitMaxMs: Long = 30_000,
        val healthPollMs: Long = 500,
        val healthTimeoutMs: Long = 45_000,
        val takeoverHealthTimeoutMs: Long = 30_000,
        val systemdHealthMs: Long = 10_000,
        val watchPollMs: Long = 5_000,
        val serviceDownMs: Long = 30_000,
        val readOnlyDownMs: Long = 15_000,
        val freeProbeMs: Long = 2_000,
        val freeProbes: Int = 3,
        val stuckPolls: Int = 3,
        val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
        val crashWindowMs: Long = 120_000,
        val maxExits: Int = 5,
        val maxUnhealthyStarts: Int = 5,
        val healthyResetMs: Long = 60_000,
        val stopGraceMs: Long = 5_000,
        val portFreeMs: Long = 10_000,
        val exitingBrokerMs: Long = 15_000,
        val maxShortRuns: Int = 5,
    )

    internal enum class Mode { CHILD, SERVICE, READ_ONLY, ORPHAN }

    private val _gitAvailable = MutableStateFlow<Boolean?>(null)
    /** What the local broker's `/host` last said about git (legacy boolean; see [gitRequirement]). */
    val gitAvailable: StateFlow<Boolean?> = _gitAvailable.asStateFlow()

    private val _gitRequirement = MutableStateFlow<dev.supermux.net.GitRequirement?>(null)
    /**
     * The local broker's `requirements.git`, refreshed by every probe (the watchers poll), so the
     * wizard and Settings ▸ Hosting follow the broker's own 10 s re-check. Null: no answer yet.
     */
    val gitRequirement: StateFlow<dev.supermux.net.GitRequirement?> = _gitRequirement.asStateFlow()

    private val _keepAwake = MutableStateFlow<dev.supermux.net.KeepAwakeState?>(null)
    /**
     * The local broker's "Keep this computer awake" (`/host`'s `keepAwake`), refreshed by every
     * probe (the watchers poll every few seconds) and by the answer to a change ([publishKeepAwake]).
     * Null: no answer yet, or a broker older than keep-awake.
     */
    val keepAwake: StateFlow<dev.supermux.net.KeepAwakeState?> = _keepAwake.asStateFlow()

    /** The state the broker answered a `PUT /settings/keep-awake` with. */
    fun publishKeepAwake(state: dev.supermux.net.KeepAwakeState) {
        _keepAwake.value = state
    }

    /** Every probe of the saved port; a broker's answer also refreshes [gitAvailable] / [gitRequirement] / [keepAwake]. */
    internal val probe: suspend (port: Int) -> HostProbeResult = { port ->
        probe(port).also { r ->
            if (r is HostProbeResult.Supermux) {
                _gitAvailable.value = r.gitAvailable
                _gitRequirement.value = r.gitRequirement
                _keepAwake.value = r.keepAwake
            }
        }
    }

    /**
     * Run on every [quit], before the broker is stopped: the lid helper's lease and the app-held
     * sleep inhibitor are released on ANY quit. Hooks must be idempotent and must not throw.
     */
    private val quitHooks = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onQuit(hook: () -> Unit) {
        quitHooks += hook
    }

    private val _status = MutableStateFlow<HostingStatus>(HostingStatus.Starting)
    val status: StateFlow<HostingStatus> = _status.asStateFlow()

    private val _prefs = MutableStateFlow(runCatching(loadPrefs).getOrDefault(HostingPrefs()))
    val prefs: StateFlow<HostingPrefs> = _prefs.asStateFlow()

    internal val _hostId = MutableStateFlow<String?>(null)
    val hostId: StateFlow<String?> = _hostId.asStateFlow()

    /** Why the broker isn't running in the background although the user asked for it (then it runs as a child). */
    internal val _backgroundError = MutableStateFlow<String?>(null)
    val backgroundError: StateFlow<String?> = _backgroundError.asStateFlow()

    val localBaseUrl: String get() = "http://127.0.0.1:${_prefs.value.port}"
    val logFile: Path = stateDir.resolve("desktop-broker.log")
    internal val pidFile = ChildPidFile(stateDir.resolve("desktop-broker.pid"))
    internal val brokerPidFile: Path = stateDir.resolve("broker.pid")

    internal val lock = Mutex()
    @Volatile internal var mode: Mode? = null
        set(v) {
            if (field != v) log("mode: ${field?.name?.lowercase() ?: "none"} -> ${v?.name?.lowercase() ?: "none"}")
            field = v
        }
    @Volatile internal var child: ChildHandle? = null
    /** The child stands in for the Linux XDG autostart (starts only at login): it outlives the app. */
    @Volatile internal var childDetached = false
    @Volatile internal var quitting = false
    /** Serialises [quit] (the window, the `finally` and the shutdown hook may all call it); never [lock]. */
    private val quitMonitor = Any()

    /**
     * Quitting the app stops the broker: it is our attached child (child mode, including the fallback
     * when the background install failed). False for a service, the detached XDG stand-in, read-only
     * and orphan modes, which keep running after the app.
     */
    val quitStopsBroker: Boolean get() = mode == Mode.CHILD && child != null && !childDetached
    @Volatile private var watchJob: Job? = null
    @Volatile internal var watchGen = 0L
    internal val retries = Retries(timing)
    @Volatile private var pending: Pending? = null
    private var questionGen = 0L
    private var bundled: String? = null
    /** An update restart ran on this launch: never restart for an update again (it would loop). */
    private var updateTried = false
    internal var lastHealthyBuild: String? = null
        set(v) { field = v; _build.value = v }

    private val _build = MutableStateFlow<String?>(null)
    /** The running broker's build ("1.5.0 (abc)") as its `/host` last reported it; null when unknown. */
    val build: StateFlow<String?> = _build.asStateFlow()
    /** The last [binaries] call couldn't copy the broker out of the packaged app. */
    @Volatile internal var lastCopyFailed = false

    private sealed interface Question {
        val hostId: String?
        val port: Int
        data class Takeover(override val hostId: String?, override val port: Int) : Question
        data class Downgrade(override val hostId: String, override val port: Int) : Question
    }

    /** An open question and its generation [token]. [answer] completes with null when it is abandoned. */
    private class Pending(val q: Question, val token: Long) {
        val answer = CompletableDeferred<Boolean?>()
    }

    // ── public API ─────────────────────────────────────────────────────────────────────

    /** App launch and "Try again": probe the saved port, decide, execute. Suspends while a takeover question is open. */
    suspend fun ensure() = guarded("ensure") {
        val p = lock.withLock { ensureLocked() } ?: return@guarded
        val reply: Boolean? = try {
            p.answer.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    if (pending?.token == p.token) {
                        pending = null
                        _status.value = HostingStatus.CantStart(INTERRUPTED)
                    }
                }
            }
            throw e
        }
        val yes = reply ?: return@guarded // abandoned (a newer ensure, hosting off, quit)
        log("answer: ${if (yes) "yes" else "no"}")
        lock.withLock {
            // The world may have moved on while the user was deciding.
            if (!_prefs.value.hosting || pending?.token != p.token) return@withLock
            pending = null
            if (yes) takeoverLocked(p.q) else leaveAloneLocked(p.q)
        }
    }

    /** "Let the app manage and update it?" Only answers an open takeover question. */
    fun answerTakeover(manage: Boolean) {
        pending?.takeIf { it.q is Question.Takeover }?.answer?.complete(manage)
    }

    /** "A newer supermux is running. Keep it, or use the app's version?" Only answers an open downgrade question. */
    fun answerDowngrade(useBundled: Boolean) {
        pending?.takeIf { it.q is Question.Downgrade }?.answer?.complete(useBundled)
    }

    suspend fun setBackground(on: Boolean) = guarded("setBackground") {
        log("background ${if (on) "on" else "off"} (asked)")
        lock.withLock {
            val p = _prefs.value.copy(background = on)
            savePrefsNow(p)
            // Read-only: not ours to move. Orphan: it holds the port; the next launch applies the choice.
            if (!p.hosting || mode == Mode.READ_ONLY || mode == Mode.ORPHAN || mode == null) return@withLock
            if (on) {
                if (mode == Mode.SERVICE) return@withLock
                val bins = binaries()
                if (bins.brokerPath == null) {
                    _backgroundError.value = DEV_BACKGROUND
                    log(DEV_BACKGROUND)
                    return@withLock
                }
                stopWatch()
                stopChildLocked()
                _status.value = HostingStatus.Starting
                afterLaunch(p, launchLocked(p, bins, carriedStore.load(), allowChildFallback = true))
            } else {
                _backgroundError.value = null
                if (mode == Mode.CHILD && !childDetached && child?.isAlive == true) {
                    // Child mode normally has nothing installed, but a definition written for the next
                    // login (the app ran as the 1.0.0 job), or a 1.0.0 one, would start at login.
                    removeOurDefinitionsLocked(p.port)?.let { why ->
                        savePrefsNow(p.copy(background = true))
                        _backgroundError.value = why
                        log(why)
                    }
                    return@withLock
                }
                stopWatch()
                removeOurDefinitionsLocked(p.port)?.let { why ->
                    // Still registered (e.g. UAC declined): it would respawn next to a child.
                    savePrefsNow(p.copy(background = true))
                    cantStart(why)
                    return@withLock
                }
                stopChildLocked()
                _status.value = HostingStatus.Starting
                val bins = binaries()
                afterLaunch(p, launchChildLocked(p, bins, carriedStore.load()))
            }
        }
    }

    suspend fun setHosting(on: Boolean) = guarded("setHosting") {
        log("hosting ${if (on) "on" else "off"} (asked)")
        if (on) {
            lock.withLock { savePrefsNow(_prefs.value.copy(hosting = true)) }
            ensure()
            return@guarded
        }
        lock.withLock {
            val p = _prefs.value.copy(hosting = false)
            savePrefsNow(p)
            abandonQuestion()
            val was = mode
            stopWatch()
            mode = null
            if (was != Mode.READ_ONLY) {
                removeOurDefinitionsLocked(p.port)?.let { why ->
                    // Still registered (e.g. UAC declined) or its broker still running: we are
                    // still hosting, so say so rather than claim "off".
                    savePrefsNow(p.copy(hosting = true))
                    cantStart(why)
                    return@withLock
                }
                stopChildLocked()
                // A broker of ours we could neither re-parent nor stop is still hosting.
                if (probe(p.port).isOurs()) {
                    _status.value = HostingStatus.CantStart(STILL_RUNNING)
                    return@withLock
                }
            }
            _hostId.value = null
            _build.value = null
            _gitAvailable.value = null
            _gitRequirement.value = null
            _keepAwake.value = null
            _status.value = HostingStatus.NotHosting
        }
    }

    suspend fun setRelay(on: Boolean) = guarded("setRelay") {
        log("relay ${if (on) "on" else "off"} (asked)")
        lock.withLock {
            val p = _prefs.value.copy(relay = on)
            savePrefsNow(p)
            if (p.hosting && (mode == Mode.SERVICE || mode == Mode.CHILD)) relaunchLocked(p)
        }
    }

    /** Tray/Settings "Restart" (and "Try again" when nothing is running). */
    suspend fun restart() = guarded("restart") {
        log("restart (asked)")
        val needsEnsure = lock.withLock {
            val p = _prefs.value
            if (!p.hosting) return@withLock false
            when (mode) {
                Mode.READ_ONLY -> false
                Mode.SERVICE -> {
                    stopWatch()
                    retries.reset()
                    _status.value = HostingStatus.Starting
                    withContext(io) { BrokerService.restart(osEnv) }
                    afterLaunch(p, awaitHealthy(p.port, null, timing.healthTimeoutMs))
                    false
                }
                Mode.CHILD -> { relaunchLocked(p); false }
                Mode.ORPHAN, null -> true
            }
        }
        if (needsEnsure) ensure()
    }

    /** "Even with the lid closed": only the saved choice; [KeepAwakeControls] holds the lease. */
    suspend fun setLidClosed(on: Boolean) = guarded("setLidClosed") {
        lock.withLock { savePrefsNow(_prefs.value.copy(lidClosed = on)) }
    }

    /** The user no longer wants [hostId] left alone: forget it and re-run the launch decision. */
    suspend fun forgetLeftAlone(hostId: String) = guarded("forgetLeftAlone") {
        lock.withLock {
            val p = _prefs.value
            if (hostId in p.leftAloneHostIds) savePrefsNow(p.copy(leftAloneHostIds = p.leftAloneHostIds - hostId))
        }
        ensure()
    }

    /**
     * The app is exiting. Child mode stops the child (destroy, 5 s grace, destroyForcibly). Service and
     * read-only modes make no OS calls. Blocking, never throws, never waits for [lock].
     */
    fun quit() = synchronized(quitMonitor) {
        for (hook in quitHooks) {
            try { hook() } catch (e: Exception) { log("quit hook: ${e.message ?: e}") }
        }
        try {
            quitting = true
            stopWatch()
            abandonQuestion()
            val c = child
            if (mode == Mode.CHILD && c != null && !childDetached) {
                log("quit: stopping our broker child (pid ${c.pid ?: "?"})")
                c.destroy()
                runCatching { c.onExit().get(timing.stopGraceMs, TimeUnit.MILLISECONDS) }
                if (c.isAlive) c.destroyForcibly()
                child = null
                pidFile.delete()
            }
        } catch (e: Exception) {
            log("quit: ${e.message ?: e}")
        }
    }

    // ── ensure ─────────────────────────────────────────────────────────────────────────

    /** Runs under [lock]. Returns an open question for the user, or null when done. */
    private suspend fun ensureLocked(): Pending? {
        abandonQuestion()
        stopWatch()
        retries.reset()
        var prefs = runCatching(loadPrefs).getOrElse { log("prefs: ${it.message}"); _prefs.value }
        _prefs.value = prefs
        if (!prefs.hosting) {
            mode = null
            _status.value = HostingStatus.NotHosting
            return null
        }
        _status.value = HostingStatus.Starting

        var found = probe(prefs.port)
        log("probe :${prefs.port}: ${found.describe()}")
        // Finish or undo a takeover a crash interrupted (journal in <stateDir>/takeover-backup).
        val healthy = found.isOurs()
        val recovered = withContext(io) { Takeover.recoverPending(stateDir, osEnv, ourServiceHealthy = healthy) }
        if (recovered) {
            log("an interrupted takeover was ${if (healthy) "finished" else "rolled back"}")
            runCatching { if (healthy) carriedStore.promotePending() else carriedStore.deletePending() }
            found = probe(prefs.port)
        }
        val rolledBack = recovered && !healthy

        bundled = runCatching { bundledBuild() }.getOrNull()
        val appState = stateDir.toString()
        var plan = decideHost(found, prefs, bundled, appState)
        // Busy = maybe a broker still booting; after a rollback the restored service needs a moment too.
        fun waiting(p: HostPlan) = p == HostPlan.Wait || (rolledBack && p == HostPlan.Start)
        if (waiting(plan)) {
            val deadline = now() + timing.waitMaxMs
            log("port ${prefs.port} is busy; waiting up to ${timing.waitMaxMs / 1000} s")
            while (waiting(plan) && now() < deadline) {
                delay(timing.waitProbeMs)
                found = probe(prefs.port)
                plan = decideHost(found, prefs, bundled, appState)
            }
            log("after waiting: ${found.describe()}")
        }
        if (rolledBack && plan == HostPlan.Start) return cantStart(RESTORED_SILENT) // never start ours next to it
        if (plan == HostPlan.Wait) plan = HostPlan.MovePort
        if (plan == HostPlan.UpdateOwn && updateTried) {
            log("already restarted for an update on this launch; keeping the running build")
            plan = HostPlan.UseOwn
        }
        log("plan: $plan (bundled ${bundled ?: "unknown"})")
        if (plan == HostPlan.Start || plan == HostPlan.MovePort) {
            if (child?.isAlive == true || adoptOrphanLocked()) {
                // It's ours, just not answering: restart it where it is rather than start a second one.
                log("our broker isn't answering on port ${prefs.port}; restarting it")
                stopChildLocked()
                plan = HostPlan.Start
            }
            // The plan came from a probe that found no supermux there: one answering now has just started.
            when (val sb = secondBrokerCheck(prefs.port, silentBefore = true)) {
                SecondBroker.None -> Unit
                is SecondBroker.Refuse -> return cantStart(sb.reason)
                is SecondBroker.Answering -> {
                    // It was still starting: decide again on what answers now (ours, or a takeover question).
                    found = sb.found
                    plan = decideHost(found, prefs, bundled, appState)
                    if (plan == HostPlan.UpdateOwn && updateTried) plan = HostPlan.UseOwn
                    log("a broker that was still starting answers now: ${found.describe()}; plan: $plan")
                }
            }
        }

        when (plan) {
            HostPlan.NotHosting -> { mode = null; _status.value = HostingStatus.NotHosting }
            HostPlan.Start -> {
                val bins = binaries()
                afterLaunch(prefs, launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true))
            }
            HostPlan.MovePort -> {
                val from = prefs.port
                prefs = prefs.copy(port = freePort())
                log("port $from is taken by something else; moving to ${prefs.port}")
                savePrefsNow(prefs)
                val bins = binaries()
                afterLaunch(prefs, launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true))
            }
            HostPlan.UseOwn -> {
                _build.value = (found as HostProbeResult.Supermux).build
                useOwnLocked(prefs, found.hostId)
            }
            HostPlan.UpdateOwn -> {
                log("update: ${(found as? HostProbeResult.Supermux)?.build ?: "unknown"} -> ${bundled ?: "unknown"}")
                updateOwnLocked(prefs)
            }
            HostPlan.ReadOnly -> {
                _build.value = (found as HostProbeResult.Supermux).build
                readOnlyLocked(prefs, found.hostId)
            }
            is HostPlan.AskTakeover -> return ask(Question.Takeover(plan.hostId, prefs.port))
            is HostPlan.AskDowngrade -> return ask(Question.Downgrade(plan.hostId, prefs.port))
            HostPlan.Wait -> Unit // unreachable: turned into MovePort above
        }
        return null
    }

    private fun ask(q: Question): Pending {
        log("asking: ${q::class.simpleName?.lowercase()} for ${q.hostId ?: "a pre-/host broker"} on port ${q.port}")
        val p = Pending(q, ++questionGen)
        pending = p
        _status.value = when (q) {
            is Question.Takeover -> HostingStatus.AskTakeover(q.hostId)
            is Question.Downgrade -> HostingStatus.AskDowngrade(q.hostId)
        }
        return p
    }

    private fun abandonQuestion() {
        pending?.answer?.complete(null)
        pending = null
    }

    private fun cantStart(reason: String): Pending? {
        log("can't start: $reason")
        mode = null
        _status.value = HostingStatus.CantStart(reason)
        return null
    }

    private suspend fun useOwnLocked(prefs: HostingPrefs, hostId: String) {
        _hostId.value = hostId
        if (serviceOverPreviousChildLocked(prefs.port)) return
        mode = when {
            claimChildLocked() -> Mode.CHILD
            ourServiceInstalled() -> Mode.SERVICE
            // Ours, but neither a service nor a child we can re-parent (or the XDG autostart ran it at
            // login): watch it, and start our own once it goes away. Starting one now would be a second broker.
            else -> Mode.ORPHAN
        }
        _status.value = HostingStatus.Running(prefs.port, readOnly = false)
        startWatch()
    }

    /**
     * Our child from a previous run is alive, but our (non-XDG) service definition is installed: the
     * service wins, never a service+child pair. Stop the child, wait for the port, reinstall the
     * service. Not [BrokerService.remove]: on Windows its taskkill would hit the child too.
     */
    private suspend fun serviceOverPreviousChildLocked(port: Int): Boolean {
        if (child?.isAlive == true || !ourServiceInstalled() || !adoptOrphanLocked()) return false
        log("our broker child from a previous session runs next to our installed service; switching to the service")
        stopChildLocked()
        awaitPortFree(port)
        val p = _prefs.value.copy(background = true)
        savePrefsNow(p)
        afterLaunch(p, launchLocked(p, binaries(), carriedStore.load(), allowChildFallback = true))
        return true
    }

    /** Our live child, or our child from a previous run re-parented: child mode whatever the prefs say. */
    private suspend fun claimChildLocked(): Boolean {
        if (child?.isAlive == true) return true
        if (!adoptOrphanLocked()) return false
        if (withContext(io) { BrokerService.isOursXdgAutostart(osEnv) }) {
            childDetached = true // the XDG autostart's stand-in: still background mode
        } else if (_prefs.value.background) {
            savePrefsNow(_prefs.value.copy(background = false))
            _backgroundError.value = PREVIOUS_CHILD
            log(PREVIOUS_CHILD)
        }
        return true
    }

    private suspend fun updateOwnLocked(prefs0: HostingPrefs) {
        updateTried = true
        val windows = osEnv.os == OsEnv.Os.WINDOWS
        if (serviceOverPreviousChildLocked(prefs0.port)) { warnIfStillOld(); return }
        if (claimChildLocked()) {
            val prefs = _prefs.value
            val detached = childDetached
            if (windows) stopChildLocked() // Windows can't replace a running .exe
            val bins = binaries()
            if (lastCopyFailed && !windows) return keepRunning(prefs, Mode.CHILD)
            stopChildLocked()
            val why = if (detached) launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true)
            else launchChildLocked(prefs, bins, carriedStore.load())
            afterLaunch(prefs, why)
            warnIfStillOld()
            return
        }
        if (ourServiceInstalled()) {
            // Our service: restart it the way it runs, whatever prefs.background says.
            // Windows can't replace a running .exe: stop the task's broker first (no elevation; the
            // install below restarts the task, and only asks for UAC when its definition changed).
            if (windows && !stopWindowsServiceLocked(prefs0.port)) return keepServiceAfterFailedStop(prefs0)
            val bins = binaries()
            if (lastCopyFailed && !windows) return keepRunning(prefs0, Mode.SERVICE)
            afterLaunch(prefs0, launchLocked(prefs0.copy(background = true), bins, carriedStore.load(), allowChildFallback = true, serviceStopped = windows))
            warnIfStillOld()
            return
        }
        log("our broker is running outside this app's control; the new build applies when it restarts")
        binaries()
        mode = Mode.ORPHAN
        _status.value = HostingStatus.Running(prefs0.port, readOnly = false)
        startWatch()
    }

    private fun keepRunning(prefs: HostingPrefs, m: Mode) {
        log("couldn't copy the new broker out of the app; keeping the running one")
        mode = m
        _status.value = HostingStatus.Running(prefs.port, readOnly = false)
        startWatch()
    }

    private fun warnIfStillOld() {
        if (_status.value is HostingStatus.Running && !BrokerVersion.sameBuild(lastHealthyBuild, bundled)) {
            log("warning: after the update restart the broker reports $lastHealthyBuild, not $bundled")
        }
    }

    private fun readOnlyLocked(prefs: HostingPrefs, hostId: String?) {
        mode = Mode.READ_ONLY
        _hostId.value = hostId
        _status.value = HostingStatus.Running(prefs.port, readOnly = true)
        startWatch()
    }

    private fun leaveAloneLocked(q: Question) {
        log("leaving ${q.hostId ?: "it"} alone (read-only)")
        var p = _prefs.value
        q.hostId?.let { p = p.copy(leftAloneHostIds = p.leftAloneHostIds + it) }
        savePrefsNow(p)
        readOnlyLocked(p, q.hostId)
    }

    // ── takeover ───────────────────────────────────────────────────────────────────────

    private suspend fun takeoverLocked(q: Question) {
        stopWatch()
        _status.value = HostingStatus.Starting
        val olds = withContext(io) { Takeover.findOldServices(osEnv) }
        log("takeover: found ${olds.size} old service(s)${if (olds.isEmpty()) "" else ": " + olds.joinToString { it.name }}")
        if (olds.isEmpty()) {
            cantStart("supermux is already running on port ${q.port} but wasn't started by a service. Quit it, then try again.")
            return
        }
        val prepared = when (val r = withContext(io) { Takeover.prepare(olds, stateDir, osEnv) }) {
            is Takeover.PrepareResult.Failed -> { cantStart("Couldn't take over: ${r.reason}"); return }
            is Takeover.PrepareResult.Ok -> r.prepared
        }
        log("takeover: old service(s) backed up and stopped; carrying ${prepared.carriedEnv.size} setting(s)")
        val prefsBefore = _prefs.value
        val why = try {
            carriedStore.savePending(prepared.carriedEnv)
            val p = prefsBefore.copy(relay = prepared.oldRelay ?: prefsBefore.relay)
            savePrefsNow(p)
            val bins = binaries()
            launchLocked(p, bins, prepared.carriedEnv, allowChildFallback = false, healthTimeoutMs = timing.takeoverHealthTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (why == null) {
            log("takeover: our broker is healthy; committing")
            withContext(io) { Takeover.commit(prepared, osEnv) }
            runCatching { carriedStore.promotePending() }.onFailure { log("couldn't save the carried-over settings: ${it.message}") }
            afterLaunch(_prefs.value, null)
            return
        }
        log("takeover: our broker didn't start; rolling back")
        stopChildLocked()
        val restored = withContext(io) { Takeover.rollback(prepared, osEnv) }
        log("takeover: rollback ${if (restored) "restored the old service" else "couldn't restore the old service"}")
        runCatching { carriedStore.deletePending() }
        savePrefsNow(_prefs.value.copy(relay = prefsBefore.relay))
        cantStart("Couldn't take over: $why" + if (restored) "" else " The old service couldn't be restored either.")
    }

    /** Publish the outcome of a launch and start watching. */
    internal fun afterLaunch(prefs: HostingPrefs, why: String?) {
        if (why == null) {
            retries.healthy(now())
            _status.value = HostingStatus.Running(prefs.port, readOnly = false)
            startWatch()
        } else {
            cantStart(why)
        }
    }

    /** Restart in the current mode with a freshly built env (relay change, tray restart in child mode). */
    private suspend fun relaunchLocked(p: HostingPrefs) {
        stopWatch()
        retries.reset()
        _status.value = HostingStatus.Starting
        val stopped = mode == Mode.SERVICE && osEnv.os == OsEnv.Os.WINDOWS
        if (stopped && !stopWindowsServiceLocked(p.port)) return keepServiceAfterFailedStop(p)
        val bins = binaries()
        val why = if (mode == Mode.SERVICE) {
            launchLocked(p.copy(background = true), bins, carriedStore.load(), allowChildFallback = true, serviceStopped = stopped)
        } else {
            val detached = childDetached
            stopChildLocked()
            if (detached) launchLocked(p, bins, carriedStore.load(), allowChildFallback = true)
            else launchChildLocked(p, bins, carriedStore.load())
        }
        afterLaunch(p, why)
    }

    // ── watching (loops in HostWatchers.kt) ────────────────────────────────────────────

    internal fun stopWatch() {
        watchGen++
        watchJob?.cancel()
        watchJob = null
    }

    internal fun startWatch() {
        stopWatch()
        val gen = watchGen
        val m = mode ?: return
        watchJob = scope.launch {
            try {
                when (m) {
                    Mode.CHILD -> watchChild(gen)
                    Mode.SERVICE -> watchService(gen)
                    Mode.READ_ONLY -> watchReadOnly(gen)
                    Mode.ORPHAN -> watchOrphan(gen)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("watch: ${e.message ?: e}")
                lock.withLock { if (gen == watchGen) cantStart(e.message ?: e.toString()) }
            }
        }
    }

    internal fun publish(s: HostingStatus) {
        _status.value = s
    }

    internal fun publishHostId(id: String) {
        _hostId.value = id
    }

    internal fun publishBuild(b: String?) {
        _build.value = b
    }

    internal fun fail(reason: String) {
        cantStart(reason)
    }

    internal val currentStatus: HostingStatus get() = _status.value
    internal val currentPrefs: HostingPrefs get() = _prefs.value

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private suspend inline fun guarded(what: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { runCatching { settleAfterCancel() } }
            throw e
        } catch (e: Exception) {
            log("$what failed: ${e.message ?: e}")
            _status.value = HostingStatus.CantStart(e.message ?: e.toString())
        }
    }

    /** A cancelled call must not leave the UI stuck in Starting/Restarting, or a broker untracked. */
    private suspend fun settleAfterCancel() = lock.withLock {
        val s = _status.value
        if (s != HostingStatus.Starting && s !is HostingStatus.Restarting) return@withLock
        val p = _prefs.value
        val r = withTimeoutOrNull(5_000) { runCatching { probe(p.port) }.getOrNull() }
        val readOnly = mode == Mode.READ_ONLY
        if (r is HostProbeResult.Supermux && (readOnly || r.managedBy == "desktop")) {
            _hostId.value = r.hostId
            if (mode == null) {
                mode = when {
                    child?.isAlive == true -> Mode.CHILD
                    ourServiceInstalled() -> Mode.SERVICE
                    else -> Mode.ORPHAN
                }
            }
            _status.value = HostingStatus.Running(p.port, readOnly)
            startWatch()
        } else {
            stopChildLocked()
            mode = null
            _status.value = HostingStatus.CantStart(INTERRUPTED)
        }
    }

    private fun HostProbeResult.describe(): String = when (this) {
        is HostProbeResult.Supermux -> buildString {
            append("supermux ").append(hostId)
            append(", build ").append(build ?: "?")
            append(", mode ").append(mode ?: "?")
            append(", managed by ").append(managedBy ?: "nothing")
            val ours = this@HostSupervisor.stateDir
            stateDir?.let { d ->
                val same = runCatching { Path.of(d).toRealPath() == ours.toRealPath() }.getOrDefault(d == ours.toString())
                append(if (same) ", our state dir" else ", another state dir")
            }
            if (gitRequirement?.ok == false) append(", no git")
        }
        HostProbeResult.Busy -> "busy (answers, not ready)"
        HostProbeResult.ForeignProcess -> "something else holds the port"
        HostProbeResult.PortFree -> "free"
    }

    private fun savePrefsNow(p: HostingPrefs) {
        _prefs.value = p
        try { savePrefs(p) } catch (e: Exception) { log("couldn't save hosting prefs: ${e.message}") }
    }

    companion object {
        const val INTERRUPTED = "Interrupted"
        const val QUITTING = "the app is quitting"
        const val DEV_BACKGROUND = "Background mode needs the packaged app; running while the app is open."
        const val PREVIOUS_CHILD =
            "supermux was already running with the app from a previous session, so it isn't running in the background. Turn background mode on again to switch."
        const val STILL_RUNNING = "supermux is still running from a previous session. Quit it to stop hosting."
        const val RESTORED_SILENT = "The previous supermux service was restored but isn't answering. Check it, then try again."
        const val NEXT_LOGIN =
            "An older version set supermux up to open the app at login. From your next login supermux runs in the background on its own; until then it runs with the app."

        fun defaultLog(stateDir: Path): (String) -> Unit {
            val file = HostLogFile(stateDir.resolve("desktop-host.log"))
            return { msg ->
                System.err.println("supermux host: $msg")
                file.append(msg)
            }
        }

        fun defaultBunPath(): String {
            HostBinaries.whichOnPath("bun")?.let { return it.toString() }
            val home = System.getProperty("user.home") ?: return "bun"
            val local = Path.of(home, ".bun", "bin", "bun")
            return if (Files.isExecutable(local)) local.toString() else "bun"
        }
    }
}

internal fun HostProbeResult.isOurs() = this is HostProbeResult.Supermux && managedBy == "desktop"
