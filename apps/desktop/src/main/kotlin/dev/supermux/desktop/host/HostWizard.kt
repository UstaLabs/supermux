package dev.supermux.desktop.host

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.host.PairingPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * First-run desktop-as-host wizard (Plan 3 Task 3 / spec §6, D6 choice A). Makes THIS computer a
 * host: the [BrokerSidecar] brings up/adopts the local broker, the wizard mints a one-time claim from
 * it, builds a v1 [PairingPayload] (hostId from the sidecar, `relayUrl` when hosting-remote), and
 * renders it as a scannable QR next to the spec §6 copy, a CHECKED-by-default keep-alive box, and the
 * relay-disclosure line.
 *
 * The payload construction ([buildPairingPayload]) is pure and unit-tested; [HostWizardContent] is a
 * stateless render tested via Compose UI; [HostWizardModel] wires the async claim/QR/keep-alive via
 * injectable seams so the network bootstrap stays testable and Main-wiring stays thin.
 */

private val json = Json { encodeDefaults = true }

/** The local device token + the one-time phone claim the wizard just minted from the local broker. */
data class HostClaim(val localToken: String, val claimSecret: String, val relayUrl: String? = null)

/**
 * Pure builder for the wizard's pairing payload. `directUrl` is always the local broker; `relayUrl`
 * is set only when hosting-remote is on. The result round-trips through [PairingPayload.parse] (which
 * validates the base32 hostId + supermux relay origin), so a malformed hostId/relay is caught here.
 */
fun buildPairingPayload(
    hostId: String,
    name: String,
    claimSecret: String,
    directUrl: String?,
    relayUrl: String? = null,
): PairingPayload = PairingPayload(
    v = 1,
    action = "pair",
    hostId = hostId,
    name = name,
    relayUrl = relayUrl,
    directUrl = directUrl,
    claimSecret = claimSecret,
)

/** Encode a payload to the compact JSON string the QR carries (the same shape the phone parses). */
fun encodePairingPayload(payload: PairingPayload): String = json.encodeToString(payload)

// ── UI state ─────────────────────────────────────────────────────────────────────

sealed interface HostWizardUiState {
    /** Sidecar coming up / claim minting. */
    data object Preparing : HostWizardUiState
    /** Ready to show the QR. [relayEnabled] switches the disclosure copy. */
    data class Ready(val payloadJson: String, val qr: ImageBitmap, val relayEnabled: Boolean) : HostWizardUiState
    data class Error(val message: String) : HostWizardUiState
}

// ── Stateful model (async claim + QR + keep-alive) ─────────────────────────────────

/**
 * Drives the wizard: awaits the sidecar's hostId, mints a claim ([mintClaim]), builds + encodes the
 * payload, renders the QR ([qrOf]), and on finish auto-pairs "This computer" into the fleet
 * ([onPairThisComputer]) and installs/skips the login keep-alive ([onInstallKeepAlive]) per the box.
 */
class HostWizardModel(
    private val scope: CoroutineScope,
    private val hostName: String,
    private val provideHostId: suspend () -> String?,
    private val provideLocalUrl: () -> String?,
    private val mintClaim: suspend () -> HostClaim?,
    private val provideRelayUrl: () -> String? = { null },
    private val onPairThisComputer: (localToken: String, directUrl: String?, hostId: String) -> Unit,
    private val onInstallKeepAlive: (Boolean) -> Unit,
    private val qrOf: (String) -> ImageBitmap = { qrBitmap(it) },
) {
    private val _state = MutableStateFlow<HostWizardUiState>(HostWizardUiState.Preparing)
    val state: StateFlow<HostWizardUiState> = _state.asStateFlow()

    private var claim: HostClaim? = null
    private var hostId: String? = null
    private var directUrl: String? = null

    /** Idempotent-ish: build the payload once. Failures land in [HostWizardUiState.Error]. */
    fun prepare() {
        _state.value = HostWizardUiState.Preparing
        scope.launch {
            val id = runCatching { provideHostId() }.getOrNull()
            if (id.isNullOrBlank()) {
                _state.value = HostWizardUiState.Error("Couldn't start the local host. Is the broker able to run on this machine?")
                return@launch
            }
            val c = runCatching { mintClaim() }.getOrNull()
            if (c == null || c.claimSecret.isBlank()) {
                _state.value = HostWizardUiState.Error("Couldn't create a pairing code from the local host.")
                return@launch
            }
            val relay = c.relayUrl ?: provideRelayUrl()
            val local = provideLocalUrl()
            val payload = buildPairingPayload(id, hostName, c.claimSecret, directUrl = local, relayUrl = relay)
            val jsonStr = encodePairingPayload(payload)
            claim = c
            hostId = id
            directUrl = local
            _state.value = HostWizardUiState.Ready(jsonStr, qrOf(jsonStr), relayEnabled = relay != null)
        }
    }

    /** Finish: auto-pair "This computer" and install (or skip) the login keep-alive per [keepAlive]. */
    fun finish(keepAlive: Boolean) {
        val c = claim; val id = hostId
        if (c != null && id != null) onPairThisComputer(c.localToken, directUrl, id)
        onInstallKeepAlive(keepAlive)
    }
}

