// Ported from apps/android/src/main/kotlin/dev/supermux/android/pairing/OnboardingScreen.kt and
// PairTofuDialog.kt — keep in sync (state wiring, copy, TOFU flow). QR-scan mode is Android-only
// (camera) and is dropped entirely here; Paste + Manual remain. Desktop's OutlinedTextField
// accepts Ctrl+V natively, so there is no separate paste-from-clipboard affordance.
package dev.supermux.desktop.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.PairUrl

private enum class PairMode { Paste, Manual }

/**
 * First-connect onboarding gate. Native M3 (NOT an iOS clone): [Scaffold] + large
 * headline + M3 `OutlinedTextField`/`Button`/`SegmentedButton`/`AlertDialog`.
 *
 * Two input modes via a [SingleChoiceSegmentedButtonRow] (Android's third mode, QR-scan,
 * needs a camera and is dropped on desktop):
 *  - **Paste link** (default) — paste the full pairing URL; Ctrl+V works natively in the field.
 *  - **Manual** host + token.
 *
 * Both paths funnel through [PairingState.validate] → TOFU [PairTofuDialog] → persist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    pairing: PairingState,
    onPaired: () -> Unit,
    initialDeepLink: PairUrl? = null,
) {
    val cs = MaterialTheme.colorScheme
    val state by pairing.state.collectAsState()
    val validating = state is PairingUiState.Validating

    var mode by remember { mutableStateOf(PairMode.Paste) }
    var linkInput by remember { mutableStateOf("") }
    var manualHost by remember { mutableStateOf("") }
    var manualToken by remember { mutableStateOf("") }

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

    Scaffold(containerColor = cs.surfaceContainerHigh) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                Icons.Filled.Devices,
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
                "Run `bun run pair <device-name>` on your broker, then paste the link here.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(PairMode.Paste, PairMode.Manual)
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; pairing.resetError() },
                        enabled = !validating,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) {
                        Text(
                            when (m) {
                                PairMode.Paste -> "Paste link"
                                PairMode.Manual -> "Manual"
                            },
                        )
                    }
                }
            }

            when (mode) {
                PairMode.Paste -> {
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it; pairing.resetError() },
                        label = { Text("Pairing link") },
                        placeholder = { Text("https://host/pair?t=…") },
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        enabled = !validating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { pairing.validate(linkInput) },
                        enabled = !validating && linkInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair") }
                }

                PairMode.Manual -> {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it; pairing.resetError() },
                        label = { Text("Broker host") },
                        placeholder = { Text("ws://127.0.0.1:9898 or https://host") },
                        singleLine = true,
                        enabled = !validating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it; pairing.resetError() },
                        label = { Text("Device token") },
                        singleLine = true,
                        enabled = !validating,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        // Manual host is the fallback base; the bare token is validated via /me-/pair.json.
                        onClick = { pairing.validate(manualToken.trim(), fallbackBase = manualHost.trim()) },
                        enabled = !validating && manualHost.isNotBlank() && manualToken.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair") }
                }
            }

            if (validating) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator()
            }
            (state as? PairingUiState.Error)?.let { err ->
                Text(
                    err.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
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
            Column {
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
        confirmButton = { TextButton(onClick = onConfirm) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
