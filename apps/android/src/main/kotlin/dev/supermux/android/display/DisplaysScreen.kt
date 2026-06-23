package dev.supermux.android.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermux.net.DisplayStream
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.VncClient
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Displays management screen — list / start / stop / view. The Android analog of iOS
 * `DisplaysView` (InfoPages.swift) and web `DisplaysView.vue`, following the existing
 * `DevicesScreen`/`ProxyScreen` pattern (Scaffold + TopAppBar + LazyColumn of rows).
 *
 * Rows come from the live [displays] StateFlow (kept current by `display_added`/
 * `display_removed`). "+" starts a host-default display; swipe-to-dismiss stops one;
 * tapping a row opens a full-screen viewer reusing [DisplayStreamSurface].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaysScreen(
    onBack: () -> Unit,
    displays: StateFlow<List<DisplayStream>>,
    onStart: suspend (sessionName: String) -> Unit,
    onStop: suspend (id: String) -> Unit,
    connectVnc: (String) -> VncClient,
    connectScrcpy: (String) -> ScrcpyClient,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val list by displays.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewing by remember { mutableStateOf<DisplayStream?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Displays", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { onStart("") } },
                        modifier = Modifier.testTag("displays_start"),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Start display", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (list.isEmpty()) {
                Text(
                    "No active displays.",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("displays_list")) {
                    items(list, key = { it.id }) { d ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { target ->
                                if (target != SwipeToDismissBoxValue.Settled) {
                                    scope.launch {
                                        onStop(d.id)
                                        snackbar.showMessage("Display stopped")
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(cs.errorContainer)
                                        .padding(horizontal = 20.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text("Stop", color = cs.onErrorContainer, fontWeight = FontWeight.Medium)
                                }
                            },
                        ) {
                            DisplayRow(display = d, onClick = { viewing = d })
                        }
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                }
            }
        }
    }

    viewing?.let { stream ->
        DisplayViewerDialog(
            stream = stream,
            connectVnc = connectVnc,
            connectScrcpy = connectScrcpy,
            onClose = { viewing = null },
        )
    }
}

@Composable
private fun DisplayRow(display: DisplayStream, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val title = "${display.display.ifEmpty { display.id }} · ${display.provider}"
    val subtitle = "${display.sessionName.ifEmpty { "—" }} · ${display.status}"
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(subtitle, color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

/**
 * Full-screen live viewer for a single display, reusing [DisplayStreamSurface] (the same
 * surface + input the chat Display tab uses) with a close affordance. NOTE: opening a
 * stream here that's also live in a chat tab spins up a SECOND client — correct (the
 * broker multiplexes multiple WS clients per display) but not resource-optimal; the
 * cross-surface warm cache is deferred (spec §6.3).
 */
@Composable
private fun DisplayViewerDialog(
    stream: DisplayStream,
    connectVnc: (String) -> VncClient,
    connectScrcpy: (String) -> ScrcpyClient,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF000000))
                .testTag("display_viewer"),
        ) {
            DisplayStreamSurface(stream, connectScrcpy, connectVnc)
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .size(44.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}

private suspend fun SnackbarHostState.showMessage(message: String) {
    runCatching { showSnackbar(message) }
}
