// #117: applets in the sidebar — a grid of the host's pinned applets between Agents and Workspaces (3×3 on a desktop,
// 4 across and two rows on a phone), and the All applets page, a plain icon grid.
//
// An applet is a personal web tool supermux runs for the user, not a session and not a project. Clicking (tapping) its
// icon OPENS it, straight away, with no page in between. Everything else is in its menu: right-click or the ⋯ that
// shows on hover on a desktop, long-press on a phone. The list is the host picker's host, one host at a time.
package dev.supermux.ui.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AddBox
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.InstallDesktop
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.supermux.net.AppletDto
import dev.supermux.net.MAX_APPLET_PINS
import dev.supermux.net.PHONE_PINS
import dev.supermux.net.pinned
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.chat.DecodedImage
import dev.supermux.ui.chat.rememberDecodedImage
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import kotlinx.coroutines.launch

/** What the sidebar's Applets block and the All applets page need: the host's applets and what they can do. */
class AppletsActions(
    /** The active host's applets, pins included. */
    val applets: List<AppletDto>,
    /** The active host's name, for the All applets page ("on GMKtec"). */
    val hostName: String,
    /** Opens the applet in its own window (desktop) or activity (Android). */
    val open: (AppletDto) -> Unit,
    /** Shows the All applets page. */
    val showAll: () -> Unit,
    /** Shows Applet info, at a tab ("secrets" for what he must fix; blank = Overview). */
    val info: (AppletDto, String) -> Unit,
    /** Pins or unpins; null on success, else the reason, shown where he clicked. */
    val pin: suspend (AppletDto, Boolean) -> String?,
    /** The icon's bytes, null when it has none (the tile is lettered). */
    val loadIcon: suspend (AppletDto) -> ByteArray?,
    /** Putting an applet on this device's launcher; null where it can't (no such menu item). */
    val installer: AppletInstall? = null,
)

/** [AppletsActions.installer], bound to the host. */
class AppletInstall(val phone: Boolean, val isInstalled: (AppletDto) -> Boolean, val install: suspend (AppletDto) -> String?)

internal const val APPLETS_HEADER_KEY = "chrome:applets"
internal const val APPLETS_GRID_KEY = "chrome:applets_grid"

/**
 * The Applets block as list items: the header with "All applets", then the grid of pinned applets. Nothing at all
 * when the host has no applets, or can't have them ([AppletsActions] is null then).
 */
internal fun LazyListScope.appletsZone(actions: AppletsActions, phone: Boolean) {
    val pins = actions.applets.pinned().take(if (phone) PHONE_PINS else MAX_APPLET_PINS)
    if (actions.applets.isEmpty()) return
    item(key = APPLETS_HEADER_KEY) { AppletsHeader(onAll = actions.showAll, phone = phone) }
    item(key = APPLETS_GRID_KEY) {
        Column(Modifier.fillMaxWidth()) {
            AppletGrid(
                pins,
                columns = if (phone) 4 else 3,
                iconSize = if (phone) 54.dp else 40.dp,
                actions = actions,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = if (phone) 10.dp else 8.dp),
                testTagPrefix = "applet_pin",
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Same header as Agents and Workspaces: the title, then "All applets" on the right. */
@Composable
private fun AppletsHeader(onAll: () -> Unit, phone: Boolean) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            localized(Str.sessions_applets_title),
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            localized(Str.sessions_applets_all),
            // A link on a phone (the prototype's teal); a quiet one on a desktop that lights up on hover.
            color = if (phone) cs.primary else if (hovered) cs.onSurface else cs.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onAll)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .testTag("applets_all"),
        )
    }
}

