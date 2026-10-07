package dev.supermux.chat

/**
 * Flatten markdown-ish agent text for read-aloud (mirrors web `plainTextForSpeech`).
 *
 * Pure, and the ONE copy: both apps used to carry an identical function beside their own
 * `MessageTts` object; cluster D3 moved the state machine to `:ui` and the text flattening here,
 * where it is testable without Compose or a synthesiser.
 */
fun plainTextForSpeech(md: String): String {
    if (md.isBlank()) return ""
    var s = md
    s = s.replace(Regex("```[\\s\\S]*?```"), " ")
    s = s.replace(Regex("`([^`]+)`"), "$1")
    s = s.replace(Regex("!\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
    s = s.replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*[-*+]\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
    s = s.replace(Regex("^\\s*>\\s?", RegexOption.MULTILINE), "")
    s = s.replace(Regex("(\\*\\*|__)(.*?)\\1"), "$2")
    s = s.replace(Regex("(\\*|_)(.*?)\\1"), "$2")
    s = s.replace(Regex("~~(.*?)~~"), "$1")
    s = s.replace(Regex("\\n{2,}"), ". ")
    s = s.replace('\n', ' ')
    s = s.replace(Regex("\\s+"), " ").trim()
    s = s.replace(Regex("(?:\\.\\s*){2,}"), ". ").replace(Regex("\\s+"), " ").trim()
    return s
}

/**
 * The picker-option id standing for "no explicit model — whatever the agent defaults to".
 *
 * A sentinel rather than `null`/`""` because it has to survive a round trip through a picker row's
 * id. Every composer/launcher/continue surface on both hosts uses this one constant.
 */
const val DEFAULT_MODEL_ID: String = "__default__"
