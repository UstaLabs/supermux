package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #144, Ahmet's report: "What fills it" columns zig-zagged where a row had a › (its numbers moved left). Every row now
 * keeps the chevron's slot and the numbers sit in fixed, right-aligned, tabular columns: their right edges line up,
 * on the popover and the sheet, dark and light. Set CONTEXT_SHOTS_DIR to also write the screenshots.
 */
@OptIn(ExperimentalTestApi::class)
class ContextColumnsTest {
    private val rows = listOf(
        Triple("System prompt", 6_400L, false), Triple("System tools", 8_100L, false), Triple("MCP tools", 3_300L, true),
        Triple("MCP server instructions", 983L, false), Triple("Memory files", 76L, true), Triple("Skills", 9_500L, true),
        Triple("Messages", 822_000L, false),
    )

    @Test fun numbers_line_up_with_and_without_a_drill_in() {
        for (sheet in listOf(false, true)) for (dark in listOf(true, false)) runDesktopComposeUiTest(width = 420, height = 360) {
            setContent {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    Surface {
                        Column(Modifier.width(if (sheet) 400.dp else 380.dp)) {
                            rows.forEachIndexed { i, (name, tokens, drill) ->
                                CategoryRow(sheet, Color(0xFF4BBAA7 + i * 0x101010), name, tokens, 1_000_000L, onClick = if (drill) ({}) else null)
                            }
                            CategoryRow(sheet, Color.Transparent, "Free", 117_000L, 1_000_000L, dim = true, outlined = true)
                        }
                    }
                }
            }
            for (tag in listOf("context_row_tokens", "context_row_pct")) {
                val rights = onAllNodesWithTag(tag).fetchSemanticsNodes().map { it.boundsInRoot.right }
                assertTrue(rights.size == rows.size + 1, "$tag: ${rights.size} rows")
                assertTrue(rights.all { abs(it - rights.first()) < 0.5f }, "$tag right edges differ (sheet=$sheet dark=$dark): $rights")
            }
            System.getenv("CONTEXT_SHOTS_DIR")?.let { dir ->
                File(dir).mkdirs()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "context-columns-${if (sheet) "sheet" else "popover"}-${if (dark) "dark" else "light"}.png"))
            }
        }
    }
}
