// Desktop's Settings ▸ Hosting: builds the shared page's state from the app-wide HostSupervisor.
package dev.supermux.desktop.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.desktop.host.BrokerVersion
import dev.supermux.desktop.host.DesktopHostBootstrap
import dev.supermux.desktop.host.HostSupervisor
import dev.supermux.desktop.host.HostWizardContent
import dev.supermux.desktop.host.HostWizardModel
import dev.supermux.desktop.host.HostWizardUiState
import dev.supermux.desktop.host.HostingPrefs
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.desktop.host.displayLocalUrl
import dev.supermux.desktop.host.hostingStatusLine
import dev.supermux.desktop.host.lanIpv4
import dev.supermux.desktop.host.openFile
import dev.supermux.desktop.host.systemNetIfs
import dev.supermux.desktop.host.tailLines
import dev.supermux.host.PairedHostStore
import dev.supermux.state.FleetStore
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingSettingsScreen
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app-wide supervisor (provided in `Main.kt`); null in tests and where the app does not host. */
val LocalHostSupervisor = staticCompositionLocalOf<HostSupervisor?> { null }

/** The app's paired-host store: the pairing dialogs reuse "This computer"'s token from it. */
val LocalPairedHostStore = staticCompositionLocalOf<PairedHostStore?> { null }

/** The live fleet, refreshed when the turn-on wizard writes "This computer" into the store. */
val LocalHostingFleet = staticCompositionLocalOf<FleetStore?> { null }

/** Live sessions on this computer's broker (the tray's `FleetFacts.localSessions`). */
val LocalHostingSessions = compositionLocalOf { 0 }

/**
 * Supervisor calls outlive the page: leaving Settings mid-restart must not cancel the restart.
 * The supervisor serialises them itself.
 */
private val hostingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The open turn-on wizard, hoisted out of composition: a layout change (rail ↔ compact) or leaving
 * Settings must not drop a wizard whose broker already minted a token.
 */
object HostingTurnOn {
    val model = MutableStateFlow<HostWizardModel?>(null)
}

private val OFF_STATE = HostingUiState(
    hosting = false, statusDot = "⚪", statusText = "Not hosting", readOnly = false, localUrl = null,
    relayUrl = null, relay = false, background = false, sessions = 0, logTail = emptyList(),
)

private object NoHostingActions : HostingActions {
    override fun setHosting(on: Boolean) = Unit
    override fun setBackground(on: Boolean) = Unit
    override fun setRelay(on: Boolean) = Unit
    override fun restart() = Unit
    override fun retry() = Unit
    override fun showLog() = Unit
    override fun pairDevice() = Unit
    override fun manageIt() = Unit
    override fun installGit() = Unit
}

/**
 * Pure: Settings ▸ Hosting's state from the supervisor's. [localUrl] is the display address (already
 * swapped to the LAN IP); [canPair] whether there is a store to pair into. The git row shows only
 * while running against a broker that said `gitAvailable: false`.
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
    gitAvailable: Boolean?,
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
        gitMissing = running && gitAvailable == false,
    )
}

/** Apple's "install the Command Line Tools" prompt. Off the UI thread; best-effort. */
private fun installCommandLineTools(log: (String) -> Unit) {
    hostingScope.launch(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("xcode-select", "--install").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            // Exit 1 with "already installed"/"already in progress" is not a failure worth more than a line.
            log("xcode-select --install exited $code${if (out.isNotEmpty()) ": ${out.take(200)}" else ""}")
        }.onFailure { log("xcode-select --install failed: ${it.message ?: it}") }
    }
}

