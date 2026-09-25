package dev.supermux.editor.spike

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp

/** A replacement of document range [from, to) by [insert]. */
data class Edit(val from: Int, val to: Int, val insert: String)

/**
 * The smallest single replacement turning [before] into [after]. When repeated characters make the
 * split ambiguous, the common prefix is capped at the cursor, so the edit lands where the user typed.
 */
fun diffField(before: String, after: String, cursorAfter: Int = after.length): Edit? {
    if (before == after) return null
    val maxPrefix = minOf(before.length, after.length)
    var p = 0
    while (p < maxPrefix && before[p] == after[p]) p++
    val growth = after.length - before.length
    if (growth > 0) p = minOf(p, maxOf(0, cursorAfter - growth))
    var s = 0
    while (s < before.length - p && s < after.length - p &&
        before[before.length - 1 - s] == after[after.length - 1 - s]) s++
    return Edit(p, before.length - s, after.substring(p, after.length - s))
}

/** The slice of the document the hidden field shows: [text] starts at document offset [base]. */
data class FieldWindow(val base: Int, val text: String) {
    fun applyTo(doc: String, e: Edit): String = doc.replaceRange(base + e.from, base + e.to, e.insert)
    companion object {
        fun around(doc: String, cursor: Int, radius: Int): FieldWindow {
            val start = maxOf(0, cursor - radius); val end = minOf(doc.length, cursor + radius)
            return FieldWindow(start, doc.substring(start, end))
        }
    }
}

/**
 * The on-device probe. The top line is the DOCUMENT, rebuilt only from diffed edits. The field below it
 * is what the keyboard edits (visible here on purpose; the real editor hides it). If the document
 * and the field ever disagree, the approach is broken, and the log shows the exact change that did it.
 */
@Composable
fun ImeProbe(radius: Int = 24) {
    var doc by remember { mutableStateOf("Merhaba dünya. teh quick brown fox\nsecond line") }
    var window by remember { mutableStateOf(FieldWindow.around(doc, doc.length, radius)) }
    val field = remember { TextFieldState(window.text, TextRange(window.text.length)) }
    val log = remember { mutableStateListOf<String>() }

    LaunchedEffect(field) {
        var before = field.text.toString()
        snapshotFlow { field.text.toString() to field.selection }.collect { (after, sel) ->
            val e = diffField(before, after, sel.end)
            if (e != null) {
                doc = window.applyTo(doc, e)
                val cursorInDoc = window.base + sel.end
                window = FieldWindow.around(doc, cursorInDoc, radius)
                log.add(0, "$e comp=${field.composition} → field='${after.replace("\n", "⏎")}'")
                // Re-window once the cursor nears an edge, the way the real editor will.
                if (window.text != after) {
                    field.edit { replace(0, length, window.text); selection = TextRange(cursorInDoc - window.base) }
                }
            }
            before = field.text.toString()
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("DOCUMENT: " + doc.replace("\n", "⏎"))
        Text("FIELD (base ${window.base}): ")
        BasicTextField(field, Modifier.padding(vertical = 8.dp))
        LazyColumn { items(log) { Text(it) } }
    }
}
