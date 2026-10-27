package dev.supermux.desktop.host

import androidx.compose.foundation.Image
import dev.supermux.ui.widgets.qrBitmap
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
 * First-run desktop-as-host wizard (spec §6, D6 choice A). Makes THIS computer a
 * host: the [HostSupervisor] brings up/adopts the local broker, the wizard mints a one-time claim from
 * it, builds a v1 [PairingPayload] (hostId from the supervisor, `relayUrl` when hosting-remote), and
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
    /** The local broker coming up / claim minting. */
    data object Preparing : HostWizardUiState
    /** Ready to show the QR. [relayEnabled] switches the disclosure copy. */
    data class Ready(val payloadJson: String, val qr: ImageBitmap, val relayEnabled: Boolean) : HostWizardUiState
    data class Error(val message: String) : HostWizardUiState
}

// ── Stateful model (async claim + QR + keep-alive) ─────────────────────────────────

/**
 * Drives the wizard: awaits the supervisor's hostId, mints a claim ([mintClaim]), builds + encodes the
 * payload, renders the QR ([qrOf]), and on finish auto-pairs "This computer" into the fleet
 * ([onPairThisComputer]) and turns the background keep-alive (OS service that keeps the broker running after quit/sign-out) on or off ([onInstallKeepAlive]) per the box.
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

    /**
     * The local broker's device token this wizard minted (or reused), once [prepare] got that far.
     * Before Done nothing may be stored as "This computer" yet, so the wizard's own actions on the
     * local broker (Install git…) authenticate with this.
     */
    val localToken: String? get() = claim?.localToken?.takeIf { it.isNotBlank() }
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

    /** Finish: auto-pair "This computer" and enable (or skip) the background keep-alive service per [keepAlive]. */
    fun finish(keepAlive: Boolean) {
        val c = claim; val id = hostId
        if (c != null && id != null) onPairThisComputer(c.localToken, directUrl, id)
        onInstallKeepAlive(keepAlive)
    }
}

// ── Stateless content (Compose-UI tested) ──────────────────────────────────────────

/** Spec §6 copy — kept as constants so the UI test asserts the exact strings. */
const val HOST_WIZARD_HEADLINE = "This computer is ready to host your agents. Scan the QR with your phone."
const val HOST_WIZARD_KEEPALIVE_LABEL = "Keep running in the background"
const val HOST_WIZARD_KEEPALIVE_HELP = "Your agents stay reachable after you quit, sign out or restart."
private const val RELAY_ON_DISCLOSURE =
    "Remote access is on through relay.supermux.dev."
private const val RELAY_OFF_DISCLOSURE =
    "Your phone reaches this computer directly on your local network. Turn on remote access later to reach it from anywhere through the supermux relay."

/**
 * Pure render of the wizard for a resolved [state]. Stateless so the Compose test drives it with a
 * ready payload (no supervisor/broker). [keepAlive] is hoisted (CHECKED by default at the call site).
 */
