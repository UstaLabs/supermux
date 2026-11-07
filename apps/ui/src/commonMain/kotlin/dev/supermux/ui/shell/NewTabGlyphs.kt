// The New tab page's and the workspace split buttons' own glyphs (#91).
package dev.supermux.ui.shell

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Five thin line glyphs drawn for the page: one stroke weight, round ends, on a 24 grid, no fills.
 * Tinted like any icon.
 */
internal object NewTabGlyphs {
    /** A speech bubble with its tail at the bottom left. */
    val Chat: ImageVector by lazy {
        glyph("chat") {
            moveTo(7f, 5f)
            lineTo(17f, 5f)
            quadTo(20f, 5f, 20f, 8f)
            lineTo(20f, 13f)
            quadTo(20f, 16f, 17f, 16f)
            lineTo(10.5f, 16f)
            lineTo(7f, 19.5f)
            lineTo(7f, 16f)
            quadTo(4f, 16f, 4f, 13f)
            lineTo(4f, 8f)
            quadTo(4f, 5f, 7f, 5f)
            close()
        }
    }

    /** A prompt: a chevron and the cursor. */
    val Terminal: ImageVector by lazy {
        glyph("terminal") {
            moveTo(5f, 7.5f)
            lineTo(9.5f, 12f)
            lineTo(5f, 16.5f)
            moveTo(12.5f, 16.5f)
            lineTo(19f, 16.5f)
        }
    }

    /** A folder. */
    val Files: ImageVector by lazy {
        glyph("files") {
            moveTo(4f, 7.5f)
            quadTo(4f, 6f, 5.5f, 6f)
            lineTo(9.5f, 6f)
            lineTo(11.5f, 8f)
            lineTo(18.5f, 8f)
            quadTo(20f, 8f, 20f, 9.5f)
            lineTo(20f, 16.5f)
            quadTo(20f, 18f, 18.5f, 18f)
            lineTo(5.5f, 18f)
            quadTo(4f, 18f, 4f, 16.5f)
            close()
        }
    }

    /** Two diff lines: one added, one removed. */
    val Changes: ImageVector by lazy {
        glyph("changes") {
            moveTo(4.5f, 8.5f)
            lineTo(9f, 8.5f)
            moveTo(6.75f, 6.25f)
            lineTo(6.75f, 10.75f)
            moveTo(12f, 8.5f)
            lineTo(19.5f, 8.5f)
            moveTo(4.5f, 15.5f)
            lineTo(9f, 15.5f)
            moveTo(12f, 15.5f)
            lineTo(17f, 15.5f)
        }
    }

    /** A screen on its stand. */
    val Display: ImageVector by lazy {
        glyph("display") {
            moveTo(6f, 5f)
            lineTo(18f, 5f)
            quadTo(20f, 5f, 20f, 7f)
            lineTo(20f, 13f)
            quadTo(20f, 15f, 18f, 15f)
            lineTo(6f, 15f)
            quadTo(4f, 15f, 4f, 13f)
            lineTo(4f, 7f)
            quadTo(4f, 5f, 6f, 5f)
            close()
            moveTo(12f, 15f)
            lineTo(12f, 19f)
            moveTo(8.5f, 19f)
            lineTo(15.5f, 19f)
        }
    }

    /** A pane with a second one beside it, on the right. */
    val SplitRight: ImageVector by lazy {
        glyph("split-right") {
            frame()
            moveTo(13.5f, 5f)
            lineTo(13.5f, 19f)
        }
    }

    /** A pane with a second one under it. */
    val SplitDown: ImageVector by lazy {
        glyph("split-down") {
            frame()
            moveTo(4f, 13.5f)
            lineTo(20f, 13.5f)
        }
    }

    /** The pane both split glyphs draw: a rounded square. */
    private fun PathBuilder.frame() {
        moveTo(6f, 5f)
        lineTo(18f, 5f)
        quadTo(20f, 5f, 20f, 7f)
        lineTo(20f, 17f)
        quadTo(20f, 19f, 18f, 19f)
        lineTo(6f, 19f)
        quadTo(4f, 19f, 4f, 17f)
        lineTo(4f, 7f)
        quadTo(4f, 5f, 6f, 5f)
        close()
    }

    private fun glyph(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "newtab.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        ).build()
}
