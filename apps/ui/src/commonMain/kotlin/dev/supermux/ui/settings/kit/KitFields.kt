// The Settings kit's fields (#97): the search field at the top of Settings, and a plain field for forms (a
// login's name, a token, a URL, the language-server form). BasicTextField on the wash with a hairline — no
// Material text field.
package dev.supermux.ui.settings.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = size),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier
            .fillMaxWidth()
            .height(if (m.phone) 36.dp else 30.dp)
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
                Modifier.fillMaxSize().clip(shape).background(c.wash).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KitGlyphs.Search, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(hint, color = c.faint, fontSize = size, maxLines = 1)
                    inner()
                }
                when {
                    query.isNotEmpty() -> Icon(
                        KitGlyphs.Close,
                        contentDescription = "Clear search",
                        tint = c.faint,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                            ) { onQueryChange("") }
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
 * [error]), "Show"/"Hide" for a [secret], Enter (and the keyboard's Done) runs [onSubmit].
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
    Column(modifier.fillMaxWidth()) {
        if (label != null) Text(label, fontSize = 12.sp, color = c.dim, modifier = Modifier.padding(bottom = 6.dp))
        // A new field on every Show/Hide: Compose 1.12's text field fixes its Password semantics when it is created
        // (CoreTextFieldSemanticsModifierNode.updateNodeSemantics never stores a changed isPassword), so the same
        // field would go on telling screen readers it is a password after "Show". The cost: Show/Hide drops focus.
        key(shown) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = if (enabled) c.text else c.faint,
                    fontSize = size,
                    fontFamily = if (mono) MonoFontFamily else null,
                ),
                cursorBrush = SolidColor(c.accent),
                // The Password transformation itself, not a look-alike mask: it is also what keeps copy and cut off
                // while the secret is hidden.
                visualTransformation = if (secret && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboardOptions,
                keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
                interactionSource = src,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (m.phone) 40.dp else 32.dp)
                    .then(if (onSubmit != null) Modifier.submitOnEnter(enabled) { onSubmit() } else Modifier)
                    .then(if (fieldTag != null) Modifier.testTag(fieldTag) else Modifier),
                decorationBox = { inner ->
                    Row(
                        Modifier
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
                        if (secret) KitTextAction(if (shown) "Hide" else "Show", { shown = !shown })
                    }
                },
            )
        }
        if (error != null) Text(error, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 4.dp))
    }
}