/** A grid of applet icons, [columns] across. */
@Composable
private fun AppletGrid(
    applets: List<AppletDto>,
    columns: Int,
    iconSize: Dp,
    actions: AppletsActions,
    modifier: Modifier = Modifier,
    labelSize: Float = 11.5f,
    testTagPrefix: String,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        applets.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { a ->
                    Box(Modifier.weight(1f)) { AppletCell(a, iconSize, labelSize, actions, testTag = "${testTagPrefix}_${a.name}") }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One applet: its icon and title. Click/tap opens it. Its menu: right-click, or the ⋯ that shows on hover, on a
 * desktop; long-press on a phone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppletCell(a: AppletDto, iconSize: Dp, labelSize: Float, actions: AppletsActions, testTag: String) {
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Where the menu opens, relative to the cell: the pointer for a right-click, under the ⋯ otherwise.
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val needs = a.needsYou.isNotEmpty() || a.status != "ok"
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered || menuAt != null) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .then(
                if (pointer) {
                    Modifier.pointerInput(a.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent()
                                if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) {
                                    menuAt = e.changes.firstOrNull()?.position ?: Offset.Zero
                                    e.changes.forEach { it.consume() }
                                }
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { actions.open(a) },
                onLongClick = if (pointer) null else ({ menuAt = Offset.Zero }),
            )
            .testTag(testTag)
            .padding(top = 8.dp, bottom = 6.dp, start = 2.dp, end = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                AppletIcon(a, iconSize, actions.loadIcon)
                if (needs) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(0.dp)
                            .size(12.dp)
                            .border(2.dp, cs.surfaceContainerLow, CircleShape)
                            .clip(CircleShape)
                            .background(cs.error),
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                a.title.ifBlank { a.name },
                color = cs.onSurface,
                fontSize = labelSize.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (pointer && (hovered || menuAt != null)) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(cs.surfaceContainerHighest)
                    .clickable { menuAt = Offset(Float.NaN, Float.NaN) }
                    .testTag("${testTag}_more"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = localized(Str.shell_more), tint = cs.onSurface, modifier = Modifier.size(15.dp))
            }
        }
        menuAt?.let { at ->
            AppletMenu(a, actions, anchor = at, sheet = !pointer, onDismiss = { menuAt = null })
        }
    }
}

