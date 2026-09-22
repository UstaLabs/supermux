package dev.supermux.terminal.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.supermux.terminal.TerminalColor
import dev.supermux.terminal.TerminalColors
import kotlin.math.roundToInt

/**
 * Everything the terminal surface needs to look like itself: the default colours, the 16 ANSI
 * colours, the font and how much room a line gets.
 *
 * It imports NO app theme on purpose — `:terminal-compose` depends on `:terminal-core` and Compose
 * and nothing else, so a host maps its own design tokens onto this (see `Terminal`).
 *
 * The engine resolves palette indices to RGBA itself, so it needs all 256 entries. [engineColors]
 * builds them from [ansi]: indices 0–15 are [ansi] verbatim, 16–231 are the standard xterm 6×6×6
 * colour cube over the levels `0, 95, 135, 175, 215, 255` (`index = 16 + 36·r + 6·g + b`) and
 * 232–255 are the 24 xterm greys `8 + 10·i`. Programs address those two ranges numerically
 * (`\e[38;5;208m`), so they are NOT a place for taste: only 0–15 are themeable.
 *
 * - [foreground] / [background] replace [TerminalColor.DEFAULT] wherever a cell carries it.
 * - [cursorText] is the colour a filled block cursor draws its glyph in; null means "the cell's own
 *   background", which is the usual inverse-video look.
 * - [selectionForeground] null means "keep the cell's own foreground" (only the background changes).
 * - [selectionHandle] is the colour of the two touch handles a selection grows on a phone, and
 *   [selectionHandleSize] how big they are — big enough to grab with a finger, which is a size in
 *   DP rather than in cells because a fingertip does not get smaller with the font.
 * - [boldBrightensAnsi] applies the old xterm habit of drawing bold ANSI 0–7 with 8–15. Off by
 *   default: Ghostty reports the resolved colour and the weight separately, and doubling the two is
 *   what makes themes look washed out.
 * - [faintAlpha] is the alpha SGR 2 (faint) multiplies the foreground by.
 */
@Immutable
data class TerminalTheme(
    val foreground: Color = Color(0xFFD8DED3),
    val background: Color = Color(0xFF050605),
    val cursor: Color = Color(0xFFC2E58C),
    val cursorText: Color? = null,
    val selectionBackground: Color = Color(0x553E7BD6),
    val selectionForeground: Color? = null,
    val selectionHandle: Color = Color(0xFF3E7BD6),
    val selectionHandleSize: Dp = 12.dp,
    val ansi: List<Color> = DEFAULT_ANSI,
    val fontFamily: FontFamily = FontFamily.Monospace,
    val fontSize: TextUnit = 13.sp,
    /** Cell height as a multiple of the font's natural line height. */
    val lineHeightScale: Float = 1.2f,
    val boldBrightensAnsi: Boolean = false,
    val faintAlpha: Float = 0.55f,
) {
    init {
        require(ansi.size == ANSI_COLORS) { "ansi must have $ANSI_COLORS entries, got ${ansi.size}" }
        require(lineHeightScale > 0f) { "lineHeightScale must be positive" }
        require(faintAlpha in 0f..1f) { "faintAlpha must be in 0..1" }
        require(selectionHandleSize.value > 0f) { "selectionHandleSize must be positive" }
    }

    /**
     * The same colours in the engine's encoding, to hand to `TerminalSession.colors(...)`. Call it
     * whenever the theme changes and BEFORE repainting: cells that carry [TerminalColor.DEFAULT] are
     * resolved by this renderer, but cells that already carry a palette colour were resolved inside
     * the engine and only a `colors(...)` call can change those.
     */
    fun engineColors(): TerminalColors = TerminalColors(
        foreground = foreground.toEngineColor(),
        background = background.toEngineColor(),
        cursor = cursor.toEngineColor(),
        palette = palette256(ansi),
    )

    companion object {
        const val ANSI_COLORS: Int = 16

        /**
         * The classic xterm 16. Deliberately neutral: a host that wants its own palette passes one,
         * and no supermux design token leaks into this package.
         */
        val DEFAULT_ANSI: List<Color> = listOf(
            Color(0xFF000000), Color(0xFFCD0000), Color(0xFF00CD00), Color(0xFFCDCD00),
            Color(0xFF0000EE), Color(0xFFCD00CD), Color(0xFF00CDCD), Color(0xFFE5E5E5),
            Color(0xFF7F7F7F), Color(0xFFFF0000), Color(0xFF00FF00), Color(0xFFFFFF00),
            Color(0xFF5C5CFF), Color(0xFFFF00FF), Color(0xFF00FFFF), Color(0xFFFFFFFF),
        )

        /** The six levels of the xterm 6×6×6 cube (indices 16–231). */
        private val CUBE_LEVELS = intArrayOf(0, 95, 135, 175, 215, 255)

        /**
         * 256 engine colours: [ansi] (16) + the xterm cube (216) + the xterm greys (24). See the
         * class documentation for why only the first 16 are themeable.
         */
        fun palette256(ansi: List<Color>): List<Long> {
            require(ansi.size == ANSI_COLORS) { "ansi must have $ANSI_COLORS entries" }
            val palette = ArrayList<Long>(TerminalColor.PALETTE_SIZE)
            ansi.mapTo(palette) { it.toEngineColor() }
            for (red in CUBE_LEVELS) {
                for (green in CUBE_LEVELS) {
                    for (blue in CUBE_LEVELS) palette += TerminalColor.rgba(red, green, blue)
                }
            }
            for (step in 0 until 24) {
                val level = 8 + step * 10
                palette += TerminalColor.rgba(level, level, level)
            }
            check(palette.size == TerminalColor.PALETTE_SIZE)
            return palette
        }
    }
}

/** This colour in the engine's `0xRRGGBBAA` encoding (never [TerminalColor.DEFAULT]). */
fun Color.toEngineColor(): Long = TerminalColor.rgba(
    (red * 255f).roundToInt().coerceIn(0, 255),
    (green * 255f).roundToInt().coerceIn(0, 255),
    (blue * 255f).roundToInt().coerceIn(0, 255),
    (alpha * 255f).roundToInt().coerceIn(0, 255),
)

/**
 * An engine colour as a Compose one. [TerminalColor.DEFAULT] (and anything malformed, which only a
 * broken binding could produce) becomes [fallback] — never a silent black.
 */
fun engineColorToCompose(color: Long, fallback: Color): Color =
    if (!TerminalColor.isValid(color) || TerminalColor.isDefault(color)) {
        fallback
    } else {
        Color(
            red = TerminalColor.red(color),
            green = TerminalColor.green(color),
            blue = TerminalColor.blue(color),
            alpha = TerminalColor.alpha(color),
        )
    }
