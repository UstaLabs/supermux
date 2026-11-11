// A Browser tab's bars (#105): what sits over and in place of its page. Plain composables over plain values, so each is
// tested on its own; BrowserPane wires them to the page.
package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.browser.DisplayAddress
import dev.supermux.webview.FindResult

/** How the address bar shows a page: a preview as its port, never the scheme. */
internal fun DisplayAddress.text(): String = when (this) {
    is DisplayAddress.Preview -> "port $port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Local -> "localhost:$port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Web -> host + path.takeIf { it != "/" }.orEmpty()
}

/**
 * The bar over a page: back, forward, reload (stop while it loads), the address and a menu. The address shows [shown]
 * (a preview as its port) and, while it has the focus, all of [full] to edit. Enter submits; Escape gives up.
 */
@Composable
internal fun BrowserToolbar(
    shown: String,
    full: String?,
    canGoBack: Boolean,
    canGoForward: Boolean,
    loading: Boolean,
    zoomPercent: Int,
    address: FocusRequester,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onSubmit: (String) -> Unit,
    onOpenExternally: () -> Unit,
    onFind: () -> Unit,
    onZoom: (direction: Int) -> Unit,
    onInspect: () -> Unit,
    /** Null where the tab can't send a picture to a chat: then the menu doesn't offer it (#122). */
    onScreenshot: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    var field by remember { mutableStateOf(TextFieldValue(shown)) }
    // Expanding to the full address happens after the click's own selection change, which would overwrite it if done in the focus callback.
    LaunchedEffect(editing) {
        if (editing) field = TextFieldValue(full.orEmpty(), TextRange(0, full.orEmpty().length))
    }
    LaunchedEffect(shown, editing) { if (!editing) field = TextFieldValue(shown) }
    var menu by remember { mutableStateOf(false) }
    fun submit() {
        onSubmit(field.text)
        editing = false
        focus.clearFocus()
    }
    Row(modifier.fillMaxWidth().height(40.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.testTag("browser-back")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.testTag("browser-forward")) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
        }
        IconButton(onClick = onReload, modifier = Modifier.testTag("browser-reload")) {
            Icon(if (loading) Icons.Filled.Close else Icons.Filled.Refresh, contentDescription = if (loading) "Stop" else "Reload")
        }
        BasicTextField(
            value = field,
            onValueChange = { field = it },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            modifier = Modifier.weight(1f).focusRequester(address).testTag("browser-address")
                .onFocusChanged { f ->
                    if (f.isFocused) {
                        editing = true
                    } else {
                        editing = false
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { submit(); true }
                        Key.Escape -> { editing = false; field = TextFieldValue(shown); focus.clearFocus(); true }
                        else -> false
                    }
                },
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().background(cs.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (field.text.isEmpty()) Text("Type a site, or a port such as 5173", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    inner()
                }
            },
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("browser-menu")) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Find in page") }, onClick = { menu = false; onFind() }, modifier = Modifier.testTag("browser-menu-find"))
                DropdownMenuItem(text = { Text("Zoom in") }, onClick = { onZoom(+1) })
                DropdownMenuItem(text = { Text("Zoom out") }, onClick = { onZoom(-1) })
                DropdownMenuItem(text = { Text("Actual size ($zoomPercent%)") }, onClick = { onZoom(0) })
                onScreenshot?.let { send ->
                    DropdownMenuItem(text = { Text("Send screenshot to chat") }, onClick = { menu = false; send() }, modifier = Modifier.testTag("browser-menu-screenshot"))
                }
                DropdownMenuItem(text = { Text("Inspect") }, onClick = { menu = false; onInspect() })
                DropdownMenuItem(text = { Text("Open in your browser") }, onClick = { menu = false; onOpenExternally() }, modifier = Modifier.testTag("browser-menu-external"))
            }
        }
    }
}

/** What the find bar says about an answer; a null answer is an engine that can't find yet (plans 2b–2d). */
internal fun findText(query: String, result: FindResult?): String = when {
    query.isEmpty() -> ""
    result == null -> "Find isn't available here yet"
    !result.found -> "No matches"
    result.current != null && result.count != null -> "${result.current} of ${result.count}"
    result.count != null -> "${result.count} matches"
    else -> "Found"
}

/** Find in page: the query, what the engine found, previous, next and close. Enter is next, Shift+Enter previous. */
/**
 * The find bar's query and what the last search said. The pane holds it, so its find-next keys (#122) step the same
 * search the bar shows.
 */
internal class FindState {
    var query by mutableStateOf("")
    var said by mutableStateOf("")
}

/** The find bar: [onStep] searches [find]'s query forwards or backwards and writes what it found into [find]. */
@Composable
internal fun FindBar(find: FindState, onStep: (backwards: Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp).testTag("browser-find"), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            value = find.query,
            onValueChange = { find.query = it; onStep(false) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("browser-find-field").onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { onStep(e.isShiftPressed); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
        )
        Text(find.said, color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp).testTag("browser-find-count"))
        IconButton(onClick = { onStep(true) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous") }
        IconButton(onClick = { onStep(false) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next") }
        IconButton(onClick = onClose, modifier = Modifier.testTag("browser-find-close")) { Icon(Icons.Filled.Close, contentDescription = "Close find") }
    }
}

/** A message in place of the page (a new tab, text that isn't an address, no engine), with an action when there's one. */
@Composable
internal fun BrowserMessage(text: String, tag: String, action: Pair<String, () -> Unit>? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag(tag), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, run) -> TextButton(onClick = run) { Text(label) } }
    }
}