/** An applet's icon: its own image when it has one, else a tile in the project tiles' colours with its first letter. */
@Composable
internal fun AppletIcon(a: AppletDto, size: Dp, loadIcon: suspend (AppletDto) -> ByteArray?) {
    val radius = size * 0.275f
    var bytes by remember(a.id, a.iconUrl, a.updatedAt) { mutableStateOf<ByteArray?>(null) }
    if (a.iconUrl != null) {
        LaunchedEffect(a.id, a.iconUrl, a.updatedAt) { bytes = runCatching { loadIcon(a) }.getOrNull() }
    }
    val data = bytes
    if (data != null) {
        val px = with(LocalDensity.current) { size.roundToPx() }
        val decoded = rememberDecodedImage(data, size = px, memoryCacheKey = "applet:${a.id}:${a.updatedAt}:$px")
        if (decoded is DecodedImage.Ready) {
            Image(
                painter = decoded.painter,
                contentDescription = a.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(radius)),
            )
            return
        }
    }
    val title = a.title.ifBlank { a.name }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(radius))
            .background(monogramHue(title))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(radius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.first().uppercaseChar().toString(),
            color = Color.White,
            fontSize = (size.value * 0.44f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** "Updated 7m ago", "Updated just now", or null when the time can't be read. */
internal fun appletUpdatedText(ts: String?): String? {
    val ago = archivedRelTime(ts)
    return when {
        ago.isEmpty() -> null
        ago == localized(Str.archived_time_now) -> localized(Str.applets_updated_just_now)
        else -> localized(Str.applets_menu_updated, ago)
    }
}

/** One line of an applet's menu. */
private class AppletMenuItem(val icon: ImageVector, val label: String, val warn: Boolean = false, val tag: String, val dim: Boolean = false, val run: suspend () -> String?)

/**
 * An applet's menu: a popup at the pointer (desktop) or a sheet (phone). Items appear only for what this device can
 * do; a failed action keeps the menu open and says why, in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppletMenu(a: AppletDto, actions: AppletsActions, anchor: Offset, sheet: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val pinned = a.pin != null
    val full = actions.applets.count { it.pin != null } >= MAX_APPLET_PINS
    val items = buildList {
        add(AppletMenuItem(Icons.AutoMirrored.Outlined.OpenInNew, localized(Str.applets_menu_open), tag = "open") { actions.open(a); null })
        actions.installer?.let { inst ->
            if (inst.isInstalled(a)) {
                add(AppletMenuItem(Icons.Outlined.Check, localized(if (inst.phone) Str.applets_menu_on_home else Str.applets_menu_installed), tag = "installed", dim = true) { null })
            } else {
                add(AppletMenuItem(if (inst.phone) Icons.Outlined.AddBox else Icons.Outlined.InstallDesktop, localized(if (inst.phone) Str.applets_menu_add_home else Str.applets_menu_install), tag = "install") { inst.install(a) })
            }
        }
        if (pinned) add(AppletMenuItem(Icons.Outlined.GridView, localized(Str.applets_menu_unpin), tag = "unpin") { actions.pin(a, false) })
        else if (!full) add(AppletMenuItem(Icons.Outlined.GridView, localized(Str.applets_menu_pin), tag = "pin") { actions.pin(a, true) })
        add(AppletMenuItem(Icons.Outlined.Info, localized(Str.applets_menu_info), tag = "info") { actions.info(a, ""); null })
    }
    val needs = a.needsYou
    val body: @Composable () -> Unit = {
        AppletMenuBody(a, actions, needs.map { n -> localized(if (n.state == "expired") Str.applets_menu_expired else Str.applets_menu_needed, n.secret) }, items, error, sheet, onFix = { onDismiss(); actions.info(a, "secrets") }) { item ->
            scope.launch {
                val why = item.run()
                if (why == null) onDismiss() else error = why
            }
        }
    }
    if (sheet) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = state,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("applet_menu_${a.name}"),
        ) { body() }
    } else {
        val density = LocalDensity.current
        // The ⋯ (NaN) opens the menu just under the button, at the cell's right; a right-click at the pointer.
        val offset = with(density) {
            if (anchor.x.isNaN()) IntOffset(0, 30.dp.roundToPx()) else IntOffset(anchor.x.toInt(), anchor.y.toInt())
        }
        Popup(
            alignment = if (anchor.x.isNaN()) Alignment.TopEnd else Alignment.TopStart,
            offset = offset,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true),
        ) {
            Box(
                Modifier
                    .width(250.dp)
                    .shadow(18.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(6.dp)
                    .testTag("applet_menu_${a.name}"),
            ) { body() }
        }
    }
}

@Composable
private fun AppletMenuBody(
    a: AppletDto,
    actions: AppletsActions,
    needLines: List<String>,
    items: List<AppletMenuItem>,
    error: String?,
    sheet: Boolean,
    onFix: () -> Unit,
    onPick: (AppletMenuItem) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = if (sheet) 24.dp else 0.dp, start = if (sheet) 4.dp else 0.dp, end = if (sheet) 4.dp else 0.dp)) {
        // The head: the applet, and when it last changed.
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AppletIcon(a, 34.dp, actions.loadIcon)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title.ifBlank { a.name }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                appletUpdatedText(a.updatedAt)?.let { Text(it, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Spacer(Modifier.height(4.dp))
        // What he needs to fix comes first: it opens Applet info at Secrets.
        needLines.forEach { line -> AppletMenuLine(Icons.Outlined.Key, line, warn = true, sheet = sheet, tag = "applet_menu_need") { onFix() } }
        items.forEach { item ->
            if (item.dim) AppletMenuLine(item.icon, item.label, warn = false, sheet = sheet, tag = "applet_menu_${item.tag}", dim = true, onClick = null)
            else AppletMenuLine(item.icon, item.label, warn = item.warn, sheet = sheet, tag = "applet_menu_${item.tag}") { onPick(item) }
        }
        if (error != null) {
            Text(
                error,
                color = cs.error,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp).testTag("applet_menu_error"),
            )
        }
    }
}

@Composable
private fun AppletMenuLine(icon: ImageVector, label: String, warn: Boolean, sheet: Boolean, tag: String, dim: Boolean = false, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint = when {
        warn -> cs.error
        dim -> dev.supermux.ui.theme.LocalSemantics.current.success
        else -> cs.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (sheet) 48.dp else 38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered && onClick != null) cs.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = when { warn -> cs.error; dim -> cs.onSurfaceVariant; else -> cs.onSurface }, fontSize = if (sheet) 15.sp else 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * All applets: a plain icon grid of every applet on the host picker's host. Click opens, the menu is the same as in
 * the sidebar. [phone] draws the phone's top bar (back, title) instead of the desktop's crumb and heading.
 */
@Composable
fun AppletsScreen(actions: AppletsActions, phone: Boolean, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surface).testTag("applets_screen")) {
        if (phone) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).testTag("applets_back"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = localized(Str.shell_back), tint = cs.onSurface)
                }
                Text(localized(Str.sessions_applets_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            }
        } else {
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(localized(Str.sessions_applets_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.weight(1f))
                if (actions.hostName.isNotBlank()) Text(localized(Str.applets_page_host, actions.hostName), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = if (phone) 10.dp else 32.dp, vertical = if (phone) 8.dp else 24.dp)) {
                val width = maxWidth
                Column(Modifier.fillMaxWidth()) {
                    if (!phone) {
                        Text(localized(Str.sessions_applets_title), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        Spacer(Modifier.height(12.dp))
                    }
                    // The desktop grid fits as many 110dp-wide cells as the width allows; a phone has 4 across.
                    val columns = if (phone) 4 else maxOf(1, (width / 120.dp).toInt())
                    AppletGrid(
                        actions.applets.sortedBy { it.title.ifBlank { it.name }.lowercase() },
                        columns = columns,
                        iconSize = 64.dp,
                        labelSize = 13f,
                        actions = actions,
                        testTagPrefix = "applet_all",
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            localized(if (phone) Str.applets_hint_phone else Str.applets_hint_desktop),
                            fontSize = 13.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
