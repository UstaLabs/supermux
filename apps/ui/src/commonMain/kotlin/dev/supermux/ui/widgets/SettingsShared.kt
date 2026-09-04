// The union of both apps' former `settings/SettingsShared.kt`. Desktop's typography-token version
// is the base (it was itself a port of Android's, with the sp literals replaced by the type scale —
// and under the touch scale those tokens resolve to exactly the sizes Android hard-coded, so the
// phone is unchanged). Android's mobile-IME KeyboardOptions and desktop's Enter-to-submit are both
// kept: they are additive.
//
// `openUrl`/`copyToClipboard` used to live here on Android; they moved to `LocalPlatform` in A4 and
// are NOT re-exported.
package dev.supermux.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space

/** Enter-to-submit: fire [submit] (and consume the event) on Enter/NumPad-Enter when [enabled]. */
fun Modifier.submitOnEnter(enabled: Boolean, submit: () -> Unit): Modifier =
    onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown &&
            (e.key == Key.Enter || e.key == Key.NumPadEnter) &&
            enabled
        ) {
            submit()
            true
        } else {
            false
        }
    }

/** Standard OutlinedTextField colours used across the settings forms. */
@Composable
fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/**
 * A monospaced secret field (password transformation, autocorrect + autocaps off — an API key is
 * not a sentence). Optional Enter-to-submit for the pointer platforms.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    submitEnabled: Boolean = value.trim().isNotEmpty(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = if (onSubmit != null) {
            modifier.submitOnEnter(submitEnabled) { onSubmit() }
        } else {
            modifier
        },
        placeholder = {
            Text(
                placeholder,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
        colors = settingsFieldColors(),
    )
}

/** Section header: a small-caps title + optional trailing slot (e.g. a refresh button). */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** A small caption (secondary). */
@Composable
fun SettingsCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // labelSmall is Medium in both type scales; a caption is body copy, so it keeps the Normal
        // weight both apps drew before the token move.
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Normal,
        modifier = modifier,
    )
}

/** A copyable monospaced command chip (used for `claude setup-token`). */
@Composable
fun CopyableCommand(command: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    var copied by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            command,
            color = cs.onSurface,
            fontFamily = MonoFontFamily,
            style = MaterialTheme.typography.labelMedium,
            // As above: the token is Medium, a shell command is not emphasised text.
            fontWeight = FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(Radii.sm))
                .padding(horizontal = Space.md, vertical = Space.sm),
        )
        IconButton(onClick = {
            platform.copyToClipboard(command)
            copied = true
        }) {
            Icon(
                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                contentDescription = "Copy",
                tint = cs.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Max width for settings detail content on a wide pane. */
val SettingsDetailMaxWidth = 720.dp