// ── Stateless content (Compose-UI tested) ──────────────────────────────────────────

/** Spec §6 copy — kept as constants so the UI test asserts the exact strings. */
const val HOST_WIZARD_HEADLINE = "This computer is ready to host your agents. Scan the QR with your phone."
const val HOST_WIZARD_KEEPALIVE_LABEL = "Keep this computer available when the app is closed and after I sign in"
private const val RELAY_ON_DISCLOSURE =
    "Remote access is on through relay.supermux.dev. Connections are encrypted in transit; relay traffic is not end-to-end encrypted yet."
private const val RELAY_OFF_DISCLOSURE =
    "Your phone reaches this computer directly on your local network. Turn on remote access later to reach it from anywhere through the supermux relay."

/**
 * Pure render of the wizard for a resolved [state]. Stateless so the Compose test drives it with a
 * ready payload (no sidecar/broker). [keepAlive] is hoisted (CHECKED by default at the call site).
 */
@Composable
fun HostWizardContent(
    state: HostWizardUiState,
    keepAlive: Boolean,
    onKeepAliveChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onConnectInstead: () -> Unit,
    onRetry: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(containerColor = cs.surfaceContainerHigh) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp)
                .testTag("host_wizard"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Icon(Icons.Filled.Computer, contentDescription = null, modifier = Modifier.size(44.dp), tint = cs.primary)

            when (state) {
                is HostWizardUiState.Preparing -> {
                    Text(
                        "Setting up this computer as a host…",
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    CircularProgressIndicator(Modifier.testTag("host_wizard_progress"))
                }

                is HostWizardUiState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("host_wizard_error"),
                    )
                    Button(onClick = onRetry, modifier = Modifier.testTag("host_wizard_retry")) { Text("Try again") }
                    TextButton(onClick = onConnectInstead, modifier = Modifier.testTag("host_wizard_connect_instead")) {
                        Text("Connect to a different broker instead")
                    }
                }

                is HostWizardUiState.Ready -> {
                    Text(
                        HOST_WIZARD_HEADLINE,
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("host_wizard_headline"),
                    )
                    // The QR sits on a fixed white card so it scans in dark mode too.
                    Image(
                        bitmap = state.qr,
                        contentDescription = "Pairing QR code",
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .padding(12.dp)
                            .testTag("host_wizard_qr"),
                    )

                    // CHECKED-by-default keep-alive box (spec §6 / D6).
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("host_wizard_keepalive_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = keepAlive,
                            onCheckedChange = onKeepAliveChange,
                            modifier = Modifier.testTag("host_wizard_keepalive_checkbox"),
                        )
                        Text(
                            HOST_WIZARD_KEEPALIVE_LABEL,
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Text(
                        if (state.relayEnabled) RELAY_ON_DISCLOSURE else RELAY_OFF_DISCLOSURE,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.testTag("host_wizard_relay_disclosure"),
                    )

                    Button(
                        onClick = onFinish,
                        modifier = Modifier.fillMaxWidth().testTag("host_wizard_done"),
                    ) { Text("Done") }
                    TextButton(onClick = onConnectInstead, modifier = Modifier.testTag("host_wizard_connect_instead")) {
                        Text("Connect to a different broker instead")
                    }
                }
            }
        }
    }
}

/**
 * Stateful wizard: collects [model] state and hoists the CHECKED-by-default keep-alive box. Calls
 * [HostWizardModel.prepare] once on first composition and [HostWizardModel.finish] + [onDone] on Done.
 */
@Composable
fun HostWizard(
    model: HostWizardModel,
    onDone: () -> Unit,
    onConnectInstead: () -> Unit,
) {
    val state by model.state.collectAsState()
    var keepAlive by remember { mutableStateOf(true) } // CHECKED by default (spec §6 / D6)
    androidx.compose.runtime.LaunchedEffect(model) { model.prepare() }
    HostWizardContent(
        state = state,
        keepAlive = keepAlive,
        onKeepAliveChange = { keepAlive = it },
        onFinish = { model.finish(keepAlive); onDone() },
        onConnectInstead = onConnectInstead,
        onRetry = { model.prepare() },
    )
}
