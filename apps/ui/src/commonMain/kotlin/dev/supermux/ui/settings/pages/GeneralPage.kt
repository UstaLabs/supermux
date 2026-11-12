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
import dev.supermux.ui.i18n.AppLanguage
import dev.supermux.ui.i18n.localized
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
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.common_clear
import dev.supermux.ui.resources.common_dark
import dev.supermux.ui.resources.common_light
import dev.supermux.ui.resources.common_system
import dev.supermux.ui.resources.general_cache
import dev.supermux.ui.resources.general_cache_calculating
import dev.supermux.ui.resources.general_cache_clearing
import dev.supermux.ui.resources.general_cache_empty
import dev.supermux.ui.resources.general_cache_empty_session
import dev.supermux.ui.resources.general_cache_size_kept
import dev.supermux.ui.resources.general_cache_size_session
import dev.supermux.ui.resources.general_chat_detail
import dev.supermux.ui.resources.general_chat_detail_description
import dev.supermux.ui.resources.general_chat_detail_high
import dev.supermux.ui.resources.general_chat_detail_low
import dev.supermux.ui.resources.general_chat_detail_medium
import dev.supermux.ui.resources.general_language
import dev.supermux.ui.resources.general_language_description
import dev.supermux.ui.resources.general_live_replies
import dev.supermux.ui.resources.general_live_replies_description
import dev.supermux.ui.resources.general_terminal_colours
import dev.supermux.ui.resources.general_terminal_colours_description
import dev.supermux.ui.resources.general_terminal_match_app
import dev.supermux.ui.resources.general_text_size
import dev.supermux.ui.resources.general_text_size_description
import dev.supermux.ui.resources.general_theme
import dev.supermux.ui.resources.general_this_app
import dev.supermux.ui.resources.general_title
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.StringResource

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
        localized(Res.string.general_title),
        modifier,
        scope = { KitDeviceScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "appearance_settings_screen",
    ) {
        LookRows(defaultAppearance)
        KitSectionHead(localized(Res.string.general_this_app))
        AppUpdateRows(updater = updater)
        if (cache != null) CacheRow(cache)
    }
}

/** An option's tag: `general_‹anchor›_‹its English label›` — never translated, so UI automation works in any language. */
private fun optionTag(anchor: String): (String) -> String = { "general_${anchor}_$it" }

private fun themeId(mode: AppearanceMode) = when (mode) {
    AppearanceMode.SYSTEM -> "System"
    AppearanceMode.LIGHT -> "Light"
    AppearanceMode.DARK -> "Dark"
}

private fun terminalId(mode: TerminalAppearance) = when (mode) {
    TerminalAppearance.FOLLOW_APP -> "Match app" // i18n-ok: a test tag, not shown
    TerminalAppearance.DARK -> "Dark"
    TerminalAppearance.LIGHT -> "Light"
}

private fun themeRes(mode: AppearanceMode) = when (mode) {
    AppearanceMode.SYSTEM -> Res.string.common_system
    AppearanceMode.LIGHT -> Res.string.common_light
    AppearanceMode.DARK -> Res.string.common_dark
}

private fun terminalRes(mode: TerminalAppearance) = when (mode) {
    TerminalAppearance.FOLLOW_APP -> Res.string.general_terminal_match_app
    TerminalAppearance.DARK -> Res.string.common_dark
    TerminalAppearance.LIGHT -> Res.string.common_light
}

private fun chatDetailRes(level: ChatDetailLevel) = when (level) {
    ChatDetailLevel.LOW -> Res.string.general_chat_detail_low
    ChatDetailLevel.MEDIUM -> Res.string.general_chat_detail_medium
    ChatDetailLevel.HIGH -> Res.string.general_chat_detail_high
}

/** Each option's label in the current language, looked up once so the kit's plain `(T) -> String` can read it. */
@Composable
private fun <T> labels(options: List<T>, res: (T) -> StringResource): Map<T, String> =
    options.associateWith { localized(res(it)) }

