// This computer's hosting on desktop: the machine page's While you're away, built from the app-wide HostSupervisor.
package dev.supermux.desktop.settings

import dev.supermux.desktop.platform.DesktopOs
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import dev.supermux.desktop.host.AppleInstaller
import dev.supermux.desktop.host.BrokerVersion
import dev.supermux.desktop.host.DesktopHostBootstrap
import dev.supermux.desktop.host.HostSupervisor
import dev.supermux.desktop.host.HostWizardModel
import dev.supermux.desktop.host.HostWizardUiState
import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.desktop.host.KeepAwakeControls
import dev.supermux.desktop.host.LidSleepHelper
import dev.supermux.desktop.host.LidStatus
import dev.supermux.desktop.host.SystemOsEnv
import dev.supermux.desktop.host.displayLocalUrl
import dev.supermux.desktop.host.hostingStatusLine
import dev.supermux.desktop.host.lanIpv4
import dev.supermux.desktop.host.openFile
import dev.supermux.desktop.host.systemNetIfs
import dev.supermux.desktop.host.tailLines
import dev.supermux.desktop.host.thisComputerRecord
import dev.supermux.desktop.host.urlPort
import dev.supermux.host.PairedHostStore
import dev.supermux.net.GitRequirement
import dev.supermux.net.KeepAwakeState
import dev.supermux.state.FleetStore
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingCopy
import dev.supermux.ui.settings.HostingPhase
import dev.supermux.ui.settings.HostingPowerUi
import dev.supermux.ui.settings.KeepAwakeUi
import dev.supermux.ui.settings.LidClosedUi
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.settings.pages.ThisComputerHosting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app-wide supervisor (provided in `Main.kt`); null in tests and where the app does not host. */
val LocalHostSupervisor = staticCompositionLocalOf<HostSupervisor?> { null }

/** The app's paired-host store: the pairing dialogs reuse "This computer"'s token from it. */
val LocalPairedHostStore = staticCompositionLocalOf<PairedHostStore?> { null }

/** The live fleet, refreshed when the turn-on wizard writes "This computer" into the store. */
val LocalHostingFleet = staticCompositionLocalOf<FleetStore?> { null }

/** The app-wide keep-awake controls (provided in `Main.kt`); null in tests and where the app does not host. */
val LocalKeepAwakeControls = staticCompositionLocalOf<KeepAwakeControls?> { null }

/** Live sessions on this computer's broker (the tray's `FleetFacts.localSessions`). */
val LocalHostingSessions = compositionLocalOf { 0 }

/**
 * Supervisor calls outlive the page: leaving Settings mid-restart must not cancel the restart.
 * The supervisor serialises them itself.
 */
private val hostingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Pure: Settings ▸ Hosting's state from the supervisor's. [localUrl] is the display address (already
 * swapped to the LAN IP); [canPair] whether there is a store to pair into. The "needs git" banner
 * renders the running broker's own `requirements.git` (there is no app-side git check).
 */
internal fun desktopHostingUiState(
    status: HostingStatus,
    prefs: HostingPrefs,
    sessions: Int,
    build: String?,
    localUrl: String?,
    relayUrl: String?,
    logTail: List<String>,
    canPair: Boolean,
    backgroundError: String?,
    gitRequirement: GitRequirement?,
    power: HostingPowerUi? = null,
    checkoutMoved: String? = null,
): HostingUiState {
    val readOnly = DesktopHostBootstrap.isReadOnly(status)
    val running = status is HostingStatus.Running
    val line = hostingStatusLine(status, prefs, sessions, BrokerVersion.versionOf(build))
    return HostingUiState(
        hosting = prefs.hosting,
        statusDot = line.dot,
        statusText = line.text,
        readOnly = readOnly,
        localUrl = if (running) localUrl else null,
        relayUrl = relayUrl?.takeIf { prefs.relay || readOnly },
        relay = prefs.relay,
        background = prefs.background,
        sessions = sessions,
        logTail = logTail,
        failed = status is HostingStatus.CantStart,
        restartEnabled = running && !readOnly,
        canPair = running && canPair,
        backgroundError = backgroundError,
        gitRequirement = gitRequirement.takeIf { running },
        power = power?.let { p -> if (running) p else p.copy(keepAwake = null) },
        checkoutMoved = checkoutMoved.takeIf { running },
        phase = hostingPhase(status),
        failReason = (status as? HostingStatus.CantStart)?.reason,
    )
}

