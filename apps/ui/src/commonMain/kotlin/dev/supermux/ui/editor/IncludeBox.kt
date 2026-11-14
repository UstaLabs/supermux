package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

/**
 * #72: whether a file (or every file) goes into the next commit — git's index as a small box:
 * filled with a check (all of it), a bar (part of it), or empty. A 32dp target around a 16dp box.
 */
@Composable
internal fun IncludeBox(state: String, enabled: Boolean, tag: String, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(4.dp)
    val label = localized(Str.git_include_in_commit)
    Box(
        Modifier
            .size(32.dp)
            .clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .semantics { contentDescription = label; stateDescription = state }
            .testTag(tag)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        val box = Modifier.size(16.dp)
        when (state) {
            "all" -> Box(box.background(cs.primary, shape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp))
            }
            "partial" -> Box(box.border(1.5.dp, cs.primary, shape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 8.dp, height = 2.dp).background(cs.primary, RoundedCornerShape(1.dp)))
            }
            else -> Box(box.border(1.5.dp, cs.onSurfaceVariant.copy(alpha = 0.7f), shape))
        }
    }
}
