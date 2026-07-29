package dev.supermux.android.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.android.chat.TimelineItem
import dev.supermux.android.chat.TimelineItemRow
import dev.supermux.android.chat.ToolStatus
import dev.supermux.android.chat.mergeTimeline
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.SupermuxTheme
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.LogEntry
import java.time.Instant

/**
 * Debug-only harness to eyeball the chat bubble layout with deterministic data,
 * without a broker or pairing. Launch with:
 *   adb shell am start -n dev.supermux.android/.debug.ChatPreviewActivity
 */
class ChatPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val now = Instant.now()
        fun ts(minsAgo: Long) = now.minusSeconds(minsAgo * 60).toString()

        val messages = listOf(
            LogEntry(
                id = "m1",
                ts = ts(12),
                direction = "inbound",
                text = "Can you summarize the chat layout plan and keep it short?",
            ),
            LogEntry(
                id = "m2",
                ts = ts(11),
                direction = "outbound",
                text = """Here's the plan:

1. **User messages** — right-aligned bubble, max ~75% width
2. **Agent replies** — full width, no gutter dots
3. **Footer** — small time + copy under each agent reply

Minimal chrome, no heavy animation.""",
            ),
            LogEntry(
                id = "m3",
                ts = ts(8),
                direction = "inbound",
                text = "Looks good — ship the Android version first.",
            ),
            LogEntry(
                id = "m4",
                ts = ts(6),
                direction = "outbound",
                text = "Done. User bubbles sit on the right; agent text is full-bleed with a quiet `HH:mm` + copy row. The session-log dots and spine are gone.",
            ),
            LogEntry(
                id = "m5",
                ts = ts(2),
                direction = "inbound",
                text = "Nice. Screenshot it on the emulator please.",
            ),
            LogEntry(
                id = "m6",
                ts = ts(1),
                direction = "outbound",
                text = "On it — installing the debug build and capturing the chat stream now.",
            ),
        )

        val activity = listOf(
            ActivityEvent(
                ts = ts(10),
                kind = "tool",
                tool = "Edit",
                title = "Edit: Timeline.kt",
                description = "Timeline.kt",
                callId = "c1",
                phase = "completed",
            ),
        )

        val items = mergeTimeline(messages, activity, hideTools = false)
        // Force the tool to DONE for the preview.
        val previewItems = items.map { item ->
            if (item is TimelineItem.Tool) item.copy(status = ToolStatus.DONE) else item
        }

        setContent {
            SupermuxTheme {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .statusBarsPadding(),
                ) {
                    Text(
                        text = "chat preview",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Space.md),
                    ) {
                        items(previewItems, key = {
                            when (it) {
                                is TimelineItem.Msg -> it.entry.id
                                is TimelineItem.Tool -> it.event.callId ?: it.event.ts
                            }
                        }) { item ->
                            TimelineItemRow(item, highDetail = false)
                        }
                    }
                }
            }
        }
    }
}
