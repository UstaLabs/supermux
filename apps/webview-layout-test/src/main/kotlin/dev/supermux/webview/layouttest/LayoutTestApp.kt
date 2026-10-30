package dev.supermux.webview.layouttest

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.webViewOverlay
import dev.supermux.webview.webViewScrim

/** Records where this is laid out, in window px, under [name], for the scenario's probes. */
fun Modifier.record(model: TestModel, name: String): Modifier =
    onGloballyPositioned { model.bounds[name] = it.boundsInWindow() }

/** The test window's content. */
@Composable
fun LayoutTestApp(model: TestModel) {
    val density = LocalDensity.current.density
    SideEffect { model.density = density }
    LaunchedEffect(model) { model.consume() }
    val page = model.page
    LaunchedEffect(page, model.layout) { if (page != 0L) Native.setLayout(page, model.layout.ordinal) }
    LaunchedEffect(page, model.keyRouting) { if (page != 0L) Native.setKeyRouting(page, model.keyRouting) }
    LaunchedEffect(page, model.accessibilityBridge) {
        if (page != 0L) Native.setAccessibilityBridge(model.window, model.accessibilityBridge)
    }
    MaterialTheme {
        ProvideWebViewOverlays {
            Column(
                Modifier.fillMaxSize().background(Color.White).pointerInput(model) {
                    // Every press Compose's main content gets, before any child handles it.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press) {
                                val at = event.changes.first().position
                                model.composeClick("press@${at.x.toInt()},${at.y.toInt()}")
                            }
                        }
                    }
                },
            ) {
                TopBar(model)
                Column(Modifier.fillMaxSize().verticalScroll(model.listScroll).padding(16.dp)) {
                    ListRow(model, 0)
                    PageItem(model)
                    for (i in 1..30) ListRow(model, i)
                }
            }
            if (model.dialogOpen) TestDialog(model)
        }
    }
}

@Composable
private fun TopBar(model: TestModel) {
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The beacon: a screenshot that shows it shows this window's current frame (ShotCheck: Marker).
        Box(Modifier.size(16.dp).background(Palette.marker).record(model, "beacon"))
        for (layout in Layout.entries) {
            val label = if (layout == Layout.Under) "A: under" else "B: over"
            if (model.layout == layout) {
                Button(onClick = {}) { Text(label) }
            } else {
                OutlinedButton(onClick = { model.layout = layout }) { Text(label) }
            }
        }
        Box {
            Button(onClick = { model.menuOpen = true }) { Text("Menu") }
            val shape = MenuDefaults.shape
            DropdownMenu(
                expanded = model.menuOpen,
                onDismissRequest = { model.menuOpen = false },
                modifier = Modifier.webViewOverlay(shape).record(model, "menu"),
                offset = DpOffset(0.dp, 112.dp),
                shape = shape,
                containerColor = Palette.menu,
            ) {
                for (i in 0 until 4) {
                    DropdownMenuItem(
                        text = { Text("Menu item $i") },
                        onClick = {
                            model.composeClick("menu-item-$i")
                            model.menuOpen = false
                        },
                        modifier = Modifier.record(model, "menu-item-$i"),
                    )
                }
            }
        }
        Button(onClick = { model.dialogOpen = true }) { Text("Dialog") }
        OutlinedTextField(
            value = model.composeText,
            onValueChange = { model.composeText = it },
            modifier = Modifier.width(180.dp).record(model, "compose-field"),
            singleLine = true,
            placeholder = { Text("Compose field") },
        )
        Toggle("⌘ keys", model.keyRouting) { model.keyRouting = it }
        Toggle("AX bridge", model.accessibilityBridge) { model.accessibilityBridge = it }
        Toggle("Cut-outs", model.cutoutsEnabled) { model.cutoutsEnabled = it }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ListRow(model: TestModel, index: Int) {
    Box(
        Modifier.fillMaxWidth().height(72.dp).background(if (index % 2 == 0) Palette.rowA else Palette.rowB)
            .record(model, "row-$index").clickable { model.composeClick("row-$index") },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text("Compose row $index", Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun Marker(modifier: Modifier) = Box(modifier.background(Palette.marker))

/** The page with what the edge offsets are measured against: cyan markers left, top and right, in Compose's frame. */
@Composable
private fun PageItem(model: TestModel) {
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().height((480 + Palette.MARKER_DP).dp)) {
        Spacer(Modifier.width(model.paneWidth))
        // The pane divider: drag it to move and resize the page.
        Box(
            Modifier.width(10.dp).fillMaxHeight().background(Palette.handle).draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    model.paneWidth = (model.paneWidth + with(density) { delta.toDp() }).coerceIn(0.dp, 400.dp)
                },
            ),
        )
        Marker(Modifier.width(Palette.MARKER_DP.dp).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Marker(Modifier.fillMaxWidth().height(Palette.MARKER_DP.dp))
            PageSlot(model, Modifier.fillMaxSize())
        }
        Marker(Modifier.width(Palette.MARKER_DP.dp).fillMaxHeight())
    }
}

/** The library README's recipe: the dialog's own scrim off, a marked one drawn as its content. */
@Composable
private fun TestDialog(model: TestModel) {
    val shape = AlertDialogDefaults.shape
    Dialog(
        onDismissRequest = { model.dialogOpen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Transparent),
    ) {
        Box(
            Modifier.fillMaxSize().background(Palette.scrim).webViewScrim(Palette.scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.composeClick("scrim") },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                Modifier.widthIn(max = 420.dp).webViewOverlay(shape).record(model, "dialog"),
                shape = shape,
                color = Palette.dialog,
                tonalElevation = 0.dp,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("A Compose dialog over the page", style = MaterialTheme.typography.headlineSmall)
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            model.composeClick("dialog-cancel")
                            model.dialogOpen = false
                        }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                model.composeClick("dialog-ok")
                                model.dialogOpen = false
                            },
                            modifier = Modifier.record(model, "dialog-ok"),
                        ) { Text("OK") }
                    }
                }
            }
        }
    }
}
