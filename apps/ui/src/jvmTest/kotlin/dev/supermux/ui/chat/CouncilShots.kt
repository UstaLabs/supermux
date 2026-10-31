package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.proto.ActivityEvent
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilRound
import dev.supermux.proto.CouncilView
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
 * Design shots of the council card (spec §5.1, variant A) in the real chat panel: a council in round
 * 2 with A reported, B working and C failed, at phone and desktop width, light and dark, collapsed
 * and with A's row open. A no-op unless COUNCIL_SHOTS_DIR is set:
 *   COUNCIL_SHOTS_DIR=/tmp/council-shots ./gradlew :ui:jvmTest --tests '*CouncilShots*' --rerun
 */
@OptIn(ExperimentalTestApi::class)
class CouncilShots {
    private val council = CouncilView(
        id = "c-3",
        name = "council-3",
        question = "Parakeet or Whisper Small on-device for Turkish?",
        round = 2,
        focus = "background noise",
        callId = "toolu_council",
        createdAt = "2026-10-30T12:30:01.000Z",
        members = listOf(
            CouncilMember(letter = "A", session = "ma", name = "council-3 A", agent = "codex", model = "gpt-6", label = "GPT-6", status = "reported"),
            CouncilMember(letter = "B", session = "mb", name = "council-3 B", agent = "grok", label = "Grok 4.7", status = "working"),
            CouncilMember(letter = "C", session = "mc", name = "council-3 C", agent = "antigravity", label = "Gemini 4 Argon", status = "failed"),
        ),
        rounds = listOf(
            CouncilRound(
                round = 1, startedAt = "2026-10-30T12:30:01.000Z", completedAt = "2026-10-30T12:36:00.000Z",
                reports = listOf(
                    CouncilReport(
                        id = "r1", letter = "A", seq = 1, at = "2026-10-30T12:33:00.000Z",
                        text = "Whisper Small is the only phone-sized option with Turkish. Expect mid-teens WER on clean read speech. " +
                            "Don't quantize below 8-bit: non-English collapses at 4-bit (Korean 6.9 → 20%).",
                    ),
                    CouncilReport(
                        id = "r2", letter = "B", seq = 2, at = "2026-10-30T12:34:00.000Z",
                        text = "Community reports: Turkish on phones is weak across the board; Whisper Small ~14% WER, Turbo ~6% but Mac-only.",
                    ),
                    CouncilReport(
                        id = "r3", letter = "C", seq = 3, at = "2026-10-30T12:35:00.000Z",
                        text = "FLEURS Turkish: Whisper Large-v3 Turbo 5.95%, Qwen3-ASR 1.7B 8.81%, Whisper Small 14.24%. No Parakeet score (not supported).",
                    ),
                ),
            ),
            CouncilRound(
                round = 2, focus = "background noise", startedAt = "2026-10-30T12:36:00.000Z",
                reports = listOf(
                    CouncilReport(
                        id = "r4", letter = "A", seq = 4, at = "2026-10-30T12:40:00.000Z",
                        text = "**Whisper Small.** Parakeet's 25 languages don't include Turkish; on-device Small is ~14% WER on clean " +
                            "FLEURS Turkish, worse in noise. With noise, add VAD and a Turkish domain prompt. Keep Whisper Small, int8.",
                    ),
                ),
            ),
        ),
    )

    private fun forwarded(id: String, ts: String, letter: String, round: Int, text: String) = LogEntry(
        id = id, ts = ts, direction = "inbound", channel = "inbox", chat_id = "",
        text = "council-3 · round $round · $letter — x [council-3 $letter:11111111-1111-4111-8111-111111111111]:\n$text",
        from_session_id = "c-3", from_session_name = "council-3", council_id = "c-3", council_round = round, council_letter = letter,
    )

    private val messages = listOf(
        LogEntry(id = "in:web:1", ts = "2026-10-30T12:29:50Z", direction = "inbound", channel = "web", chat_id = "web", text = "ask a council: parakeet or whisper small for turkish on phones?"),
        forwarded("in:inbox:1", "2026-10-30T12:33:00.000Z", "A", 1, "Whisper Small is the only phone-sized option…"),
        forwarded("in:inbox:2", "2026-10-30T12:40:00.000Z", "A", 2, "Whisper Small."),
        LogEntry(
            id = "in:inbox:n1", ts = "2026-10-30T12:41:00.000Z", direction = "inbound", channel = "inbox", chat_id = "",
            from_session_id = "supermux", from_session_name = "supermux",
            text = "council-3 · C [council-3 C:33333333-3333-4333-8333-333333333333] stopped with an error:\n" +
                "API error (status 402 Payment Required): usage balance exhausted",
        ),
        LogEntry(
            id = "out:1", ts = "2026-10-30T12:41:30Z", direction = "outbound", op = "reply",
            text = "Round 2 is running with the focus on **noise**. A reported and C failed; I'll summarise when B is in.",
        ),
    )

    private val activity = listOf(
        ActivityEvent(ts = "2026-10-30T12:30:00.000Z", kind = "tool", tool = "council", title = "council", phase = "started", callId = "toolu_council"),
        ActivityEvent(ts = "2026-10-30T12:30:02.000Z", kind = "tool_result", title = "done", phase = "completed", callId = "toolu_council"),
    )

    private class Shape(val name: String, val widthDp: Int, val heightDp: Int, val compact: Boolean)

    @Test fun shots() {
        val dir = System.getenv("COUNCIL_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val shapes = listOf(Shape("phone", 400, 900, compact = true), Shape("desktop", 760, 820, compact = false))
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) for (shape in shapes) for (open in listOf(false, true)) {
            runDesktopComposeUiTest(shape.widthDp * 2, shape.heightDp * 2) {
                setPlatformContent(
                    pointer = !shape.compact,
                    widthClass = if (shape.compact) WindowWidthClass.Compact else WindowWidthClass.Expanded,
                    inputMode = if (shape.compact) InputMode.Touch else InputMode.Pointer,
                ) {
                    CompositionLocalProvider(LocalDensity provides Density(2f)) {
                        SupermuxTheme(appearance = theme) {
                            Box(Modifier.size(shape.widthDp.dp, shape.heightDp.dp)) {
                                ChatPanel(
                                    session = SessionInfo(id = "s-1", name = "personal-1", workdir = "/home/ahmet/openwhisper", agent = "claude"),
                                    state = ChatState(messages = messages, activity = activity, councils = listOf(council)),
                                    actions = ChatActions(endCouncil = {}),
                                    draft = "",
                                    onDraftChange = {},
                                )
                            }
                        }
                    }
                }
                waitForIdle()
                if (open) {
                    onNodeWithTag("council-row:c-3:A").performClick()
                    waitForIdle()
                }
                val file = File(dir, "${shape.name}-${theme.name.lowercase()}${if (open) "-open" else ""}.png")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
            }
        }
    }
}
