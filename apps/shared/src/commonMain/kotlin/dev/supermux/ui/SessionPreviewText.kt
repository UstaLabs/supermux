package dev.supermux.ui

import dev.supermux.net.isOpenableLink

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

/**
 * A whole message as plain text to read in full (the assistant overlay's bubble): its markdown goes as in
 * [sessionPreviewPlainText], but nothing is cut and its lines stay, bullets as "•" and numbered lines as
 * they are, so a long answer still reads as paragraphs and lists.
 */
fun messagePlainText(raw: String): String = messagePlainLinked(raw).text

/** A link kept in plain text: the characters from [start] until [end] open [url]. */
data class PlainLink(val start: Int, val end: Int, val url: String)

/** A message as plain text ([messagePlainText]) and its `[label](url)` links. Bare URLs stay in the text as they are. */
data class PlainMessage(val text: String, val links: List<PlainLink>)

/**
 * [messagePlainText], keeping where its links go (the assistant overlay, #62): each `[label](url)` leaves its
 * label, which still opens its url. The labels are marked while the markdown goes, and the marks are read off
 * at the end, so every rule above applies to a label as to any other text.
 */
fun messagePlainLinked(raw: String): PlainMessage {
    val urls = mutableListOf<String>()
    var s = raw.replace(LINK_OPEN.toString(), "").replace(LINK_CLOSE.toString(), "")
    if (s.contains("```")) s = FENCE_RE.replace(s, " ")
    if (s.contains("](")) {
        s = LINK_RE.replace(s) { m ->
            val label = m.groupValues[1]
            if (label.isBlank() || !isOpenableLink(m.groupValues[2])) label else "$LINK_OPEN$label$LINK_CLOSE".also { urls += m.groupValues[2] }
        }
    }
    s = HEADING_QUOTE_RE.replace(s, "")
    s = BULLET_RE.replace(s, "• ")
    if (s.contains("*")) {
        s = STRONG_RE.replace(s, "$1")
        s = EMPHASIS_RE.replace(s, "$1")
    }
    if (s.contains("`")) s = CODE_RE.replace(s, "$1")
    s = SPACES_RE.replace(s, " ").lines().joinToString("\n") { it.trim() }
    s = BLANK_LINES_RE.replace(s, "\n\n").trim()
    // The marks come out; where each label now sits is its link.
    val text = StringBuilder(s.length)
    val links = mutableListOf<PlainLink>()
    var start = -1
    for (c in s) {
        when (c) {
            LINK_OPEN -> start = text.length
            LINK_CLOSE -> {
                val url = urls.getOrNull(links.size)
                if (start >= 0 && url != null && text.length > start) links += PlainLink(start, text.length, url)
                start = -1
            }
            else -> text.append(c)
        }
    }
    return PlainMessage(text.toString(), links)
}

/** Where a link's label starts and ends while [messagePlainLinked] works (private-use code points). */
private const val LINK_OPEN = '\uE000'
private const val LINK_CLOSE = '\uE001'

/** Heading and quote markers at a line start: a message read in full keeps its bullets and numbers. */
private val HEADING_QUOTE_RE = Regex("""^[ \t]*(#{1,6}[ \t]+|>[ \t]?)""", RegexOption.MULTILINE)

private val BULLET_RE = Regex("""^[ \t]*[-*+][ \t]+""", RegexOption.MULTILINE)

private val SPACES_RE = Regex("""[ \t]+""")

/** More than one blank line in a row reads as one. */
private val BLANK_LINES_RE = Regex("""\n{3,}""")

/** Fence markers go, the code between them stays — it is usually the informative part. */
private val FENCE_RE = Regex("```[a-zA-Z0-9_+-]*")

/** `[label](url)` → `label`: the URL is noise in a one-line preview. */
private val LINK_RE = Regex("""\[([^\]]*)\]\(([^)]*)\)""")

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
