// Ported from apps/android/.../settings/MoreScreens.kt DevicesScreen / AddDeviceDialog.
// Desktop adaptations:
//   - FAB → header "Add device" button (desktop pointer chrome; matches Personal Assistants)
//   - Android BarcodeEncoder QR → host/QrCode.kt qrBitmap (zxing already on desktop classpath)
//   - LocalContext copy → LocalClipboardManager
//   - sp/dp hardcodes → theme Space / Radii / MaterialTheme.typography
//   - null load = Error (Agents pattern); empty list = "No devices registered."
//   - testTags for compose UI tests + SM_DEVICES headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.host.qrBitmap
import dev.supermux.desktop.session.relTime
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.DeviceDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ERROR_AUTO_RETRY_MS = 3_000L

/** Load model — failure is distinct from a legitimate empty device list. */
internal sealed class DevicesLoadState {
    data object Loading : DevicesLoadState()
    data object Empty : DevicesLoadState()
    data class Ready(val devices: List<DeviceDto>) : DevicesLoadState()
    data class Error(val message: String) : DevicesLoadState()
}

@Composable
fun DevicesSettingsScreen(
    /**
     * Load paired devices.
     * `null` = transport/decode failure; empty list = legitimate empty; non-empty = data.
     */
    devicesLoad: suspend () -> List<DeviceDto>?,
    deviceAdd: suspend (name: String) -> AddDeviceResponse?,
    deviceRevoke: suspend (name: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var loadState by remember { mutableStateOf<DevicesLoadState>(DevicesLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var revokeTarget by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadOnce() {
        val previous = loadState
        if (previous !is DevicesLoadState.Ready) {
            loadState = DevicesLoadState.Loading
        }
        val result = devicesLoad()
        loadState = when {
            result == null -> DevicesLoadState.Error("Couldn't load devices.")
            result.isEmpty() -> DevicesLoadState.Empty
            else -> DevicesLoadState.Ready(result)
        }
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    // Auto-retry while in Error so a broker reconnect recovers without close/reopen.
    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is DevicesLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = devicesLoad()
            if (result != null) {
                loadState = if (result.isEmpty()) DevicesLoadState.Empty else DevicesLoadState.Ready(result)
                break
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("devices_settings_screen"),
    ) {
        // Hub chrome: action row only — no nested Back/title (hub owns navigation).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { showAdd = true },
                modifier = Modifier.testTag("devices_add_button"),
            ) { Text("Add device") }
        }
        HorizontalDivider(color = cs.outlineVariant)

        Box(
            Modifier.fillMaxSize().weight(1f, fill = true),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val state = loadState) {
                is DevicesLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            modifier = Modifier.testTag("devices_settings_loading"),
                        )
                    }
                }
                is DevicesLoadState.Empty -> {
                    Box(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxSize()
                            .padding(Space.xl)
                            .testTag("devices_settings_empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No devices registered.",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is DevicesLoadState.Error -> {
                    Column(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxWidth()
                            .padding(Space.xl)
                            .testTag("devices_settings_error"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.md),
                    ) {
                        Text(
                            state.message,
                            color = cs.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(
                            onClick = { reloadKey++ },
                            modifier = Modifier.testTag("devices_settings_retry"),
                        ) { Text("Retry") }
                    }
                }
                is DevicesLoadState.Ready -> {
                    LazyColumn(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxWidth()
                            .fillMaxSize()
                            .testTag("devices_list"),
                        contentPadding = PaddingValues(bottom = Space.xl),
                    ) {
                        items(state.devices, key = { it.name }) { device ->
                            DeviceRow(
                                device = device,
                                onRevoke = { revokeTarget = device.name },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    // Confirm revoke dialog (Android: "Revoke device?" / "Remove \"…\" from authorized devices?")
    revokeTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke device?") },
            text = { Text("Remove \"$name\" from authorized devices?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = deviceRevoke(name)
                            if (ok) {
                                val current = (loadState as? DevicesLoadState.Ready)?.devices
                                    ?: emptyList()
                                val next = current.filterNot { it.name == name }
                                loadState = if (next.isEmpty()) {
                                    DevicesLoadState.Empty
                                } else {
                                    DevicesLoadState.Ready(next)
                                }
                            }
                            revokeTarget = null
                        }
                    },
                    modifier = Modifier.testTag("devices_revoke_confirm"),
                ) { Text("Revoke", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { revokeTarget = null },
                    modifier = Modifier.testTag("devices_revoke_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("devices_revoke_dialog"),
        )
    }

    // Add-device dialog: name → one-time pairing link with QR + copy.
    if (showAdd) {
        AddDeviceDialog(
            onAdd = deviceAdd,
            onDismiss = { minted ->
                showAdd = false
                if (minted) reloadKey++
            },
        )
    }
}

@Composable
private fun AddDeviceDialog(
    onAdd: suspend (String) -> AddDeviceResponse?,
    onDismiss: (minted: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<AddDeviceResponse?>(null) }
    var copied by remember { mutableStateOf(false) }
    val minted = result != null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss(minted) },
        title = { Text(if (minted) "Pairing link" else "Add device") },
        text = {
            if (result == null) {
                Column {
                    Text(
                        "Give the new device a name. You'll get a one-time link to open on it.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(Space.md))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; error = null },
                        singleLine = true,
                        placeholder = { Text("e.g. Work laptop") },
                        isError = error != null,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .submitOnEnter(!busy && name.isNotBlank()) {
                                val trimmed = name.trim()
                                if (trimmed.isEmpty() || busy) return@submitOnEnter
                                busy = true
                                error = null
                                scope.launch {
                                    val r = onAdd(trimmed)
                                    busy = false
                                    if (r == null) error = "Couldn't create the device. Try again."
                                    else result = r
                                }
                            }
                            .testTag("devices_add_name"),
                        colors = settingsFieldColors(),
                    )
                    error?.let {
                        Spacer(Modifier.height(Space.sm))
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("devices_add_error"),
                        )
                    }
                }
            } else {
                val url = result!!.url
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.testTag("devices_pairing_result"),
                ) {
                    Text(
                        "Open this link on the new device, or scan it:",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(Space.md))
                    val qr = remember(url) {
                        runCatching { qrBitmap(url, sizePx = 512) }.getOrNull()
                    }
                    if (qr != null) {
                        Image(
                            bitmap = qr,
                            contentDescription = "Pairing QR code",
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(Radii.sm))
                                .background(Color.White)
                                .padding(Space.sm)
                                .testTag("devices_pairing_qr"),
                        )
                        Spacer(Modifier.height(Space.md))
                    }
                    Text(
                        url,
                        color = cs.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radii.sm))
                            .background(cs.surfaceContainerHigh)
                            .padding(Space.sm)
                            .testTag("devices_pairing_url"),
                    )
                    Spacer(Modifier.height(Space.sm))
                    Text(
                        "Treat this link like a password — anyone who opens it gets access until you revoke the device.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            if (result == null) {
                TextButton(
                    enabled = !busy && name.isNotBlank(),
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isEmpty()) return@TextButton
                        busy = true
                        error = null
                        scope.launch {
                            val r = onAdd(trimmed)
                            busy = false
                            if (r == null) error = "Couldn't create the device. Try again."
                            else result = r
                        }
                    },
                    modifier = Modifier.testTag("devices_add_create"),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(18.dp),
                            color = cs.primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Create")
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(result!!.url))
                        copied = true
                    },
                    modifier = Modifier.testTag("devices_add_copy"),
                ) { Text(if (copied) "Copied" else "Copy link") }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = { onDismiss(minted) },
                modifier = Modifier.testTag("devices_add_dismiss"),
            ) {
                Text(if (minted) "Done" else "Cancel")
            }
        },
        modifier = Modifier.testTag("devices_add_dialog"),
    )
}

@Composable
private fun DeviceRow(device: DeviceDto, onRevoke: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag("device_row_${device.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name,
                color = cs.onSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
            val lastSeen = relTime(device.last_seen_at)
            if (lastSeen.isNotEmpty()) {
                Text(
                    "Last seen $lastSeen",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("device_last_seen_${device.name}"),
                )
            }
        }
        TextButton(
            onClick = onRevoke,
            modifier = Modifier.testTag("device_revoke_${device.name}"),
        ) {
            Text("Revoke", color = cs.error)
        }
    }
}
