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
import java.io.RandomAccessFile
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
 * Never two brokers on one state dir: the broker's own pid-file guard only works on Linux, so before
 * starting one the supervisor stops its own child and refuses when `broker.pid` names a live process.
 *
 * No public method throws: failures are logged and published as [HostingStatus.CantStart].
 */
class HostSupervisor(
    internal val stateDir: Path = BrokerPaths.defaultStateDir(),
    private val loadPrefs: () -> HostingPrefs,
    private val savePrefs: (HostingPrefs) -> Unit,
    internal val probe: suspend (port: Int) -> HostProbeResult = { port -> withContext(Dispatchers.IO) { HostProber.probe(port) } },
    internal val osEnv: OsEnv = SystemOsEnv,
    /** Copies the bundled broker/tmux/frpc/zmx out of the app image (blocking; run on [io]). */
    internal val materialize: () -> HostBinaries.SidecarBinaries = { HostBinaries.resolve(stateDir) },
    /** True in a packaged app (a resources dir): a null broker path then means the copy failed. */
    private val packaged: () -> Boolean = { HostBinaries.isPackaged() },
    /** The bundled broker's build ("1.5.0 (abc)"), or null in a dev checkout / on failure. */
    private val bundledBuild: suspend () -> String? = { BrokerVersion.defaultBundledBuild(stateDir) },
    private val startChild: (ChildLaunch) -> ChildHandle = ::defaultStartChild,
    private val processes: ProcessTable = SystemProcessTable,
    /** The app's own environment; the child gets it minus every `MUX_*` key, plus [brokerEnv]. */
    private val baseEnv: () -> Map<String, String> = System::getenv,
    private val repoDir: () -> Path? = { DesktopHostBootstrap.detectRepoDir() },
    private val bunPath: () -> String = ::defaultBunPath,
    private val hostName: String = DesktopHostBootstrap.defaultHostName(),
    private val freePort: () -> Int = { ServerSocket(0).use { it.localPort } },
    internal val now: () -> Long = System::currentTimeMillis,
    internal val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val existingPath: String? = System.getenv("PATH"),
    private val userHome: String = System.getProperty("user.home") ?: ".",
    internal val timing: Timing = Timing(),
    internal val log: (String) -> Unit = { System.err.println("supermux host: $it") },
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
    )

    internal enum class Mode { CHILD, SERVICE, READ_ONLY, ORPHAN }

    private val _status = MutableStateFlow<HostingStatus>(HostingStatus.Starting)
    val status: StateFlow<HostingStatus> = _status.asStateFlow()

    private val _prefs = MutableStateFlow(runCatching(loadPrefs).getOrDefault(HostingPrefs()))
    val prefs: StateFlow<HostingPrefs> = _prefs.asStateFlow()

    private val _hostId = MutableStateFlow<String?>(null)
    val hostId: StateFlow<String?> = _hostId.asStateFlow()

    /** Why the broker isn't running in the background although the user asked for it (then it runs as a child). */
    private val _backgroundError = MutableStateFlow<String?>(null)
    val backgroundError: StateFlow<String?> = _backgroundError.asStateFlow()

    val localBaseUrl: String get() = "http://127.0.0.1:${_prefs.value.port}"
    val logFile: Path = stateDir.resolve("desktop-broker.log")
    internal val pidFile = ChildPidFile(stateDir.resolve("desktop-broker.pid"))
    private val brokerPidFile: Path = stateDir.resolve("broker.pid")

    internal val lock = Mutex()
    @Volatile internal var mode: Mode? = null
    @Volatile internal var child: ChildHandle? = null
    /** The child stands in for the Linux XDG autostart (starts only at login): it outlives the app. */
    @Volatile internal var childDetached = false
    @Volatile private var quitting = false
    @Volatile private var watchJob: Job? = null
    @Volatile internal var watchGen = 0L
    internal val retries = Retries(timing)
    @Volatile private var pending: Pending? = null
    private var questionGen = 0L
    private var bundled: String? = null
    /** An update restart ran on this launch: never restart for an update again (it would loop). */
    private var updateTried = false
    private var lastHealthyBuild: String? = null

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
        lock.withLock {
            val p = _prefs.value.copy(background = on)
            savePrefsNow(p)
            // Read-only: not ours to move. Orphan: it holds the port; the next launch applies the choice.
            if (!p.hosting || mode == Mode.READ_ONLY || mode == Mode.ORPHAN || mode == null) return@withLock
            if (on) {
                if (mode == Mode.SERVICE) return@withLock
                val bins = withContext(io) { materialize() }
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
                if (mode == Mode.CHILD && !childDetached && child?.isAlive == true) return@withLock
                stopWatch()
                if (ourServiceInstalled()) withContext(io) { BrokerService.remove(osEnv) }
                stopChildLocked()
                _status.value = HostingStatus.Starting
                val bins = withContext(io) { materialize() }
                afterLaunch(p, launchChildLocked(p, bins, carriedStore.load()))
            }
        }
    }

    suspend fun setHosting(on: Boolean) = guarded("setHosting") {
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
                if (ourServiceInstalled()) withContext(io) { BrokerService.remove(osEnv) }
                stopChildLocked()
                // A broker of ours we could neither re-parent nor stop is still hosting.
                if (probe(p.port).isOurs()) {
                    _status.value = HostingStatus.CantStart(STILL_RUNNING)
                    return@withLock
                }
            }
            _hostId.value = null
            _status.value = HostingStatus.NotHosting
        }
    }

    suspend fun setRelay(on: Boolean) = guarded("setRelay") {
        lock.withLock {
            val p = _prefs.value.copy(relay = on)
            savePrefsNow(p)
            if (p.hosting && (mode == Mode.SERVICE || mode == Mode.CHILD)) relaunchLocked(p)
        }
    }

    /** Tray/Settings "Restart" (and "Try again" when nothing is running). */
    suspend fun restart() = guarded("restart") {
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
    fun quit() {
        try {
            quitting = true
            stopWatch()
            abandonQuestion()
            val c = child
            if (mode == Mode.CHILD && c != null && !childDetached) {
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
        // Finish or undo a takeover a crash interrupted (journal in <stateDir>/takeover-backup).
        val healthy = found.isOurs()
        val recovered = withContext(io) { Takeover.recoverPending(stateDir, osEnv, ourServiceHealthy = healthy) }
        if (recovered) {
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
            while (waiting(plan) && now() < deadline) {
                delay(timing.waitProbeMs)
                found = probe(prefs.port)
                plan = decideHost(found, prefs, bundled, appState)
            }
        }
        if (rolledBack && plan == HostPlan.Start) return cantStart(RESTORED_SILENT) // never start ours next to it
        if (plan == HostPlan.Wait) plan = HostPlan.MovePort
        if (plan == HostPlan.UpdateOwn && updateTried) {
            log("already restarted for an update on this launch; keeping the running build")
            plan = HostPlan.UseOwn
        }
        if (plan == HostPlan.Start || plan == HostPlan.MovePort) {
            if (child?.isAlive == true || adoptOrphanLocked()) {
                // It's ours, just not answering: restart it where it is rather than start a second one.
                log("our broker isn't answering on port ${prefs.port}; restarting it")
                stopChildLocked()
                plan = HostPlan.Start
            }
            secondBrokerReason(prefs.port)?.let { return cantStart(it) }
        }

        when (plan) {
            HostPlan.NotHosting -> { mode = null; _status.value = HostingStatus.NotHosting }
            HostPlan.Start -> {
                val bins = withContext(io) { materialize() }
                afterLaunch(prefs, launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true))
            }
            HostPlan.MovePort -> {
                prefs = prefs.copy(port = freePort())
                savePrefsNow(prefs)
                val bins = withContext(io) { materialize() }
                afterLaunch(prefs, launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true))
            }
            HostPlan.UseOwn -> useOwnLocked(prefs, (found as HostProbeResult.Supermux).hostId)
            HostPlan.UpdateOwn -> updateOwnLocked(prefs)
            HostPlan.ReadOnly -> readOnlyLocked(prefs, (found as HostProbeResult.Supermux).hostId)
            is HostPlan.AskTakeover -> return ask(Question.Takeover(plan.hostId, prefs.port))
            is HostPlan.AskDowngrade -> return ask(Question.Downgrade(plan.hostId, prefs.port))
            HostPlan.Wait -> Unit // unreachable: turned into MovePort above
        }
        return null
    }

    private fun ask(q: Question): Pending {
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
        if (claimChildLocked()) {
            val prefs = _prefs.value
            val detached = childDetached
            if (windows) stopChildLocked() // Windows can't replace a running .exe
            val bins = withContext(io) { materialize() }
            if (copyFailed(bins) && !windows) return keepRunning(prefs, Mode.CHILD)
            stopChildLocked()
            val why = if (detached) launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true)
            else launchChildLocked(prefs, bins, carriedStore.load())
            afterLaunch(prefs, why)
            warnIfStillOld()
            return
        }
        if (ourServiceInstalled()) {
            // Our service: restart it the way it runs, whatever prefs.background says.
            if (windows) withContext(io) { BrokerService.remove(osEnv) } // re-creating the task doesn't restart it
            val bins = withContext(io) { materialize() }
            if (copyFailed(bins) && !windows) return keepRunning(prefs0, Mode.SERVICE)
            afterLaunch(prefs0, launchLocked(prefs0.copy(background = true), bins, carriedStore.load(), allowChildFallback = true))
            warnIfStillOld()
            return
        }
        log("our broker is running outside this app's control; the new build applies when it restarts")
        withContext(io) { materialize() }
        mode = Mode.ORPHAN
        _status.value = HostingStatus.Running(prefs0.port, readOnly = false)
        startWatch()
    }

    private fun copyFailed(bins: HostBinaries.SidecarBinaries) = bins.brokerPath == null && packaged()

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
        if (olds.isEmpty()) {
            cantStart("supermux is already running on port ${q.port} but wasn't started by a service. Quit it, then try again.")
            return
        }
        val prepared = when (val r = withContext(io) { Takeover.prepare(olds, stateDir, osEnv) }) {
            is Takeover.PrepareResult.Failed -> { cantStart("Couldn't take over: ${r.reason}"); return }
            is Takeover.PrepareResult.Ok -> r.prepared
        }
        val prefsBefore = _prefs.value
        val why = try {
            carriedStore.savePending(prepared.carriedEnv)
            val p = prefsBefore.copy(relay = prepared.oldRelay ?: prefsBefore.relay)
            savePrefsNow(p)
            val bins = withContext(io) { materialize() }
            launchLocked(p, bins, prepared.carriedEnv, allowChildFallback = false, healthTimeoutMs = timing.takeoverHealthTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (why == null) {
            withContext(io) { Takeover.commit(prepared, osEnv) }
            runCatching { carriedStore.promotePending() }.onFailure { log("couldn't save the carried-over settings: ${it.message}") }
            afterLaunch(_prefs.value, null)
            return
        }
        stopChildLocked()
        val restored = withContext(io) { Takeover.rollback(prepared, osEnv) }
        runCatching { carriedStore.deletePending() }
        savePrefsNow(_prefs.value.copy(relay = prefsBefore.relay))
        cantStart("Couldn't take over: $why" + if (restored) "" else " The old service couldn't be restored either.")
    }

    // ── launching ──────────────────────────────────────────────────────────────────────

    /**
     * Start the broker the way [prefs] says. Returns null once `/host` is healthy, else the reason.
     * Background mode installs the OS service; the dev checkout (no packaged broker) and, when
     * [allowChildFallback], a failed install (after removing its leftovers) run it as a child instead.
     */
    internal suspend fun launchLocked(
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
            _backgroundError.value = DEV_BACKGROUND
            log(DEV_BACKGROUND)
            return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
        }
        val spec = BrokerService.Spec(broker, brokerEnvFor(prefs, bins, carried), logFile)
        val failure = when (val result = withContext(io) { BrokerService.install(spec, osEnv) }) {
            is BrokerService.Result.Installed -> when {
                // Linux XDG autostart: nothing runs until the next login, so run it now ourselves.
                // Still background mode for the prefs and UI; the child outlives the app.
                result.path == BrokerService.xdgAutostartPath(osEnv) ->
                    return launchChildLocked(prefs, bins, carried, healthTimeoutMs, detached = true)
                result.enabled -> {
                    mode = Mode.SERVICE
                    childDetached = false
                    return awaitHealthy(prefs.port, null, healthTimeoutMs)
                }
                else -> {
                    // systemd: `enable --now` failed, though the restart may still have started it.
                    mode = Mode.SERVICE
                    if (awaitHealthy(prefs.port, null, minOf(timing.systemdHealthMs, healthTimeoutMs)) == null) return null
                    "the service didn't start"
                }
            }
            is BrokerService.Result.Failed -> result.message
            BrokerService.Result.Unsupported -> "not supported on this system"
            is BrokerService.Result.Removed -> result.toString()
        }
        if (!allowChildFallback) return failure
        withContext(io) { BrokerService.remove(osEnv) } // never leave a definition that could start a second broker
        mode = null
        _backgroundError.value = "Couldn't keep supermux running in the background: $failure"
        log(_backgroundError.value!!)
        return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
    }

    internal suspend fun launchChildLocked(
        prefs: HostingPrefs,
        bins: HostBinaries.SidecarBinaries,
        carried: Map<String, String>,
        healthTimeoutMs: Long = timing.healthTimeoutMs,
        detached: Boolean = false,
    ): String? {
        if (quitting) return QUITTING
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
                mode = Mode.CHILD
                c.pid?.let { pidFile.write(it, c.startMillis) }
                c
            }
        } ?: return QUITTING
        val why = awaitHealthy(prefs.port, c, healthTimeoutMs)
        if (why != null) stopChildLocked()
        return why
    }

    private fun brokerEnvFor(prefs: HostingPrefs, bins: HostBinaries.SidecarBinaries, carried: Map<String, String>) =
        brokerEnv(prefs, bins, carried, stateDir, hostName, existingPath, userHome, osEnv.os)

    /** The app's env minus every inherited `MUX_*` key, plus ours. */
    private fun childEnv(prefs: HostingPrefs, bins: HostBinaries.SidecarBinaries, carried: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in baseEnv()) if (!k.uppercase().startsWith("MUX_")) out[k] = v
        out.putAll(brokerEnvFor(prefs, bins, carried))
        return out
    }

    /** Poll `/host` until OUR broker answers. Null = healthy (and [hostId] set), else why not. */
    internal suspend fun awaitHealthy(port: Int, c: ChildHandle?, timeoutMs: Long): String? {
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
        val bins = withContext(io) { materialize() }
        val why = if (mode == Mode.SERVICE) {
            if (osEnv.os == OsEnv.Os.WINDOWS) withContext(io) { BrokerService.remove(osEnv) }
            launchLocked(p.copy(background = true), bins, carriedStore.load(), allowChildFallback = true)
        } else {
            val detached = childDetached
            stopChildLocked()
            if (detached) launchLocked(p, bins, carriedStore.load(), allowChildFallback = true)
            else launchChildLocked(p, bins, carriedStore.load())
        }
        afterLaunch(p, why)
    }

    internal suspend fun stopChildLocked() = withContext(NonCancellable) {
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

    private fun adoptOrphanLocked(): Boolean {
        val rec = pidFile.read() ?: return false
        val c = runCatching { adoptFromPidFile(rec, processes) }.getOrNull()?.takeIf { it.isAlive } ?: return false
        child = c
        childDetached = false
        return true
    }

    /** Our service definition (not the XDG autostart, which is supervised like a child). */
    internal suspend fun ourServiceInstalled(): Boolean =
        withContext(io) { BrokerService.isOursInstalled(osEnv) && !BrokerService.isOursXdgAutostart(osEnv) }

    /**
     * The broker's own guard against a second broker on one state dir reads /proc (Linux only), so
     * check its pid file here: a live process that isn't our child means don't start another.
     */
    internal fun secondBrokerReason(port: Int): String? {
        val pid = runCatching { Files.readString(brokerPidFile).trim().toLong() }.getOrNull() ?: return null
        if (pid == child?.pid) return null
        val info = processes.info(pid) ?: return null
        // A process that started after the pid file was written can't be the broker that wrote it (pid reuse).
        val written = runCatching { Files.getLastModifiedTime(brokerPidFile).toMillis() }.getOrNull()
        val started = info.startMillis
        if (written != null && started != null && started > written + 1_000) return null
        return "supermux is already running on this computer (pid $pid) but isn't answering on port $port. Quit it, then try again."
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

    private fun savePrefsNow(p: HostingPrefs) {
        _prefs.value = p
        try { savePrefs(p) } catch (e: Exception) { log("couldn't save hosting prefs: ${e.message}") }
    }

    internal fun logTail(lines: Int = 20): String {
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

    companion object {
        const val INTERRUPTED = "Interrupted"
        const val QUITTING = "the app is quitting"
        const val DEV_BACKGROUND = "Background mode needs the packaged app; running while the app is open."
        const val PREVIOUS_CHILD =
            "supermux was already running with the app from a previous session, so it isn't running in the background. Turn background mode on again to switch."
        const val STILL_RUNNING = "supermux is still running from a previous session. Quit it to stop hosting."
        const val RESTORED_SILENT = "The previous supermux service was restored but isn't answering. Check it, then try again."

        fun defaultBunPath(): String {
            HostBinaries.whichOnPath("bun")?.let { return it.toString() }
            val home = System.getProperty("user.home") ?: return "bun"
            val local = Path.of(home, ".bun", "bin", "bun")
            return if (Files.isExecutable(local)) local.toString() else "bun"
        }
    }
}

internal fun HostProbeResult.isOurs() = this is HostProbeResult.Supermux && managedBy == "desktop"
