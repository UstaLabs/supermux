// Settings › Notifications (#163): what may notify the person — Agents, Sessions and Applets each with a level and any
// one of them with its own, whether what needs you breaks through Off, and supermux's own notices. The person's own
// (kept on the broker, the same on every device); the broker decides every notification with it.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AppletDto
import dev.supermux.net.BrokerApi
import dev.supermux.proto.NotificationPrefs
import dev.supermux.proto.NotificationPrefsPatch
import dev.supermux.proto.NotifyLevel
import dev.supermux.proto.SessionInfo
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.notify.NotifyItem
import dev.supermux.ui.notify.NotifyLevelMenu
import dev.supermux.ui.notify.appletItem
import dev.supermux.ui.notify.notifyChoices
import dev.supermux.ui.notify.res
import dev.supermux.ui.notify.sessionItem
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.session.AvatarRigView
import dev.supermux.ui.session.rememberAgentAvatars
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitHairline
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitOptionsRow
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.settings.localizedSettingsTitle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the Notifications page reads and changes, against one host (desktop) or the fleet's active one (phones, web). */
@Immutable
class NotificationSettingsActions(
    val prefs: StateFlow<NotificationPrefs?>,
    val sessions: StateFlow<List<SessionInfo>>,
    val applets: StateFlow<List<AppletDto>?> = MutableStateFlow(null),
    /** Null on success, else why it failed. */
    val patch: suspend (NotificationPrefsPatch) -> String? = { null },
    /** Whether a session is on this page's machine (the fleet lists every machine's). */
    val onThisMachine: (String) -> Boolean = { true },
    val apiFor: (String) -> BrokerApi? = { null },
    val loadAppletIcon: suspend (AppletDto) -> ByteArray? = { null },
)

/** [NotificationSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberNotificationSettingsActions(app: HostStore): NotificationSettingsActions = remember(app) {
    NotificationSettingsActions(
        prefs = app.notificationPrefs,
        sessions = app.sessions,
        applets = app.applets,
        patch = { app.patchNotificationPrefs(it) },
        apiFor = { app.api },
        loadAppletIcon = { app.appletIconBytes(it) },
    )
}

/** [NotificationSettingsActions] against the fleet's ACTIVE host — Android's, iOS's and the web's wiring. */
@Composable
fun rememberNotificationSettingsActions(fleet: FleetStore): NotificationSettingsActions = remember(fleet) {
    NotificationSettingsActions(
        prefs = fleet.activeNotificationPrefs,
        sessions = fleet.sessions,
        applets = fleet.activeApplets,
        patch = { fleet.patchNotificationPrefs(it) },
        onThisMachine = { id -> fleet.appFor(id) === fleet.activeApp() },
        apiFor = { id -> fleet.appFor(id)?.api },
        loadAppletIcon = { fleet.appletIconBytes(it) },
    )
}

private fun SessionInfo.isAgent() = role == "personal_assistant"

@Composable
fun NotificationsPage(
    actions: NotificationSettingsActions,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
) {
    // Per account; with two or more machines each keeps its own, so the scope names the machine then.
    val machines = LocalSettingsMachines.current
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Notifications)),
        modifier,
        scope = { if ((machines?.all?.size ?: 0) >= 2) KitMachineScope() else AccountScope() },
        lede = localized(Str.settings_notify_lede),
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "notifications_settings_screen",
    ) {
        val prefs by actions.prefs.collectAsState()
        val p = prefs
        when {
            !currentMachineOnline() -> KitOfflineNote()
            p == null -> KitNote(localized(Str.settings_notify_unavailable), Modifier.testTag("notify_unavailable"))
            else -> NotificationRows(actions, p)
        }
    }
}

