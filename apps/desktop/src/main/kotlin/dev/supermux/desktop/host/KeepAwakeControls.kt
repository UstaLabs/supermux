package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.net.KeepAwakePatch
import dev.supermux.net.KeepAwakeState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Keep this computer awake while hosting" on the HOST computer (spec "Keep the computer awake
 * while hosting"): the tray and Settings ▸ Hosting drive it; the phone and web never do.
 *
 *  - "Keep this computer awake" / "Also on battery" are the broker's setting: changed with
 *    `PUT /settings/keep-awake` on the LOCAL broker (`127.0.0.1`, which the broker requires) with
 *    "This computer"'s token; shown from [HostSupervisor.keepAwake].
 *  - Linux, when the broker says `reasonCode == "denied"`: the app holds the inhibitor itself
 *    while it is open ([AppSleepInhibitor]).
 *  - "Even with the lid closed" (MacBooks): the lid helper ([LidSleepHelper]) and its lease
 *    ([LidLease]), released on ANY quit through [HostSupervisor.onQuit].
 *
 * One per process ([DesktopHostBootstrap.keepAwake]). Nothing here throws.
 */
class KeepAwakeControls(
    private val supervisor: HostSupervisor,
    private val os: OsEnv = supervisor.osEnv,
    private val user: String = System.getProperty("user.name") ?: "",
    val facts: PowerFactsCache = PowerFactsCache(os),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val lease: LidLease = LidLease(
        FileLeaseOps(Path.of(if (LidSleepHelper.validUser(user)) LidSleepHelper.leasePath(user) else "/nonexistent/lidsleep.lease")),
        scope,
        log = supervisor.log,
    ),
    private val lidInstalledCheck: () -> Boolean = { LidSleepHelper.isInstalled(user) },
    private val lidInstall: () -> LidSleepHelper.Outcome = { LidSleepHelper.install(user, os, supervisor.log) },
    private val lidUninstall: () -> LidSleepHelper.Outcome = { LidSleepHelper.uninstall(os, supervisor.log) },
    private val inhibitor: AppSleepInhibitor? =
        if (os.os == OsEnv.Os.LINUX) AppSleepInhibitor(which = { HostBinaries.whichOnPath(it)?.toString() }, log = supervisor.log) else null,
    private val put: suspend (url: String, token: String, patch: KeepAwakePatch) -> KeepAwakeState? = ::putKeepAwake,
) {
    /** "This computer"'s paired records (for its token); set by the app once its store exists. */
    @Volatile var hosts: () -> List<PairedHost> = { emptyList() }

    val keepAwake: StateFlow<KeepAwakeState?> get() = supervisor.keepAwake
    val hasBattery: StateFlow<Boolean?> get() = facts.hasBattery
    val fileVaultOff: StateFlow<Boolean?> get() = facts.fileVaultOff

    private val _appHeld = MutableStateFlow(false)
    /** Linux: the app holds the sleep inhibitor itself (the broker's was denied). */
    val appHeld: StateFlow<Boolean> = _appHeld.asStateFlow()

    private val _lidInstalled = MutableStateFlow(false)
    val lidHelperInstalled: StateFlow<Boolean> = _lidInstalled.asStateFlow()

    private val _lidClosed = MutableStateFlow(supervisor.prefs.value.lidClosed)
    /** "Even with the lid closed" as the user last chose it (the saved pref, updated at once). */
    val lidClosed: StateFlow<Boolean> = _lidClosed.asStateFlow()

    private val _lidBusy = MutableStateFlow(false)
    /** An install or uninstall prompt is open. */
    val lidBusy: StateFlow<Boolean> = _lidBusy.asStateFlow()

    private val _lidError = MutableStateFlow<String?>(null)
    val lidError: StateFlow<String?> = _lidError.asStateFlow()

    private val _writeError = MutableStateFlow<String?>(null)
    /** Why the last keep-awake change didn't reach the broker. */
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    val isMac: Boolean get() = os.os == OsEnv.Os.MAC

    private val started = AtomicBoolean(false)
    @Volatile private var quitting = false

    init {
        supervisor.onQuit(::quit)
    }

    /** Start the probes, resume the lid lease, and follow the broker for the Linux fallback. Once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        facts.load(scope)
        scope.launch {
            val installed = isMac && withContext(io) { runCatching(lidInstalledCheck).getOrDefault(false) }
            _lidInstalled.value = installed
            if (_lidClosed.value && installed && !quitting) {
                supervisor.log("lid closed: resuming the lease")
                lease.start()
            }
        }
        if (inhibitor != null) {
            scope.launch {
                supervisor.keepAwake.map { appShouldHold(it) }.distinctUntilChanged().collect { hold ->
                    withContext(io) {
                        if (hold && !quitting) inhibitor.hold() else inhibitor.release()
                    }
                    _appHeld.value = inhibitor.held
                }
            }
        }
    }

    /** "Keep this computer awake". True when the broker took it. */
    suspend fun setEnabled(on: Boolean): Boolean = write(KeepAwakePatch(enabled = on))

    /** "Also on battery". */
    suspend fun setOnBattery(on: Boolean): Boolean = write(KeepAwakePatch(onBattery = on))

    private suspend fun write(patch: KeepAwakePatch): Boolean {
        val r = setKeepAwakeOnLocalBroker(
            localUrl = supervisor.localBaseUrl,
            hostId = supervisor.hostId.value,
            hosts = runCatching { hosts() }.getOrDefault(emptyList()),
            patch = patch,
            log = supervisor.log,
            put = put,
        )
        if (r != null) {
            supervisor.publishKeepAwake(r)
            _writeError.value = null
        } else {
            _writeError.value = WRITE_FAILED
        }
        return r != null
    }

    /**
     * "Even with the lid closed". Ticking installs the helper first when it isn't (one admin
     * prompt); a cancelled or failed install leaves the box unticked with the reason in [lidError].
     */
    suspend fun setLidClosed(on: Boolean) {
        if (!isMac || _lidBusy.value) return
        _lidError.value = null
        if (!on) {
            _lidClosed.value = false
            lease.stop()
            supervisor.setLidClosed(false)
            return
        }
        if (!_lidInstalled.value) {
            _lidBusy.value = true
            val r = try {
                withContext(io) { runCatching(lidInstall).getOrElse { LidSleepHelper.Outcome.Failed(it.message ?: it.toString()) } }
            } finally {
                _lidBusy.value = false
            }
            if (r is LidSleepHelper.Outcome.Failed) {
                _lidError.value = r.reason
                return
            }
            _lidInstalled.value = withContext(io) { runCatching(lidInstalledCheck).getOrDefault(true) }
        }
        if (quitting) return
        _lidClosed.value = true
        supervisor.setLidClosed(true)
        lease.start()
    }

    /** "Uninstall lid helper": admin prompt; boots it out, removes it, gives sleep back. */
    suspend fun uninstallLidHelper() {
        if (!isMac || _lidBusy.value) return
        _lidError.value = null
        _lidBusy.value = true
        val r = try {
            withContext(io) { runCatching(lidUninstall).getOrElse { LidSleepHelper.Outcome.Failed(it.message ?: it.toString()) } }
        } finally {
            _lidBusy.value = false
        }
        if (r is LidSleepHelper.Outcome.Failed) {
            _lidError.value = r.reason
            return
        }
        lease.stop()
        _lidClosed.value = false
        supervisor.setLidClosed(false)
        _lidInstalled.value = withContext(io) { runCatching(lidInstalledCheck).getOrDefault(false) }
    }

    /** Every quit: delete the lease (lid sleep returns within 45 s) and drop the app-held inhibitor. */
    fun quit() {
        quitting = true
        runCatching { lease.stop() }
        runCatching { inhibitor?.release(final = true) }
        _appHeld.value = false
    }

    companion object {
        const val WRITE_FAILED = "Couldn't change this on the local supermux. Try again."

        /** Linux fallback: the broker's own inhibitor was refused while the setting is on. */
        fun appShouldHold(s: KeepAwakeState?): Boolean =
            s != null && s.enabled && !s.active && s.reasonCode == KeepAwakeState.REASON_DENIED

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** `PUT /settings/keep-awake` on the local broker. Null when it didn't take it. */
        suspend fun putKeepAwake(localUrl: String, token: String, patch: KeepAwakePatch): KeepAwakeState? = withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                val req = HttpRequest.newBuilder(URI.create("$localUrl/settings/keep-awake"))
                    .timeout(Duration.ofSeconds(10))
                    .header("authorization", "Bearer $token")
                    .header("content-type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(json.encodeToString(KeepAwakePatch.serializer(), patch)))
                    .build()
                val resp = client.send(req, BodyHandlers.ofString())
                if (resp.statusCode() !in 200..299) null
                else json.decodeFromString(KeepAwakeState.serializer(), resp.body())
            }.getOrNull()
        }
    }
}

/**
 * Pure apart from [put]: change keep-awake on the local broker with "This computer"'s stored token
 * (the same lookup as the git install). [localUrl] is the supervisor's `http://127.0.0.1:<port>`:
 * the broker only accepts this change from a direct loopback caller.
 */
internal suspend fun setKeepAwakeOnLocalBroker(
    localUrl: String,
    hostId: String?,
    hosts: List<PairedHost>,
    patch: KeepAwakePatch,
    log: (String) -> Unit,
    put: suspend (url: String, token: String, patch: KeepAwakePatch) -> KeepAwakeState?,
): KeepAwakeState? {
    val token = DesktopHostBootstrap.thisComputerRecord(hosts, hostId)?.token?.takeIf { it.isNotBlank() }
    if (token == null) {
        log("keep awake: no token for this computer")
        return null
    }
    val r = put(localUrl, token, patch)
    log("keep awake: ${patch.enabled?.let { "enabled=$it " } ?: ""}${patch.onBattery?.let { "onBattery=$it " } ?: ""}-> ${r?.let { "active=${it.active}" } ?: "refused or unreachable"}")
    return r
}
