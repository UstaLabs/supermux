package dev.supermux.ui

/**
 * Agents write markdown; a session-list row shows plain text. Mail and Messages both strip
 * formatting from their preview line, and without this the sidebar rows read as
 * ``**Committed** on `main` `` noise instead of a sentence.
 *
 * Deliberately conservative and cheap — it runs for every visible row on every recomposition:
 * the input is capped to a preview's worth of text first (which also bounds the regex work), each
 * rule is gated on a substring probe, and the patterns are compiled once at class-init.
 *
 * Conservative matters as much as cheap here. The false positives are what a naive
 * strip-all-punctuation pass gets wrong on real messages: `shipped v1. 2 more` is not an ordered
 * list, `2 * 3 = 6` is not emphasis, and `last_read_at` is not italics — a list/quote/heading
 * marker only counts at a line start, and `_` is never treated as emphasis at all.
 */
fun sessionPreviewPlainText(raw: String): String {
    // A two-line preview never needs more than this.
    var s = if (raw.length > PREVIEW_INPUT_CAP) raw.substring(0, PREVIEW_INPUT_CAP) else raw
    if (s.contains("```")) s = FENCE_RE.replace(s, " ")
    if (s.contains("](")) s = LINK_RE.replace(s, "$1")
    // Block markers only ever sit at a line start, so a single-line message that merely *contains*
    // a `-` or a `.` must not pay for this pass — nor risk it.
    if (s.contains("\n") || s.firstOrNull()?.let { it in BLOCK_MARKER_STARTS } == true) {
        s = BLOCK_MARKER_RE.replace(s, "")
    }
    if (s.contains("*")) {
        s = STRONG_RE.replace(s, "$1")
        s = EMPHASIS_RE.replace(s, "$1")
    }
    if (s.contains("`")) s = CODE_RE.replace(s, "$1")
    return WHITESPACE_RE.replace(s, " ").trim()
}

/** Cap on the raw text considered; the rendered row is one or two lines regardless. */
private const val PREVIEW_INPUT_CAP = 300

/** Fence markers go, the code between them stays — it is usually the informative part. */
private val FENCE_RE = Regex("```[a-zA-Z0-9_+-]*")

/** `[label](url)` → `label`: the URL is noise in a one-line preview. */
private val LINK_RE = Regex("""\[([^\]]*)\]\([^)]*\)""")

/** First characters that could begin a block marker — the gate for the line-start pass. */
private const val BLOCK_MARKER_STARTS = "#>-*+0123456789"

/** Heading / quote / bullet / ordered markers, at a line start ONLY. */
private val BLOCK_MARKER_RE =
    Regex("""^[ \t]*(#{1,6}[ \t]+|>[ \t]?|[-*+][ \t]+|\d+\.[ \t]+)""", RegexOption.MULTILINE)

private val STRONG_RE = Regex("""\*\*([^*]+)\*\*""")

/** Single `*emphasis*` only — never a bare `*` used as a bullet, a literal, or multiplication. */
private val EMPHASIS_RE = Regex("""(?<![*\w])\*([^*\n]+)\*(?!\*)""")

private val CODE_RE = Regex("`+([^`\n]+)`+")

private val WHITESPACE_RE = Regex("""\s+""")
