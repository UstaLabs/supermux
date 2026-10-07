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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
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
    private val home: String? = System.getProperty("user.home"),
    val facts: PowerFactsCache = PowerFactsCache(os),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val lease: LidLease = LidLease(
        FileLeaseOps(Path.of(if (LidSleepHelper.validUser(user)) LidSleepHelper.leasePath(user) else "/nonexistent/lidsleep.lease")),
        scope,
        log = supervisor.log,
    ),
    private val lidInstallState: () -> LidSleepHelper.InstallState = { LidSleepHelper.installState(user) },
    private val lidInstall: () -> LidSleepHelper.Outcome = { LidSleepHelper.install(user, os, log = supervisor.log) },
    private val lidUninstall: () -> LidSleepHelper.Outcome = { LidSleepHelper.uninstall(os, supervisor.log) },
    /** Right now on battery power? Null when it can't tell. Polled only while it matters. */
    private val onBatteryNow: () -> Boolean? = { PowerFacts.detectOnBattery(os) },
    private val batteryPollMs: Long = 30_000,
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

    private val _lid = MutableStateFlow(
        LidStatus(pref = supervisor.prefs.value.lidClosed, homeSupported = LidSleepHelper.homeSupported(user, home)),
    )
    /** "Even with the lid closed": the saved choice, the helper, and whether the lease is held. */
    val lid: StateFlow<LidStatus> = _lid.asStateFlow()

    private val _localOnBattery = MutableStateFlow<Boolean?>(null)

    private val _writeError = MutableStateFlow<String?>(null)
    /** Why the last keep-awake change didn't reach the broker. */
    val writeError: StateFlow<String?> = _writeError.asStateFlow()

    val isMac: Boolean get() = os.os == OsEnv.Os.MAC

    private val started = AtomicBoolean(false)
    @Volatile private var quitting = false

    init {
        supervisor.onQuit(::quit)
    }

    /** Start the probes, follow the lid inputs, and follow the broker for the Linux fallback. Once. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        facts.load(scope)
        if (isMac) {
            scope.launch {
                val st = withContext(io) { runCatching(lidInstallState).getOrDefault(LidSleepHelper.InstallState.NOT_INSTALLED) }
                _lid.update { it.copy(state = st) }
            }
            // The lease follows the choice, the helper, and the battery rule.
            scope.launch {
                combine(_lid, supervisor.keepAwake, _localOnBattery) { l, ka, bat -> l to pausedOnBattery(ka, bat) }
                    .collect { (l, paused) ->
                        if (l.pausedOnBattery != paused) _lid.update { it.copy(pausedOnBattery = paused) }
                        reconcileLease()
                    }
            }
            // "Also on battery" off: watch the power source while the lid choice is on.
            scope.launch {
                while (true) {
                    val l = _lid.value
                    _localOnBattery.value = if (l.pref && supervisor.keepAwake.value?.onBattery == false) {
                        withContext(io) { runCatching(onBatteryNow).getOrNull() }
                    } else null
                    delay(batteryPollMs)
                }
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

    @Synchronized
    private fun reconcileLease() {
        val hold = _lid.value.holding && !quitting
        if (hold && !lease.held) {
            supervisor.log("lid closed: holding the lease")
            lease.start()
        } else if (!hold && lease.held) {
            supervisor.log("lid closed: releasing the lease")
            lease.stop()
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
     * "Even with the lid closed". Ticking installs (or reinstalls, when it watches another user)
     * the helper first (one admin prompt); a cancelled or failed install leaves it off with the
     * reason in [LidStatus.error].
     */
    suspend fun setLidClosed(on: Boolean) {
        val now = _lid.value
        if (!isMac || now.busy || quitting) return
        if (on && !now.homeSupported) return
        _lid.update { it.copy(error = null) }
        if (!on) {
            _lid.update { it.copy(pref = false) }
            reconcileLease()
            supervisor.setLidClosed(false)
            return
        }
        if (now.state != LidSleepHelper.InstallState.INSTALLED) {
            _lid.update { it.copy(busy = true) }
            val r = try {
                withContext(io) { runCatching(lidInstall).getOrElse { LidSleepHelper.Outcome.Failed(it.message ?: it.toString()) } }
            } finally {
                _lid.update { it.copy(busy = false) }
            }
            if (r is LidSleepHelper.Outcome.Failed) {
                _lid.update { it.copy(error = r.reason) }
                return
            }
            val st = withContext(io) { runCatching(lidInstallState).getOrDefault(LidSleepHelper.InstallState.INSTALLED) }
            _lid.update { it.copy(state = st) }
        }
        if (quitting) return
        _lid.update { it.copy(pref = true) }
        reconcileLease()
        supervisor.setLidClosed(true)
    }

    /** "Uninstall lid helper": admin prompt; boots it out, removes it, gives sleep back. */
    suspend fun uninstallLidHelper() {
        if (!isMac || _lid.value.busy) return
        _lid.update { it.copy(error = null, busy = true) }
        val r = try {
            withContext(io) { runCatching(lidUninstall).getOrElse { LidSleepHelper.Outcome.Failed(it.message ?: it.toString()) } }
        } finally {
            _lid.update { it.copy(busy = false) }
        }
        if (r is LidSleepHelper.Outcome.Failed) {
            _lid.update { it.copy(error = r.reason) }
            return
        }
        val st = withContext(io) { runCatching(lidInstallState).getOrDefault(LidSleepHelper.InstallState.NOT_INSTALLED) }
        _lid.update { it.copy(pref = false, state = st) }
        reconcileLease()
        supervisor.setLidClosed(false)
    }

    /**
     * Every quit: latch the lease shut first (no start can slip in after this), delete it (lid
     * sleep returns within 45 s), and drop the app-held inhibitor without waiting.
     */
    fun quit() {
        quitting = true
        runCatching { lease.close() }
        runCatching { inhibitor?.release(final = true) }
        _appHeld.value = false
    }

    companion object {
        /**
         * The lid rule for battery: with "Also on battery" OFF, lid sleep comes back while on
         * battery (the broker says `on_battery`, or the local check does). With it ON, it holds.
         */
        fun pausedOnBattery(ka: KeepAwakeState?, localOnBattery: Boolean?): Boolean =
            ka != null && !ka.onBattery && (ka.reasonCode == KeepAwakeState.REASON_ON_BATTERY || localOnBattery == true)

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
 * "Even with the lid closed" as the app sees it.
 * @property pref the saved choice.
 * @property state whether the helper is installed, and for whom.
 * @property homeSupported this account's home is `/Users/<user>` (the daemon watches that path).
 */
data class LidStatus(
    val pref: Boolean = false,
    val state: LidSleepHelper.InstallState = LidSleepHelper.InstallState.NOT_INSTALLED,
    val busy: Boolean = false,
    val error: String? = null,
    val homeSupported: Boolean = true,
    val pausedOnBattery: Boolean = false,
) {
    /** What the toggle shows: ON only when something actually holds (never on with no helper). */
    val on: Boolean get() = pref && homeSupported && state == LidSleepHelper.InstallState.INSTALLED

    /** The app touches the lease. */
    val holding: Boolean get() = on && !pausedOnBattery
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
    val token = thisComputerRecord(hosts, hostId, urlPort(localUrl))?.token?.takeIf { it.isNotBlank() }
    if (token == null) {
        log("keep awake: no token for this computer")
        return null
    }
    val r = put(localUrl, token, patch)
    log("keep awake: ${patch.enabled?.let { "enabled=$it " } ?: ""}${patch.onBattery?.let { "onBattery=$it " } ?: ""}-> ${r?.let { "active=${it.active}" } ?: "refused or unreachable"}")
    return r
}
