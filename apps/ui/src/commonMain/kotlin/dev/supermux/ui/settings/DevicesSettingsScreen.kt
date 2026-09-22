// The one paired-devices settings screen for both apps (cluster E4).
//
// Base = desktop's `settings/DevicesSettingsScreen.kt`: the Loading/Empty/Error load model (a
// failed GET is never "no devices"), the auto-retry that recovers from a broker reconnect, the
// revoke confirm that keeps the row when the DELETE fails, the pairing-link dialog with its QR and
// every test tag. Android's `MoreScreens.kt` `DevicesScreen` contributes the Compact branch — its
// `TopAppBar` (when the hub did not paint one) and its FAB — and gains all of the above; it used
// to filter the revoked row out locally whether or not the broker agreed, and to show "No devices
// registered." for a transport failure.
//
// The QR comes from the shared `widgets/QrCode.kt` now; Android's `BarcodeEncoder` is gone.
package dev.supermux.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.chat.parseChatTs
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.DeviceDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.qrBitmap
import dev.supermux.ui.widgets.settingsFieldColors
import dev.supermux.ui.widgets.submitOnEnter
import kotlin.time.Clock
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

/**
 * Every broker call the Devices screen makes, in one holder.
 *
 * Shapes are desktop's: the load returns `null` for a transport/decode failure (an empty list means
 * "no devices"), and the revoke reports whether the broker actually accepted the DELETE.
 */
@Immutable
class DevicesSettingsActions(
    /** `null` = transport/decode failure; empty = legitimately no devices. */
    val devicesLoad: suspend () -> List<DeviceDto>? = { null },
    val deviceAdd: suspend (name: String) -> AddDeviceResponse? = { null },
    val deviceRevoke: suspend (name: String) -> Boolean = { false },
)

