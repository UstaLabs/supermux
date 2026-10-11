// The stretch of the composer's text that the current dictation owns. Live transcripts arrive
// CUMULATIVE — each pass re-transcribes everything said so far — so every update replaces that
// stretch rather than appending to it, while whatever the user typed around it is left alone.
package dev.supermux.ui.chat

/**
 * Tracks one recording's text inside a field the user can keep editing.
 *
 * [apply] puts the latest transcript where the previous one was. If the user edited INSIDE the
 * dictated words, those words are theirs now: the segment restarts at the end of the field and
 * only the words spoken after them are written there (cumulative transcripts repeat the earlier
 * words, so they are dropped by count).
 */
class DictationSegment(private var anchor: Int) {
    /** Exactly what this segment last wrote, including the separating space it may have added. */
    private var written = ""

    /** The words of earlier transcripts that the user has taken over by editing them. */
    private var ownedWords = 0

    /** The last transcript applied, so an edit can tell how many words it is taking over. */
    private var lastTranscript = ""

    /** [text] with this segment showing [transcript] (the whole recording's text so far). */
    fun apply(text: String, transcript: String): String {
        val at = locate(text)
        val base: String
        val start: Int
        if (at >= 0) {
            base = text.removeRange(at, at + written.length)
            start = at
        } else {
            // The user edited the dictated words: keep their version, continue after it.
            ownedWords = words(lastTranscript).size
            base = text
            start = text.length
        }
        lastTranscript = transcript
        val fresh = words(transcript).drop(ownedWords).joinToString(" ")
        val sep = if (fresh.isNotEmpty() && start > 0 && !base[start - 1].isWhitespace()) " " else ""
        written = if (fresh.isEmpty()) "" else sep + fresh
        anchor = start
        return base.substring(0, start) + written + base.substring(start)
    }

    /** [text] without this segment's words — the user discarded the recording. */
    fun remove(text: String): String {
        val at = locate(text)
        if (at < 0) return text
        val out = text.removeRange(at, at + written.length)
        written = ""
        return out
    }

    /** Where [written] sits in [text] now: at the anchor if nothing moved it, else its last
     *  occurrence (the user typed before it); -1 when it no longer exists verbatim. With nothing
     *  written yet, the end of the field — after anything the user typed meanwhile. */
    private fun locate(text: String): Int {
        if (written.isEmpty()) return text.length
        if (text.regionMatches(anchor, written, 0, written.length)) return anchor
        return text.lastIndexOf(written)
    }

    private fun words(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
}
