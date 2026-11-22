// #163: the Notifications choice for one agent, session or applet — the same items wherever it is offered (a row's
// menu, the chat header's ⋮, Settings › Notifications' pills), and the bell-off mark a row shows when it is Off.
package dev.supermux.ui.notify

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.proto.NotificationPrefs
import dev.supermux.proto.NotifyLevel
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem

/** One agent or session (by session id) or applet (by applet id) whose Notifications are being chosen. */
data class NotifyTarget(val id: String, val agent: Boolean = false, val applet: Boolean = false)

/**
 * What a row's Notifications menu reads and changes, provided by the shell for the whole app. Null on a host whose
 * broker has no notification settings (an older one): rows then offer Mute as before.
 */
@Immutable
class NotifyControls(
    /** The notification settings of the host that owns a session. */
    val prefsForSession: (String) -> NotificationPrefs?,
    /** The active host's, where its applets are. */
    val appletPrefs: NotificationPrefs?,
    val set: (NotifyTarget, NotifyLevel?) -> Unit,
    /** Opens Settings › Notifications; null where the shell can't. */
    val openSettings: (() -> Unit)? = null,
    /** Changes whenever any host's settings change, so readers redraw. */
    val version: Any? = null,
) {
    fun item(t: NotifyTarget): NotifyItem? =
        if (t.applet) appletPrefs.appletItem(t.id) else prefsForSession(t.id).sessionItem(t.id, t.agent)

    override fun equals(other: Any?): Boolean = other is NotifyControls && other.version == version && other.appletPrefs == appletPrefs
    override fun hashCode(): Int = (version?.hashCode() ?: 0) * 31 + (appletPrefs?.hashCode() ?: 0)
}

val LocalNotifyControls = compositionLocalOf<NotifyControls?> { null }

/** A target's item, or null where Notifications can't be chosen (no controls, an older broker). */
@Composable
fun notifyItemOrNull(t: NotifyTarget): NotifyItem? = LocalNotifyControls.current?.item(t)

/** The Notifications choices for [t] as their own menu, opened from a row's "Notifications…". */
@Composable
fun NotifyTargetMenu(t: NotifyTarget, onDismiss: () -> Unit) {
    val ctl = LocalNotifyControls.current
    val item = ctl?.item(t)
    if (ctl == null || item == null) {
        // Nothing to choose here (an older broker): let the row forget it asked.
        LaunchedEffect(t) { onDismiss() }
        return
    }
    NotifyLevelMenu(
        expanded = true,
        onDismiss = onDismiss,
        item = item,
        tagPrefix = "notify_menu",
        onPick = { ctl.set(t, it) },
        onOpenSettings = ctl.openSettings,
    )
}

/** The levels one item can take: an applet is On or Off; an agent or a session All, Needs me or Off. */
fun notifyChoices(applet: Boolean): List<NotifyLevel> =
    if (applet) listOf(NotifyLevel.On, NotifyLevel.Off) else listOf(NotifyLevel.All, NotifyLevel.Needs, NotifyLevel.Off)

fun NotifyLevel.res(): StringKey = when (this) {
    NotifyLevel.All -> Str.notify_level_all
    NotifyLevel.Needs -> Str.notify_level_needs
    NotifyLevel.Off -> Str.notify_level_off
    NotifyLevel.On -> Str.notify_level_on
}

/** One item's place in the settings: what it is, its own level (null = its group's) and its group's. */
data class NotifyItem(val own: NotifyLevel?, val group: NotifyLevel, val applet: Boolean) {
    val effective: NotifyLevel get() = own ?: group
}

/** A session's (or Agent's) item, or null on a broker without notification settings. */
fun NotificationPrefs?.sessionItem(sessionId: String, agent: Boolean): NotifyItem? =
    this?.let { NotifyItem(it.ownLevel(sessionId), it.groupLevel(agent), applet = false) }

fun NotificationPrefs?.appletItem(appletId: String): NotifyItem? =
    this?.let { NotifyItem(it.overrides.applet[appletId], it.applets, applet = true) }

/** "Default" when it follows its group, else its own level: the value a menu row shows beside "Notifications". */
@Composable
fun notifyValueWord(item: NotifyItem): String = item.own?.let { localized(it.res()) } ?: localized(Str.notify_default_short)

/**
 * The choices, as menu rows: "Default · ‹group level›", then each level, a check on the current one; then
 * "Notification settings…" when [onOpenSettings] is given. [onPick] gets null for Default.
 */
@Composable
fun ColumnScope.NotifyLevelMenuItems(
    item: NotifyItem,
    tagPrefix: String,
    onPick: (NotifyLevel?) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val rows = listOf<NotifyLevel?>(null) + notifyChoices(item.applet)
    for (level in rows) {
        val on = item.own == level
        DropdownMenuItem(
            text = { Text(if (level == null) localized(Str.notify_default, localized(item.group.res())) else localized(level.res())) },
            leadingIcon = {
                Box(Modifier.size(16.dp)) {
                    if (on) Icon(KitGlyphs.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                }
            },
            modifier = Modifier.testTag("${tagPrefix}_${level?.wire ?: "default"}"),
            onClick = { onPick(level) },
        )
    }
    if (onOpenSettings != null) {
        HorizontalDivider(Modifier.testTag("${tagPrefix}_divider"), color = cs.outlineVariant)
        DropdownMenuItem(
            text = { Text(localized(Str.notify_open_settings)) },
            leadingIcon = { Box(Modifier.size(16.dp)) },
            modifier = Modifier.testTag("${tagPrefix}_settings"),
            onClick = onOpenSettings,
        )
    }
}

/** The choices as their own menu: what "Notifications…" opens from a right-click menu or a ⋮. */
@Composable
fun NotifyLevelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    item: NotifyItem,
    tagPrefix: String,
    onPick: (NotifyLevel?) -> Unit,
    onOpenSettings: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        NotifyLevelMenuItems(
            item,
            tagPrefix,
            onPick = { onDismiss(); onPick(it) },
            onOpenSettings = onOpenSettings?.let { open -> { onDismiss(); open() } },
        )
    }
}

/** The bell struck through, beside a name whose notifications are Off. */
@Composable
fun NotifyOffMark(modifier: Modifier = Modifier, size: Dp = 13.dp) {
    Icon(
        KitGlyphs.BellOff,
        contentDescription = localized(Str.notify_off_mark),
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = modifier.size(size).testTag("notify_off_mark"),
    )
}
