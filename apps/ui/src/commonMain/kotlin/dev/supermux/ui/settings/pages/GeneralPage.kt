// General (#97 spec §2.2, §10.6, §11): this device's look and chat — theme, text size, live replies, chat detail,
// terminal colours — and "This app": the version with the app updater inline ([AppUpdateRows]) and the attachment
// cache. Restyled in place from the old Appearance and Storage screens (cluster E7), so the keys and their single
// source of truth are unchanged:
//
//  - Theme, text size, live replies and the terminal's colours are `SettingsKeys.APPEARANCE` / `TEXT_SCALE` /
//    `LIVE_REPLIES` / `TERMINAL_APPEARANCE` through [UiPrefs]; desktop's sidebar theme toggle writes the same key,
//    so flipping it moves the option here and choosing here repaints the shell.
//  - Chat detail is `CHAT_DETAIL_LEVEL`, the composer footer's and the chat menu's key (those stay).
//  - Material You is gone: it had done nothing since the brand palette became the only palette.
//  - The cache: nothing is ever deleted in the background — Clear and forgetting a host are the only deletions.
package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.media.MediaCache
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.settings.kit.KitActionRow
import dev.supermux.ui.settings.kit.KitDeviceScope
import dev.supermux.ui.settings.kit.KitOptionsRow
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSliderRow
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.terminal.TerminalAppearance
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import dev.supermux.ui.worktree.formatBytes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The General page.
 *
 * @param defaultAppearance what "never chosen" means on THIS host — the fallback its root theme applies (desktop
 *   opens dark, the others follow the system), so the Theme row shows what the app actually paints.
 * @param cache the attachment cache; its row shows only where there is one.
 * @param updater the app's own updater, run inline by the App version row.
 */
