package dev.supermux.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The overlay's avatar for an Agent with no avatar of its own (the name-derived one) in every state the
 * overlay shows, listening and speaking at several voice levels, on the overlay's glass circle at its
 * real size and enlarged. For eyeballing a design change; a no-op unless SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/shots ./gradlew :ui:jvmTest --tests '*AssistantOverlayShots*'
 */
@OptIn(ExperimentalTestApi::class)
class AssistantOverlayShots {
    private class Cell(val label: String, val state: String, val level: Float = 0f)

    private val cells = listOf(
        Cell("idle", "idle"), Cell("working", "working"), Cell("waiting", "waiting"), Cell("asleep", "asleep"),
        Cell("listen 0", "listening", 0f), Cell("listen .5", "listening", 0.5f), Cell("listen 1", "listening", 1f),
        Cell("speak .1", "speaking", 0.1f), Cell("speak .6", "speaking", 0.6f), Cell("speak 1", "speaking", 1f),
    )

    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                SupermuxTheme(appearance = theme) {
                    val cs = MaterialTheme.colorScheme
                    Column(Modifier.background(cs.surfaceContainerLowest).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (head in listOf(56, 112)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (cell in cells) Column(Modifier.width((head + 12).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size((head + 8).dp), contentAlignment = Alignment.Center) {
                                    // The overlay's glass circle, as OverlayHead draws it.
                                    Box(
                                        Modifier.size(head.dp).clip(CircleShape).background(cs.surfaceContainerHigh.copy(alpha = 0.78f))
                                            .border(1.dp, cs.outlineVariant.copy(alpha = 0.5f), CircleShape),
                                    )
                                    AgentAvatar(
                                        "Dockie",
                                        online = cell.state != "asleep",
                                        working = cell.state == "working",
                                        unread = cell.state == "waiting",
                                        size = (head * 0.82f).toInt(),
                                        listening = cell.state == "listening",
                                        speaking = cell.state == "speaking",
                                        level = cell.level,
                                    )
                                }
                                Text(cell.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(700)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "overlay-avatar-${theme.name.lowercase()}.png"))
        }
    }
}
