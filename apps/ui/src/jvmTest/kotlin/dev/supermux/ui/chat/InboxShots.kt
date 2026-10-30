package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Design shots of decision #19 in the real chat panel: the user's bubble, an agent reply, a message
 * from another session, a supermux notice, a council report and an error reply, at phone and desktop
 * width, light and dark. A no-op unless INBOX_SHOTS_DIR is set (`--rerun`: the variable is not a
 * task input, so an up-to-date test task would otherwise be skipped):
 *   INBOX_SHOTS_DIR=/tmp/inbox-shots ./gradlew :ui:jvmTest --tests '*InboxShots*' --rerun
 */
@OptIn(ExperimentalTestApi::class)
class InboxShots {
    private val conversation = listOf(
        LogEntry(id = "in:web:1", ts = "2026-10-30T09:12:00Z", direction = "inbound", channel = "web", chat_id = "web", text = "say hi to personal-3"),
        LogEntry(
            id = "out:1", ts = "2026-10-30T09:12:14Z", direction = "outbound", op = "reply",
            text = "Sent **personal-3** a hello. I'll tell you what it says back.",
        ),
        LogEntry(
            id = "in:inbox:m1", ts = "2026-10-30T09:13:02Z", direction = "inbound", channel = "inbox", chat_id = "",
            text = "Hi! I'm on the **auth refactor** right now.\nThe flaky login test is fixed in `auth/session.test.ts`.",
            from_session_id = "s-3", from_session_name = "personal-3", from_agent = "claude",
        ),
        LogEntry(
            id = "in:inbox:m2", ts = "2026-10-30T09:14:40Z", direction = "inbound", channel = "inbox", chat_id = "",
            text = "Your subagent [Grok Search Test:6b084ccf-107c-4078-9ba9-bdef08de7949] stopped with an error:\n" +
                "API error (status 402 Payment Required): Grok Build usage balance exhausted",
            from_session_id = "supermux", from_session_name = "supermux",
        ),
        LogEntry(
            id = "in:inbox:m3", ts = "2026-10-30T09:16:10Z", direction = "inbound", channel = "inbox", chat_id = "",
            text = "council-1 · round 1 · B — Grok [council-1 B:7f3a2c41-0d9e-4b8a-9c11-52e0b7d1a6f3]:\n" +
                "Yes, with rotation: keep two keys live and retire the older one every week.",
            from_session_id = "c-1", from_session_name = "council-1", council_id = "c-1", council_round = 1, council_letter = "B",
        ),
        LogEntry(
            id = "out:2", ts = "2026-10-30T09:17:30Z", direction = "outbound", op = "reply", error = true,
            text = "StopFailure: API Error: 402 Payment Required (usage balance exhausted)",
        ),
    )

    private class Shape(val name: String, val widthDp: Int, val heightDp: Int, val compact: Boolean)

    @Test fun shots() {
        val dir = System.getenv("INBOX_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val shapes = listOf(Shape("phone", 400, 900, compact = true), Shape("desktop", 760, 820, compact = false))
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) for (shape in shapes) {
            runDesktopComposeUiTest(shape.widthDp * 2, shape.heightDp * 2) {
                setPlatformContent(
                    pointer = !shape.compact,
                    widthClass = if (shape.compact) WindowWidthClass.Compact else WindowWidthClass.Expanded,
                    inputMode = if (shape.compact) InputMode.Touch else InputMode.Pointer,
                ) {
                    // The app always provides chat search: a listed sender's name is a link.
                    val search = remember { ChatSearchHost(searchAll = { emptyList() }, openSession = {}) }
                    CompositionLocalProvider(LocalDensity provides Density(2f), LocalChatSearch provides search) {
                        SupermuxTheme(appearance = theme) {
                            Box(Modifier.size(shape.widthDp.dp, shape.heightDp.dp)) {
                                ChatPanel(
                                    session = SessionInfo(id = "s-1", name = "personal-1", workdir = "/home/ahmet/supermux", agent = "claude"),
                                    state = ChatState(messages = conversation, listedSessions = setOf("s-1", "s-3")),
                                    actions = ChatActions(),
                                    draft = "",
                                    onDraftChange = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                val file = File(dir, "${shape.name}-${theme.name.lowercase()}.png")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            }
        }
    }
}
