package dev.supermux.android.pairing

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermux.android.R
import dev.supermux.net.PairUrl

private enum class PairMode { Paste, Scan, Manual }

/**
 * First-connect onboarding gate. Native M3 (NOT an iOS clone): [Scaffold] + large
 * headline + M3 `OutlinedTextField`/`Button`/`SegmentedButton`/`AlertDialog`, edge-to-edge.
 *
 * Three input modes via a [SingleChoiceSegmentedButtonRow]:
 *  - **Paste link** (default — no camera needed for dev/CI, mirrors iOS's "paste is primary").
 *  - **Scan** a QR (validated on a physical device; emulator webcams are flaky).
 *  - **Manual** host + token.
 *
 * All paths funnel through [PairingViewModel.validate] → TOFU [PairTofuDialog] → persist.
 * A `supermux://pair` deep link arrives as [initialDeepLink] and skips straight to validation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onPaired: () -> Unit,
    initialDeepLink: PairUrl? = null,
    vm: PairingViewModel = viewModel(),
) {
    val cs = MaterialTheme.colorScheme
    val state by vm.state.collectAsStateWithLifecycle()
    val validating = state is PairingUiState.Validating

    var mode by rememberSaveable { mutableStateOf(PairMode.Paste) }
    var linkInput by rememberSaveable { mutableStateOf("") }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var manualToken by rememberSaveable { mutableStateOf("") }

    val qrLaunch = rememberQrScanLauncher { decoded ->
        if (decoded != null) vm.validate(decoded, fallbackBase = null)
    }

    // A deep-link arrival (cold start) validates immediately → straight to the TOFU dialog.
    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink != null) vm.validatePair(initialDeepLink)
    }

    when (val s = state) {
        is PairingUiState.Confirm -> PairTofuDialog(
            pair = s.pair,
            deviceName = s.deviceName,
            onConfirm = { vm.confirmPersist(s.pair) },
            onDismiss = { vm.cancelConfirm() },
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
                .imePadding()
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(
                painter = painterResource(R.drawable.mux_logo),
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
                "Run `bun run pair <name>` on your broker, then scan the QR or paste the pairing link.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(PairMode.Scan, PairMode.Paste, PairMode.Manual)
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; vm.resetError() },
                        enabled = !validating,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
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

            when (mode) {
                PairMode.Scan -> {
                    Button(
                        onClick = qrLaunch,
                        enabled = !validating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Scan QR code")
                    }
                }

                PairMode.Paste -> {
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = { linkInput = it; vm.resetError() },
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.validate(linkInput) },
                        enabled = !validating && linkInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pair") }
                }

                PairMode.Manual -> {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it; vm.resetError() },
                        label = { Text("Broker host") },
                        placeholder = { Text("ws://10.0.2.2:9898 or https://host") },
                        singleLine = true,
                        enabled = !validating,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = manualToken,
                        onValueChange = { manualToken = it; vm.resetError() },
                        label = { Text("Device token") },
                        singleLine = true,
                        enabled = !validating,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        // Manual host is the fallback base; the bare token is validated via /me-/pair.json.
                        onClick = { vm.validate(manualToken.trim(), fallbackBase = manualHost.trim()) },
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
