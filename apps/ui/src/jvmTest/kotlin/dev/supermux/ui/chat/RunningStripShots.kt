package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.Subagent
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Design screenshots of the running strip's lanes. No-op unless STRIP_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class RunningStripShots {
    @Test
    fun shots() {
        val dir = System.getenv("STRIP_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val now = System.currentTimeMillis()
        val m = 60_000L
        fun tool(id: String, sub: String, at: Long, dur: Long?, tool: String, title: String, failed: Boolean = false) = listOfNotNull(
            ActivityEvent(ts = (now - at).toString(), kind = "tool", tool = tool, title = title, detail = title.substringAfter(": "), callId = id, subagentId = sub),
            dur?.let { ActivityEvent(ts = (now - at + it).toString(), kind = "tool_result", callId = id, subagentId = sub, title = if (failed) "error" else "done", phase = "completed") },
        )
        val sec = 1_000L
        val subs = listOf(
            Subagent(id = "a", name = "Explore", description = "Map the auth flow", model = "opus", startedAt = now - 9 * m),
            Subagent(id = "b", name = "general-purpose", description = "Write the migration", model = "sonnet", startedAt = now - 6 * m),
            Subagent(id = "c", name = "Explore", description = "Find flaky tests", model = "haiku", status = "completed", startedAt = now - 2 * m, endedAt = now - 20 * sec),
        )
        val rows = tool("1", "a", 75 * sec, 6 * sec, "Grep", "Grep: createSession") +
            tool("2", "a", 58 * sec, 2 * sec, "Read", "Read: auth/session.ts") +
            tool("3", "a", 52 * sec, 3 * sec, "Read", "Read: auth/token.ts") +
            tool("4", "a", 40 * sec, 14 * sec, "Bash", "Bash: bun test auth") +
            tool("5", "a", 16 * sec, 4 * sec, "Read", "Read: auth/refresh.ts") +
            tool("6", "b", 70 * sec, 8 * sec, "Read", "Read: db/schema.ts") +
            tool("7", "b", 46 * sec, 4 * sec, "Edit", "Edit: db/migrations/0042.sql", failed = true) +
            tool("8", "b", 30 * sec, null, "Write", "Write: db/migrations/0042.sql") +
            tool("9", "c", 90 * sec, 30 * sec, "Bash", "Bash: gradle test --rerun") +
            tool("10", "c", 45 * sec, 20 * sec, "Grep", "Grep: @Ignore")
        val tasks = listOf(ServerFrame.BgTask(id = "t1", kind = "shell", label = "bun run dev", startedAt = now - 4 * m))
        for (dark in listOf(true, false)) for ((name, w) in listOf("phone" to 390, "desktop" to 820)) {
            runDesktopComposeUiTest(width = w, height = 330) {
                setPlatformContent(
                    platform = FakePlatform(),
                    pointer = w > 500,
                    widthClass = if (w > 500) WindowWidthClass.Expanded else WindowWidthClass.Compact,
                    inputMode = if (w > 500) InputMode.Pointer else InputMode.Touch,
                ) {
                    SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
                            RunningStrip(subs, tasks, onOpenSubagent = {}, activity = rows, agent = "claude")
                        }
                    }
                }
                waitForIdle()
                val tag = "strip-$name-${if (dark) "dark" else "light"}"
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$tag-collapsed.png"))
                onNodeWithTag("subagent-strip-more").performClick()
                waitForIdle()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$tag.png"))
            }
        }
    }
}