/** Pure: the machine page's word for where hosting is. A takeover or downgrade question is still starting. */
internal fun hostingPhase(status: HostingStatus): HostingPhase = when (status) {
    HostingStatus.NotHosting -> HostingPhase.Off
    HostingStatus.Starting, is HostingStatus.AskTakeover, is HostingStatus.AskDowngrade -> HostingPhase.Starting
    is HostingStatus.Running -> HostingPhase.Running
    is HostingStatus.Restarting -> HostingPhase.Restarting
    is HostingStatus.CantStart -> HostingPhase.Failed
}

/**
 * Pure: the "Keep this computer awake" row from the broker's [state]. Null hides it (the broker
 * hasn't said). [appHeld]: on Linux the app holds the inhibitor itself because the broker's was
 * denied; [hasBattery] shows "Also on battery".
 */
internal fun keepAwakeUi(state: KeepAwakeState?, hasBattery: Boolean?, appHeld: Boolean, writeError: String? = null): KeepAwakeUi? {
    if (state == null) return null
    val reason = listOfNotNull(state.reason, state.hint).joinToString("\n").takeIf { it.isNotBlank() }
    var warning: String? = null
    var note: String? = null
    when {
        !state.supported -> note = reason
        !state.enabled -> Unit
        appHeld -> note = HostingCopy.APP_HELD
        // Linux at boot: no desktop session yet, so the inhibitors are refused; the broker re-tries.
        state.reasonCode == KeepAwakeState.REASON_DENIED && state.retrying -> note = HostingCopy.WAITING_FOR_LOGIN
        state.reasonCode == KeepAwakeState.REASON_ON_BATTERY -> note = HostingCopy.PAUSED_ON_BATTERY
        !state.active && reason != null -> warning = reason
    }
    return KeepAwakeUi(
        enabled = state.enabled,
        onBattery = state.onBattery,
        switchEnabled = state.supported,
        showOnBattery = hasBattery == true,
        warning = warning,
        note = note,
        error = writeError,
    )
}

/**
 * Pure: "Even with the lid closed" exists only on a Mac with a battery. The switch shows ON only
 * while something actually holds; a saved choice without the helper (or with another user's) shows
 * OFF with a note saying what ticking does.
 */
internal fun lidClosedUi(isMac: Boolean, hasBattery: Boolean?, lid: LidStatus): LidClosedUi? {
    if (!isMac || hasBattery != true) return null
    val installed = lid.state == LidSleepHelper.InstallState.INSTALLED
    val note = when {
        !lid.homeSupported -> HostingCopy.LID_HOME_UNSUPPORTED
        lid.state == LidSleepHelper.InstallState.OTHER_USER -> HostingCopy.LID_OTHER_USER
        !installed && lid.pref -> HostingCopy.LID_NOT_INSTALLED
        !installed -> HostingCopy.LID_INSTALL
        lid.on && lid.pausedOnBattery -> HostingCopy.LID_PAUSED_ON_BATTERY
        else -> null
    }
    return LidClosedUi(
        on = lid.on,
        installed = installed,
        busy = lid.busy,
        error = lid.error,
        enabled = lid.homeSupported,
        note = note,
    )
}

/** Pure: the whole power section. The reboot copy is always there on desktop. */
internal fun desktopPowerUi(
    keepAwake: KeepAwakeState?,
    hasBattery: Boolean?,
    appHeld: Boolean,
    writeError: String?,
    isMac: Boolean,
    lid: LidStatus,
    fileVaultOff: Boolean?,
): HostingPowerUi = HostingPowerUi(
    keepAwake = keepAwakeUi(keepAwake, hasBattery, appHeld, writeError),
    lidClosed = lidClosedUi(isMac, hasBattery, lid),
    autoLoginHint = isMac && fileVaultOff == true,
)

/**
 * Install git on this computer THROUGH ITS BROKER (`POST /system/install-git`), with "This
 * computer"'s token: the broker runs `xcode-select --install` / winget itself, exactly as it would
 * for a phone. True when the installer started. Only ever called from a click (never on its own).
 * On a Mac, Apple's installer then opens behind this window (the broker that spawned it has none),
 * so it is brought to the front ([AppleInstaller]).
 */
internal suspend fun installGitOnThisComputer(
    sup: HostSupervisor,
    hostStore: PairedHostStore?,
    wizardToken: String? = null,
): Boolean = installGitOnLocalBroker(
    localUrl = sup.localBaseUrl,
    hostId = sup.hostId.value,
    hosts = hostStore?.list().orEmpty(),
    wizardToken = wizardToken,
    log = sup.log,
).also { started ->
    if (started && SystemOsEnv.os == DesktopOs.MACOS) {
        hostingScope.launch(Dispatchers.IO) {
            if (!AppleInstaller.bringToFront()) sup.log("install git: Apple's installer didn't come up to bring to the front")
        }
    }
}

