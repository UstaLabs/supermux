package dev.supermux.android.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.proto.SlashCommand

/**
 * The `/command` autocomplete dropdown, shared by both composers (chat + New Session launcher) —
 * the Android counterpart to iOS SlashMenu.swift. Stateless: the caller passes the current matches,
 * the keyboard-highlighted index, and the apply action. [showActionGlyph] adds the ⚡ marker for
 * control commands (chat shows it; the launcher's preview commands are insert-only). The container
 * background/clip is supplied by [modifier] so each composer can tint it for its own surface.
 */
@Composable
fun SlashMenu(
    matches: List<SlashCommand>,
    selectedIndex: Int,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "slash_item_",
    showActionGlyph: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        matches.forEachIndexed { i, cmd ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$testTagPrefix${cmd.name}")
                    .background(
                        if (i == selectedIndex) cs.surfaceContainerHighest else Color.Transparent,
                    )
                    .clickable { onSelect(cmd) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${cmd.sigil}${cmd.name}",
                    color = cs.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.width(120.dp),
                )
                cmd.description?.let { desc ->
                    Text(
                        text = desc,
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                // Trailing "executes" glyph for control commands (iOS bolt.fill).
                if (showActionGlyph && cmd.action != null) {
                    Spacer(Modifier.weight(1f))
                    Icon(
                        painter = painterResource(R.drawable.ic_zap),
                        contentDescription = null,
                        tint = cs.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
        }
    }
}