@Composable
fun GeneralPage(
    modifier: Modifier = Modifier,
    defaultAppearance: AppearanceMode = AppearanceMode.SYSTEM,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    cache: MediaCache? = LocalPlatform.current.mediaCache,
    updater: AppUpdater = LocalPlatform.current.updates,
) {
    KitPage(
        "General",
        modifier,
        scope = { KitDeviceScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "appearance_settings_screen",
    ) {
        LookRows(defaultAppearance)
        KitSectionHead("This app")
        AppUpdateRows(updater = updater)
        if (cache != null) CacheRow(cache)
    }
}

/** An option's tag: `general_‹anchor›_‹its visible label›`. */
private fun optionTag(anchor: String): (String) -> String = { "general_${anchor}_$it" }

private fun themeLabel(mode: AppearanceMode) = when (mode) {
    AppearanceMode.SYSTEM -> "System"
    AppearanceMode.LIGHT -> "Light"
    AppearanceMode.DARK -> "Dark"
}

private fun terminalLabel(mode: TerminalAppearance) = when (mode) {
    TerminalAppearance.FOLLOW_APP -> "Match app"
    TerminalAppearance.DARK -> "Dark"
    TerminalAppearance.LIGHT -> "Light"
}

@Composable
private fun LookRows(defaultAppearance: AppearanceMode) {
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val appearance by prefs.appearance(defaultAppearance).collectAsState(defaultAppearance)
    val terminalAppearance by prefs.terminalAppearance.collectAsState(TerminalAppearance.FOLLOW_APP)
    val storedTextScale by prefs.textScale.collectAsState(TEXT_SCALE_DEFAULT)
    val liveReplies by prefs.liveReplies.collectAsState(true)
    val chatDetail by prefs.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
    // The slider is DRAGGED, so it cannot be driven by the persisted value: a write per step of the gesture is a
    // disk write per frame, and the knob would lag the finger by a round trip through the store. The drag lives
    // here and is persisted once, when the finger lifts; this local value is dropped again as soon as the store
    // has caught up to it.
    var draggedTextScale by remember { mutableStateOf<Float?>(null) }
    val textScale = draggedTextScale ?: storedTextScale
    LaunchedEffect(storedTextScale) {
        if (draggedTextScale == storedTextScale) draggedTextScale = null
    }

    KitOptionsRow(
        "Theme",
        AppearanceMode.entries,
        appearance,
        onSelect = { scope.launch { prefs.putAppearance(it) } },
        optionLabel = ::themeLabel,
        modifier = Modifier.kitAnchor("theme").testTag("general_theme"),
        optionTag = { optionTag("theme")(themeLabel(it)) },
    )
    KitSliderRow(
        "Text size",
        value = textScale,
        // Snapped to whole 5 % steps here too, so the stored value stays clean ("1.2", not "1.2000000476").
        onChange = { draggedTextScale = (it * 20).roundToInt() / 20f },
        range = TEXT_SCALE_MIN..TEXT_SCALE_MAX,
        step = 0.05f,
        valueText = "${(textScale * 100).roundToInt()}%",
        modifier = Modifier.kitAnchor("text-size").testTag("general_text-size"),
        description = "Scales all text in the app.",
        sliderTag = "appearance_text_scale_slider",
        onChangeFinished = {
            val picked = draggedTextScale
            if (picked != null && picked != storedTextScale) {
                scope.launch { prefs.putTextScale(picked) }
            } else {
                // The drag ended back ON the stored value: no write lands, so the effect above never fires — drop
                // the local value here or the row would stop following an outside change for good.
                draggedTextScale = null
            }
        },
    )
    // Card #76: replies stream in under the ink edge by default; off, they appear once finished.
    KitToggleRow(
        "Show replies as they're written",
        checked = liveReplies,
        onCheckedChange = { on -> scope.launch { prefs.putLiveReplies(on) } },
        modifier = Modifier.kitAnchor("live-replies").testTag("appearance_live_replies_switch"),
        description = "Off: a reply appears once the agent has finished it.",
    )
    KitOptionsRow(
        "Chat detail",
        ChatDetailLevel.entries,
        chatDetail,
        onSelect = { scope.launch { prefs.putChatDetailLevel(it) } },
        optionLabel = { it.label },
        modifier = Modifier.kitAnchor("chat-detail").testTag("general_chat-detail"),
        description = "How much of each tool call a chat shows.",
        optionTag = { optionTag("chat-detail")(it.label) },
    )
    KitOptionsRow(
        "Terminal colours",
        TerminalAppearance.entries,
        terminalAppearance,
        onSelect = { scope.launch { prefs.putTerminalAppearance(it) } },
        optionLabel = ::terminalLabel,
        modifier = Modifier.kitAnchor("terminal-colours").testTag("general_terminal-colours"),
        description = "Programs that pick their own colours keep them.",
        optionTag = { optionTag("terminal-colours")(terminalLabel(it)) },
    )
}

/** The cache's size and Clear. No confirmation: nothing is lost — a file downloads again when it is next viewed. */
@Composable
private fun CacheRow(cache: MediaCache) {
    val scope = rememberCoroutineScope()
    var size by remember(cache) { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(cache) { size = cache.sizeBytes() }
    val current = size
    val kept = if (cache.persistent) "kept so they open instantly and offline." else "kept only until this page closes."
    KitActionRow(
        "Cached images and files",
        button = "Clear",
        onClick = {
            clearing = true
            scope.launch {
                cache.clear()
                size = cache.sizeBytes()
                clearing = false
            }
        },
        modifier = Modifier.kitAnchor("cache").testTag("general_cache"),
        description = when {
            clearing -> "Clearing…"
            current == null -> "Calculating…"
            current == 0L -> if (cache.persistent) "Empty" else "Empty. Images and files are $kept"
            else -> "${formatBytes(current)}, $kept"
        },
        descriptionTag = "storage_size",
        enabled = !clearing && (current ?: 0L) > 0L,
        buttonTag = "storage_clear",
    )
}
