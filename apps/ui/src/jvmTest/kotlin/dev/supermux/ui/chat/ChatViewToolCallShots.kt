package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.time.Instant

/**
 * The real ChatPanel at Medium detail, fed a real slice of a Claude Code session (user line, agent
 * replies, hidden thinking, tool calls and their results, in order) at compressed real timing. The slice is session
 * data, so it lives outside the repo. A no-op unless both are set:
 *   TOOLCALL_CHAT=/tmp/slice.json SHOTS_DIR=/tmp/chat ./gradlew :ui:jvmTest --tests '*ChatViewToolCallShots*'
 */
@OptIn(ExperimentalTestApi::class)
class ChatViewToolCallShots {
    private fun s(o: JsonObject?, vararg keys: String) =
        keys.firstNotNullOfOrNull { k -> o?.get(k)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() } }

    @Test fun record() {
        val slice = System.getenv("TOOLCALL_CHAT")?.let(::File) ?: return
        val root = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        val events = Json.parseToJsonElement(slice.readText()).jsonArray.map { it.jsonObject }
        val encoder = Executors.newFixedThreadPool(6)
        for (theme in listOf(AppearanceMode.DARK, AppearanceMode.LIGHT)) runDesktopComposeUiTest(860, 1720) {
            val dir = File(root, theme.name.lowercase()).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var messages by mutableStateOf(listOf<LogEntry>())
            var activity by mutableStateOf(listOf<ActivityEvent>())
            var working by mutableStateOf(false)
            mainClock.autoAdvance = false
            setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    SupermuxTheme(appearance = theme) {
                        Box(Modifier.size(430.dp, 860.dp)) {
                            ChatPanel(
                                session = SessionInfo(id = "s1", name = "KMP Tool Call Component", workdir = "/home/ahmet/supermux", agent = "claude"),
                                state = ChatState(
                                    messages = messages,
                                    activity = activity,
                                    agent = if (working) AgentStatus(phase = "running", state = "working", working = true, detail = "running") else null,
                                ),
                                actions = ChatActions(),
                                draft = "",
                                onDraftChange = {},
                                showHeader = true,
                            )
                        }
                    }
                }
            }
            var frame = 0
            // 30fps keeps the whole replay inside runTest's one-minute budget; encoding is off-thread.
            fun run(ms: Long) = repeat((ms / 33).toInt().coerceAtLeast(1)) {
                mainClock.advanceTimeBy(33)
                val img = onRoot().captureToImage().toAwtImage()
                val f = File(dir, "f%04d.png".format(frame++))
                encoder.execute { ImageIO.write(img, "png", f) }
            }
            fun ms(ts: String) = Instant.parse(ts).toEpochMilliseconds()

            run(400)
            events.forEachIndexed { i, e ->
                val ts = e["ts"]!!.jsonPrimitive.content
                val next = events.getOrNull(i + 1)?.get("ts")?.jsonPrimitive?.content
                val gap = if (next != null) ms(next) - ms(ts) else 0L
                when (e["k"]!!.jsonPrimitive.content) {
                    "user" -> {
                        messages = messages + LogEntry(id = "u$i", ts = ts, direction = "inbound", text = s(e, "text"))
                        working = true
                        run(900)
                    }
                    "say" -> {
                        messages = messages + LogEntry(id = "a$i", ts = ts, direction = "outbound", text = s(e, "text"))
                        run(900)
                    }
                    "tool" -> {
                        working = true
                        val tool = s(e, "tool")!!
                        val input = e["input"] as? JsonObject
                        val arg = s(input, "command", "file_path", "path", "pattern", "query", "url", "skill", "name", "prompt").orEmpty()
                        val body = when (tool) {
                            "Bash" -> ActivityToolBody(kind = "bash", command = arg)
                            "Write" -> ActivityToolBody(kind = "write", path = arg, content = s(input, "content"))
                            else -> ActivityToolBody(kind = "generic", input = arg.ifBlank { null })
                        }
                        activity = activity + ActivityEvent(
                            ts = ts, kind = "tool", tool = tool,
                            title = (if (arg.isNotBlank()) "$tool: ${arg.lineSequence().first()}" else tool).take(120),
                            detail = arg.take(2000), description = s(input, "description"), phase = "started",
                            callId = s(e, "id"), body = body,
                        )
                        run(gap.coerceIn(250, 700))
                    }
                    // Claude's hidden thinking: the broker's redacted reasoning row, held for the real gap.
                    "think" -> {
                        activity = activity + ActivityEvent(ts = ts, kind = "reasoning", title = "Thinking", redacted = true)
                        run(gap.coerceIn(300, 900))
                    }
                    "result" -> {
                        val err = e["err"]?.jsonPrimitive?.booleanOrNull == true
                        val out = s(e, "out")
                        activity = activity + ActivityEvent(
                            ts = ts, kind = "tool_result", callId = s(e, "id"), title = if (err) "error" else "done",
                            phase = "completed", detail = out?.take(2000),
                            body = ActivityToolBody(kind = "generic", output = out),
                        )
                        // The gap after a result is the agent deciding what to do next.
                        run(gap.coerceIn(120, 900))
                    }
                }
            }
            working = false
            run(2500)
        }
        encoder.shutdown()
        encoder.awaitTermination(10, TimeUnit.MINUTES)
    }
}
