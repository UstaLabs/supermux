// The stretch of the composer's text that the current dictation owns: everything from where the
// dictation started to the end of the field. Every live pass answers with the COMPLETE dictated
// text (spoken edits applied, the user's own edits kept), so each update replaces that whole
// stretch; what was in the field before the dictation started is never touched.
package dev.supermux.ui.chat

class DictationSegment(private val anchor: Int) {

    /** What the dictated stretch of [text] holds now — sent as the current text of the next pass. */
    fun region(text: String): String = text.substring(anchor.coerceAtMost(text.length)).trimStart()

    /** [text] with the dictated stretch replaced by [transcript]; a space separates it from text
     *  typed before the dictation. */
    fun apply(text: String, transcript: String): String {
        val before = text.substring(0, anchor.coerceAtMost(text.length))
        val t = transcript.trim()
        val sep = if (t.isNotEmpty() && before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        return before + sep + t
    }

    /** [text] without the dictated stretch — the user discarded the recording. */
    fun remove(text: String): String = text.substring(0, anchor.coerceAtMost(text.length))
}
