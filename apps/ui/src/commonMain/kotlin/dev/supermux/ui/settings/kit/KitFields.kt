// The Settings kit's fields (#97): the search field at the top of Settings, and a plain field for forms (a
// login's name, a token, a URL, the language-server form). BasicTextField on the wash with a hairline — no
// Material text field.
package dev.supermux.ui.settings.kit

import dev.supermux.ui.platform.LocalPlatform

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.widgets.submitOnEnter

/** "Search settings": the wash, a search glyph, a clear button while there is a query, Escape clears. */
@Composable
fun KitSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "Search settings",
    shortcut: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val c = kitColors()
    val m = kitMetrics()
    val shape = RoundedCornerShape(if (m.phone) 9.dp else 7.dp)
    val size = if (m.phone) 15.sp else 12.5.sp
    val clearSrc = remember { MutableInteractionSource() }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = LocalTextStyle.current.merge(TextStyle(color = c.text, fontSize = size)),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (m.phone) 36.dp else 30.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Escape && query.isNotEmpty()) {
                    onQueryChange("")
                    true
                } else {
                    false
                }
            }
            .testTag("settings_search"),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().clip(shape).background(c.wash).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KitGlyphs.Search, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(hint, color = c.faint, fontSize = size, maxLines = 1)
                    inner()
                }
                when {
                    // No 48 dp box here: the field would jump taller on a phone the moment a query appears.
                    // Compose already widens a small touch target by itself.
                    query.isNotEmpty() -> Icon(
                        KitGlyphs.Close,
                        contentDescription = "Clear search",
                        tint = c.faint,
                        modifier = Modifier
                            .clickable(interactionSource = clearSrc, indication = null, role = Role.Button) {
                                onQueryChange("")
                                // The button goes with the query; the field keeps the focus for the next search.
                                focusRequester.requestFocus()
                            }
                            .kitFocusRing(clearSrc, CircleShape)
                            .padding(2.dp)
                            .size(16.dp)
                            .testTag("settings_search_clear"),
                    )
                    shortcut != null -> Text(shortcut, color = c.faint, fontSize = 11.sp)
                }
            }
        },
    )
}

/**
 * A form field: an optional label above, the wash with a hairline (accent while focused, error tone with an
 * [error]), "Show"/"Hide" for a [secret], Enter (and the keyboard's Done) runs [onSubmit]. The label and the error
 * are the field's own name and error for screen readers; a secret gets the password keyboard while hidden.
 */
@Composable
fun KitTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    secret: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    mono: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onSubmit: (() -> Unit)? = null,
    fieldTag: String? = null,
) {
    val c = kitColors()
    val m = kitMetrics()
    var shown by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(7.dp)
    val size = if (m.phone) 15.sp else 13.sp
    val fieldFocus = remember { FocusRequester() }
    var actionFocused by remember { mutableStateOf(false) }
    // Show/Hide rebuilds the field (below). If the field had focus, or the action had it (a click focuses it on the
    // desktop), the new field gets it back.
    var refocus by remember { mutableStateOf(false) }
    // The caret and the keyboard's composing region live out here, outside that rebuild, so Show/Hide leaves the
    // caret where it was. The same bookkeeping BasicTextField(String) keeps inside itself.
    var fieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val current = fieldValue.copy(text = value)
    SideEffect {
        if (current.selection != fieldValue.selection || current.composition != fieldValue.composition) {
            fieldValue = current
        }
    }
    var lastText by remember(value) { mutableStateOf(value) }
    LaunchedEffect(shown) {
        if (!refocus) return@LaunchedEffect
        refocus = false
        // A frame later: the old field's removal clears focus as the rebuild is applied.
        withFrameNanos { }
        fieldFocus.requestFocus()
    }
    Column(modifier.fillMaxWidth()) {
        // A new field on every Show/Hide: Compose 1.12's text field fixes its Password semantics when it is created
        // (CoreTextFieldSemanticsModifierNode.updateNodeSemantics never stores a changed isPassword), so the same
        // field would go on telling screen readers it is a password after "Show". Focus is put back afterwards.
        key(shown) {
            BasicTextField(
                value = current,
                onValueChange = { next ->
                    fieldValue = next
                    val changed = lastText != next.text
                    lastText = next.text
                    if (changed) onValueChange(next.text)
                },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = if (enabled) c.text else c.faint,
                        fontSize = size,
                        fontFamily = if (mono) MonoFontFamily else null,
                    ),
                ),
                cursorBrush = SolidColor(c.accent),
                // The Password transformation itself, not a look-alike mask: it is also what keeps copy and cut off
                // while the secret is hidden.
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboardFor(keyboardOptions, secret = secret, hidden = secret && !shown, submits = onSubmit != null),
                keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                interactionSource = src,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .then(if (onSubmit != null) Modifier.submitOnEnter(enabled) { onSubmit() } else Modifier)
                    .then(error?.let { message -> Modifier.semantics { this.error(message) } } ?: Modifier)
                    .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier),
                decorationBox = { inner ->
                    Column {
                        // Inside the field, so the label stays its name for screen readers once it is filled, and a
                        // tap on the label focuses it.
                        if (label != null) Text(label, fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(bottom = 6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = if (m.phone) 40.dp else 32.dp)
                                .clip(shape)
                                .background(c.wash)
                                .border(
                                    1.dp,
                                    when {
                                        error != null -> c.danger
                                        focused -> c.accent
                                        else -> c.line
                                    },
                                    shape,
                                )
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (value.isEmpty() && placeholder != null) Text(placeholder, color = c.faint, fontSize = size, maxLines = 1)
                                inner()
                            }
                            if (secret) {
                                TextActionBase(
                                    if (shown) "Hide" else "Show",
                                    onClick = {
                                        refocus = focused || actionFocused
                                        shown = !shown
                                    },
                                    modifier = Modifier.onFocusChanged { actionFocused = it.isFocused },
                                    // In the field: a 48 dp target would make the field taller.
                                    touchTarget = false,
                                )
                            }
                        }
                    }
                },
            )
        }
        if (error != null) Text(error, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * The keyboard for a field: Done when it submits, unless [given] names an action; a secret never autocorrects or
 * capitalises (a token is not a sentence, shown or not) and gets the password keyboard while [hidden].
 */
internal fun keyboardFor(given: KeyboardOptions, secret: Boolean, hidden: Boolean, submits: Boolean): KeyboardOptions {
    val submit = if (submits) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default
    val guard = if (secret) {
        KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Unspecified,
        )
    } else {
        KeyboardOptions.Default
    }
    // merge: the argument's set values win.
    return submit.merge(given).merge(guard)
}

/**
 * A command to copy ("claude setup-token"), in the mono face on the wash with Copy at its end. Only in a sheet: a page
 * shows no raw commands (spec §11's carve-out).
 */
@Composable
fun KitCopyCommand(command: String, modifier: Modifier = Modifier) {
    val c = kitColors()
    val platform = LocalPlatform.current
    var copied by remember(command) { mutableStateOf(false) }
    val shape = RoundedCornerShape(7.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.wash)
            .border(1.dp, c.line, shape)
            .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SelectionContainer(Modifier.weight(1f)) { Text(command, fontFamily = MonoFontFamily, fontSize = 12.5.sp, color = c.text) }
        TextActionBase(
            if (copied) "Copied" else "Copy",
            onClick = {
                platform.copyToClipboard(command)
                copied = true
            },
            touchTarget = false,
        )
    }
}
