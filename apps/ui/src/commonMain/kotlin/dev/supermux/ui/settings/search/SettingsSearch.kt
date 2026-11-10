// Search over Settings (#97 spec §2.5, §10.9, §11): one declared entry per searchable row, so the index is data a
// test checks against the pages (SettingsSearchAnchorsTest, Task 18) instead of text scraped off the screen. Each
// page task adds its rows here, in page order.
package dev.supermux.ui.settings.search

import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.settingsTitle

/** What this device shows, for rows that exist only on some platforms. */
data class SettingsSearchContext(val caps: Caps, val phone: Boolean, val liveTranscript: Boolean)

/** One searchable row; [anchor] null is the page itself. [keywords] are other words people type for it. */
data class SettingsSearchEntry(
    val section: SettingsSection,
    val anchor: String?,
    val label: String,
    val keywords: String = "",
    val shownWhen: (SettingsSearchContext) -> Boolean = { true },
)

data class SettingsSearchGroup(val section: SettingsSection, val hits: List<SettingsSearchEntry>)

/** A page's name in search and in the rail: Privacy & labs is "Privacy" on a device without labs. */
fun settingsPageTitle(section: SettingsSection, ctx: SettingsSearchContext): String =
    settingsTitle(SettingsPlace(section), labs = ctx.caps.assistantOverlay)

/** Every word of [query] must appear, in any case, in an entry's label, keywords or page title. */
fun searchSettings(
    query: String,
    ctx: SettingsSearchContext,
    entries: List<SettingsSearchEntry> = SettingsSearchIndex,
    limit: Int = 40,
): List<SettingsSearchGroup> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries.asSequence()
        .filter { it.shownWhen(ctx) }
        .filter { e ->
            val hay = "${e.label} ${e.keywords} ${settingsPageTitle(e.section, ctx)}".lowercase()
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
    SettingsSearchEntry(SettingsSection.General, "theme", "Theme", "dark light mode appearance colour"),
    SettingsSearchEntry(SettingsSection.General, "text-size", "Text size", "font zoom scale bigger smaller"),
    SettingsSearchEntry(SettingsSection.General, "live-replies", "Show replies as they're written", "stream streaming"),
    SettingsSearchEntry(SettingsSection.General, "chat-detail", "Chat detail", "tools verbose transcript"),
    SettingsSearchEntry(SettingsSection.General, "terminal-colours", "Terminal colours", "terminal palette color"),
    SettingsSearchEntry(SettingsSection.General, "app-version", "App version", "update upgrade release check"),
    SettingsSearchEntry(SettingsSection.General, "cache", "Cached images and files", "storage cache disk media clear"),
    page(SettingsSection.Providers, "agents accounts logins sign in"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-claude", "Claude Code", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-codex", "Codex", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-opencode", "OpenCode", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-cursor", "Cursor", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-grok", "Grok", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "provider-antigravity", "Antigravity", "agent sign in login account install"),
    SettingsSearchEntry(SettingsSection.Providers, "auto-switch", "Switch accounts at a limit", "auto switch limit usage account"),
    SettingsSearchEntry(SettingsSection.Providers, "limit-resume", "Resume after a limit reset", "resume wait limit reset usage"),
    page(SettingsSection.Voice, "dictation speech"),
    SettingsSearchEntry(SettingsSection.Voice, "speech-engine", "Speech recognition", "dictation transcription stt engine whisper"),
    SettingsSearchEntry(SettingsSection.Voice, "languages", "Languages", "language detect"),
    SettingsSearchEntry(SettingsSection.Voice, "glossary", "Words to spell your way", "glossary dictionary terms spelling"),
    SettingsSearchEntry(SettingsSection.Voice, "read-aloud", "Read replies aloud", "tts speak voice preview"),
    SettingsSearchEntry(SettingsSection.Voice, "tidy-up-engine", "Tidy-up engine", "cleanup"),
    SettingsSearchEntry(SettingsSection.Voice, "tidy-up-model", "Tidy-up model", "cleanup model"),
    SettingsSearchEntry(
        SettingsSection.Voice, "dictation", "Turn speech into text", "dictation on device offline",
        shownWhen = { it.liveTranscript },
    ),
    page(SettingsSection.Coding, "editor git"),
    SettingsSearchEntry(SettingsSection.Coding, "github", "GitHub & GitLab", "git gitlab github account token clone push pr"),
    SettingsSearchEntry(SettingsSection.Coding, "wrap", "Wrap long lines", "editor soft wrap"),
    SettingsSearchEntry(SettingsSection.Coding, "editor-font", "Editor font size", "zoom"),
    SettingsSearchEntry(SettingsSection.Coding, "terminal-font", "Terminal font size", "zoom pinch"),
    SettingsSearchEntry(SettingsSection.Coding, "side-by-side", "Side-by-side diffs", "diff changes split"),
    SettingsSearchEntry(
        SettingsSection.Coding, "accessory-bar", "Keys above the keyboard", "accessory bar",
        shownWhen = { it.phone },
    ),
    SettingsSearchEntry(SettingsSection.Coding, "code-intelligence", "Code intelligence", "language server lsp autocomplete"),
    SettingsSearchEntry(SettingsSection.Coding, "add-language-server", "Add your own language server", "custom lsp"),
    page(SettingsSection.Memory, "curator"),
    SettingsSearchEntry(SettingsSection.Memory, "memory-nightly", "Tidy up memory every night", "curator nightly digest memory"),
    SettingsSearchEntry(SettingsSection.Memory, "memory-time", "At", "curator time schedule hour"),
    SettingsSearchEntry(SettingsSection.Memory, "memory-next", "Next tidy-up", "run now curator"),
    SettingsSearchEntry(SettingsSection.Memory, "memory-agent", "Done by", "curator agent model effort"),
    page(SettingsSection.PrivacyLabs, "telemetry experimental"),
    SettingsSearchEntry(SettingsSection.PrivacyLabs, "telemetry", "Send anonymous usage statistics", "telemetry analytics privacy statistics"),
    SettingsSearchEntry(
        SettingsSection.PrivacyLabs, "assistant-overlay", "Assistant overlay", "experimental bubble float head talk",
        shownWhen = { it.caps.assistantOverlay },
    ),
)
