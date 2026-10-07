// The in-place name field of the Files tree (see InlineEdit.kt): what a row shows instead of its
// name while it is being renamed, and what a new entry's temporary row holds.
package dev.supermux.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

/**
 * A single-line name field that lives in a tree row, VS Code's rules:
 *  - Enter commits (an invalid name keeps the field open with its message; a blank one cancels);
 *    Esc cancels; focus leaving the field commits a valid, changed name and cancels anything else.
 *  - The name is validated as you type ([validateNewName] against [siblings]); the message shows in
 *    a small line under the field. While [onSubmit] runs the field is read-only with a spinner; a
 *    refusal from the broker shows in the same line and keeps the field open.
 *
 * [current] is the entry's own name on a rename (null for a new entry). [onDone] / [onCancel] get
 * whether the TREE should take focus back — yes after Enter / Esc, no after a blur (the user put
 * focus somewhere else on purpose).
 */
@Composable
fun InlineNameField(
    initial: String,
    current: String?,
    folder: Boolean,
    siblings: List<String>,
    placeholder: String?,
    onSubmit: suspend (String) -> Result<Unit>,
    onDone: (name: String, refocusTree: Boolean) -> Unit,
    onCancel: (refocusTree: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, renameSelectionEnd(initial, folder)))) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Once committed or cancelled, the field ignores everything (the blur its own removal causes).
    var finished by remember { mutableStateOf(false) }
    // onFocusChanged reports "not focused" once before the first focus: that is not a blur.
    var hadFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = field.text
    val invalid = validateNewName(name, siblings, current)
    val shown = serverError ?: invalid?.takeIf { it.isNotEmpty() }

    fun cancel(refocus: Boolean) {
        if (finished || busy) return
        finished = true
        onCancel(refocus)
    }
    fun commit(refocus: Boolean) {
        if (finished || busy) return
        when {
            invalid == "" && (current == null || !refocus) -> cancel(refocus) // blank: nothing to create
            invalid != null -> if (!refocus) cancel(false) // Enter keeps it open to fix; a blur drops it
            name == current -> cancel(refocus) // a rename that kept its name
            else -> {
                busy = true
                scope.launch {
                    val r = onSubmit(name)
                    busy = false
                    r.onSuccess { finished = true; onDone(name, refocus) }
                        .onFailure { serverError = fsOpErrorMessage(it) }
                }
            }
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surface, RoundedCornerShape(2.dp))
                .border(1.dp, if (shown != null) cs.error else cs.primary, RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val style = TextStyle(color = cs.onSurface, fontFamily = MonoFontFamily, fontSize = 13.sp)
                if (name.isEmpty() && placeholder != null) {
                    Text(placeholder, style = style.copy(color = cs.onSurfaceVariant), maxLines = 1)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        if (it.text != field.text) serverError = null
                        field = it
                    },
                    readOnly = busy,
                    singleLine = true,
                    textStyle = style,
                    cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commit(refocus = true) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { st ->
                            if (st.isFocused) {
                                hadFocus = true
                            } else if (hadFocus && !finished && !busy) {
                                if (commitsOnBlur(name, invalid, current)) commit(refocus = false) else cancel(refocus = false)
                            }
                        }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { commit(refocus = true); true }
                                Key.Escape -> { cancel(refocus = true); true }
                                else -> false
                            }
                        }
                        .testTag("tree_inline_field"),
                )
            }
            if (busy) {
                CircularProgressIndicator(
                    Modifier.padding(start = 4.dp).size(10.dp).testTag("tree_inline_busy"),
                    strokeWidth = 1.5.dp,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        if (shown != null) {
            Text(
                shown,
                color = cs.onErrorContainer,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(cs.errorContainer, RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("tree_inline_error"),
            )
        }
    }
}
