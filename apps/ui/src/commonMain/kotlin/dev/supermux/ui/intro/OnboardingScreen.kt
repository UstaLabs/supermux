// The ONE first-connect pairing screen (cluster G6). It replaces desktop's
// `pairing/OnboardingScreen.kt` (the base: Paste + Manual, Enter-to-submit, auto-focus) and
// Android's `pairing/OnboardingScreen.kt` (Scan + Paste + Manual, URI keyboards, imePadding),
// plus the two byte-identical `PairTofuDialog`s.
//
// The union, and where each half survives:
//   - Scan is offered ONLY where `Caps.camera` is true (desktop showed a two-mode row and would
//     otherwise get a button that returns null immediately) — the `AddHostScreen` precedent.
//     It goes through `Platform.scanQr()`, so no `rememberLauncherForActivityResult` reaches
//     `:ui`.
//   - Enter-to-submit and the auto-focused primary field are POINTER-only: `LocalPointerAvailable`
//     is the "is there a real keyboard/mouse" question, and auto-focusing on a phone pops the IME
//     over the copy the user has not read yet.
//   - The URI keyboard + autocorrect-off options and `imePadding()` are unconditional (additive
//     everywhere; desktop ignores both).
//   - Inputs are `rememberSaveable` (Android's) so a rotation mid-pairing keeps the pasted link.
//   - The brand mark replaces desktop's generic `Icons.Filled.Devices` — `mux_logo` is already a
//     `:ui` composeResource (F4).
package dev.supermux.ui.intro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.PairUrl
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.PairingUiState
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.mux_logo
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.submitOnEnter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

internal enum class PairMode { Scan, Paste, Manual }