/** Settings ▸ Hosting on desktop. Where the app does not host (no supervisor) it shows the "off" copy. */
@Composable
fun DesktopHostingSettings(onBack: () -> Unit, topBarShown: Boolean) {
    val sup = LocalHostSupervisor.current
    if (sup == null) {
        HostingSettingsScreen(state = OFF_STATE, actions = NoHostingActions, onBack = onBack, topBarShown = topBarShown)
        return
    }
    val hostStore = LocalPairedHostStore.current
    val fleet by rememberUpdatedState(LocalHostingFleet.current)
    val sessions = LocalHostingSessions.current
    val status by sup.status.collectAsState()
    val prefs by sup.prefs.collectAsState()
    val hostId by sup.hostId.collectAsState()
    val build by sup.build.collectAsState()
    val backgroundError by sup.backgroundError.collectAsState()
    val gitAvailable by sup.gitAvailable.collectAsState()
    val wizard by HostingTurnOn.model.collectAsState()

    val lanIp by produceState<String?>(null) { value = withContext(Dispatchers.IO) { lanIpv4(systemNetIfs()) } }
    var relayUrl by remember { mutableStateOf<String?>(null) }
    var logTail by remember { mutableStateOf(emptyList<String>()) }
    var showPair by remember { mutableStateOf(false) }

    // The relay address comes from /me, with "This computer"'s token.
    LaunchedEffect(status, prefs.relay, hostId) {
        if (status !is HostingStatus.Running) {
            relayUrl = null
            return@LaunchedEffect
        }
        val token = hostStore?.let { DesktopHostBootstrap.thisComputerRecord(it.list(), hostId) }
            ?.token?.takeIf { it.isNotBlank() }
        relayUrl = token?.let { DesktopHostBootstrap.localRelayUrl(sup.localBaseUrl, it) }
    }
    LaunchedEffect(status) {
        logTail = if (status is HostingStatus.CantStart) withContext(Dispatchers.IO) { tailLines(sup.logFile) } else emptyList()
    }

    val state = desktopHostingUiState(
        status = status,
        prefs = prefs,
        sessions = sessions,
        build = build,
        localUrl = displayLocalUrl(sup.localBaseUrl, lanIp),
        relayUrl = relayUrl,
        logTail = logTail,
        canPair = hostStore != null,
        backgroundError = backgroundError,
        gitAvailable = gitAvailable,
    )
    val actions = remember(sup, hostStore) {
        object : HostingActions {
            override fun setHosting(on: Boolean) {
                when {
                    !on -> hostingScope.launch { sup.setHosting(false) }
                    // Read-only (or nowhere to store a pairing): just turn it on and stay on the page.
                    hostStore == null || DesktopHostBootstrap.isReadOnly(sup.status.value) ->
                        hostingScope.launch { sup.setHosting(true) }
                    // Otherwise the wizard's start + pair flow.
                    else -> openTurnOnWizard(sup, hostStore) { fleet?.refreshFromStore() }
                }
            }
            override fun setBackground(on: Boolean) { hostingScope.launch { sup.setBackground(on) } }
            override fun setRelay(on: Boolean) { hostingScope.launch { sup.setRelay(on) } }
            override fun restart() { hostingScope.launch { sup.restart() } }
            override fun retry() { hostingScope.launch { sup.ensure() } }
            override fun showLog() { openFile(sup.logFile) }
            override fun pairDevice() { showPair = true }
            override fun manageIt() {
                val id = sup.hostId.value
                hostingScope.launch { if (id != null) sup.forgetLeftAlone(id) else sup.ensure() }
            }
            override fun installGit() { installCommandLineTools(sup.log) }
        }
    }

    HostingSettingsScreen(state = state, actions = actions, onBack = onBack, topBarShown = topBarShown)

    if (showPair && hostStore != null) {
        PairQrDialog(sup, hostStore, lanIp, onClose = { showPair = false })
    }
    wizard?.let { m -> TurnOnWizardDialog(sup, m) }
}

private fun openTurnOnWizard(sup: HostSupervisor, hostStore: PairedHostStore, refreshFleet: () -> Unit) {
    if (HostingTurnOn.model.value != null) return
    val m = DesktopHostBootstrap.buildModel(hostingScope, hostStore, sup, onStoreChanged = refreshFleet)
    HostingTurnOn.model.value = m
    hostingScope.launch {
        sup.setHosting(true)
        m.prepare()
    }
}

/**
 * Turning hosting on from Settings: the wizard's start + pair flow. It cannot be dismissed by a
 * click outside or Back; the close button, like "Connect to a different broker instead", turns
 * hosting back off. Done pairs "This computer" and applies the keep-alive box.
 */
