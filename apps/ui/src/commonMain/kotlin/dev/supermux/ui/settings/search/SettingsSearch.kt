// Search over Settings (#97 spec §2.5, §10.9, §11): one declared entry per searchable row, so the index is data a
// test checks against the pages (SettingsSearchAnchorsTest, Task 18) instead of text scraped off the screen. Each
// page task adds its rows here, in page order.
package dev.supermux.ui.settings.search

import androidx.compose.runtime.Composable
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.currentKeyOs
import dev.supermux.ui.keys.localizedTitle
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.settings.MachineSub
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.pages.shortcutAnchor
import dev.supermux.ui.settings.settingsTitle

/**
 * What this device shows, for rows that exist only on some platforms. [hardwareKeyboard]: the Keyboard shortcuts
 * page is listed (`LocalHardwareKeyboard`); [mediaCache]: General has its cached images and files row.
 */
data class SettingsSearchContext(
    val caps: Caps,
    val phone: Boolean,
    val liveTranscript: Boolean,
    val hardwareKeyboard: Boolean = true,
    val mediaCache: Boolean = true,
)

/**
 * One searchable row; [anchor] null is the page itself. [keywords] are other words people type for it. [sub] set: the
 * result is that sub-page of the section, opened at its top ([anchor] is then only the result's id).
 */
data class SettingsSearchEntry(
    val section: SettingsSection,
    val anchor: String?,
    val label: String,
    val keywords: String = "",
    val shownWhen: (SettingsSearchContext) -> Boolean = { true },
    val sub: String? = null,
    /** This row's label and extra keywords in the app's language (`settings_search.xml`); null keeps the English. */
    val text: SearchText? = null,
)

/** A row's catalog keys: what a result shows in the app's language, and more words it is found by there. */
data class SearchText(val label: StringKey, val keywords: StringKey)

data class SettingsSearchGroup(val section: SettingsSection, val hits: List<SettingsSearchEntry>)

/** A page's name in search and in the rail: Privacy & labs is "Privacy" on a device without labs. */
fun settingsPageTitle(section: SettingsSection, ctx: SettingsSearchContext): String =
    settingsTitle(SettingsPlace(section), labs = ctx.caps.assistantOverlay)

/** An entry in the app's language (#123): what a result shows, and more words it is found by. */
data class LocalizedSearchEntry(val label: String, val keywords: String = "")

/** The catalog key name a row's [SettingsSearchEntry.text] should carry: `search_‹section›_‹anchor›` (+ `_kw`). */
fun searchKey(entry: SettingsSearchEntry): String =
    "search_${entry.section.name.lowercase()}_${(entry.anchor ?: "page").replace('-', '_')}"

/**
 * Every entry of [entries] in the app's language: a page entry is its page's name ([pageTitles]); a row's label and
 * extra keywords are its [SettingsSearchEntry.text]; a shortcut's is its command's title. An entry with no text (a
 * provider's name) keeps its English.
 */
@Composable
fun localizedSearchEntries(
    entries: List<SettingsSearchEntry>,
    pageTitles: Map<SettingsSection, String>,
): Map<SettingsSearchEntry, LocalizedSearchEntry> {
    return entries.associateWith { e ->
        LocalizedSearchEntry(
            label = when {
                e.anchor == null -> pageTitles[e.section] ?: e.label
                // A shortcut's row is its command: its title in the app's language.
                e.section == SettingsSection.KeyboardShortcuts ->
                    AppCommands.all.firstOrNull { shortcutAnchor(it) == e.anchor }?.let { localizedTitle(it) } ?: e.label
                else -> e.text?.let { localized(it.label) } ?: e.label
            },
            keywords = e.text?.let { localized(it.keywords) }.orEmpty(),
        )
    }
}

/**
 * Every word of [query] must appear, in any case, in an entry's label, keywords or page title — in English or, from
 * [translations], in the app's language: a Turkish reader can type either.
 */
fun searchSettings(
    query: String,
    ctx: SettingsSearchContext,
    entries: List<SettingsSearchEntry> = SettingsSearchIndex,
    limit: Int = 40,
    translations: Map<SettingsSearchEntry, LocalizedSearchEntry> = emptyMap(),
): List<SettingsSearchGroup> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries.asSequence()
        .filter { it.shownWhen(ctx) }
        .filter { e ->
            val local = translations[e]
            val hay = "${e.label} ${e.keywords} ${settingsPageTitle(e.section, ctx)} ${local?.label.orEmpty()} ${local?.keywords.orEmpty()}"
                .lowercase()
            words.all { it in hay }
        }
        .take(limit)
        .toList()
        .groupBy { it.section }
        .map { (section, hits) -> SettingsSearchGroup(section, hits) }
}

