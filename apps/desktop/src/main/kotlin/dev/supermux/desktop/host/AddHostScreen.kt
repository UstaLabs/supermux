// Ported from apps/android/src/main/kotlin/dev/supermux/android/host/AddHostScreen.kt — keep in
// sync (copy, claim/abort semantics). Desktop drops the QR-Scan mode entirely (no camera); the
// desktop-idiomatic paths are Paste-link (Ctrl+V works natively in the field) and a typed URL for
// Tailscale/VPN/reverse-proxy users. Native M3, mirrors the desktop OnboardingScreen.
package dev.supermux.desktop.host

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.host.PairingPayload
import kotlinx.coroutines.launch

private enum class AddMode { Paste, Url }

/**
 * Add-host flow (spec §3.4 / §5): two desktop-idiomatic ways to add a broker to the fleet.
 *  - **Paste link** (default) — paste the pairing payload (the QR's `{v:1,action:"pair",…,claimSecret}`
 *    JSON); parsed with [PairingPayload.parse] (rejects wrong version/action + non-supermux relay
 *    origins) then claimed. [onClaim] aborts if the returned hostId ≠ the pasted one.
 *  - **URL** — a plain typed host URL for Tailscale/VPN/reverse-proxy users: GET /host to confirm it's
 *    a supermux broker, then trust-on-first-connect claim (or a "mint a claim on the host" hint).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHostScreen(
    onBack: () -> Unit,
    defaultDeviceName: String,
    onClaim: suspend (PairingPayload, deviceName: String) -> FleetState.AddHostResult,
    onClaimByUrl: suspend (url: String, deviceName: String) -> FleetState.AddHostResult,
    onAdded: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(AddMode.Paste) }
    var pasteInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun handle(result: FleetState.AddHostResult) {
        busy = false
        when (result) {
            is FleetState.AddHostResult.Added -> onAdded()
            is FleetState.AddHostResult.NeedsClaim ->
                info = "Found ${result.identity.name.ifBlank { "the host" }}. It's already set up — " +
                    "run `mux pair` (or the host's Add-device screen) to mint a pairing link, then paste it above."
            is FleetState.AddHostResult.Error -> error = result.message
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

    fun claimUrl(raw: String) {
        error = null; info = null; busy = true
        scope.launch { handle(onClaimByUrl(raw.trim(), deviceName.trim().ifBlank { defaultDeviceName })) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add host", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("add_host_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(18.dp))
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
                .padding(horizontal = 28.dp, vertical = 16.dp),
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
                val modes = listOf(AddMode.Paste, AddMode.Url)
                modes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { mode = m; error = null; info = null },
                        enabled = !busy,
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        modifier = Modifier.testTag("add_host_mode_${m.name.lowercase()}"),
                    ) {
                        Text(when (m) { AddMode.Paste -> "Paste link"; AddMode.Url -> "URL" })
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
                AddMode.Paste -> {
                    OutlinedTextField(
                        value = pasteInput,
                        onValueChange = { pasteInput = it; error = null },
                        label = { Text("Pairing link") },
                        placeholder = { Text("{\"v\":1,\"action\":\"pair\",…}") },
                        minLines = 1,
                        maxLines = 4,
                        enabled = !busy,
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
                        modifier = Modifier.fillMaxWidth().testTag("add_host_url_field"),
                    )
                    Button(
                        onClick = { claimUrl(urlInput) },
                        enabled = !busy && urlInput.isNotBlank(),
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
