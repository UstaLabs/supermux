package dev.supermux.ui.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.supermux.terminal.compose.TerminalTheme
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.supermuxLight
import dev.supermux.ui.theme.LocalPanes
import kotlinx.coroutines.flow.flowOf

/** Whether the terminal follows the app's light/dark mode or stays one of the two. */
enum class TerminalAppearance { FOLLOW_APP, DARK, LIGHT }

/**
 * The terminal's colours, separate from the font and size [TerminalTheme] also carries.
 *
 * Every place that paints behind the grid (the pane, the tab strip's body, the renderer itself)
 * reads THIS, so a pane can never show a dark frame around a light grid while the setting changes.
 */
@Immutable
data class TerminalPalette(
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selectionBackground: Color,
    val selectionHandle: Color,
    val ansi: List<Color>,
) {
    fun toTheme(): TerminalTheme = TerminalTheme(
        foreground = foreground,
        background = background,
        cursor = cursor,
        selectionBackground = selectionBackground,
        selectionHandle = selectionHandle,
        ansi = ansi,
    )
}

/**
 * The 16 ANSI colours on a light background (GitHub Light's terminal palette). The xterm defaults
 * are tuned for black: their yellow and both whites disappear on paper, so "white" here is a mid
 * grey and every colour is dark enough to read as text. Only 0–15 change — programs that pick from
 * the 256-colour cube or send truecolor keep exactly what they asked for.
 */
val LIGHT_TERMINAL_ANSI: List<Color> = listOf(
    Color(0xFF24292F), Color(0xFFCF222E), Color(0xFF116329), Color(0xFF4D2D00),
    Color(0xFF0969DA), Color(0xFF8250DF), Color(0xFF1B7C83), Color(0xFF6E7781),
    Color(0xFF57606A), Color(0xFFA40E26), Color(0xFF1A7F37), Color(0xFF633C01),
    Color(0xFF218BFF), Color(0xFFA475F9), Color(0xFF3192AA), Color(0xFF8C959F),
)

private val lightTones = supermuxLight()

/** The light terminal: the light theme's paper and ink, whatever mode the app itself is in. */
val LightTerminalPalette: TerminalPalette = TerminalPalette(
    dark = false,
    background = Color(lightTones.code),
    foreground = Color(lightTones.foreground),
    cursor = Color(0xFF1F6F5F),
    selectionBackground = Color(0x400969DA),
    selectionHandle = Color(0xFF0969DA),
    ansi = LIGHT_TERMINAL_ANSI,
)

/**
 * The dark terminal: the pane tones' own terminal colours (a shade lighter than pure black when the
 * app around it is light) and the renderer's xterm defaults for everything else — exactly what the
 * terminal looked like before it could be light.
 */
private fun darkTerminalPalette(terminal: Int, terminalForeground: Int): TerminalPalette {
    val defaults = TerminalTheme()
    return TerminalPalette(
        dark = true,
        background = Color(terminal),
        foreground = Color(terminalForeground),
        cursor = defaults.cursor,
        selectionBackground = defaults.selectionBackground,
        selectionHandle = defaults.selectionHandle,
        ansi = defaults.ansi,
    )
}

/** Which palette [mode] means inside an app that is currently [appDark]. */
fun TerminalAppearance.isDark(appDark: Boolean): Boolean = when (this) {
    TerminalAppearance.FOLLOW_APP -> appDark
    TerminalAppearance.DARK -> true
    TerminalAppearance.LIGHT -> false
}

/** The terminal palette for this point in the tree: the stored setting applied to the app's mode. */
@Composable
fun terminalPalette(): TerminalPalette {
    val prefs = LocalUiPrefsOrNull.current
    val mode by remember(prefs) { prefs?.terminalAppearance ?: flowOf(TerminalAppearance.FOLLOW_APP) }
        .collectAsState(TerminalAppearance.FOLLOW_APP)
    val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val panes = LocalPanes.current
    val dark = mode.isDark(appDark)
    return remember(dark, panes.terminal, panes.terminalForeground) {
        if (dark) darkTerminalPalette(panes.terminal, panes.terminalForeground) else LightTerminalPalette
    }
}