@Composable
private fun TurnOnWizardDialog(sup: HostSupervisor, model: HostWizardModel) {
    val state by model.state.collectAsState()
    var keepAlive by remember(model) { mutableStateOf(true) }
    fun cancel() {
        HostingTurnOn.model.value = null
        hostingScope.launch { sup.setHosting(false) }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.size(width = 480.dp, height = 640.dp).clip(RoundedCornerShape(Space.lg))) {
            HostWizardContent(
                state = state,
                keepAlive = keepAlive,
                onKeepAliveChange = { keepAlive = it },
                onFinish = {
                    model.finish(keepAlive)
                    HostingTurnOn.model.value = null
                },
                onConnectInstead = ::cancel,
                onRetry = { model.prepare() },
            )
            IconButton(
                onClick = ::cancel,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm).testTag("hosting_wizard_close"),
            ) { Icon(Icons.Filled.Close, contentDescription = "Close and turn hosting off") }
        }
    }
}

/**
 * "Pair a device…": the QR for one more device, with no side effects. It never starts the broker,
 * never makes a secretless claim, and never re-pairs "This computer" or touches the keep-alive. Its
 * scope is the dialog's, so closing it cancels a mint in flight.
 */
@Composable
fun PairQrDialog(sup: HostSupervisor, hostStore: PairedHostStore, lanIp: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember {
        val token = DesktopHostBootstrap.thisComputerRecord(hostStore.list(), sup.hostId.value)?.token
        DesktopHostBootstrap.pairOnlyModel(
            scope = scope,
            hostName = DesktopHostBootstrap.defaultHostName(),
            token = token,
            hostId = { sup.hostId.value },
            directUrl = { displayLocalUrl(sup.localBaseUrl, lanIp) },
            mint = { t -> DesktopHostBootstrap.mintClaimForToken(sup.localBaseUrl, t) },
        )
    }
    LaunchedEffect(model) { model?.prepare() }
    val state = model?.state?.collectAsState()?.value
    Dialog(onDismissRequest = onClose) {
        PairQrContent(state, needsPairing = model == null, onRefresh = { model?.prepare() }, onClose = onClose)
    }
}

/** Copy the pair dialog shows. */
object PairQrCopy {
    const val NEEDS_PAIRING = "Pair this computer first"
    const val EXPIRES = "This code expires in 10 minutes"
}

/** Stateless body of [PairQrDialog]. [needsPairing]: there is no token for this computer. */
@Composable
fun PairQrContent(state: HostWizardUiState?, needsPairing: Boolean, onRefresh: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(Space.lg), color = cs.surfaceContainerHigh) {
        Column(
            Modifier.width(360.dp).padding(Space.xl).testTag("hosting_pair_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Text("Pair a device", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            when {
                needsPairing || state == null -> Text(
                    PairQrCopy.NEEDS_PAIRING,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("hosting_pair_needs_pairing"),
                )
                state is HostWizardUiState.Preparing -> CircularProgressIndicator(Modifier.testTag("hosting_pair_progress"))
                state is HostWizardUiState.Error -> {
                    Text(state.message, color = cs.error, textAlign = TextAlign.Center)
                    Button(onClick = onRefresh, modifier = Modifier.testTag("hosting_pair_retry")) { Text("Try again") }
                }
                state is HostWizardUiState.Ready -> {
                    Text(
                        "Scan this with the supermux app on your phone or another device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    // A fixed white card so it scans in dark mode too.
                    Image(
                        bitmap = state.qr,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(Space.md))
                            .background(Color.White)
                            .padding(Space.md)
                            .testTag("hosting_pair_qr"),
                    )
                    Text(
                        PairQrCopy.EXPIRES,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.testTag("hosting_pair_expires"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (state is HostWizardUiState.Ready) {
                    OutlinedButton(onClick = onRefresh, modifier = Modifier.testTag("hosting_pair_refresh")) { Text("Refresh") }
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("hosting_pair_done")) { Text("Done") }
            }
        }
    }
}
