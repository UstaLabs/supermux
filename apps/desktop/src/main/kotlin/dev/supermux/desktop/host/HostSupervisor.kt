package dev.supermux.desktop.host

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Starts, updates, supervises and takes over the local broker on every launch (spec §HostSupervisor).
 *
 * Every state transition runs under ONE [Mutex] ([lock]), so the public setters never race [ensure]
 * or the watch loop. The watch loop is a single job, cancelled and restarted on every mode change;
 * a generation counter ([watchGen]) makes a stale loop drop out as soon as it next takes the lock.
 * Waiting for the user's takeover answer happens OUTSIDE the lock so Settings stays usable meanwhile.
 *
 * No public method throws: failures are logged and published as [HostingStatus.CantStart].
 */
class HostSupervisor(
    private val stateDir: Path = BrokerPaths.defaultStateDir(),
    private val loadPrefs: () -> HostingPrefs,
    private val savePrefs: (HostingPrefs) -> Unit,
    private val probe: suspend (port: Int) -> HostProbeResult = { port -> withContext(Dispatchers.IO) { HostProber.probe(port) } },
    private val osEnv: OsEnv = SystemOsEnv,
    /** Copies the bundled broker/tmux/frpc/zmx out of the app image (blocking; run on [io]). */
    private val materialize: () -> HostBinaries.SidecarBinaries = { HostBinaries.resolve(stateDir) },
    /** The bundled broker's build ("1.5.0 (abc)"), or null in a dev checkout / on failure. */
    private val bundledBuild: suspend () -> String? = { BrokerVersion.defaultBundledBuild(stateDir) },
    private val startChild: (ChildLaunch) -> ChildHandle = ::defaultStartChild,
    /** Re-parent a broker child left by a previous app run, if [pid] is still that broker. */
    private val adoptChild: (pid: Long) -> ChildHandle? = ::defaultAdoptChild,
    private val repoDir: () -> Path? = { DesktopHostBootstrap.detectRepoDir() },
    private val bunPath: () -> String = ::defaultBunPath,
    private val hostName: String = DesktopHostBootstrap.defaultHostName(),
    private val freePort: () -> Int = { ServerSocket(0).use { it.localPort } },
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val carriedStore: CarriedEnvStore = CarriedEnvStore(stateDir.resolve("desktop-carried-env.json")),
    private val existingPath: String? = System.getenv("PATH"),
    private val userHome: String = System.getProperty("user.home") ?: ".",
    private val timing: Timing = Timing(),
    private val log: (String) -> Unit = { System.err.println("supermux host: $it") },
) {
    data class Timing(
        val waitProbeMs: Long = 2_000,
        val waitMaxMs: Long = 30_000,
        val healthPollMs: Long = 500,
        val healthTimeoutMs: Long = 45_000,
        val takeoverHealthTimeoutMs: Long = 30_000,
        val watchPollMs: Long = 5_000,
        val serviceDownMs: Long = 30_000,
        val readOnlyDownMs: Long = 15_000,
        val backoffMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000),
        val crashWindowMs: Long = 120_000,
        val maxExits: Int = 5,
        val stopGraceMs: Long = 5_000,
    )

    private enum class Mode { CHILD, SERVICE, READ_ONLY, ORPHAN }

    private val _status = MutableStateFlow<HostingStatus>(HostingStatus.Starting)
    val status: StateFlow<HostingStatus> = _status.asStateFlow()

    private val _prefs = MutableStateFlow(runCatching(loadPrefs).getOrDefault(HostingPrefs()))
    val prefs: StateFlow<HostingPrefs> = _prefs.asStateFlow()

    private val _hostId = MutableStateFlow<String?>(null)
    val hostId: StateFlow<String?> = _hostId.asStateFlow()

    /** "Couldn't keep supermux running in the background: …" after a failed service install (then it runs as a child). */
    private val _backgroundError = MutableStateFlow<String?>(null)
    val backgroundError: StateFlow<String?> = _backgroundError.asStateFlow()

    val localBaseUrl: String get() = "http://127.0.0.1:${_prefs.value.port}"
    val logFile: Path = stateDir.resolve("desktop-broker.log")
    private val pidFile: Path = stateDir.resolve("desktop-broker.pid")

    private val lock = Mutex()
    @Volatile private var mode: Mode? = null
    @Volatile private var child: ChildHandle? = null
    /** Linux XDG fallback: the child stands in for a service that only starts at next login; it outlives the app. */
    @Volatile private var childDetached = false
    @Volatile private var quitting = false
    @Volatile private var watchJob: Job? = null
    @Volatile private var watchGen = 0L
    private val exits = ArrayDeque<Long>()
    /** The open takeover/downgrade question. Completed with null when it is abandoned. */
    @Volatile private var question: CompletableDeferred<Boolean?>? = null

    private sealed interface Question {
        val hostId: String?
        val port: Int
        data class Takeover(override val hostId: String?, override val port: Int) : Question
        data class Downgrade(override val hostId: String, override val port: Int) : Question
    }

    // ── public API ─────────────────────────────────────────────────────────────────────

    /** App launch and "Try again": probe the saved port, decide, execute. Suspends while a takeover question is open. */
    suspend fun ensure() = guarded("ensure") {
        val q = lock.withLock { ensureLocked() } ?: return@guarded
        val answer = CompletableDeferred<Boolean?>()
        question?.complete(null)
        question = answer
        _status.value = when (q) {
            is Question.Takeover -> HostingStatus.AskTakeover(q.hostId)
            is Question.Downgrade -> HostingStatus.AskDowngrade(q.hostId)
        }
        val reply: Boolean? = try { answer.await() } finally { if (question === answer) question = null }
        val yes = reply ?: return@guarded // abandoned (a newer ensure, hosting off, quit)
        lock.withLock {
            if (yes) takeoverLocked(q) else leaveAloneLocked(q)
        }
    }

    /** "Let the app manage and update it?" */
    fun answerTakeover(manage: Boolean) {
        question?.complete(manage)
    }

    /** "A newer supermux is running. Keep it, or use the app's version?" */
    fun answerDowngrade(useBundled: Boolean) {
        question?.complete(useBundled)
    }

    suspend fun setBackground(on: Boolean) = guarded("setBackground") {
        lock.withLock {
            val before = _prefs.value
            val p = before.copy(background = on)
            savePrefsNow(p)
            // Read-only: not ours to move. Orphan: it holds the port; the next launch applies the choice.
            if (!p.hosting || mode == Mode.READ_ONLY || mode == Mode.ORPHAN || mode == null) return@withLock
            if (on) {
                if (mode == Mode.SERVICE) return@withLock
                val bins = withContext(io) { materialize() }
                if (bins.brokerPath == null) {
                    log("background mode needs the packaged app; the dev broker keeps running as the app's child")
                    return@withLock
                }
                stopWatch()
                stopChildLocked()
                _status.value = HostingStatus.Starting
                val why = launchLocked(p, bins, carriedStore.load(), allowChildFallback = true)
                afterLaunch(p, why)
            } else {
                if (mode == Mode.CHILD && !childDetached && child?.isAlive == true) return@withLock
                stopWatch()
                if (ourServiceInstalled()) withContext(io) { BrokerService.remove(osEnv) }
                stopChildLocked()
                _status.value = HostingStatus.Starting
                val bins = withContext(io) { materialize() }
                val why = launchChildLocked(p, bins, carriedStore.load())
                afterLaunch(p, why)
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
            savePrefsNow(_prefs.value.copy(hosting = false))
            val wasReadOnly = mode == Mode.READ_ONLY
            stopWatch()
            question?.complete(null)
            if (!wasReadOnly) {
                if (ourServiceInstalled()) withContext(io) { BrokerService.remove(osEnv) }
                stopChildLocked()
            }
            mode = null
            _hostId.value = null
            _status.value = HostingStatus.NotHosting
        }
    }

    suspend fun setRelay(on: Boolean) = guarded("setRelay") {
        lock.withLock {
            val p = _prefs.value.copy(relay = on)
            savePrefsNow(p)
            if (!p.hosting) return@withLock
            when (mode) {
                Mode.SERVICE, Mode.CHILD -> relaunchLocked(p)
                else -> Unit
            }
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
                    _status.value = HostingStatus.Starting
                    resetExits()
                    withContext(io) { BrokerService.restart(osEnv) }
                    val why = awaitHealthy(p.port, null, timing.healthTimeoutMs)
                    afterLaunch(p, why)
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
            stopWatch()
            mode = null
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
            question?.complete(null)
            val c = child
            if (mode == Mode.CHILD && c != null && !childDetached) {
                c.destroy()
                runCatching { c.onExit().get(timing.stopGraceMs, TimeUnit.MILLISECONDS) }
                if (c.isAlive) c.destroyForcibly()
                child = null
                runCatching { Files.deleteIfExists(pidFile) }
            }
        } catch (e: Exception) {
            log("quit: ${e.message ?: e}")
        }
    }

    // ── ensure ─────────────────────────────────────────────────────────────────────────

    /** Runs under [lock]. Returns a question for the user, or null when done. */
    private suspend fun ensureLocked(): Question? {
        // A question left open by an earlier ensure is stale now: answering it must not act.
        question?.complete(null)
        stopWatch()
        resetExits()
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
        val healthy = found is HostProbeResult.Supermux && found.managedBy == "desktop"
        val recovered = withContext(io) { Takeover.recoverPending(stateDir, osEnv, ourServiceHealthy = healthy) }
        val rolledBack = recovered && !healthy
        if (recovered) found = probe(prefs.port)

        val bundled = runCatching { bundledBuild() }.getOrNull()
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
            if (plan == HostPlan.Wait) plan = HostPlan.MovePort
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
            HostPlan.UseOwn -> useOwnLocked(prefs, found as HostProbeResult.Supermux)
            HostPlan.UpdateOwn -> updateOwnLocked(prefs)
            HostPlan.ReadOnly -> readOnlyLocked(prefs, (found as HostProbeResult.Supermux).hostId)
            is HostPlan.AskTakeover -> return Question.Takeover(plan.hostId, prefs.port)
            is HostPlan.AskDowngrade -> return Question.Downgrade(plan.hostId, prefs.port)
            HostPlan.Wait -> Unit // unreachable: turned into MovePort above
        }
        return null
    }

    private suspend fun useOwnLocked(prefs: HostingPrefs, found: HostProbeResult.Supermux) {
        _hostId.value = found.hostId
        mode = when {
            prefs.background && ourServiceInstalled() -> Mode.SERVICE
            child?.isAlive == true -> Mode.CHILD
            adoptOrphanLocked() -> Mode.CHILD // re-parent our child from a previous app run
            // Ours, but neither our service nor a child we can re-parent: watch it, and start our
            // own once it goes away (starting one now would fight it for the port).
            else -> Mode.ORPHAN
        }
        _status.value = HostingStatus.Running(prefs.port, readOnly = false)
        startWatch()
    }

    private suspend fun updateOwnLocked(prefs: HostingPrefs) {
        val windows = osEnv.os == OsEnv.Os.WINDOWS
        val service = prefs.background && ourServiceInstalled()
        if (service) {
            // Windows can't replace a running .exe, and re-creating the task doesn't restart it.
            if (windows) withContext(io) { BrokerService.remove(osEnv) }
            val bins = withContext(io) { materialize() }
            afterLaunch(prefs, launchLocked(prefs, bins, carriedStore.load(), allowChildFallback = true))
            return
        }
        if (child?.isAlive != true && !adoptOrphanLocked()) {
            log("our broker is running outside this app's control; the new build applies when it restarts")
            withContext(io) { materialize() }
            _status.value = HostingStatus.Running(prefs.port, readOnly = false)
            mode = Mode.ORPHAN
            startWatch()
            return
        }
        if (windows) stopChildLocked()
        val bins = withContext(io) { materialize() }
        stopChildLocked()
        afterLaunch(prefs, launchChildLocked(prefs, bins, carriedStore.load()))
    }

    private fun readOnlyLocked(prefs: HostingPrefs, hostId: String?) {
        mode = Mode.READ_ONLY
        _hostId.value = hostId
        _status.value = HostingStatus.Running(prefs.port, readOnly = true)
        startWatch()
    }

    private suspend fun leaveAloneLocked(q: Question) {
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
            mode = null
            _status.value = HostingStatus.CantStart(
                "supermux is already running on port ${q.port} but wasn't started by a service. Quit it, then try again.",
            )
            return
        }
        val prepared = when (val r = withContext(io) { Takeover.prepare(olds, stateDir, osEnv) }) {
            is Takeover.PrepareResult.Failed -> {
                mode = null
                _status.value = HostingStatus.CantStart("Couldn't take over: ${r.reason}")
                return
            }
            is Takeover.PrepareResult.Ok -> r.prepared
        }
        val prefsBefore = _prefs.value
        val carriedBefore = carriedStore.load()
        val p = prefsBefore.copy(relay = prepared.oldRelay ?: prefsBefore.relay)
        savePrefsNow(p)
        carriedStore.save(prepared.carriedEnv)

        val why = try {
            val bins = withContext(io) { materialize() }
            launchLocked(p, bins, prepared.carriedEnv, allowChildFallback = false, healthTimeoutMs = timing.takeoverHealthTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (why == null) {
            withContext(io) { Takeover.commit(prepared, osEnv) }
            afterLaunch(p, null)
            return
        }
        stopChildLocked()
        val restored = withContext(io) { Takeover.rollback(prepared, osEnv) }
        runCatching { carriedStore.save(carriedBefore) }
        mode = null
        _status.value = HostingStatus.CantStart(
            "Couldn't take over: $why" + if (restored) "" else " The old service couldn't be restored either.",
        )
    }

    // ── launching ──────────────────────────────────────────────────────────────────────

    /**
     * Start the broker the way [prefs] says. Returns null once `/host` is healthy, else the reason.
     * Background mode installs the OS service; the dev checkout (no packaged broker) and, when
     * [allowChildFallback], a failed install run it as the app's child instead.
     */
    private suspend fun launchLocked(
        prefs: HostingPrefs,
        bins: HostBinaries.SidecarBinaries,
        carried: Map<String, String>,
        allowChildFallback: Boolean,
        healthTimeoutMs: Long = timing.healthTimeoutMs,
    ): String? {
        _backgroundError.value = null
        val broker = bins.brokerPath
        if (!prefs.background) return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
        if (broker == null) {
            log("background mode needs the packaged app; running the dev broker as the app's child")
            return launchChildLocked(prefs, bins, carried, healthTimeoutMs)
        }
        val env = brokerEnv(prefs, bins, carried, hostName, existingPath, userHome, osEnv.os)
        val result = withContext(io) { BrokerService.install(BrokerService.Spec(broker, env, logFile), osEnv) }
        return when (result) {
            is BrokerService.Result.Installed -> if (result.enabled) {
                mode = Mode.SERVICE
                childDetached = false
                awaitHealthy(prefs.port, null, healthTimeoutMs)
            } else {
                // Linux XDG autostart: nothing runs until the next login, so run it now ourselves.
                // Still background mode for the prefs and UI; the child outlives the app.
                launchChildLocked(prefs, bins, carried, healthTimeoutMs, detached = true)
            }
            else -> {
                val why = when (result) {
                    is BrokerService.Result.Failed -> result.message
                    BrokerService.Result.Unsupported -> "not supported on this system"
                    else -> result.toString()
                }
                if (!allowChildFallback) return why
                _backgroundError.value = "Couldn't keep supermux running in the background: $why"
                log(_backgroundError.value!!)
                launchChildLocked(prefs, bins, carried, healthTimeoutMs)
            }
        }
    }

    private suspend fun launchChildLocked(
        prefs: HostingPrefs,
        bins: HostBinaries.SidecarBinaries,
        carried: Map<String, String>,
        healthTimeoutMs: Long = timing.healthTimeoutMs,
        detached: Boolean = false,
    ): String? {
        if (quitting) return "the app is quitting"
        val repo = if (bins.brokerPath == null) repoDir() else null
        val argv = bins.brokerPath?.let { listOf(it.toString()) }
            ?: repo?.let { listOf(bunPath(), it.resolve("src/main.ts").toString()) }
            ?: return "the supermux broker isn't bundled with this app"
        val env = brokerEnv(prefs, bins, carried, hostName, existingPath, userHome, osEnv.os)
        runCatching { Files.createDirectories(stateDir) }
        val c = withContext(io) { startChild(ChildLaunch(argv, env, repo, logFile)) }
        if (quitting) { // quit() ran while it was spawning
            c.destroy()
            return "the app is quitting"
        }
        child = c
        childDetached = detached
        mode = Mode.CHILD
        c.pid?.let { pid -> runCatching { Files.writeString(pidFile, pid.toString()) } }
        val why = awaitHealthy(prefs.port, c, healthTimeoutMs)
        if (why != null) stopChildLocked()
        return why
    }

    /** Poll `/host` until OUR broker answers. Null = healthy (and [hostId] set), else why not. */
    private suspend fun awaitHealthy(port: Int, c: ChildHandle?, timeoutMs: Long): String? {
        val deadline = now() + timeoutMs
        while (true) {
            if (c != null && !c.isAlive) return "supermux stopped while starting." + logTail()
            val r = probe(port)
            if (r is HostProbeResult.Supermux && r.managedBy == "desktop") {
                _hostId.value = r.hostId
                return null
            }
            if (now() >= deadline) return "supermux didn't answer on port $port." + logTail()
            delay(timing.healthPollMs)
        }
    }

    /** Publish the outcome of a launch and start watching. */
    private fun afterLaunch(prefs: HostingPrefs, why: String?) {
        if (why == null) {
            _status.value = HostingStatus.Running(prefs.port, readOnly = false)
            startWatch()
        } else {
            log("can't start: $why")
            mode = null
            _status.value = HostingStatus.CantStart(why)
        }
    }

    /** Restart in the current mode with a freshly built env (relay change, tray restart in child mode). */
    private suspend fun relaunchLocked(p: HostingPrefs) {
        stopWatch()
        resetExits()
        _status.value = HostingStatus.Starting
        val bins = withContext(io) { materialize() }
        val why = if (mode == Mode.SERVICE) {
            if (osEnv.os == OsEnv.Os.WINDOWS) withContext(io) { BrokerService.remove(osEnv) }
            launchLocked(p, bins, carriedStore.load(), allowChildFallback = true)
        } else {
            val detached = childDetached
            stopChildLocked()
            if (detached) launchLocked(p, bins, carriedStore.load(), allowChildFallback = true)
            else launchChildLocked(p, bins, carriedStore.load())
        }
        afterLaunch(p, why)
    }

    private suspend fun stopChildLocked() {
        val c = child ?: return
        child = null
        childDetached = false
        runCatching { Files.deleteIfExists(pidFile) }
        if (!c.isAlive) return
        c.destroy()
        if (!awaitExit(c, timing.stopGraceMs)) c.destroyForcibly()
    }

    private fun adoptOrphanLocked(): Boolean {
        val pid = runCatching { Files.readString(pidFile).trim().toLong() }.getOrNull() ?: return false
        val c = runCatching { adoptChild(pid) }.getOrNull()?.takeIf { it.isAlive } ?: return false
        child = c
        childDetached = false
        return true
    }

    // ── watching ───────────────────────────────────────────────────────────────────────

    private fun stopWatch() {
        watchGen++
        watchJob?.cancel()
        watchJob = null
    }

    private fun startWatch() {
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
                lock.withLock { if (gen == watchGen) _status.value = HostingStatus.CantStart(e.message ?: e.toString()) }
            }
        }
    }

    private fun resetExits() = synchronized(exits) { exits.clear() }

    private suspend fun watchChild(gen: Long) {
        while (true) {
            val c = child
            if (c != null && !awaitExit(c, timing.watchPollMs)) continue
            val attempt = lock.withLock {
                if (gen != watchGen) return
                if (c != null && child !== c) return
                child = null
                runCatching { Files.deleteIfExists(pidFile) }
                val t = now()
                val n = synchronized(exits) {
                    exits.addLast(t)
                    while (exits.isNotEmpty() && t - exits.first() > timing.crashWindowMs) exits.removeFirst()
                    exits.size
                }
                if (n > timing.maxExits) {
                    watchGen++
                    mode = null
                    _status.value = HostingStatus.CantStart("supermux keeps stopping." + logTail())
                    return
                }
                _status.value = HostingStatus.Restarting(n)
                n
            }
            delay(timing.backoffMs[minOf(attempt, timing.backoffMs.size) - 1])
            lock.withLock {
                if (gen != watchGen) return
                val p = _prefs.value
                val bins = withContext(io) { materialize() }
                val why = launchChildLocked(p, bins, carriedStore.load(), detached = childDetached)
                if (why == null) _status.value = HostingStatus.Running(p.port, readOnly = false)
                else log("restart failed: $why")
                // On failure child is null again and the next turn counts another exit.
            }
        }
    }

    private suspend fun watchService(gen: Long) {
        var downSince: Long? = null
        var kicked = false
        while (true) {
            delay(timing.watchPollMs)
            val port = _prefs.value.port
            val r = probe(port)
            lock.withLock {
                if (gen != watchGen) return
                if (r is HostProbeResult.Supermux && r.managedBy == "desktop") {
                    downSince = null
                    kicked = false
                    _hostId.value = r.hostId
                    if (_status.value !is HostingStatus.Running) _status.value = HostingStatus.Running(port, readOnly = false)
                    return@withLock
                }
                val t = now()
                val since = downSince ?: t.also { downSince = it }
                if (t - since < timing.serviceDownMs) return@withLock
                if (!kicked) {
                    kicked = true
                    downSince = t
                    _status.value = HostingStatus.Restarting(1)
                    log("the broker service has been down for ${timing.serviceDownMs / 1000} s; restarting it")
                    withContext(io) { BrokerService.restart(osEnv) }
                } else {
                    watchGen++
                    mode = null
                    _status.value = HostingStatus.CantStart("supermux stopped and didn't come back after a restart." + logTail())
                    return
                }
            }
        }
    }

    /** Never stops, restarts or edits the broker: only observes it. */
    private suspend fun watchReadOnly(gen: Long) {
        var downSince: Long? = null
        while (true) {
            delay(timing.watchPollMs)
            val port = _prefs.value.port
            val r = probe(port)
            lock.withLock {
                if (gen != watchGen) return
                if (r is HostProbeResult.Supermux) {
                    downSince = null
                    if (_status.value !is HostingStatus.Running) _status.value = HostingStatus.Running(port, readOnly = true)
                    return@withLock
                }
                val t = now()
                val since = downSince ?: t.also { downSince = it }
                if (t - since >= timing.readOnlyDownMs) {
                    watchGen++
                    mode = null
                    _status.value = HostingStatus.CantStart("The broker set up outside the app stopped.")
                    return
                }
            }
        }
    }

    /** Our broker from a previous app run that we couldn't re-parent: once it's gone, start our own child. */
    private suspend fun watchOrphan(gen: Long) {
        while (true) {
            delay(timing.watchPollMs)
            val p = _prefs.value
            if (probe(p.port) != HostProbeResult.PortFree) continue
            lock.withLock {
                if (gen != watchGen) return
                _status.value = HostingStatus.Restarting(1)
                val bins = withContext(io) { materialize() }
                afterLaunch(p, launchLocked(p, bins, carriedStore.load(), allowChildFallback = true))
                return
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────

    private suspend inline fun guarded(what: String, crossinline block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$what failed: ${e.message ?: e}")
            _status.value = HostingStatus.CantStart(e.message ?: e.toString())
        }
    }

    private fun savePrefsNow(p: HostingPrefs) {
        _prefs.value = p
        try { savePrefs(p) } catch (e: Exception) { log("couldn't save hosting prefs: ${e.message}") }
    }

    private suspend fun ourServiceInstalled(): Boolean = withContext(io) { BrokerService.isOursInstalled(osEnv) }

    private fun logTail(lines: Int = 20): String {
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
        fun defaultBunPath(): String {
            HostBinaries.whichOnPath("bun")?.let { return it.toString() }
            val home = System.getProperty("user.home") ?: return "bun"
            val local = Path.of(home, ".bun", "bin", "bun")
            return if (Files.isExecutable(local)) local.toString() else "bun"
        }
    }
}
