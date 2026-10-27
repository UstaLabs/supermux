package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.chat.ToolStatus
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.ActivityToolBody
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real tool calls (extracted from Claude Code transcripts into TOOLCALL_FIXTURE — never committed,
 * it is session data) shaped the way the broker shapes them, through [toToolCallData] and the
 * component. Stills per group, plus a 60fps replay of the longest group. No-op unless both are set:
 *   TOOLCALL_FIXTURE=/tmp/fixture.json SHOTS_DIR=/tmp/real ./gradlew :ui:jvmTest --tests '*ToolCallRealShots*'
 */
@OptIn(ExperimentalTestApi::class)
class ToolCallRealShots {
    private class Call(val event: ActivityEvent, val status: ToolStatus, val output: String?, val resultBody: ActivityToolBody?, val durationMs: Long?)

    private fun s(o: JsonObject?, vararg keys: String) =
        keys.firstNotNullOfOrNull { k -> o?.get(k)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() } }

    /** Mirrors the broker: "Tool: summary" title, description, detail, and a bash/edit/generic body. */
    private fun toCall(o: JsonObject, i: Int): Call {
        val tool = o["tool"]!!.jsonPrimitive.content
        val input = o["input"] as? JsonObject
        val arg = s(input, "command", "file_path", "path", "pattern", "query", "url", "skill", "name", "prompt").orEmpty()
        val output = s(o, "output")
        val isError = (o["isError"]?.jsonPrimitive?.booleanOrNull) == true
        val title = (if (arg.isNotBlank()) "$tool: ${arg.lineSequence().first()}" else tool).take(120)
        val body = when (tool) {
            "Bash" -> ActivityToolBody(kind = "bash", command = arg)
            "Edit" -> ActivityToolBody(
                kind = "edit", path = arg,
                diff = "--- a/$arg\n+++ b/$arg\n" + s(input, "old_string").orEmpty().lines().joinToString("\n") { "-$it" } +
                    "\n" + s(input, "new_string").orEmpty().lines().joinToString("\n") { "+$it" },
            )
            "Write" -> ActivityToolBody(kind = "write", path = arg, content = s(input, "content"))
            else -> ActivityToolBody(kind = "generic", input = arg.ifBlank { null })
        }
        val event = ActivityEvent(
            ts = "2026-10-27T12:00:00.000Z", kind = "tool", tool = tool, title = title, seq = i,
            detail = arg.take(2000), description = s(input, "description"), phase = "started", callId = s(o, "id"), body = body,
        )
        val resultBody = if (tool == "Bash") ActivityToolBody(kind = "bash", command = arg, output = output) else null
        return Call(event, if (isError) ToolStatus.ERROR else ToolStatus.DONE, output, resultBody, o["durationMs"]?.jsonPrimitive?.longOrNull)
    }

    private fun Call.data(status: ToolStatus = this.status) = event.toToolCallData(status, output, resultBody, durationMs)

    @Test fun shots() {
        val fixture = System.getenv("TOOLCALL_FIXTURE")?.let(::File) ?: return
        val root = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        val groups = Json.parseToJsonElement(fixture.readText()).jsonArray.map { g -> g.jsonArray.mapIndexed { i, c -> toCall(c.jsonObject, i) } }

        // Stills: each group open, plus its first call and its first failure open on their own.
        for (theme in listOf(AppearanceMode.DARK, AppearanceMode.LIGHT)) runDesktopComposeUiTest(900, 4800) {
            val dir = File(root, "stills-${theme.name.lowercase()}").apply { mkdirs() }
            var gi by mutableStateOf(0)
            setContent {
                CompositionLocalProvider(LocalPlatform provides FakePlatform(), LocalDensity provides Density(2f)) {
                    SupermuxTheme(appearance = theme) {
                        Box(Modifier.size(440.dp, 2400.dp), contentAlignment = Alignment.TopStart) {
                            val g = groups[gi]
                            Column(
                                Modifier.width(440.dp).testTag("shot").background(MaterialTheme.colorScheme.background).padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                androidx.compose.runtime.key(gi) {
                                    ToolCallGroup(g.map { it.data() }, defaultOpen = true, autoCollapse = false)
                                    ToolCall(g.first().data(), defaultOpen = true)
                                    g.firstOrNull { it.status == ToolStatus.ERROR }?.let { ToolCall(it.data(), defaultOpen = true, onRetry = {}) }
                                }
                            }
                        }
                    }
                }
            }
            for (i in groups.indices) {
                gi = i
                mainClock.advanceTimeBy(3_000)
                ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", File(dir, "group%02d.png".format(i)))
            }
        }

        // Replay: three real runs arriving live at their real pace (capped), as they would in a chat —
        // a mixed-tool run, a run with a real failure, and a 12-call run — then a few rows opened.
        val runs = listOf(2, 4, 5).filter { it < groups.size }.map { groups[it] }
        // PNG encoding runs off the test body: runTest gives the body a hard 1 minute.
        val encoder = Executors.newFixedThreadPool(6)
        for (theme in listOf(AppearanceMode.DARK, AppearanceMode.LIGHT)) runDesktopComposeUiTest(1360, 1800) {
            val dir = File(root, "replay-${theme.name.lowercase()}").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var shown by mutableStateOf(List(runs.size) { emptyList<ToolCallData>() })
            var active by mutableStateOf(-1)
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalPlatform provides FakePlatform(), LocalDensity provides Density(2f)) {
                    SupermuxTheme(appearance = theme) {
                        val bg = if (theme == AppearanceMode.LIGHT) ToolCallColors.Light.surfaceMuted else ToolCallColors.Dark.surfaceMuted
                        Box(Modifier.size(680.dp, 900.dp).background(bg).padding(24.dp)) {
                            Column(Modifier.width(632.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                shown.forEachIndexed { gi, calls ->
                                    if (calls.isNotEmpty()) androidx.compose.runtime.key(gi) {
                                        ToolCallGroup(calls, active = active == gi, onRetry = {}, modifier = Modifier.testTag("group$gi"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            var frame = 0
            fun run(ms: Long) = repeat((ms / 16).toInt()) {
                mainClock.advanceTimeBy(16)
                val img = onRoot().captureToImage().toAwtImage()
                val f = File(dir, "f%04d.png".format(frame++))
                encoder.execute { ImageIO.write(img, "png", f) }
            }
            fun setRun(gi: Int, list: List<ToolCallData>) { shown = shown.toMutableList().also { it[gi] = list } }
            run(300)
            runs.forEachIndexed { gi, calls ->
                active = gi
                for (c in calls) {
                    setRun(gi, shown[gi] + c.data(ToolStatus.RUNNING))
                    run((c.durationMs ?: 400).coerceIn(250, 1200))
                    setRun(gi, shown[gi].dropLast(1) + c.data())
                    run(140)
                }
                active = -1
                run(1300)
                if (gi == 1) {
                    // The failed call stays open: look at it, then fold the run away.
                    calls.firstOrNull { it.status == ToolStatus.ERROR }?.let { failed ->
                        onNodeWithTag("tool_call_head_${failed.event.callId}").performClick(); run(2200)
                        onNodeWithTag("tool_call_head_${failed.event.callId}").performClick(); run(700)
                    }
                    onNode(hasTestTag("tool_group_head") and hasAnyAncestor(hasTestTag("group1"))).performClick(); run(900)
                }
            }
            // Reopen the long run and look inside one real call.
            onNode(hasTestTag("tool_group_head") and hasAnyAncestor(hasTestTag("group2"))).performClick(); run(900)
            onNodeWithTag("tool_call_head_${runs[2][1].event.callId}").performClick(); run(2600)
        }
        encoder.shutdown()
        encoder.awaitTermination(10, TimeUnit.MINUTES)
    }
}