/**
 * Pure apart from [post]: the stored "This computer" token, else [wizardToken] — a fresh install
 * that has not reached Done has no stored record yet, only the token its wizard just minted.
 */
internal suspend fun installGitOnLocalBroker(
    localUrl: String,
    hostId: String?,
    hosts: List<dev.supermux.host.PairedHost>,
    wizardToken: String?,
    log: (String) -> Unit,
    post: suspend (url: String, token: String) -> dev.supermux.net.InstallGitResult? = DesktopHostBootstrap::installGit,
): Boolean {
    val token = thisComputerRecord(hosts, hostId, urlPort(localUrl))?.token?.takeIf { it.isNotBlank() }
        ?: wizardToken?.takeIf { it.isNotBlank() }
    if (token == null) {
        log("install git: no token for this computer")
        return false
    }
    val r = post(localUrl, token)
    log("install git: ${r?.let { if (it.ok) (if (it.inProgress) "already running" else "started") else it.error ?: "failed" } ?: "unreachable"}")
    return r?.ok == true
}

/**
 * The machine page's Start hosting (#97 Step 2): the turn-on sheet's three switches, then the wizard's start + pair
 * of "This computer" without its QR (a phone pairs from Devices). It outlives the page, like every supervisor call.
 */
object HostingStart {
    /** The run in flight (null when none): its model says Preparing / Ready / Error. */
    val model = MutableStateFlow<HostWizardModel?>(null)
    internal var background = true
    internal var awake = true
}

internal fun startHosting(
    sup: HostSupervisor,
    hostStore: PairedHostStore?,
    power: KeepAwakeControls?,
    background: Boolean,
    awake: Boolean,
    relay: Boolean,
    refreshFleet: () -> Unit,
) {
    HostingStart.background = background
    HostingStart.awake = awake
    hostingScope.launch {
        if (sup.prefs.value.relay != relay) sup.setRelay(relay)
        if (sup.prefs.value.background != background) sup.setBackground(background)
        if (hostStore == null || DesktopHostBootstrap.isReadOnly(sup.status.value)) {
            sup.setHosting(true)
            return@launch
        }
        val m = DesktopHostBootstrap.buildModel(hostingScope, hostStore, sup, onStoreChanged = refreshFleet)
        HostingStart.model.value = m
        sup.setHosting(true)
        finishStart(m, power)
    }
}

/** Prepares [m] and, once it is ready, pairs "This computer" and applies the sheet's switches. */
private suspend fun finishStart(m: HostWizardModel, power: KeepAwakeControls?) {
    m.prepare()
    val end = m.state.first { it !is HostWizardUiState.Preparing }
    if (end is HostWizardUiState.Ready) {
        m.finish(HostingStart.background)
        HostingStart.model.value = null
        power?.setEnabled(HostingStart.awake)
    }
}

/** The hosting of this computer as its machine page shows it; null where the app does not host. */
@Composable
fun rememberThisComputerHosting(): ThisComputerHosting? {
    val sup = LocalHostSupervisor.current ?: return null
    val hostStore = LocalPairedHostStore.current
    val power = LocalKeepAwakeControls.current
    val fleet by rememberUpdatedState(LocalHostingFleet.current)
    val state = rememberDesktopHostingState(sup)
    val status by sup.status.collectAsState()
    val starting by HostingStart.model.collectAsState()
    val startState = starting?.state?.collectAsState()?.value
    var recent by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(status) {
        while (true) {
            recent = withContext(Dispatchers.IO) { tailLines(sup.logFile, 3) }
            delay(5_000)
        }
    }
    val actions = rememberDesktopHostingActions(sup, onPair = {})
    return ThisComputerHosting(
        state = state,
        actions = actions,
        recentLog = recent,
        readLog = { withContext(Dispatchers.IO) { tailLines(sup.logFile, 400) } },
        startHosting = { bg, awake, relay -> startHosting(sup, hostStore, power, bg, awake, relay) { fleet?.refreshFromStore() } },
        restart = { hostingScope.async { sup.restart() }.await() },
        turnOnError = (startState as? HostWizardUiState.Error)?.message,
        retryTurnOn = { starting?.let { m -> hostingScope.launch { finishStart(m, power) } } },
    )
}

