package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilRound
import dev.supermux.proto.CouncilView
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/** Design screenshots of the council card, folded and open. No-op unless COUNCIL_SHOTS_DIR is set. */
@OptIn(ExperimentalTestApi::class)
class CouncilCardShots {
    @Test
    fun shots() {
        val dir = System.getenv("COUNCIL_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val council = CouncilView(
            id = "c-3", name = "council-3", question = "Parakeet or Whisper Small on-device for Turkish dictation?",
            round = 1, callId = "x", createdAt = "2026-10-30T12:30:00.000Z",
            members = listOf(
                CouncilMember(letter = "A", session = "ma", name = "A", agent = "codex", label = "GPT-6", status = "reported"),
                CouncilMember(letter = "B", session = "mb", name = "B", agent = "claude", label = "Opus 5.5", status = "working"),
                CouncilMember(letter = "C", session = "mc", name = "C", agent = "gemini", label = "Gemini 4", status = "working"),
                CouncilMember(letter = "D", session = "md", name = "D", agent = "grok", label = "Grok 4.7", status = "failed"),
            ),
            rounds = listOf(
                CouncilRound(
                    round = 1, startedAt = "2026-10-30T12:30:00.000Z",
                    reports = listOf(CouncilReport(id = "r1", letter = "A", seq = 1, text = "Whisper Small is the only phone-sized option that holds up with **Turkish** accents; Parakeet needs a fine-tune.", at = "2026-10-30T12:33:00.000Z")),
                ),
            ),
        )
        val live = mapOf("mb" to "Searching the web for Parakeet Turkish WER", "mc" to "Thinking")
        for (dark in listOf(true, false)) for ((name, w) in listOf("phone" to 390, "desktop" to 720)) {
            runDesktopComposeUiTest(width = w, height = 520) {
                setPlatformContent(
                    platform = FakePlatform(),
                    pointer = w > 500,
                    widthClass = if (w > 500) WindowWidthClass.Expanded else WindowWidthClass.Compact,
                    inputMode = if (w > 500) InputMode.Pointer else InputMode.Touch,
                ) {
                    SupermuxTheme(appearance = if (dark) AppearanceMode.DARK else AppearanceMode.LIGHT) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), contentAlignment = Alignment.TopCenter) {
                            CompositionLocalProvider(LocalCouncilActivity provides live) {
                                CouncilCard(TimelineItem.CouncilCard(council, failures = mapOf("D" to "API error (status 402 Payment Required)")), killSession = {})
                            }
                        }
                    }
                }
                mainClock.autoAdvance = false
                mainClock.advanceTimeBy(100)
                val tag = "council-$name-${if (dark) "dark" else "light"}"
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$tag-folded.png"))
                onNodeWithTag("council-toggle:c-3").performClick()
                mainClock.advanceTimeBy(1000)
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$tag-open.png"))
            }
        }
    }
}
