package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.browser.DeviceGroup
import dev.supermux.browser.DeviceMode
import dev.supermux.browser.DevicePresets
import dev.supermux.browser.PageAppearance
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem

/**
 * Device mode's bar under the toolbar (#122 phase 2, DevTools' device toolbar): the device (a preset or Responsive),
 * its size to type, rotate, and close. [onChange] gets every new mode; [onClose] turns device mode off.
 */
@Composable
internal fun DeviceBar(
    mode: DeviceMode,
    onChange: (DeviceMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the page's agent and appearance can change here: without it, no Mobile/Desktop and no ◐ at all. */
    emulation: Boolean = true,
) {
    val c = kitColors()
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().testTag("browser-device-bar")) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(role = Role.DropdownList) { menu = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("browser-device-preset"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(mode.preset?.name ?: "Responsive", color = c.text, fontSize = 12.5.sp, maxLines = 1)
                    Icon(KitGlyphs.ChevronDown, contentDescription = null, tint = c.dim, modifier = Modifier.size(13.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DeviceRow("Responsive", null, mode.presetId == null, "browser-device-responsive") {
                        menu = false
                        onChange(mode.resized())
                    }
                    for (group in DeviceGroup.entries) {
                        Text(
                            group.label, color = c.faint, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
                        )
                        for (p in DevicePresets.all.filter { it.group == group }) {
                            DeviceRow(p.name, "${p.width} × ${p.height}", p.id == mode.presetId, "browser-device-${p.id}") {
                                menu = false
                                onChange(mode.withPreset(p))
                            }
                        }
                    }
                }
            }
            SideField(mode.width, "Width", "browser-device-width") { onChange(mode.resized(width = it)) }
            Text("×", color = c.faint, fontSize = 12.sp)
            SideField(mode.height, "Height", "browser-device-height") { onChange(mode.resized(height = it)) }
            BarButton(KitGlyphs.Rotate, "Rotate", "browser-device-rotate", { onChange(mode.rotated()) }, on = mode.rotated)
            if (emulation) {
                BarDivider()
                BarChoice(
                    label = if (mode.mobile) "Mobile" else "Desktop",
                    tag = "browser-device-agent",
                    choices = listOf(Triple("Mobile", "browser-device-agent-mobile", mode.mobile), Triple("Desktop", "browser-device-agent-desktop", !mode.mobile)),
                ) { i -> onChange(mode.withMobile(i == 0)) }
                BarChoice(
                    label = "◐ " + mode.appearance.label,
                    tag = "browser-device-appearance",
                    choices = PageAppearance.entries.map { Triple(it.label, "browser-device-appearance-${it.name.lowercase()}", it == mode.appearance) },
                ) { i -> onChange(mode.copy(appearance = PageAppearance.entries[i])) }
            }
            Spacer(Modifier.weight(1f))
            BarButton(KitGlyphs.Close, "Close device mode", "browser-device-close", onClose)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
    }
}

/** A hairline between the bar's groups. */
@Composable
private fun BarDivider() {
    Box(Modifier.padding(horizontal = 2.dp).size(1.dp, 16.dp).background(kitColors().line))
}

/** A label that opens a menu of [choices] (label, tag, chosen); [onChoose] gets the index chosen. */
@Composable
private fun BarChoice(label: String, tag: String, choices: List<Triple<String, String, Boolean>>, onChoose: (Int) -> Unit) {
    val c = kitColors()
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = c.dim, fontSize = 12.sp, maxLines = 1)
            Icon(KitGlyphs.ChevronDown, contentDescription = null, tint = c.faint, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEachIndexed { i, (name, rowTag, chosen) ->
                DeviceRow(name, null, chosen, rowTag) { open = false; onChoose(i) }
            }
        }
    }
}

@Composable
private fun DeviceRow(name: String, size: String?, chosen: Boolean, tag: String, onClick: () -> Unit) {
    val c = kitColors()
    DropdownMenuItem(
        text = { Text(name) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        leadingIcon = {
            if (chosen) Icon(KitGlyphs.Check, contentDescription = null, modifier = Modifier.size(15.dp))
            else Spacer(Modifier.width(15.dp))
        },
        trailingIcon = size?.let { { Text(it, fontSize = 11.5.sp, color = c.faint) } },
    )
}

/** One side of the size, typed: it applies on Enter or when the field loses the focus, and a non-number is undone. */
@Composable
private fun SideField(value: Int, label: String, tag: String, onValue: (Int) -> Unit) {
    val c = kitColors()
    var text by remember(value) { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    fun commit() {
        val typed = text.trim().toIntOrNull()
        if (typed != null && typed != value) onValue(typed) else text = value.toString()
    }
    BasicTextField(
        value = text,
        onValueChange = { t -> text = t.filter(Char::isDigit).take(5) },
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 12.sp, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier
            .width(54.dp)
            .heightIn(min = 22.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(c.wash)
            .border(1.dp, if (focused) c.accent else c.wash, RoundedCornerShape(5.dp))
            .padding(horizontal = 4.dp, vertical = 3.dp)
            .onFocusChanged { f -> if (focused && !f.isFocused) commit(); focused = f.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { commit(); true } else false
            }
            .semantics { contentDescription = label }
            .testTag(tag),
    )
}

/**
 * The page at [mode]'s size, centred on the pane's backdrop with a hairline round it; a device bigger than the pane
 * keeps its size and the pane pans (fit-to-pane scaling is a later car). [page] fills the viewport.
 */
@Composable
internal fun DeviceStage(mode: DeviceMode, page: @Composable () -> Unit) {
    val c = kitColors()
    BoxWithConstraints(Modifier.fillMaxSize().background(c.pane)) {
        val pane = this
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Box(
                Modifier.widthIn(min = pane.maxWidth).heightIn(min = pane.maxHeight).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.border(1.dp, c.line).padding(1.dp)) {
                    Box(Modifier.size(mode.width.dp, mode.height.dp).testTag("browser-viewport")) { page() }
                }
            }
        }
    }
}
