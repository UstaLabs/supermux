// The Settings kit's chrome (#97): the phone's top bar, the sheet, the rail's rows and machine cards, and the
// scope line under a page title ("Only this computer", "On ‹machine› · every device that uses it").
package dev.supermux.ui.settings.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.ui.widgets.Dialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem

/**
 * A paired machine as Settings shows it. [local] = it runs on this computer (desktop). [status] is the card's line
 * ("Running", "Offline · last seen 2h ago", "Not hosting"); [lastSeen] is `formatLastSeen`'s text or "".
 */
@Immutable
data class KitMachine(
    val id: String,
    val name: String,
    val local: Boolean,
    val online: Boolean,
    val status: String,
    val lastSeen: String = "",
)

fun KitMachine.glyph(): ImageVector = if (local) KitGlyphs.Laptop else KitGlyphs.Server

/** Every machine Settings knows, the one its machine pages are about ([currentId]), and how to switch it. */
@Immutable
class SettingsMachines(val all: List<KitMachine>, val currentId: String?, val onPick: (String) -> Unit) {
    val current: KitMachine? get() = all.firstOrNull { it.id == currentId } ?: all.firstOrNull()
}

/** Provided by the hub; null in previews and in tests that don't set it. */
val LocalSettingsMachines = staticCompositionLocalOf<SettingsMachines?> { null }

/** The name machine pages use in their copy ("‹machine›'s local time"). */
@Composable
@ReadOnlyComposable
fun currentMachineName(): String = LocalSettingsMachines.current?.current?.name ?: "this machine"

/** False when the machine Settings is about is offline: its sections show [KitOfflineNote] instead of content. */
@Composable
@ReadOnlyComposable
fun currentMachineOnline(): Boolean = LocalSettingsMachines.current?.current?.online ?: true

/**
 * "studio-mini is offline · last seen 2h ago". Shown INSTEAD of a machine section's rows: `FleetStore.activeApp()`
 * falls through to another connected host when this one is offline, so loading anyway would show that host's
 * values under this one's name (spec §7, §10.2).
 */
@Composable
fun KitOfflineNote(modifier: Modifier = Modifier) {
    val m = LocalSettingsMachines.current?.current
    val name = m?.name ?: "This machine"
    val seen = m?.lastSeen?.takeIf { it.isNotEmpty() }?.let { " · last seen $it" } ?: ""
    KitNote("$name is offline$seen.", modifier.testTag("settings_offline"))
}

/** The phone's top bar: a back glyph and the page title, under the status bar. Not Material's TopAppBar. */
@Composable
fun KitTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backTag: String = "settings_detail_back",
    titleTag: String = "settings_detail_title",
) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Row(
        modifier
            .fillMaxWidth()
            .background(c.pane)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) c.wash else Color.Transparent)
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" }
                .testTag(backTag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(KitGlyphs.Back, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
        }
        Text(
            title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(titleTag),
        )
    }
}

/**
 * A sheet: a centred card on the chrome tone with a hairline, a title, a line of text, the body and the buttons
 * at the end. It is a dialog (it registers with `LocalModalHost`), so system back and a tap outside close it.
 */
@Composable
fun KitSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    tag: String = "settings_sheet",
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val c = kitColors()
        val shape = RoundedCornerShape(11.dp)
        Column(
            modifier
                .padding(16.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(c.chrome)
                .border(1.dp, c.line, shape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .testTag(tag),
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            if (description != null) {
                Text(description, fontSize = 12.5.sp, lineHeight = 19.sp, color = c.dim, modifier = Modifier.padding(top = 6.dp))
            }
            content()
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = buttons,
            )
        }
    }
}

/**
 * A row of the rail (wide) or of the index (phone). Wide: glyph + label, the wash when [selected]. Phone: a
 * 54 dp row with the [description] under the label, a chevron and a hairline.
 */
