package dev.supermux.android.host

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.android.AddHostResult
import dev.supermux.android.R
import dev.supermux.android.pairing.rememberQrScanLauncher
import dev.supermux.host.PairingPayload
import kotlinx.coroutines.launch

private enum class AddMode { Scan, Paste, Url }

/**
 * Add-host flow (spec §3.4 / §5): three ways to add a broker to the fleet.
 *  - **Scan** a pairing QR (reuses the onboarding [rememberQrScanLauncher]).
 *  - **Paste** the pairing payload (the QR's `{v:1,action:"pair",…,claimSecret}` JSON).
 *  - **URL** — a plain typed host URL for Tailscale/VPN/reverse-proxy users: GET /host to confirm
 *    it's a supermux broker, then trust-on-first-connect claim (or "mint a claim on the host" hint).
 *
 * Scan/Paste parse with [PairingPayload.parse] (rejects wrong version/action + non-supermux relay
 * origins) then claim; the VM aborts if the returned hostId ≠ the scanned one. Native M3, mirrors
 * [dev.supermux.android.pairing.OnboardingScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHostScreen(
    onBack: () -> Unit,
    defaultDeviceName: String,
    onClaim: suspend (PairingPayload, deviceName: String) -> AddHostResult,
    onClaimByUrl: suspend (url: String, deviceName: String, allowInsecure: Boolean) -> AddHostResult,
    onAdded: () -> Unit,
    // True when a typed URL is plain HTTP to a non-loopback host → the unencrypted opt-in is required
    // before it can be added (spec §3.5). Defaults to never-required for single-arg callers/previews.
    needsInsecureOptIn: (String) -> Boolean = { false },
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(AddMode.Paste) }
    var pasteInput by rememberSaveable { mutableStateOf("") }
    var urlInput by rememberSaveable { mutableStateOf("") }
    var deviceName by rememberSaveable { mutableStateOf(defaultDeviceName) }
    var insecureAck by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun handle(result: AddHostResult) {
        busy = false
        when (result) {
            is AddHostResult.Added -> onAdded()
            is AddHostResult.NeedsClaim -> {
                info = "Found ${result.identity.name.ifBlank { "the host" }}. It's already set up — " +
                    "run `mux pair` (or the host's Add-device screen) to mint a pairing link, then paste it above."
            }
            is AddHostResult.Error -> error = result.message
        }
    }

    fun claimPayload(raw: String) {
        error = null; info = null
        val payload = PairingPayload.parse(raw)
        if (payload == null) {
            error = "That isn't a valid supermux pairing link. Copy the whole payload from the host."
            return
        }
        busy = true
        scope.launch { handle(onClaim(payload, deviceName.trim().ifBlank { defaultDeviceName })) }
    }

    val qrLaunch = rememberQrScanLauncher { decoded ->
        if (decoded != null) claimPayload(decoded)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add host", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("add_host_back")) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_left),
                            contentDescription = "Back",
                            tint = cs.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.surfaceContainerHigh,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Pair another broker to see all its sessions in one merged list.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(AddMode.Scan, AddMode.Paste, AddMode.Url)
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; error = null; info = null },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        modifier = Modifier.testTag("add_host_mode_${m.name.lowercase()}"),
                    ) {
                        Text(when (m) { AddMode.Scan -> "Scan"; AddMode.Paste -> "Paste link"; AddMode.Url -> "URL" })
                    }
                }
            }

            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text("This device's name") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("add_host_device_name"),
            )

            when (mode) {
                AddMode.Scan -> Button(
                    onClick = qrLaunch,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("add_host_scan"),
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Scan pairing QR")
                }

                AddMode.Paste -> {
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it; error = null },
                        label = { Text("Pairing link") },
                        placeholder = { Text("{\"v\":1,\"action\":\"pair\",…}") },
                        minLines = 1,
                        maxLines = 4,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("add_host_paste_field"),
                    )
                    Button(
                        onClick = { claimPayload(pasteInput) },
                        enabled = !busy && pasteInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("add_host_paste_submit"),
                    ) { Text("Add host") }
                }

                AddMode.Url -> {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it; error = null; info = null },
                        label = { Text("Host URL") },
                        placeholder = { Text("https://my-mac.tailnet.ts.net") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("add_host_url_field"),
                    )
                    // Plain-HTTP guard (spec §3.5): a non-loopback http:// / ws:// URL would carry the
                    // device token unencrypted — require a deliberate, labeled opt-in before adding it.
                    val needsOptIn = urlInput.isNotBlank() && needsInsecureOptIn(urlInput.trim())
                    if (needsOptIn) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .testTag("add_host_insecure_optin"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = insecureAck,
                                onCheckedChange = { insecureAck = it; error = null },
                                enabled = !busy,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Connect over an unencrypted connection — only on a network you trust (VPN/tailnet/LAN).",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            error = null; info = null; busy = true
                            val ack = insecureAck
                            scope.launch {
                                handle(onClaimByUrl(urlInput.trim(), deviceName.trim().ifBlank { defaultDeviceName }, ack))
                            }
                        },
                        enabled = !busy && urlInput.isNotBlank() && (!needsOptIn || insecureAck),
                        modifier = Modifier.fillMaxWidth().testTag("add_host_url_submit"),
                    ) { Text("Connect") }
                }
            }

            if (busy) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator()
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.error, textAlign = TextAlign.Center)
            }
            info?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
