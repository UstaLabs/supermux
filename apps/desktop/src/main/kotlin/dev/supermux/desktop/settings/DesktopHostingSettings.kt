// Desktop's Settings ▸ Hosting: builds the shared page's state from the app-wide HostSupervisor.
package dev.supermux.desktop.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.host.BrokerVersion
import dev.supermux.desktop.host.DesktopHostBootstrap
import dev.supermux.desktop.host.HostProbeResult
import dev.supermux.desktop.host.HostSupervisor
import dev.supermux.desktop.host.HostWizardContent
import dev.supermux.desktop.host.HostWizardModel
import dev.supermux.desktop.host.HostWizardUiState
import dev.supermux.desktop.host.HostingStatus
import dev.supermux.desktop.host.displayLocalUrl
import dev.supermux.desktop.host.hostingStatusLine
import dev.supermux.desktop.host.isLoopbackUrl
import dev.supermux.desktop.host.lanIpv4
import dev.supermux.desktop.host.openFile
import dev.supermux.desktop.host.systemInetAddresses
import dev.supermux.desktop.host.tailLines
import dev.supermux.host.PairedHostStore
import dev.supermux.ui.settings.HostingActions
import dev.supermux.ui.settings.HostingSettingsScreen
import dev.supermux.ui.settings.HostingUiState
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app-wide supervisor (provided in `Main.kt`); null in tests and where the app does not host. */
val LocalHostSupervisor = staticCompositionLocalOf<HostSupervisor?> { null }

/** The app's paired-host store: the pairing dialogs reuse "This computer"'s token from it. */
val LocalPairedHostStore = staticCompositionLocalOf<PairedHostStore?> { null }

/** Live sessions on this computer's broker (the tray's `FleetFacts.localSessions`). */
val LocalHostingSessions = compositionLocalOf { 0 }

/**
 * Supervisor calls outlive the page: leaving Settings mid-restart must not cancel the restart.
 * The supervisor serialises them itself.
 */