@Composable
fun KitNavRow(
    label: String,
    glyph: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val c = kitColors()
    val m = kitMetrics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    if (m.phone) {
        Column(
            modifier
                .fillMaxWidth()
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 18.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(glyph, contentDescription = null, tint = c.dim, modifier = Modifier.size(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 15.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (description != null) {
                        Text(
                            description,
                            fontSize = 12.sp,
                            color = c.dim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }
                Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(16.dp))
            }
            KitHairline()
        }
    } else {
        val shape = RoundedCornerShape(7.dp)
        Row(
            modifier
                .fillMaxWidth()
                .heightIn(min = 32.dp)
                .clip(shape)
                .background(
                    when {
                        selected -> c.wash
                        hovered -> c.hover
                        else -> Color.Transparent
                    },
                )
                .border(1.dp, if (focused) c.accent else Color.Transparent, shape)
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
                .semantics { this.selected = selected }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(glyph, contentDescription = null, tint = if (selected) c.accent else c.dim, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/** A machine at the top of the rail / index: glyph, name, "● Running" or "● Offline · last seen 2h ago". */
@Composable
fun KitMachineCard(machine: KitMachine, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = kitColors()
    val m = kitMetrics()
    val shape = RoundedCornerShape(if (m.phone) 12.dp else 8.dp)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val focused by src.collectIsFocusedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    selected -> c.wash
                    hovered -> c.hover
                    else -> Color.Transparent
                },
            )
            .border(
                1.dp,
                when {
                    focused -> c.accent
                    selected -> Color.Transparent
                    else -> c.line
                },
                shape,
            )
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = if (m.phone) 14.dp else 10.dp, vertical = if (m.phone) 12.dp else 9.dp)
            .testTag("settings_machine_card_${machine.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(machine.glyph(), contentDescription = null, tint = c.dim, modifier = Modifier.size(if (m.phone) 18.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                machine.name,
                fontSize = if (m.phone) 15.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                KitDotMark(if (machine.online) KitDot.Ok else KitDot.Off)
                Text(machine.status, fontSize = 11.5.sp, color = c.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.dim, modifier = Modifier.size(14.dp))
    }
}

/** "Only this computer" / "Only this phone" / "Only this device". */
@Composable
fun KitDeviceScope(modifier: Modifier = Modifier) {
    val noun = kitDeviceNoun()
    ScopeRow(modifier, if (noun == "phone") KitGlyphs.Phone else KitGlyphs.Laptop) { Text("Only this $noun") }
}

/** "On ‹machine› · every device that uses it"; the name is a picker with two or more machines. */
@Composable
fun KitMachineScope(modifier: Modifier = Modifier) {
    val ms = LocalSettingsMachines.current
    ScopeRow(modifier, ms?.current?.glyph() ?: KitGlyphs.Server) {
        Text("On")
        MachineName(ms)
        Text("· every device that uses it")
    }
}

/** A page with both kinds of rows: "Machine parts on ‹picker›", and only with two or more machines. */
@Composable
fun KitMixedScope(modifier: Modifier = Modifier) {
    val ms = LocalSettingsMachines.current ?: return
    if (ms.all.size < 2) return
    ScopeRow(modifier, ms.current?.glyph() ?: KitGlyphs.Server) {
        Text("Machine parts on")
        MachineName(ms)
    }
}

@Composable
private fun ScopeRow(modifier: Modifier, glyph: ImageVector, content: @Composable RowScope.() -> Unit) {
    val c = kitColors()
    Row(
        modifier.padding(top = 6.dp).testTag("settings_scope"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(glyph, contentDescription = null, tint = c.dim, modifier = Modifier.size(14.dp))
        ProvideTextStyle(TextStyle(color = c.dim, fontSize = 12.5.sp)) { content() }
    }
}

@Composable
private fun MachineName(ms: SettingsMachines?) {
    val c = kitColors()
    val name = ms?.current?.name ?: "this machine"
    if (ms == null || ms.all.size < 2) {
        Text(name)
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { open = true }
                .drawBehind {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(
                        c.faint,
                        Offset(0f, y),
                        Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                    )
                }
                .testTag("settings_scope_picker"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(name, color = c.text)
            Icon(KitGlyphs.ChevronDown, contentDescription = "Pick a machine", tint = c.text, modifier = Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (mc in ms.all) {
                DropdownMenuItem(
                    text = { Text(mc.name) },
                    onClick = {
                        open = false
                        if (mc.id != ms.current?.id) ms.onPick(mc.id)
                    },
                    leadingIcon = { Icon(mc.glyph(), contentDescription = null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = {
                        if (mc.id == ms.current?.id) {
                            Icon(KitGlyphs.Check, contentDescription = "Current", modifier = Modifier.size(14.dp))
                        } else {
                            Text(if (mc.local) "this computer" else "server", fontSize = 11.5.sp)
                        }
                    },
                    modifier = Modifier.testTag("settings_machine_pick_${mc.id}"),
                )
            }
        }
    }
}