private fun page(section: SettingsSection, keywords: String = "") = SettingsSearchEntry(section, null, section.label, keywords)

/** The index. Page entries first in each page's block, then its rows in page order (page tasks append). */
val SettingsSearchIndex: List<SettingsSearchEntry> = listOf(
    page(SettingsSection.General, "appearance preferences"),
    SettingsSearchEntry(
        SettingsSection.General, "theme", "Theme", "dark light mode appearance colour",
        text = SearchText(Str.search_general_theme, Str.search_general_theme_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "language", "Language", "locale translation english turkish türkçe dil",
        text = SearchText(Str.search_general_language, Str.search_general_language_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "text-size", "Text size", "font zoom scale bigger smaller",
        text = SearchText(Str.search_general_text_size, Str.search_general_text_size_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "live-replies", "Show replies as they're written", "stream streaming",
        text = SearchText(Str.search_general_live_replies, Str.search_general_live_replies_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "chat-detail", "Chat detail", "tools verbose transcript",
        text = SearchText(Str.search_general_chat_detail, Str.search_general_chat_detail_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "terminal-colours", "Terminal colours", "terminal palette color",
        text = SearchText(Str.search_general_terminal_colours, Str.search_general_terminal_colours_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "app-version", "App version", "update upgrade release check",
        text = SearchText(Str.search_general_app_version, Str.search_general_app_version_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.General, "cache", "Cached images and files", "storage cache disk media clear",
        shownWhen = { it.mediaCache },
        text = SearchText(Str.search_general_cache, Str.search_general_cache_kw),
    ),
    page(SettingsSection.KeyboardShortcuts, "keys hotkeys keybindings keymap").copy(shownWhen = { it.hardwareKeyboard }),
    // Generated from the commands, so the list never goes stale (#96 §6); a command without keys here has no row.
    *AppCommands.all.map { cmd ->
        SettingsSearchEntry(
            SettingsSection.KeyboardShortcuts, shortcutAnchor(cmd), cmd.title, "shortcut keys hotkey",
            shownWhen = { it.hardwareKeyboard && DefaultKeymap.chordsFor(cmd, currentKeyOs).isNotEmpty() },
        )
    }.toTypedArray(),
    page(SettingsSection.Providers, "agents accounts logins sign in"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-claude", "Claude Code", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-codex", "Codex", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-opencode", "OpenCode", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-cursor", "Cursor", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-grok", "Grok", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-antigravity", "Antigravity", "agent sign in login account install"),
    SettingsSearchEntry(
        SettingsSection.Providers, "auto-switch", "Switch accounts at a limit", "auto switch limit usage account",
        text = SearchText(Str.search_providers_auto_switch, Str.search_providers_auto_switch_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Providers, "limit-resume", "Resume after a limit reset", "resume wait limit reset usage",
        text = SearchText(Str.search_providers_limit_resume, Str.search_providers_limit_resume_kw),
    ),
    page(SettingsSection.Voice, "dictation speech"),
    SettingsSearchEntry(
        SettingsSection.Voice, "speech-engine", "Speech recognition", "dictation transcription stt engine whisper",
        text = SearchText(Str.search_voice_speech_engine, Str.search_voice_speech_engine_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "languages", "Languages", "language detect",
        text = SearchText(Str.search_voice_languages, Str.search_voice_languages_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "glossary", "Words to spell your way", "glossary dictionary terms spelling",
        text = SearchText(Str.search_voice_glossary, Str.search_voice_glossary_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "read-aloud", "Read replies aloud", "tts speak voice preview",
        text = SearchText(Str.search_voice_read_aloud, Str.search_voice_read_aloud_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "tidy-up-engine", "Tidy-up engine", "cleanup",
        text = SearchText(Str.search_voice_tidy_up_engine, Str.search_voice_tidy_up_engine_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "tidy-up-model", "Tidy-up model", "cleanup model",
        text = SearchText(Str.search_voice_tidy_up_model, Str.search_voice_tidy_up_model_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Voice, "dictation", "Turn speech into text", "dictation on device offline",
        shownWhen = { it.liveTranscript },
        text = SearchText(Str.search_voice_dictation, Str.search_voice_dictation_kw),
    ),
    page(SettingsSection.Coding, "editor git"),
    SettingsSearchEntry(
        SettingsSection.Coding, "github", "GitHub & GitLab", "git gitlab github account token clone push pr",
        text = SearchText(Str.search_coding_github, Str.search_coding_github_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "wrap", "Wrap long lines", "editor soft wrap",
        text = SearchText(Str.search_coding_wrap, Str.search_coding_wrap_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "editor-font", "Editor font size", "zoom",
        text = SearchText(Str.search_coding_editor_font, Str.search_coding_editor_font_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "terminal-font", "Terminal font size", "zoom pinch",
        text = SearchText(Str.search_coding_terminal_font, Str.search_coding_terminal_font_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "side-by-side", "Side-by-side diffs", "diff changes split",
        text = SearchText(Str.search_coding_side_by_side, Str.search_coding_side_by_side_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "accessory-bar", "Keys above the keyboard", "accessory bar",
        shownWhen = { it.phone },
        text = SearchText(Str.search_coding_accessory_bar, Str.search_coding_accessory_bar_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "code-intelligence", "Code intelligence", "language server lsp autocomplete",
        text = SearchText(Str.search_coding_code_intelligence, Str.search_coding_code_intelligence_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Coding, "add-language-server", "Add your own language server", "custom lsp",
        text = SearchText(Str.search_coding_add_language_server, Str.search_coding_add_language_server_kw),
    ),
    page(SettingsSection.Memory, "curator"),
    SettingsSearchEntry(
        SettingsSection.Memory, "memory-nightly", "Tidy up memory every night", "curator nightly digest memory",
        text = SearchText(Str.search_memory_memory_nightly, Str.search_memory_memory_nightly_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Memory, "memory-time", "At", "curator time schedule hour",
        text = SearchText(Str.search_memory_memory_time, Str.search_memory_memory_time_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Memory, "memory-next", "Next tidy-up", "run now curator",
        text = SearchText(Str.search_memory_memory_next, Str.search_memory_memory_next_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Memory, "memory-agent", "Done by", "curator agent model effort",
        text = SearchText(Str.search_memory_memory_agent, Str.search_memory_memory_agent_kw),
    ),
    page(SettingsSection.PrivacyLabs, "telemetry experimental"),
    SettingsSearchEntry(
        SettingsSection.PrivacyLabs, "telemetry", "Send anonymous usage statistics", "telemetry analytics privacy statistics",
        text = SearchText(Str.search_privacylabs_telemetry, Str.search_privacylabs_telemetry_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.PrivacyLabs, "assistant-overlay", "Assistant overlay", "experimental bubble float head talk",
        shownWhen = { it.caps.assistantOverlay },
        text = SearchText(Str.search_privacylabs_assistant_overlay, Str.search_privacylabs_assistant_overlay_kw),
    ),
    // The machine page (Step 2): the machine Settings is about. While you're away is this computer's, on desktop.
    SettingsSearchEntry(
        SettingsSection.Machine, "restart", "Restart", "reboot",
        text = SearchText(Str.search_machine_restart, Str.search_machine_restart_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "updates", "Update supermux", "version check for updates",
        text = SearchText(Str.search_machine_updates, Str.search_machine_updates_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "away", "While you're away", "hosting background", shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_away, Str.search_machine_away_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "keep-running", "Keep running in the background", "hosting startup service",
        shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_keep_running, Str.search_machine_keep_running_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "keep-awake", "Keep this computer awake", "sleep battery lid",
        shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_keep_awake, Str.search_machine_keep_awake_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "reach-anywhere", "Reach it from anywhere", "remote relay",
        shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_reach_anywhere, Str.search_machine_reach_anywhere_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "address", "On this network", "address url ip", shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_address, Str.search_machine_address_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "log", "Recent log", "log show", shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_log, Str.search_machine_log_kw),
    ),
    // Its row shows only while not hosting: the anchor is the whole section, so it lands either way.
    SettingsSearchEntry(
        SettingsSection.Machine, "host-on", "Host on this computer", "hosting start", shownWhen = { it.caps.localBroker },
        text = SearchText(Str.search_machine_host_on, Str.search_machine_host_on_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "devices", "Devices", "paired revoke phone computer",
        text = SearchText(Str.search_machine_devices, Str.search_machine_devices_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "pair", "Pair a phone or computer", "qr code add device link",
        text = SearchText(Str.search_machine_pair, Str.search_machine_pair_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "links", "Shared links", "proxy port public private",
        text = SearchText(Str.search_machine_links, Str.search_machine_links_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "share", "Share a port…", "expose proxy link",
        text = SearchText(Str.search_machine_share, Str.search_machine_share_kw),
    ),
    // The machine page's Worktrees sub-page (#97 Step 2).
    SettingsSearchEntry(
        SettingsSection.Machine, "worktrees", "Chat worktrees", "disk space delete clean folders",
        sub = MachineSub.WORKTREES,
        text = SearchText(Str.search_machine_worktrees, Str.search_machine_worktrees_kw),
    ),
    SettingsSearchEntry(
        SettingsSection.Machine, "limits", "Limits", "usage quota reset",
        text = SearchText(Str.search_machine_limits, Str.search_machine_limits_kw),
    ),
)