@Composable
private fun AccountScope() {
    val c = kitColors()
    Row(
        Modifier.padding(top = 6.dp).testTag("settings_scope"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(KitGlyphs.User, contentDescription = null, tint = c.dim, modifier = Modifier.size(14.dp))
        Text(localized(Str.settings_notify_scope), color = c.dim, fontSize = 12.5.sp)
    }
}

@Composable
private fun NotificationRows(actions: NotificationSettingsActions, p: NotificationPrefs) {
    val c = kitColors()
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    fun send(patch: NotificationPrefsPatch) {
        scope.launch { error = actions.patch(patch) }
    }
    val all by actions.sessions.collectAsState()
    val sessions = remember(all) { all.filter { actions.onThisMachine(it.id) } }
    val agents = remember(sessions) { sessions.filter { it.isAgent() }.sortedBy { it.name.lowercase() } }
    val chats = remember(sessions) { sessions.filter { !it.isAgent() }.sortedBy { it.name.lowercase() } }
    val applets by actions.applets.collectAsState()
    val avatars = rememberAgentAvatars(agents, apiFor = actions.apiFor)

    if (p.showTip) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.wash)
                .padding(horizontal = 14.dp, vertical = 12.dp).testTag("notify_tip"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(localized(Str.settings_notify_tip_title), color = c.text, fontSize = 13.sp)
                Text(localized(Str.settings_notify_tip_text), color = c.dim, fontSize = 12.sp, lineHeight = 17.sp)
            }
            KitGhostButton(localized(Str.settings_notify_tip_yes), onClick = { send(NotificationPrefsPatch(sessions = NotifyLevel.Needs, showTip = false)) }, modifier = Modifier.testTag("notify_tip_yes"))
            KitTextAction(localized(Str.settings_notify_tip_no), onClick = { send(NotificationPrefsPatch(showTip = false)) }, modifier = Modifier.testTag("notify_tip_no"))
        }
    }

    // Agents
    Spacer(Modifier.height(8.dp))
    KitOptionsRow(
        localized(Str.settings_notify_agents),
        notifyChoices(applet = false), p.agents,
        onSelect = { send(NotificationPrefsPatch(agents = it)) },
        optionLabel = { localized(it.res()) },
        modifier = Modifier.kitAnchor("notify-agents"),
        description = localized(Str.settings_notify_agents_desc),
        optionTag = { "notify_agents_${it.wire}" },
    )
    if (agents.isNotEmpty()) {
        ItemList {
            agents.forEachIndexed { i, a ->
                ItemRow(
                    title = a.name,
                    item = p.sessionItem(a.id, agent = true)!!,
                    tag = "notify_item_${a.id}",
                    last = i == agents.lastIndex,
                    leading = {
                        val rig = avatars[a.id]
                        if (rig != null) AvatarRigView(rig, state = "idle", hovered = false, seed = a.id, size = 22)
                        else AgentAvatar(a.name, online = true, working = false, size = 22)
                    },
                    onPick = { send(NotificationPrefsPatch(session = mapOf(a.id to it))) },
                )
            }
        }
    }

    // Sessions
    Spacer(Modifier.height(18.dp))
    KitOptionsRow(
        localized(Str.settings_notify_sessions),
        notifyChoices(applet = false), p.sessions,
        onSelect = { send(NotificationPrefsPatch(sessions = it)) },
        optionLabel = { localized(it.res()) },
        modifier = Modifier.kitAnchor("notify-sessions"),
        description = localized(Str.settings_notify_sessions_desc),
        optionTag = { "notify_sessions_${it.wire}" },
    )
    val own = chats.filter { p.ownLevel(it.id) != null }
    var adding by remember { mutableStateOf(false) }
    ItemList {
        if (own.isEmpty()) {
            Text(localized(Str.settings_notify_sessions_none), color = c.faint, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).testTag("notify_sessions_none"))
        }
        own.forEachIndexed { i, s -> SessionItem(s, p, last = i == own.lastIndex) { send(NotificationPrefsPatch(session = mapOf(s.id to it))) } }
    }
    Row(Modifier.padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KitTextAction(localized(Str.settings_notify_sessions_add), onClick = { adding = !adding }, modifier = Modifier.testTag("notify_sessions_add"))
        Text(
            localized(if (LocalPointerAvailable.current) Str.settings_notify_sessions_hint_pointer else Str.settings_notify_sessions_hint_touch),
            color = c.faint, fontSize = 12.sp,
        )
    }
    val others = chats.filter { p.ownLevel(it.id) == null }
    if (adding && others.isNotEmpty()) {
        ItemList(Modifier.padding(top = 6.dp)) {
            others.forEachIndexed { i, s ->
                SessionItem(s, p, last = i == others.lastIndex) {
                    adding = false
                    send(NotificationPrefsPatch(session = mapOf(s.id to it)))
                }
            }
        }
    }

    // Applets (only where this machine has them)
    val list = applets.orEmpty()
    if (applets != null) {
        Spacer(Modifier.height(18.dp))
        KitOptionsRow(
            localized(Str.settings_notify_applets),
            notifyChoices(applet = true), p.applets,
            onSelect = { send(NotificationPrefsPatch(applets = it)) },
            optionLabel = { localized(it.res()) },
            modifier = Modifier.kitAnchor("notify-applets"),
            description = localized(Str.settings_notify_applets_desc),
            optionTag = { "notify_applets_${it.wire}" },
        )
        // Each applet only where this app shows applets (not the web build until #114): its icon is native-only code.
        val appletScreens = dev.supermux.ui.session.LocalAppletScreens.current
        if (list.isNotEmpty() && appletScreens != null) {
            ItemList {
                list.forEachIndexed { i, a ->
                    ItemRow(
                        title = a.title.ifBlank { a.name },
                        item = p.appletItem(a.id)!!,
                        tag = "notify_item_${a.id}",
                        last = i == list.lastIndex,
                        leading = { appletScreens.Icon(a, 22.dp, actions.loadAppletIcon) },
                        onPick = { send(NotificationPrefsPatch(applet = mapOf(a.id to it))) },
                    )
                }
            }
        }
    }

    KitSectionHead(localized(Str.settings_notify_always))
    KitToggleRow(
        localized(Str.settings_notify_needs_you), p.needsYou,
        onCheckedChange = { send(NotificationPrefsPatch(needsYou = it)) },
        modifier = Modifier.kitAnchor("notify-needs-you").testTag("notify_needs_you"),
        description = localized(Str.settings_notify_needs_you_desc),
    )
    KitToggleRow(
        localized(Str.settings_notify_notices), p.notices,
        onCheckedChange = { send(NotificationPrefsPatch(notices = it)) },
        modifier = Modifier.kitAnchor("notify-notices").testTag("notify_notices"),
        description = localized(Str.settings_notify_notices_desc),
        divider = false,
    )
    KitNote(localized(Str.settings_notify_footer))
    error?.let { Text(localized(Str.notify_save_failed, it), color = c.danger, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp).testTag("notify_error")) }
}