@Composable
fun HostWizardContent(
    state: HostWizardUiState,
    keepAlive: Boolean,
    onKeepAliveChange: (Boolean) -> Unit,
    onFinish: () -> Unit,
    onConnectInstead: () -> Unit,
    onRetry: () -> Unit = {},
    /** The local broker's `requirements.git`: under the QR, the skippable [GitToolsStep] while missing. */
    gitRequirement: dev.supermux.net.GitRequirement? = null,
    onInstallGit: suspend () -> Boolean = { false },
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
                    // Git is optional: an offer the user can skip, never an install on its own.
                    GitToolsStep(requirement = gitRequirement, onInstall = onInstallGit)

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
                        Column(Modifier.weight(1f)) {
                            Text(
                                HOST_WIZARD_KEEPALIVE_LABEL,
                                style = MaterialTheme.typography.bodyMedium,
                                color = cs.onSurface,
                            )
                            Text(
                                HOST_WIZARD_KEEPALIVE_HELP,
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
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

// ── Optional developer tools (git) ───────────────────────────────────────────────────

/** The optional-git step's copy, as constants so the tests assert the exact strings. */
object GitToolsCopy {
    const val TITLE_MAC = "Install Apple's developer tools — optional"
    const val BODY_MAC = "Agents work without them; they enable git features (worktrees, diffs, change tracking)."
    const val TITLE = "Install git — optional"
    const val BODY = "Agents work without it; it enables git features (worktrees, diffs, change tracking)."
    const val INSTALL = "Install"
    const val RETRY = "Retry"
    const val SKIP = "Skip"
    const val STARTING = "Starting…"
    const val INSTALLING = "Installing…"
    const val FAILED = "Couldn't start the installer."
    fun installFailed(error: String) = "Couldn't install: $error"
}

object GitToolsTags {
    const val CARD = "host_wizard_git"
    const val INSTALL = "host_wizard_git_install"
    const val SKIP = "host_wizard_git_skip"
    const val STATUS = "host_wizard_git_status"
}

/** What this wizard did about git: nothing yet, a click in flight, the installer started, or it didn't. */
enum class GitToolsPhase { IDLE, STARTING, STARTED, FAILED }

/** What the step shows. [installLabel] null = no Install button (a manual-only host). */
data class GitToolsUi(
    val title: String,
    val body: String,
    val hint: String?,
    val status: String?,
    val installLabel: String?,
    val installEnabled: Boolean,
)

/**
 * Pure: the optional-git step for the local broker's [requirement], or null when there is nothing
 * to offer (git is there, the broker didn't say, or the user skipped it). The broker's own report
 * wins over this wizard's [phase]: a failed install shows its error with Retry, a tracked one shows
 * Installing…. After Install it stays on Installing… until the broker reports git ok — then the
 * whole step goes away.
 */
fun gitToolsUi(requirement: dev.supermux.net.GitRequirement?, skipped: Boolean, phase: GitToolsPhase): GitToolsUi? {
    if (requirement == null || requirement.ok || skipped) return null
    val mac = requirement.install == dev.supermux.net.GitRequirement.INSTALL_XCODE_SELECT
    val error = requirement.installError
    val status = when {
        error != null -> GitToolsCopy.installFailed(error)
        requirement.installing || phase == GitToolsPhase.STARTED -> requirement.installNote ?: GitToolsCopy.INSTALLING
        phase == GitToolsPhase.FAILED -> GitToolsCopy.FAILED
        else -> null
    }
    val installLabel = when {
        !requirement.installable -> null
        phase == GitToolsPhase.STARTING -> GitToolsCopy.STARTING
        error != null || phase == GitToolsPhase.FAILED -> GitToolsCopy.RETRY
        else -> GitToolsCopy.INSTALL
    }
    val waiting = phase == GitToolsPhase.STARTING ||
        (error == null && (requirement.installing || phase == GitToolsPhase.STARTED))
    return GitToolsUi(
        title = if (mac) GitToolsCopy.TITLE_MAC else GitToolsCopy.TITLE,
        body = if (mac) GitToolsCopy.BODY_MAC else GitToolsCopy.BODY,
        // The Mac copy already says what it installs; elsewhere the hint is the how (winget, a package manager).
        hint = requirement.hint.takeIf { !mac && it.isNotBlank() },
        status = status,
        installLabel = installLabel,
        installEnabled = !waiting,
    )
}

/**
 * The first-run wizard's optional "developer tools" step: [Install] runs [onInstall] (the local
 * broker's `POST /system/install-git`) — only on that click — and [Skip] hides it for this wizard.
 */
@Composable
fun GitToolsStep(requirement: dev.supermux.net.GitRequirement?, onInstall: suspend () -> Boolean) {
    var skipped by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf(GitToolsPhase.IDLE) }
    // A new failure report from the broker ends this click's Installing…: Retry is offered again.
    androidx.compose.runtime.LaunchedEffect(requirement?.installError) {
        if (requirement?.installError != null && phase == GitToolsPhase.STARTED) phase = GitToolsPhase.IDLE
    }
    val ui = gitToolsUi(requirement, skipped, phase) ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(GitToolsTags.CARD),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(ui.title, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
        Text(ui.body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        ui.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        ui.status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurface, modifier = Modifier.testTag(GitToolsTags.STATUS))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = { skipped = true }, modifier = Modifier.testTag(GitToolsTags.SKIP)) { Text(GitToolsCopy.SKIP) }
            ui.installLabel?.let { label ->
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        phase = GitToolsPhase.STARTING
                        scope.launch {
                            val ok = try {
                                onInstall()
                            } catch (c: kotlinx.coroutines.CancellationException) {
                                throw c
                            } catch (_: Throwable) {
                                false
                            }
                            phase = if (ok) GitToolsPhase.STARTED else GitToolsPhase.FAILED
                        }
                    },
                    enabled = ui.installEnabled,
                    modifier = Modifier.testTag(GitToolsTags.INSTALL),
                ) { Text(label) }
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
    gitRequirement: dev.supermux.net.GitRequirement? = null,
    onInstallGit: suspend () -> Boolean = { false },
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
        gitRequirement = gitRequirement,
        onInstallGit = onInstallGit,
    )
}
