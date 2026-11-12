package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.supermux.browser.DisplayAddress
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.keys.AppCommand
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.currentKeyOs
import dev.supermux.ui.keys.formatChord
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.webview.FindResult

/** How the address bar shows a page: a preview as its port, never the scheme. */
internal fun DisplayAddress.text(): String = when (this) {
    is DisplayAddress.Preview -> "port $port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Local -> "localhost:$port" + path.takeIf { it != "/" }.orEmpty()
    is DisplayAddress.Web -> host + path.takeIf { it != "/" }.orEmpty()
}

/**
 * The Browser tab's chrome in the app's own kit (#122, matching its prototype): #97's thin glyphs and tones, no
 * Material buttons, menus or ripples. A phone (compact and touch) gets its menu as a bottom sheet, anything with a
 * pointer the app's own menu.
 */
@Composable
internal fun phoneChrome(): Boolean = LocalWindowWidthClass.current == WindowWidthClass.Compact && !LocalPointerAvailable.current

/** A glyph button of the bar: no ripple, a soft wash on hover and press, the glyph dimmed when it can't act. */
@Composable
internal fun BarButton(glyph: ImageVector, label: String, tag: String, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 28.dp, on: Boolean = false) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val pressed by src.collectIsPressedAsState()
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(if (on || enabled && (hovered || pressed)) c.wash else Color.Transparent)
            .clickable(interactionSource = src, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = null, tint = if (on) c.accent else if (enabled) c.dim else c.faint.copy(alpha = 0.45f), modifier = Modifier.size(16.dp))
    }
}

