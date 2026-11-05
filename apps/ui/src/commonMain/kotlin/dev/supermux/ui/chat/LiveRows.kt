package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.chat.liveMarkdown
import dev.supermux.state.LiveReply
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.theme.Space

/**
 * Card #76: the reply while the agent writes it. Same look and padding as the stored reply that takes its place
 * (TimelineItemRow's message row), minus the time / copy row that only a finished message has.
 */
@Composable
internal fun LiveReplyRow(reply: LiveReply, onOpenFile: (FilePathRef) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("chat-live-reply")) {
        LinkSelectionContainer {
            MarkdownBody(text = liveMarkdown(reply.text), modifier = Modifier.fillMaxWidth(), onOpenFile = onOpenFile, linkify = true)
        }
    }
}

/**
 * Card #76: the agent's thinking while it writes it: its newest lines, muted, under "Thinking…". Once the reply
 * starts it folds to the label, like the stored thinking row that takes its place.
 */
@Composable
internal fun LiveThinkingRow(thinking: LiveReply, folded: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("activity-reasoning-live").padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (!folded) CircularProgressIndicator(Modifier.size(12.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
            Text("Thinking…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        if (!folded) {
            Text(
                thinkingTail(thinking.text),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = Space.xs),
            )
        }
    }
}

/** The newest few lines of a long think: the row stays small while the text keeps coming. */
internal fun thinkingTail(text: String, maxLines: Int = 6, maxChars: Int = 600): String {
    val tail = text.trimEnd().lines().takeLast(maxLines).joinToString("\n")
    return if (tail.length <= maxChars) tail else "…" + tail.takeLast(maxChars)
}