/**
 * First-connect onboarding gate. Native M3 (NOT an iOS clone): [Scaffold] + large
 * headline + M3 `OutlinedTextField`/`Button`/`SegmentedButton`/`AlertDialog`, edge-to-edge.
 *
 * Up to three input modes via a [SingleChoiceSegmentedButtonRow]:
 *  - **Scan** a QR, where there is a camera (`Caps.camera`).
 *  - **Paste link** (default — no camera needed for dev/CI, mirrors iOS's "paste is primary").
 *  - **Manual** host + token.
 *
 * All paths funnel through [PairingState.validate] → TOFU [PairTofuDialog] → persist. A
 * `supermux://pair` deep link arrives as [initialDeepLink] and skips straight to validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    pairing: PairingState,
    onPaired: () -> Unit,
    initialDeepLink: PairUrl? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    val pointer = LocalPointerAvailable.current
    val canScan = platform.caps.camera
    val state by pairing.state.collectAsState()
    val validating = state is PairingUiState.Validating

    var mode by rememberSaveable { mutableStateOf(PairMode.Paste) }
    var linkInput by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }

    // Submit actions shared by the Pair buttons and Enter-to-submit on the fields.
    val canSubmitPaste = !validating && linkInput.isNotBlank()
    val submitPaste = { pairing.validate(linkInput.trim()) }
    val canSubmitManual = !validating && manualHost.isNotBlank() && manualToken.isNotBlank()
    // Manual host is the fallback base; the bare token is validated via /me-/pair.json.
    val submitManual = { pairing.validate(manualToken.trim(), fallbackBase = manualHost.trim()) }

    // Desktop keyboard UX: focus the primary field of the active mode so the user can
    // Ctrl+V / type immediately without reaching for the mouse. Pointer-only — on a phone this
    // would raise the IME over the instructions before the user has read them.
    val linkFocus = remember { FocusRequester() }
    val hostFocus = remember { FocusRequester() }
    LaunchedEffect(mode, pointer) {
        if (!pointer) return@LaunchedEffect
        runCatching {
            when (mode) {
                PairMode.Paste -> linkFocus.requestFocus()
                PairMode.Manual -> hostFocus.requestFocus()
                PairMode.Scan -> Unit
            }
        }
    }

    // A deep-link arrival (cold start) validates immediately → straight to the TOFU dialog.
    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null) pairing.validatePair(initialDeepLink)
    }

    when (val s = state) {
        is PairingUiState.Confirm -> PairTofuDialog(
            pair = s.pair,
            deviceName = s.deviceName,
            onConfirm = { pairing.confirmPersist(s.pair) },
            onDismiss = { pairing.cancelConfirm() },
        )
        is PairingUiState.Paired -> LaunchedEffect(Unit) { onPaired() }
        else -> Unit
    }

    Scaffold(
        modifier = modifier.testTag("onboarding_screen"),
        containerColor = cs.surfaceContainerHigh,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                painter = painterResource(Res.drawable.mux_logo),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = cs.primary,
            )
            Text(
                "Connect to your broker",
                style = MaterialTheme.typography.headlineSmall,
                color = cs.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                if (canScan) {
                    "Run `bun run pair <name>` on your broker, then scan the QR or paste the pairing link."
                } else {
                    "Run `bun run pair <device-name>` on your broker, then paste the link here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("onboarding_hint"),
            )

            val modes = remember(canScan) {
                buildList {
                    if (canScan) add(PairMode.Scan)
                    add(PairMode.Paste)
                    add(PairMode.Manual)
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; pairing.resetError() },
                        enabled = !validating,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        modifier = Modifier.testTag("onboarding_mode_${m.name.lowercase()}"),
                    ) {
                        Text(
                            when (m) {
                                PairMode.Scan -> "Scan"
                                PairMode.Paste -> "Paste link"
                                PairMode.Manual -> "Manual"
                            },
                        )
                    }
                }
            }

            when (if (mode == PairMode.Scan && !canScan) PairMode.Paste else mode) {
                PairMode.Scan -> {
                    Button(
                        onClick = {
                            pairing.resetError()
                            scope.launch { platform.scanQr()?.let { pairing.validate(it, fallbackBase = null) } }
                        },
                        enabled = !validating,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_scan"),
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan QR code")
                    }
                }

                PairMode.Paste -> {
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it; pairing.resetError() },
                        label = { Text("Pairing link") },
                        placeholder = { Text("https://host/pair?t=… or supermux://pair?t=…") },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        enabled = !validating,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_paste_field")
                            .focusRequester(linkFocus)
                            .submitOnEnter(pointer && canSubmitPaste, submitPaste),
                    )
                    Button(
                        onClick = submitPaste,
                        enabled = canSubmitPaste,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_paste_submit"),
                    ) { Text("Pair") }
                }

                PairMode.Manual -> {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it; pairing.resetError() },
                        label = { Text("Broker host") },
                        // The loopback example differs per host: a desktop app pairs against the
                        // broker on the very machine it runs on, an emulator/phone reaches that
                        // machine through the 10.0.2.2 alias.
                        placeholder = {
                            Text(
                                if (pointer) "ws://127.0.0.1:9898 or https://host"
                                else "ws://10.0.2.2:9898 or https://host",
                            )
                        },
                        singleLine = true,
                        enabled = !validating,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_manual_host")
                            .focusRequester(hostFocus)
                            .submitOnEnter(pointer && canSubmitManual, submitManual),
                    )
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it; pairing.resetError() },
                        label = { Text("Device token") },
                        singleLine = true,
                        enabled = !validating,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_manual_token")
                            .submitOnEnter(pointer && canSubmitManual, submitManual),
                    )
                    Button(
                        onClick = submitManual,
                        enabled = canSubmitManual,
                        modifier = Modifier.fillMaxWidth().testTag("onboarding_manual_submit"),
                    ) { Text("Pair") }
                }
            }

            if (validating) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(Modifier.testTag("onboarding_validating"))
            }
            (state as? PairingUiState.Error)?.let { err ->
                Text(
                    err.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("onboarding_error"),
                )
            }
        }
    }
}

/**
 * Trust-on-first-connect confirmation. The token already validated against the broker;
 * this is the explicit "do you trust this host?" gate before the credential is persisted.
 * Native M3 [AlertDialog] showing the broker host + the resolved device name.
 */
@Composable
fun PairTofuDialog(
    pair: PairUrl,
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Connect to this broker?") },
        text = {
            Column(Modifier.testTag("pair_tofu_dialog")) {
                Text(
                    "You're about to trust and store credentials for:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    pair.baseUrl,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                )
                Text(
                    "Device: $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("pair_tofu_confirm")) { Text("Connect") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("pair_tofu_cancel")) { Text("Cancel") }
        },
    )
}