/**
 * The bar over a page: back, forward, reload (stop while it loads), the address and a menu. The address shows [shown]
 * (a preview as its port, after a green dot) and, while it has the focus, all of [full] to edit; a zoom other than 100 %
 * shows inside it. Enter submits; Escape gives up.
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
    /** The page is one of the host's port previews: a green dot before its address. */
    preview: Boolean = false,
    /** Device mode is on (lit), off, or null where it isn't offered (a phone, #122 §3.2). */
    device: Boolean? = null,
    onDevice: () -> Unit = {},
    /** A phone's "Desktop site" is on (checked), off, or null where it isn't offered (#122 2c). */
    desktopSite: Boolean? = null,
    onDesktopSite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = kitColors()
    val phone = phoneChrome()
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
    val button = if (phone) 36.dp else 28.dp
    val textSize = if (phone) 14.sp else 12.5.sp
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(if (phone) 52.dp else 38.dp).padding(horizontal = if (phone) 10.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!phone) {
                BarButton(KitGlyphs.Back, "Back", "browser-back", onBack, enabled = canGoBack)
                BarButton(KitGlyphs.Forward, "Forward", "browser-forward", onForward, enabled = canGoForward)
            }
            BarButton(if (loading) KitGlyphs.Close else KitGlyphs.Restart, if (loading) "Stop" else "Reload", "browser-reload", onReload, size = button)
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = textSize),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).focusRequester(address).testTag("browser-address")
                    .onFocusChanged { f -> editing = f.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter, Key.NumPadEnter -> { submit(); true }
                            Key.Escape -> { editing = false; field = TextFieldValue(shown); focus.clearFocus(); true }
                            else -> false
                        }
                    },
                decorationBox = { inner ->
                    val shape = RoundedCornerShape(if (phone) 10.dp else 7.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(if (phone) 36.dp else 26.dp)
                            .clip(shape)
                            .background(if (editing) c.chrome else c.wash)
                            .border(if (editing) 1.5.dp else 0.dp, if (editing) c.accent else Color.Transparent, shape)
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        if (preview && !editing) Box(Modifier.size(6.dp).clip(CircleShape).background(c.ok).testTag("browser-address-preview"))
                        Box(Modifier.weight(1f)) {
                            if (field.text.isEmpty()) Text("Type a site, or a port such as 5173", color = c.faint, fontSize = textSize, maxLines = 1)
                            inner()
                        }
                        if (zoomPercent != 100 && !editing) {
                            Text(
                                "$zoomPercent%", fontSize = 11.sp, color = c.dim, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.pane).padding(horizontal = 6.dp, vertical = 1.dp).testTag("browser-zoom"),
                            )
                        }
                    }
                },
            )
            device?.let { on -> BarButton(KitGlyphs.Phone, "Device mode", "browser-device", onDevice, on = on) }
            if (!phone) BarButton(KitGlyphs.External, "Open in your browser", "browser-external", onOpenExternally)
            Box {
                BarButton(KitGlyphs.More, "More", "browser-menu", { menu = true }, size = button)
                if (!phone) {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        MenuRow(KitGlyphs.Search, "Find in page", AppCommands.Find, "browser-menu-find") { menu = false; onFind() }
                        ZoomMenuRow(zoomPercent, onZoom)
                        MenuLine()
                        onScreenshot?.let { send -> MenuRow(KitGlyphs.Camera, "Send screenshot to chat", AppCommands.BrowserScreenshot, "browser-menu-screenshot") { menu = false; send() } }
                        device?.let { MenuRow(KitGlyphs.Phone, "Device mode", AppCommands.BrowserDeviceMode, "browser-menu-device") { menu = false; onDevice() } }
                        MenuRow(KitGlyphs.Code, "Inspect", AppCommands.BrowserInspect, "browser-menu-inspect") { menu = false; onInspect() }
                        MenuRow(KitGlyphs.External, "Open in your browser", AppCommands.BrowserOpenExternally, "browser-menu-external") { menu = false; onOpenExternally() }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
    if (phone && menu) {
        BrowserSheet(onDismiss = { menu = false }) {
            SheetRow(KitGlyphs.Search, "Find in page", "browser-menu-find") { menu = false; onFind() }
            SheetZoomRow(zoomPercent, onZoom)
            onScreenshot?.let { send -> SheetRow(KitGlyphs.Camera, "Screenshot to chat", "browser-menu-screenshot") { menu = false; send() } }
            desktopSite?.let { on -> SheetRow(KitGlyphs.Laptop, "Desktop site", "browser-menu-desktop-site", checked = on) { menu = false; onDesktopSite() } }
            SheetRow(KitGlyphs.Back, "Back", "browser-menu-back", enabled = canGoBack) { menu = false; onBack() }
            SheetRow(KitGlyphs.Forward, "Forward", "browser-menu-forward", enabled = canGoForward) { menu = false; onForward() }
            SheetRow(KitGlyphs.External, "Open in your browser", "browser-menu-external") { menu = false; onOpenExternally() }
        }
    }
}

/** This device's key for [command] as it writes it (⌘F, Ctrl+F), or nothing. */
private fun keyHint(command: AppCommand): String =
    DefaultKeymap.chordsFor(command, currentKeyOs).firstOrNull()?.let { formatChord(it, currentKeyOs) }.orEmpty()

@Composable
private fun MenuRow(glyph: ImageVector, label: String, command: AppCommand?, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        leadingIcon = { Icon(glyph, contentDescription = null, modifier = Modifier.size(15.dp)) },
        trailingIcon = command?.let { cmd -> { Text(keyHint(cmd), fontSize = 11.5.sp, color = LocalContentColor.current.copy(alpha = 0.55f)) } },
    )
}

/** Zoom out, the zoom now, zoom in — one row, the menu stays open. */
@Composable
private fun ZoomMenuRow(zoomPercent: Int, onZoom: (Int) -> Unit) {
    DropdownMenuItem(
        text = { Text("Zoom") },
        onClick = {},
        modifier = Modifier.testTag("browser-menu-zoom"),
        leadingIcon = { Spacer(Modifier.width(15.dp)) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("−", Modifier.clickable { onZoom(-1) }.padding(horizontal = 6.dp).testTag("browser-zoom-out"), fontSize = 13.sp)
                Text("$zoomPercent%", Modifier.clickable { onZoom(0) }.testTag("browser-zoom-reset"), fontSize = 11.5.sp)
                Text("+", Modifier.clickable { onZoom(+1) }.padding(horizontal = 6.dp).testTag("browser-zoom-in"), fontSize = 13.sp)
            }
        },
    )
}

