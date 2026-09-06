// The one Displays management screen for both apps (cluster G4).
//
// Base = Android's `display/DisplaysScreen.kt` (the Android analog of iOS `DisplaysView` and web
// `DisplaysView.vue`): a list of live streams, "+" to start the host default, stop a stream, tap a
// row for a full-screen viewer over the shared [DisplayStreamSurface]. Desktop GAINS it at
// `Route.Displays` — it had no displays screen at all — so the chrome follows the cluster-E gate
// `(standalone || compact) && !topBarShown` and a pointer client stops a stream with a button
// instead of a swipe (the cluster-F input rule: swipe is a Touch gesture).
package dev.supermux.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Button
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.net.DisplayStream
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.launch

/**
 * Displays management: list / start / stop / view.
 *
 * Rows come from the live [DisplayActions.displays] flow (seeded here, kept current by
 * `display_added`/`display_removed`). "+" starts a host-default display; a row opens a full-screen
 * viewer reusing [DisplayStreamSurface]; stopping is a swipe under Touch and a button under a
 * pointer.
 *
 * @param onBack leave the screen; only reachable from the chrome this screen paints for itself.
 * @param topBarShown a hub above already painted a `TopAppBar` for this detail.
 * @param standalone the screen is its own destination (`Route.Displays` on both hosts) rather than
 *   a hub section, so it owns its chrome at EVERY width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaysScreen(
    actions: DisplayActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val snackbar = remember { SnackbarHostState() }

    // Seed the list; the StateFlow stays live afterwards. Owned by the screen (Android used to do
    // this from `MainActivity`'s route, which desktop has no equivalent of).
    LaunchedEffect(actions) { actions.listDisplays() }

    val start: () -> Unit = { scope.launch { actions.startDisplay("") } }

    if ((standalone || compact) && !topBarShown) {
        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = { Text("Displays", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("displays_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                        }
                    },
                    actions = {
                        IconButton(onClick = start, modifier = Modifier.testTag("displays_start")) {
                            Icon(Icons.Filled.Add, contentDescription = "Start display", tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = cs.background,
        ) { padding ->
            DisplaysBody(actions, snackbar, showHeaderStart = false, onStart = start, modifier = Modifier.padding(padding))
        }
    } else {
        Box(modifier.fillMaxSize()) {
            DisplaysBody(actions, snackbar, showHeaderStart = true, onStart = start, modifier = Modifier.fillMaxSize())
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun DisplaysBody(
    actions: DisplayActions,
    snackbar: SnackbarHostState,
    showHeaderStart: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val list by actions.displays.collectAsState()
    var viewing by remember { mutableStateOf<DisplayStream?>(null) }

    fun stop(id: String) {
        scope.launch {
            actions.stopDisplay(id)
            runCatching { snackbar.showSnackbar("Display stopped") }
        }
    }

    Column(modifier.fillMaxSize().background(cs.background).testTag("displays_screen")) {
        // Hub / expanded chrome: the action row. The compact page hangs "+" in its own top bar.
        if (showHeaderStart) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onStart, modifier = Modifier.testTag("displays_start_button")) {
                    Text("Start display")
                }
            }
        }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No active displays.", color = cs.onSurfaceVariant, modifier = Modifier.testTag("displays_empty"))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().testTag("displays_list")) {
                items(list, key = { it.id }) { d ->
                    DisplayListItem(display = d, onOpen = { viewing = d }, onStop = { stop(d.id) })
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    viewing?.let { stream ->
        DisplayViewerDialog(stream = stream, actions = actions, onClose = { viewing = null })
    }
}

/** One row: swipe-to-stop under Touch (Android's gesture), an explicit Stop button under a pointer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DisplayListItem(display: DisplayStream, onOpen: () -> Unit, onStop: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (LocalInputMode.current != InputMode.Touch) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DisplayRow(display, onOpen, Modifier.weight(1f))
            TextButton(onClick = onStop, modifier = Modifier.padding(end = Space.sm).testTag("displays_stop_${display.id}")) {
                Text("Stop", color = cs.error)
            }
        }
        return
    }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            if (target != SwipeToDismissBoxValue.Settled) {
                onStop()
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
                Modifier.fillMaxSize().background(cs.errorContainer).padding(horizontal = Space.lg),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Stop", color = cs.onErrorContainer, fontWeight = FontWeight.Medium)
            }
        },
    ) {
        DisplayRow(display, onOpen, Modifier.fillMaxWidth())
    }
}

@Composable
private fun DisplayRow(display: DisplayStream, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val title = "${display.display.ifEmpty { display.id }} · ${display.provider}"
    val subtitle = "${display.sessionName.ifEmpty { "—" }} · ${display.status}"
    Column(
        modifier
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag("displays_row_${display.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, color = cs.onSurface, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(subtitle, color = cs.onSurfaceVariant, fontFamily = MonoFontFamily, fontSize = 11.sp)
    }
}

/**
 * Full-screen live viewer for a single display, reusing [DisplayStreamSurface] (the same surface +
 * input the chat Display tab uses) with a close affordance. NOTE: opening a stream here that is
 * also live in a chat tab spins up a SECOND client — correct (the broker multiplexes multiple WS
 * clients per display) but not resource-optimal; the cross-surface warm cache is deferred.
 */
@Composable
private fun DisplayViewerDialog(stream: DisplayStream, actions: DisplayActions, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).testTag("display_viewer")) {
            DisplayStreamSurface(stream, actions, Modifier.fillMaxSize())
            IconButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.TopStart).padding(Space.md).size(44.dp)
                    .testTag("display_viewer_close"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
            }
        }
    }
}
