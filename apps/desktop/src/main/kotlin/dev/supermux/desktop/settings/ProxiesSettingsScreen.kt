// Ported from apps/android/.../settings/MoreScreens.kt ProxyScreen / ExposePortDialog.
// Desktop adaptations:
//   - FAB → header "Expose port" button (matches Devices / Personal Assistants hub chrome)
//   - sp/dp hardcodes → theme Space / Radii / MaterialTheme.typography
//   - null load = Error; empty list = "No proxies configured."
//   - create/remove return success so reload and error banners work
//   - testTags for compose UI tests + SM_PROXIES headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.ProxyDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ERROR_AUTO_RETRY_MS = 3_000L

/** Load model — failure is distinct from a legitimate empty proxy list. */
internal sealed class ProxiesLoadState {
    data object Loading : ProxiesLoadState()
    data object Empty : ProxiesLoadState()
    data class Ready(val proxies: List<ProxyDto>) : ProxiesLoadState()
    data class Error(val message: String) : ProxiesLoadState()
}

@Composable
fun ProxiesSettingsScreen(
    /**
     * Load proxies.
     * `null` = transport/decode failure; empty list = legitimate empty; non-empty = data.
     */
    proxiesLoad: suspend () -> List<ProxyDto>?,
    /** Session names available for the expose-port dialog (active host). */
    sessionNames: () -> List<String>,
    proxyCreate: suspend (sessionName: String, port: Int, domain: String?) -> CreateProxyResponse?,
    proxySetPublic: suspend (domain: String, isPublic: Boolean) -> Boolean,
    proxyRemove: suspend (domain: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var loadState by remember { mutableStateOf<ProxiesLoadState>(ProxiesLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var removeBusy by remember { mutableStateOf(false) }
    var removeError by remember { mutableStateOf<String?>(null) }
    /** Domain pending make-public confirm; private→public only (making private is low-risk). */
    var publicTarget by remember { mutableStateOf<String?>(null) }
    var publicBusy by remember { mutableStateOf(false) }
    var publicError by remember { mutableStateOf<String?>(null) }
    var toggleError by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadOnce() {
        val previous = loadState
        if (previous !is ProxiesLoadState.Ready) {
            loadState = ProxiesLoadState.Loading
        }
        val result = proxiesLoad()
        loadState = when {
            result == null -> ProxiesLoadState.Error("Couldn't load proxies.")
            result.isEmpty() -> ProxiesLoadState.Empty
            else -> ProxiesLoadState.Ready(result)
        }
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is ProxiesLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = proxiesLoad()
            if (result != null) {
                loadState = if (result.isEmpty()) ProxiesLoadState.Empty else ProxiesLoadState.Ready(result)
                break
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("proxies_settings_screen"),
    ) {
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
                    onClick = { showCreate = true },
                    modifier = Modifier.testTag("proxies_expose_button"),
                ) { Text("Expose port") }
            }
        }
        HorizontalDivider(color = cs.outlineVariant)

        Box(
            Modifier.fillMaxSize().weight(1f, fill = true),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (val state = loadState) {
                is ProxiesLoadState.Loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            modifier = Modifier.testTag("proxies_settings_loading"),
                        )
                    }
                }
                is ProxiesLoadState.Empty -> {
                    Box(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxSize()
                            .padding(Space.xl)
                            .testTag("proxies_settings_empty"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No proxies configured.",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                is ProxiesLoadState.Error -> {
                    Column(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxWidth()
                            .padding(Space.xl)
                            .testTag("proxies_settings_error"),
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
                            modifier = Modifier.testTag("proxies_settings_retry"),
                        ) { Text("Retry") }
                    }
                }
                is ProxiesLoadState.Ready -> {
                    Column(
                        Modifier
                            .widthIn(max = SettingsDetailMaxWidth)
                            .fillMaxWidth()
                            .fillMaxSize(),
                    ) {
                        toggleError?.let { err ->
                            Text(
                                err,
                                color = cs.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(horizontal = Space.md, vertical = Space.sm)
                                    .testTag("proxies_toggle_error"),
                            )
                        }
                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("proxies_list"),
                            contentPadding = PaddingValues(bottom = Space.xl),
                        ) {
                            items(state.proxies, key = { it.domain }) { proxy ->
                                ProxyRow(
                                    proxy = proxy,
                                    onTogglePublic = { isPublic ->
                                        if (isPublic && !proxy.isPublic) {
                                            // Exposing to the public internet needs an explicit confirm.
                                            publicError = null
                                            publicBusy = false
                                            publicTarget = proxy.domain
                                        } else {
                                            scope.launch {
                                                toggleError = null
                                                val ok = proxySetPublic(proxy.domain, isPublic)
                                                if (ok) {
                                                    val current =
                                                        (loadState as? ProxiesLoadState.Ready)?.proxies.orEmpty()
                                                    loadState = ProxiesLoadState.Ready(
                                                        current.map {
                                                            if (it.domain == proxy.domain) {
                                                                it.copy(isPublic = isPublic)
                                                            } else {
                                                                it
                                                            }
                                                        },
                                                    )
                                                } else {
                                                    toggleError =
                                                        "Couldn't update visibility for \"${proxy.domain}\"."
                                                    reloadKey++
                                                }
                                            }
                                        }
                                    },
                                    onRemove = {
                                        removeError = null
                                        removeBusy = false
                                        removeTarget = proxy.domain
                                    },
                                )
                                HorizontalDivider(color = cs.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    removeTarget?.let { domain ->
        AlertDialog(
            onDismissRequest = {
                if (!removeBusy) {
                    removeTarget = null
                    removeError = null
                }
            },
            title = { Text("Remove proxy?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("Remove proxy for \"$domain\"?")
                    removeError?.let { err ->
                        Text(
                            err,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("proxies_remove_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !removeBusy,
                    onClick = {
                        if (removeBusy) return@TextButton
                        removeBusy = true
                        removeError = null
                        scope.launch {
                            val ok = proxyRemove(domain)
                            removeBusy = false
                            if (ok) {
                                removeTarget = null
                                reloadKey++
                            } else {
                                removeError = "Couldn't remove the proxy. Try again."
                            }
                        }
                    },
                    modifier = Modifier.testTag("proxies_remove_confirm"),
                ) { Text("Remove", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !removeBusy,
                    onClick = {
                        removeTarget = null
                        removeError = null
                    },
                    modifier = Modifier.testTag("proxies_remove_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("proxies_remove_dialog"),
        )
    }

    publicTarget?.let { domain ->
        AlertDialog(
            onDismissRequest = {
                if (!publicBusy) {
                    publicTarget = null
                    publicError = null
                }
            },
            title = { Text("Make proxy public?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        "\"$domain\" will be reachable from the public internet " +
                            "(not only devices on your network). Anyone with the URL can hit this port.",
                    )
                    publicError?.let { err ->
                        Text(
                            err,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("proxies_public_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !publicBusy,
                    onClick = {
                        if (publicBusy) return@TextButton
                        publicBusy = true
                        publicError = null
                        scope.launch {
                            val ok = proxySetPublic(domain, true)
                            publicBusy = false
                            if (ok) {
                                publicTarget = null
                                val current = (loadState as? ProxiesLoadState.Ready)?.proxies.orEmpty()
                                loadState = ProxiesLoadState.Ready(
                                    current.map {
                                        if (it.domain == domain) it.copy(isPublic = true) else it
                                    },
                                )
                            } else {
                                publicError = "Couldn't make the proxy public. Try again."
                            }
                        }
                    },
                    modifier = Modifier.testTag("proxies_public_confirm"),
                ) { Text("Make public", color = cs.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !publicBusy,
                    onClick = {
                        publicTarget = null
                        publicError = null
                    },
                    modifier = Modifier.testTag("proxies_public_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("proxies_public_dialog"),
        )
    }

    if (showCreate) {
        ExposePortDialog(
            sessions = sessionNames(),
            onCreate = proxyCreate,
            onDismiss = { created ->
                showCreate = false
                if (created) reloadKey++
            },
        )
    }
}

@Composable
private fun ProxyRow(
    proxy: ProxyDto,
    onTogglePublic: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val tagSafe = proxy.domain.replace(Regex("[^A-Za-z0-9._-]"), "_")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.md)
            .testTag("proxy_row_$tagSafe"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                proxy.domain,
                color = cs.onSurface,
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("proxy_domain_$tagSafe"),
            )
            if (proxy.sessionName.isNotEmpty() || proxy.port != 0) {
                Text(
                    "→ ${proxy.sessionName}:${proxy.port}",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("proxy_target_$tagSafe"),
                )
            }
            proxy.url?.takeIf { it.isNotBlank() }?.let { url ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("proxy_url_$tagSafe"),
                ) {
                    Text(
                        url,
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = MonoFontFamily,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                        modifier = Modifier
                            .size(Space.xxl)
                            .testTag("proxy_url_copy_$tagSafe"),
                    ) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "Copy URL",
                            tint = cs.primary,
                            modifier = Modifier.size(Space.md),
                        )
                    }
                    IconButton(
                        onClick = { openInBrowser(url) },
                        modifier = Modifier
                            .size(Space.xxl)
                            .testTag("proxy_url_open_$tagSafe"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Open URL",
                            tint = cs.primary,
                            modifier = Modifier.size(Space.md),
                        )
                    }
                }
            }
        }
        Text(
            if (proxy.isPublic) "public" else "private",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = Space.xs).testTag("proxy_visibility_$tagSafe"),
        )
        Switch(
            checked = proxy.isPublic,
            onCheckedChange = onTogglePublic,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
            ),
            modifier = Modifier.testTag("proxy_public_switch_$tagSafe"),
        )
        TextButton(
            onClick = onRemove,
            modifier = Modifier.testTag("proxy_remove_$tagSafe"),
        ) {
            Text("Remove", color = cs.error, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExposePortDialog(
    sessions: List<String>,
    onCreate: suspend (sessionName: String, port: Int, domain: String?) -> CreateProxyResponse?,
    onDismiss: (created: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var selectedSession by remember { mutableStateOf(sessions.firstOrNull().orEmpty()) }
    var portText by remember { mutableStateOf("") }
    var domainText by remember { mutableStateOf("") }
    var sessionMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf(false) }

    val portValid = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val canCreate = selectedSession.isNotBlank() && portValid && !busy

    fun submitCreate() {
        val port = portText.toIntOrNull() ?: return
        if (selectedSession.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            val domain = domainText.trim().ifBlank { null }
            val r = onCreate(selectedSession, port, domain)
            busy = false
            if (r == null) {
                error = "Couldn't create the proxy. Try again."
            } else {
                created = true
                onDismiss(true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss(created) },
        title = { Text("Expose port") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                ExposedDropdownMenuBox(
                    expanded = sessionMenu,
                    onExpandedChange = { sessionMenu = it },
                ) {
                    OutlinedTextField(
                        value = selectedSession.ifBlank { "Select session" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Session") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                            .testTag("proxies_create_session"),
                        colors = settingsFieldColors(),
                    )
                    ExposedDropdownMenu(
                        expanded = sessionMenu,
                        onDismissRequest = { sessionMenu = false },
                    ) {
                        if (sessions.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No sessions", color = cs.onSurfaceVariant) },
                                onClick = { sessionMenu = false },
                            )
                        } else {
                            sessions.forEach { name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedSession = name
                                        sessionMenu = false
                                    },
                                    modifier = Modifier.testTag("proxies_create_session_item_$name"),
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() }; error = null },
                    label = { Text("Port") },
                    singleLine = true,
                    isError = portText.isNotBlank() && !portValid,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .submitOnEnter(canCreate) { submitCreate() }
                        .testTag("proxies_create_port"),
                    colors = settingsFieldColors(),
                )
                OutlinedTextField(
                    value = domainText,
                    onValueChange = { domainText = it; error = null },
                    label = { Text("Domain (optional)") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .submitOnEnter(canCreate) { submitCreate() }
                        .testTag("proxies_create_domain"),
                    colors = settingsFieldColors(),
                )
                error?.let {
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("proxies_create_error"),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canCreate,
                onClick = { submitCreate() },
                modifier = Modifier.testTag("proxies_create_confirm"),
            ) {
                Text(if (busy) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = { onDismiss(created) },
                modifier = Modifier.testTag("proxies_create_cancel"),
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("proxies_create_dialog"),
    )
}