@Composable
private fun rememberLanIp(): String? {
    val lanIp by produceState<String?>(null) { value = withContext(Dispatchers.IO) { lanIpv4(systemNetIfs()) } }
    return lanIp
}

/** Settings' view of the supervisor: status, prefs, addresses, git, power. */
@Composable
private fun rememberDesktopHostingState(sup: HostSupervisor): HostingUiState {
    val hostStore = LocalPairedHostStore.current
    val sessions = LocalHostingSessions.current
    val status by sup.status.collectAsState()
    val prefs by sup.prefs.collectAsState()
    val hostId by sup.hostId.collectAsState()
    val build by sup.build.collectAsState()
    val backgroundError by sup.backgroundError.collectAsState()
    val gitRequirement by sup.gitRequirement.collectAsState()
    val checkoutMoved by sup.checkoutMoved.collectAsState()
    val power = LocalKeepAwakeControls.current
    val powerUi = power?.let { c ->
        val keepAwake by c.keepAwake.collectAsState()
        val hasBattery by c.hasBattery.collectAsState()
        val appHeld by c.appHeld.collectAsState()
        val writeError by c.writeError.collectAsState()
        val lid by c.lid.collectAsState()
        val fileVaultOff by c.fileVaultOff.collectAsState()
        desktopPowerUi(keepAwake, hasBattery, appHeld, writeError, c.isMac, lid, fileVaultOff)
    }
    val lanIp = rememberLanIp()
    var relayUrl by remember { mutableStateOf<String?>(null) }
    var logTail by remember { mutableStateOf(emptyList<String>()) }

    // The relay address comes from /me, with "This computer"'s token.
    LaunchedEffect(status, prefs.relay, hostId) {
        if (status !is HostingStatus.Running) {
            relayUrl = null
            return@LaunchedEffect
        }
        val token = hostStore?.let { thisComputerRecord(it.list(), hostId, prefs.port) }
            ?.token?.takeIf { it.isNotBlank() }
        relayUrl = token?.let { DesktopHostBootstrap.localRelayUrl(sup.localBaseUrl, it) }
    }
    LaunchedEffect(status) {
        logTail = if (status is HostingStatus.CantStart) withContext(Dispatchers.IO) { tailLines(sup.logFile) } else emptyList()
    }

    return desktopHostingUiState(
        status = status,
        prefs = prefs,
        sessions = sessions,
        build = build,
        localUrl = displayLocalUrl(sup.localBaseUrl, lanIp),
        relayUrl = relayUrl,
        logTail = logTail,
        canPair = hostStore != null,
        backgroundError = backgroundError,
        gitRequirement = gitRequirement,
        power = powerUi,
        checkoutMoved = checkoutMoved,
    )
}

/** What Settings asks of the supervisor; every call outlives the page. */
@Composable
private fun rememberDesktopHostingActions(sup: HostSupervisor, onPair: () -> Unit): HostingActions {
    val hostStore = LocalPairedHostStore.current
    val power = LocalKeepAwakeControls.current
    val pair by rememberUpdatedState(onPair)
    return remember(sup, hostStore, power) {
        object : HostingActions {
            // The machine page only stops hosting here; starting goes through its turn-on sheet ([startHosting]).
            override fun setHosting(on: Boolean) { hostingScope.launch { sup.setHosting(on) } }
            override fun setBackground(on: Boolean) { hostingScope.launch { sup.setBackground(on) } }
            override fun setRelay(on: Boolean) { hostingScope.launch { sup.setRelay(on) } }
            override fun restart() { hostingScope.launch { sup.restart() } }
            override fun retry() { hostingScope.launch { sup.ensure() } }
            override fun showLog() { openFile(sup.logFile) }
            override fun pairDevice() { pair() }
            override fun manageIt() {
                val id = sup.hostId.value
                hostingScope.launch { if (id != null) sup.forgetLeftAlone(id) else sup.ensure() }
            }
            override suspend fun installGit(): Boolean = installGitOnThisComputer(sup, hostStore)
            override fun setKeepAwake(on: Boolean) { power?.let { hostingScope.launch { it.setEnabled(on) } } }
            override fun setKeepAwakeOnBattery(on: Boolean) { power?.let { hostingScope.launch { it.setOnBattery(on) } } }
            override fun setLidClosed(on: Boolean) { power?.let { hostingScope.launch { it.setLidClosed(on) } } }
            override fun uninstallLidHelper() { power?.let { hostingScope.launch { it.uninstallLidHelper() } } }
        }
    }
}
