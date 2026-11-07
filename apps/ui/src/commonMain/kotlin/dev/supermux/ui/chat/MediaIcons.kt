// The glyphs of the media viewer and the video controls: thin, round-capped strokes on a 24-unit
// grid, the way the Mac's own symbols are drawn. Material's filled icons read as Android there.
package dev.supermux.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal object MediaIcons {
    val Close: ImageVector by lazy {
        glyph("Close") {
            line(7f, 7f, 17f, 17f)
            line(17f, 7f, 7f, 17f)
        }
    }

    val ChevronLeft: ImageVector by lazy {
        glyph("ChevronLeft") {
            moveTo(14.5f, 5.5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 18.5f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        glyph("ChevronRight") {
            moveTo(9.5f, 5.5f)
            lineTo(16f, 12f)
            lineTo(9.5f, 18.5f)
        }
    }

    val ZoomIn: ImageVector by lazy {
        glyph("ZoomIn") {
            magnifier()
            line(7.5f, 10.5f, 13.5f, 10.5f)
            line(10.5f, 7.5f, 10.5f, 13.5f)
        }
    }

    val ZoomOut: ImageVector by lazy {
        glyph("ZoomOut") {
            magnifier()
            line(7.5f, 10.5f, 13.5f, 10.5f)
        }
    }

    /** An arrow down into a tray. */
    val Save: ImageVector by lazy {
        glyph("Save") {
            line(12f, 4f, 12f, 14.5f)
            moveTo(8f, 10.5f)
            lineTo(12f, 14.5f)
            lineTo(16f, 10.5f)
            moveTo(5f, 13.5f)
            lineTo(5f, 17f)
            quadTo(5f, 19f, 7f, 19f)
            lineTo(17f, 19f)
            quadTo(19f, 19f, 19f, 17f)
            lineTo(19f, 13.5f)
        }
    }

    val Play: ImageVector by lazy {
        glyph("Play", filled = true) {
            moveTo(8f, 5.5f)
            lineTo(18.5f, 12f)
            lineTo(8f, 18.5f)
            close()
        }
    }

    val Pause: ImageVector by lazy {
        glyph("Pause", filled = true) {
            moveTo(7.5f, 5.5f)
            lineTo(10.5f, 5.5f)
            lineTo(10.5f, 18.5f)
            lineTo(7.5f, 18.5f)
            close()
            moveTo(13.5f, 5.5f)
            lineTo(16.5f, 5.5f)
            lineTo(16.5f, 18.5f)
            lineTo(13.5f, 18.5f)
            close()
        }
    }

    val Volume: ImageVector by lazy {
        glyph("Volume") {
            speaker()
            moveTo(15.5f, 9f)
            quadTo(17.5f, 12f, 15.5f, 15f)
            moveTo(18f, 6.5f)
            quadTo(21.5f, 12f, 18f, 17.5f)
        }
    }

    val Muted: ImageVector by lazy {
        glyph("Muted") {
            speaker()
            line(16f, 9.5f, 20.5f, 14.5f)
            line(20.5f, 9.5f, 16f, 14.5f)
        }
    }

    /** Two arrows out to opposite corners. */
    val Fullscreen: ImageVector by lazy {
        glyph("Fullscreen") {
            moveTo(14f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 10f)
            line(19.5f, 4.5f, 13.5f, 10.5f)
            moveTo(10f, 19.5f)
            lineTo(4.5f, 19.5f)
            lineTo(4.5f, 14f)
            line(4.5f, 19.5f, 10.5f, 13.5f)
        }
    }

    /** A box with an arrow leaving it. */
    val OpenExternal: ImageVector by lazy {
        glyph("OpenExternal") {
            moveTo(11f, 6f)
            lineTo(7f, 6f)
            quadTo(5f, 6f, 5f, 8f)
            lineTo(5f, 17f)
            quadTo(5f, 19f, 7f, 19f)
            lineTo(16f, 19f)
            quadTo(18f, 19f, 18f, 17f)
            lineTo(18f, 13f)
            line(11.5f, 12.5f, 19.5f, 4.5f)
            moveTo(14f, 4.5f)
            lineTo(19.5f, 4.5f)
            lineTo(19.5f, 10f)
        }
    }
}

private fun PathBuilder.line(x1: Float, y1: Float, x2: Float, y2: Float) {
    moveTo(x1, y1)
    lineTo(x2, y2)
}

/** A lens with its handle to the bottom right. */
private fun PathBuilder.magnifier() {
    moveTo(17f, 10.5f)
    arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 4f, y1 = 10.5f)
    arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 17f, y1 = 10.5f)
    close()
    line(15.2f, 15.2f, 20f, 20f)
}

private fun PathBuilder.speaker() {
    moveTo(4f, 9.5f)
    lineTo(7.5f, 9.5f)
    lineTo(12f, 5.5f)
    lineTo(12f, 18.5f)
    lineTo(7.5f, 14.5f)
    lineTo(4f, 14.5f)
    close()
}

/** A stroked glyph, or with [filled] a filled one whose corners the stroke rounds. Tinted by [androidx.compose.material3.Icon]. */
private fun glyph(name: String, filled: Boolean = false, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = if (filled) 1.5f else 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
        .build()
