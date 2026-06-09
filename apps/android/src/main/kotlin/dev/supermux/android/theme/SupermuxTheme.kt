package dev.supermux.android.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.supermux.ui.SupermuxColors
import dev.supermux.ui.supermuxDark

private fun Int.toColor() = Color(this)

/** Extra pane colours not in Material's ColorScheme, available via LocalPanes.current. */
val LocalPanes = staticCompositionLocalOf { supermuxDark() }

@Composable
fun SupermuxTheme(content: @Composable () -> Unit) {
    val c = supermuxDark()
    val scheme = darkColorScheme(
        background = c.background.toColor(), onBackground = c.foreground.toColor(),
        surface = c.card.toColor(), onSurface = c.foreground.toColor(),
        primary = c.primary.toColor(), onPrimary = c.primaryForeground.toColor(),
        outline = c.border.toColor(), error = c.destructive.toColor(),
        surfaceVariant = c.muted.toColor(), onSurfaceVariant = c.mutedForeground.toColor(),
    )
    CompositionLocalProvider(LocalPanes provides c) {
        MaterialTheme(colorScheme = scheme, typography = supermuxTypography(), content = content)
    }
}