@Composable
private fun LookRows(defaultAppearance: AppearanceMode) {
    val prefs = LocalUiPrefs.current
    val scope = rememberCoroutineScope()
    val appearance by prefs.appearance(defaultAppearance).collectAsState(defaultAppearance)
    val terminalAppearance by prefs.terminalAppearance.collectAsState(TerminalAppearance.FOLLOW_APP)
    val storedTextScale by prefs.textScale.collectAsState(TEXT_SCALE_DEFAULT)
    val liveReplies by prefs.liveReplies.collectAsState(true)
    val language by prefs.language.collectAsState(AppLanguage.SYSTEM)
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

    val themeNames = labels(AppearanceMode.entries, ::themeRes)
    KitOptionsRow(
        localized(Res.string.general_theme),
        AppearanceMode.entries,
        appearance,
        onSelect = { scope.launch { prefs.putAppearance(it) } },
        optionLabel = { themeNames.getValue(it) },
        modifier = Modifier.kitAnchor("theme").testTag("general_theme"),
        optionTag = { optionTag("theme")(themeId(it)) },
    )
    val systemName = localized(Res.string.common_system)
    KitOptionsRow(
        localized(Res.string.general_language),
        AppLanguage.entries,
        language,
        onSelect = { scope.launch { prefs.putLanguage(it) } },
        optionLabel = { if (it == AppLanguage.SYSTEM) systemName else it.endonym },
        modifier = Modifier.kitAnchor("language").testTag("general_language"),
        description = localized(Res.string.general_language_description),
        optionTag = { "general_language_${it.tag ?: "system"}" },
    )
    KitSliderRow(
        localized(Res.string.general_text_size),
        value = textScale,
        // Snapped to whole 5 % steps here too, so the stored value stays clean ("1.2", not "1.2000000476").
        onChange = { draggedTextScale = (it * 20).roundToInt() / 20f },
        range = TEXT_SCALE_MIN..TEXT_SCALE_MAX,
        step = 0.05f,
        valueText = "${(textScale * 100).roundToInt()}%",
        modifier = Modifier.kitAnchor("text-size").testTag("general_text-size"),
        description = localized(Res.string.general_text_size_description),
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
        localized(Res.string.general_live_replies),
        checked = liveReplies,
        onCheckedChange = { on -> scope.launch { prefs.putLiveReplies(on) } },
        modifier = Modifier.kitAnchor("live-replies").testTag("appearance_live_replies_switch"),
        description = localized(Res.string.general_live_replies_description),
    )
    val detailNames = labels(ChatDetailLevel.entries, ::chatDetailRes)
    KitOptionsRow(
        localized(Res.string.general_chat_detail),
        ChatDetailLevel.entries,
        chatDetail,
        onSelect = { scope.launch { prefs.putChatDetailLevel(it) } },
        optionLabel = { detailNames.getValue(it) },
        modifier = Modifier.kitAnchor("chat-detail").testTag("general_chat-detail"),
        description = localized(Res.string.general_chat_detail_description),
        optionTag = { optionTag("chat-detail")(it.label) },
    )
    val terminalNames = labels(TerminalAppearance.entries, ::terminalRes)
    KitOptionsRow(
        localized(Res.string.general_terminal_colours),
        TerminalAppearance.entries,
        terminalAppearance,
        onSelect = { scope.launch { prefs.putTerminalAppearance(it) } },
        optionLabel = { terminalNames.getValue(it) },
        modifier = Modifier.kitAnchor("terminal-colours").testTag("general_terminal-colours"),
        description = localized(Res.string.general_terminal_colours_description),
        optionTag = { optionTag("terminal-colours")(terminalId(it)) },
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
    val sized = if (cache.persistent) Res.string.general_cache_size_kept else Res.string.general_cache_size_session
    KitActionRow(
        localized(Res.string.general_cache),
        button = localized(Res.string.common_clear),
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
            clearing -> localized(Res.string.general_cache_clearing)
            current == null -> localized(Res.string.general_cache_calculating)
            current == 0L -> localized(
                if (cache.persistent) Res.string.general_cache_empty else Res.string.general_cache_empty_session,
            )
            else -> localized(sized, formatBytes(current))
        },
        descriptionTag = "storage_size",
        enabled = !clearing && (current ?: 0L) > 0L,
        buttonTag = "storage_clear",
    )
}