/** [DevicesSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberDevicesSettingsActions(app: HostStore): DevicesSettingsActions = remember(app) {
    DevicesSettingsActions(
        devicesLoad = { app.devices() },
        deviceAdd = { name -> app.addDevice(name) },
        deviceRevoke = { name -> app.revokeDevice(name) },
    )
}

/** [DevicesSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberDevicesSettingsActions(fleet: FleetStore): DevicesSettingsActions = remember(fleet) {
    DevicesSettingsActions(
        devicesLoad = { fleet.devices() },
        deviceAdd = { name -> fleet.addDevice(name) },
        deviceRevoke = { name -> fleet.revokeDevice(name) },
    )
}

/**
 * Paired devices: list, mint a one-time pairing link, revoke.
 *
 * @param onBack leave the screen; only reachable from the Compact top bar this screen paints for
 *   itself (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 * @param standalone the screen is its own destination (Android's `Route.Devices`, reached from a
 *   deep link or the drawer) rather than a hub section, so it owns its chrome at EVERY width — a
 *   phone in landscape is Medium, and the hub is not above it to paint a title or a Back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesSettingsScreen(
    actions: DevicesSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // The add affordance follows the chrome: with our own Scaffold it is Android's FAB, otherwise
    // desktop's header button (the hub's detail pane paints no Scaffold for us to hang one on).
    var showAdd by remember { mutableStateOf(false) }
    if ((standalone || compact) && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Devices", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("devices_settings_back"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                    modifier = Modifier.testTag("devices_add_fab"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add device")
                }
            },
            containerColor = cs.background,
        ) { padding ->
            DevicesSettingsBody(
                actions = actions,
                modifier = modifier.padding(padding),
                showHeaderAdd = false,
                showAdd = showAdd,
                onShowAddChange = { showAdd = it },
            )
        }
    } else {
        DevicesSettingsBody(
            actions = actions,
            modifier = modifier,
            showHeaderAdd = true,
            showAdd = showAdd,
            onShowAddChange = { showAdd = it },
        )
    }
}

@Composable
private fun DevicesSettingsBody(
    actions: DevicesSettingsActions,
    modifier: Modifier,
    showHeaderAdd: Boolean,
    showAdd: Boolean,
    onShowAddChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var loadState by remember { mutableStateOf<DevicesLoadState>(DevicesLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var revokeTarget by remember { mutableStateOf<String?>(null) }
    var revokeBusy by remember { mutableStateOf(false) }
    var revokeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadOnce() {
        val previous = loadState
        if (previous !is DevicesLoadState.Ready) {
            loadState = DevicesLoadState.Loading
        }
        val result = actions.devicesLoad()
        loadState = when {
            result == null -> DevicesLoadState.Error("Couldn't load devices.")
            result.isEmpty() -> DevicesLoadState.Empty
            else -> DevicesLoadState.Ready(result)
        }
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    // Auto-retry while in Error so a broker reconnect recovers without close/reopen.
    // Cancelled when leaving the section (composition disposed) — no coroutine leak.
    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is DevicesLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = actions.devicesLoad()
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
        if (showHeaderAdd) {
            // Hub chrome: action row aligned to the same max width as the list (not the full pane).
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.lg, vertical = Space.md),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.widthIn(max = SettingsDetailMaxWidth).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { onShowAddChange(true) },
                        modifier = Modifier.testTag("devices_add_button"),
                    ) { Text("Add device") }
                }
            }
            HorizontalDivider(color = cs.outlineVariant)
        }

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
                                onRevoke = {
                                    revokeError = null
                                    revokeBusy = false
                                    revokeTarget = device.name
                                },
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
            onDismissRequest = {
                if (!revokeBusy) {
                    revokeTarget = null
                    revokeError = null
                }
            },
            title = { Text("Revoke device?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Remove \"$name\" from authorized devices?")
                    revokeError?.let { err ->
                        Text(
                            err,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("devices_revoke_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !revokeBusy,
                    onClick = {
                        if (revokeBusy) return@TextButton
                        revokeBusy = true
                        revokeError = null
                        scope.launch {
                            val ok = actions.deviceRevoke(name)
                            revokeBusy = false
                            if (ok) {
                                revokeTarget = null
                                // Refresh from the broker (do not trust a local-only filter).
                                reloadKey++
                            } else {
                                // Keep the dialog open and surface the failure visibly.
                                revokeError = "Couldn't revoke the device. Try again."
                            }
                        }
                    },
                    modifier = Modifier.testTag("devices_revoke_confirm"),
                ) { Text("Revoke", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !revokeBusy,
                    onClick = {
                        revokeTarget = null
                        revokeError = null
                    },
                    modifier = Modifier.testTag("devices_revoke_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("devices_revoke_dialog"),
        )
    }

    // Add-device dialog: name → one-time pairing link with QR + copy. A dialog on BOTH hosts —
    // Android's page opened the same `AlertDialog`, so there is no compact sheet to branch to.
    if (showAdd) {
        AddDeviceDialog(
            onAdd = actions.deviceAdd,
            onDismiss = { minted ->
                onShowAddChange(false)
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
    val nameFocus = remember { FocusRequester() }

    // Autofocus the name field so typing immediately after opening the dialog works.
    // Retry across a couple of frames: AlertDialog content is not focusable on the first
    // composition tick under desktop skiko.
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { nameFocus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(16)
        }
    }

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
                            .focusRequester(nameFocus)
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
                                .size(Space.qr)
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
                            Modifier.size(IconSize.md),
                            color = cs.primary,
                            strokeWidth = Stroke.md,
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
    // Touch bump: ask LocalPointerAvailable, NOT LocalInputMode — a phone with a keyboard attached
    // is still a finger-sized target.
    val rowPadding = if (LocalPointerAvailable.current) Space.md else Space.lg
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = rowPadding)
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
            val lastSeen = deviceLastSeen(device.last_seen_at)
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

/**
 * "now" / "5m" / "3h" / "2d" for a device's last-seen timestamp — the buckets both apps' `relTime`
 * produced, over the shared epoch-millis parser instead of `java.time` (which `:ui` commonMain
 * cannot have). Unparseable or absent → empty, and the row then shows no subtitle at all.
 */
internal fun deviceLastSeen(
    ts: String?,
    nowMs: Long = Clock.System.now().toEpochMilliseconds(),
): String {
    val epochMs = parseChatTs(ts) ?: return ""
    val diffSec = (nowMs - epochMs) / 1000L
    return when {
        diffSec < 60L -> "now"
        diffSec < 3600L -> "${diffSec / 60}m"
        diffSec < 86_400L -> "${diffSec / 3600}h"
        else -> "${diffSec / 86_400}d"
    }
}