private val hostingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/** Settings ▸ Hosting on desktop. Renders nothing when no supervisor is provided. */
@Composable
fun DesktopHostingSettings(onBack: () -> Unit, topBarShown: Boolean) {
    val sup = LocalHostSupervisor.current ?: return
    val hostStore = LocalPairedHostStore.current
    val sessions = LocalHostingSessions.current
    val status by sup.status.collectAsState()
    val prefs by sup.prefs.collectAsState()
    val hostId by sup.hostId.collectAsState()
    val backgroundError by sup.backgroundError.collectAsState()

    val lanIp by produceState<String?>(null) { value = withContext(Dispatchers.IO) { lanIpv4(systemInetAddresses()) } }
    var version by remember { mutableStateOf<String?>(null) }
    var relayUrl by remember { mutableStateOf<String?>(null) }
    var logTail by remember { mutableStateOf(emptyList<String>()) }
    var showPair by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }

    // The version comes from the local /host, the relay address from /me (with "This computer"'s token).
    LaunchedEffect(status, prefs.relay, hostId) {
        if (status !is HostingStatus.Running) {
            version = null
            relayUrl = null
            return@LaunchedEffect
        }
        version = (runCatching { sup.probe(prefs.port) }.getOrNull() as? HostProbeResult.Supermux)
            ?.let { BrokerVersion.versionOf(it.build) }
        val token = hostStore?.list()?.let { hosts ->
            hosts.firstOrNull { hostId != null && it.hostId == hostId } ?: hosts.firstOrNull { isLoopbackUrl(it.directUrl) }
        }?.token?.takeIf { it.isNotBlank() }
        relayUrl = token?.let { DesktopHostBootstrap.localRelayUrl(sup.localBaseUrl, it) }
    }
    LaunchedEffect(status) {
        logTail = if (status is HostingStatus.CantStart) withContext(Dispatchers.IO) { tailLines(sup.logFile) } else emptyList()
    }

    val s = status
    val readOnly = s is HostingStatus.Running && s.readOnly
    val running = s is HostingStatus.Running
    val line = hostingStatusLine(s, prefs, sessions, version)
    val state = HostingUiState(
        hosting = prefs.hosting,
        statusDot = line.dot,
        statusText = line.text,
        readOnly = readOnly,
        localUrl = if (running) displayLocalUrl(sup.localBaseUrl, lanIp) else null,
        relayUrl = relayUrl?.takeIf { prefs.relay || readOnly },
        relay = prefs.relay,
        background = prefs.background,
        sessions = sessions,
        logTail = logTail,
        failed = s is HostingStatus.CantStart,
        restartEnabled = running && !readOnly,
        canPair = running && hostStore != null,
        backgroundError = backgroundError,
    )
    val actions = remember(sup, hostStore) {
        object : HostingActions {
            override fun setHosting(on: Boolean) {
                // Turning it on runs the wizard's start + pair flow (the wizard's dialog starts it).
                when {
                    !on -> hostingScope.launch { sup.setHosting(false) }
                    hostStore == null -> hostingScope.launch { sup.setHosting(true) }
                    else -> showWizard = true
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
        }
    }

    HostingSettingsScreen(state = state, actions = actions, onBack = onBack, topBarShown = topBarShown)

    if (showPair && hostStore != null) {
        PairQrDialog(sup, hostStore, onClose = { showPair = false })
    }
    if (showWizard && hostStore != null) {
        HostingOnWizardDialog(sup, hostStore, onClose = { showWizard = false })
    }
}

/**
 * "Pair a device…": the wizard's QR for one more device. It only mints a claim and shows it; it
 * never calls [HostWizardModel.finish], so "This computer" is not re-paired and the keep-alive is
 * left as it is.
 */
@Composable
fun PairQrDialog(sup: HostSupervisor, hostStore: PairedHostStore, onClose: () -> Unit) {
    val model = remember { DesktopHostBootstrap.buildModel(hostingScope, hostStore, sup) }
    LaunchedEffect(model) { model.prepare() }
    val state by model.state.collectAsState()
    Dialog(onDismissRequest = onClose) {
        PairQrContent(state, onRetry = { model.prepare() }, onClose = onClose)
    }
}

/** Stateless body of [PairQrDialog]. */
@Composable
fun PairQrContent(state: HostWizardUiState, onRetry: () -> Unit, onClose: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(Space.lg), color = cs.surfaceContainerHigh) {
        Column(
            Modifier.width(360.dp).padding(Space.xl).testTag("hosting_pair_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Text("Pair a device", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            when (state) {
                HostWizardUiState.Preparing -> CircularProgressIndicator(Modifier.testTag("hosting_pair_progress"))
                is HostWizardUiState.Error -> {
                    Text(state.message, color = cs.error, textAlign = TextAlign.Center)
                    Button(onClick = onRetry, modifier = Modifier.testTag("hosting_pair_retry")) { Text("Try again") }
                }
                is HostWizardUiState.Ready -> {
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
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.testTag("hosting_pair_done")) { Text("Done") }
        }
    }
}

/**
 * Turning hosting on from Settings: the wizard's start + pair flow. Hosting is switched on first,
 * then the model prepares the QR; Done pairs "This computer" and applies the keep-alive box, as the
 * first-run wizard does. "Connect to a different broker instead" turns hosting back off.
 */
@Composable
private fun HostingOnWizardDialog(sup: HostSupervisor, hostStore: PairedHostStore, onClose: () -> Unit) {
    val model = remember { DesktopHostBootstrap.buildModel(hostingScope, hostStore, sup) }
    LaunchedEffect(model) { hostingScope.launch { sup.setHosting(true); model.prepare() } }
    val state by model.state.collectAsState()
    var keepAlive by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose) {
        Box(Modifier.size(width = 480.dp, height = 640.dp).clip(RoundedCornerShape(Space.lg))) {
            HostWizardContent(
                state = state,
                keepAlive = keepAlive,
                onKeepAliveChange = { keepAlive = it },
                onFinish = { model.finish(keepAlive); onClose() },
                onConnectInstead = { hostingScope.launch { sup.setHosting(false) }; onClose() },
                onRetry = { model.prepare() },
            )
        }
    }
}
