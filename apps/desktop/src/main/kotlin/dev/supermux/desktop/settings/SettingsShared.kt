// Shared settings primitives for the desktop Settings hub (Agents / future sections).
// Ported from apps/android/.../settings/SettingsShared.kt — desktop adaptations:
//   - LocalContext openUrl/copy → openInBrowser + LocalClipboardManager
//   - No KeyboardOptions (no mobile IME concern on desktop)
//   - Enter-to-submit via onPreviewKeyEvent (desktop convention)
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space

/** Desktop Enter-to-submit: fire [submit] (and consume) on Enter/NumPad-Enter when [enabled]. */
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

/** A monospaced secret field (password transformation). Optional Enter-to-submit. */
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
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier,
    )
}

/** A copyable monospaced command chip (used for `claude setup-token`). */
@Composable
fun CopyableCommand(command: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
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
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(Radii.sm))
                .padding(horizontal = Space.md, vertical = Space.sm),
        )
        IconButton(onClick = {
            clipboard.setText(AnnotatedString(command))
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

/** Max width for settings detail content on a wide desktop pane. */
val SettingsDetailMaxWidth = 720.dp