@Composable
private fun MenuLine() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(kitColors().line))
}

/** A phone's menu: a sheet from the bottom in the kit's chrome, its grab handle on top; a tap outside closes it. */
@Composable
private fun BrowserSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = kitColors()
        Box(
            Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(c.chrome)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(top = 8.dp, bottom = 26.dp)
                    .testTag("browser-sheet"),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(38.dp, 4.dp).clip(CircleShape).background(c.line))
                content()
            }
        }
    }
}

@Composable
private fun SheetRow(glyph: ImageVector, label: String, tag: String, enabled: Boolean = true, checked: Boolean? = null, onClick: () -> Unit) {
    val c = kitColors()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(glyph, contentDescription = null, tint = if (enabled) c.dim else c.faint, modifier = Modifier.size(18.dp))
        Text(label, fontSize = 15.sp, color = if (enabled) c.text else c.faint, modifier = Modifier.weight(1f))
        if (checked == true) Icon(KitGlyphs.Check, contentDescription = "On", tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SheetZoomRow(zoomPercent: Int, onZoom: (Int) -> Unit) {
    val c = kitColors()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(horizontal = 22.dp).testTag("browser-menu-zoom"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.size(18.dp))
        Text("Zoom", fontSize = 15.sp, color = c.text, modifier = Modifier.weight(1f))
        BarButton(KitGlyphs.Minus, "Zoom out", "browser-zoom-out", { onZoom(-1) }, size = 36.dp)
        Text("$zoomPercent%", fontSize = 14.sp, color = c.dim, modifier = Modifier.clickable { onZoom(0) }.testTag("browser-zoom-reset"))
        BarButton(KitGlyphs.Plus, "Zoom in", "browser-zoom-in", { onZoom(+1) }, size = 36.dp)
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

/**
 * The find bar, floating over the page's top-right corner (a phone: across its top) in the kit's chrome: [onStep]
 * searches [find]'s query forwards or backwards and writes what it found into [find].
 */
@Composable
internal fun FindBar(find: FindState, onStep: (backwards: Boolean) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = kitColors()
    val phone = phoneChrome()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .then(if (phone) Modifier.fillMaxWidth() else Modifier)
            .height(if (phone) 44.dp else 34.dp)
            .shadow(8.dp, shape)
            .clip(shape)
            .background(c.chrome)
            .border(1.dp, c.line, shape)
            .padding(start = 10.dp, end = 4.dp)
            .testTag("browser-find"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BasicTextField(
            value = find.query,
            onValueChange = { find.query = it; onStep(false) },
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 13.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = (if (phone) Modifier.weight(1f) else Modifier.width(150.dp)).focusRequester(focus).testTag("browser-find-field").onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Enter, Key.NumPadEnter -> { onStep(e.isShiftPressed); true }
                    Key.Escape -> { onClose(); true }
                    else -> false
                }
            },
            decorationBox = { inner ->
                Box {
                    if (find.query.isEmpty()) Text("Find", color = c.faint, fontSize = 13.sp)
                    inner()
                }
            },
        )
        Text(find.said, color = c.dim, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp).testTag("browser-find-count"))
        BarButton(KitGlyphs.ChevronUp, "Previous", "browser-find-previous", { onStep(true) }, size = 24.dp)
        BarButton(KitGlyphs.ChevronDown, "Next", "browser-find-next", { onStep(false) }, size = 24.dp)
        BarButton(KitGlyphs.Close, "Close find", "browser-find-close", onClose, size = 24.dp)
    }
}

/** A message in place of the page (a new tab, text that isn't an address, no engine), with an action when there's one. */
@Composable
internal fun BrowserMessage(text: String, tag: String, action: Pair<String, () -> Unit>? = null) {
    val c = kitColors()
    Column(Modifier.fillMaxSize().padding(24.dp).testTag(tag), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = c.dim, fontSize = 13.sp, textAlign = TextAlign.Center)
        action?.let { (label, run) -> KitGhostButton(label, run, Modifier.padding(top = 12.dp)) }
    }
}