/** The prototype's `.nm-list`: items in a hairline box with 8 dp corners. */
@Composable
private fun ItemList(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = kitColors()
    Column(modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp).border(1.dp, c.line, RoundedCornerShape(8.dp))) { content() }
}

@Composable
private fun SessionItem(s: SessionInfo, p: NotificationPrefs, last: Boolean, onPick: (NotifyLevel?) -> Unit) {
    ItemRow(
        title = s.name,
        // The project's folder, not the chat's worktree (a worktree's folder is a random id).
        subtitle = dev.supermux.fs.pathLeaf(s.repo_root ?: s.workdir) + (s.session_branch?.let { " · $it" } ?: ""),
        item = p.sessionItem(s.id, agent = false)!!,
        tag = "notify_item_${s.id}",
        last = last,
        leading = null,
        onPick = onPick,
    )
}

/** One agent, session or applet: its icon, its name, "Use default" when it has its own, and its level as a menu. */
@Composable
private fun ItemRow(
    title: String,
    item: NotifyItem,
    tag: String,
    last: Boolean,
    leading: (@Composable () -> Unit)?,
    onPick: (NotifyLevel?) -> Unit,
    subtitle: String? = null,
) {
    val c = kitColors()
    val m = kitMetrics()
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = if (m.phone) 52.dp else 0.dp).padding(start = 10.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (leading != null) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { leading() }
            Column(Modifier.weight(1f)) {
                Text(title, color = c.text, fontSize = m.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = c.dim, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.own != null && !m.phone) {
                KitTextAction(localized(Str.settings_notify_use_default), onClick = { onPick(null) }, modifier = Modifier.testTag("${tag}_reset"))
            }
            LevelPill(item, "${tag}_level", onPick)
        }
        if (!last) KitHairline()
    }
}

/** The item's level: a quiet dropdown (the ghost button's hairline, 6 dp corners) opening the same choices as a row menu. */
@Composable
private fun LevelPill(item: NotifyItem, tag: String, onPick: (NotifyLevel?) -> Unit) {
    val c = kitColors()
    val m = kitMetrics()
    var open by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Box {
        Row(
            Modifier.heightIn(min = m.controlHeight).clip(shape)
                .background(if (hovered || open) c.hover else Color.Transparent)
                .border(1.dp, c.line, shape)
                .clickable(interactionSource = src, indication = null, role = Role.DropdownList) { open = true }
                .padding(start = 8.dp, end = 6.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val ink = if (item.own != null) c.text else c.dim
            Icon(if (item.effective == NotifyLevel.Off) KitGlyphs.BellOff else KitGlyphs.Bell, contentDescription = null, tint = ink, modifier = Modifier.size(14.dp))
            Text(
                item.own?.let { localized(it.res()) } ?: localized(Str.notify_default, localized(item.group.res())),
                color = ink, fontSize = m.control, maxLines = 1,
            )
            Icon(KitGlyphs.ChevronDown, contentDescription = null, tint = c.dim, modifier = Modifier.size(12.dp))
        }
        NotifyLevelMenu(expanded = open, onDismiss = { open = false }, item = item, tagPrefix = tag, onPick = onPick)
    }
}
