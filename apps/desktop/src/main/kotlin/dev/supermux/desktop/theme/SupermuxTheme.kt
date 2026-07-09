package dev.supermux.desktop.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.ui.SupermuxColors
import dev.supermux.ui.supermuxDark
import dev.supermux.ui.supermuxLight

/**
 * Slimmed pane palette. Only the genuinely-fixed app tones should be read from here
 * (`code`, `terminal`, `terminalForeground`, `warning`) — everything else now comes
 * through `MaterialTheme.colorScheme` so it follows light/dark.
 *
 * Desktop has no Material You / dynamic color: the branded OKLCH palette is the only palette.
 */
val LocalPanes = staticCompositionLocalOf { supermuxDark() }

enum class AppearanceMode { SYSTEM, LIGHT, DARK }

/** M3 shape scale derived from the brand `Radii` (Tokens.kt) + M3's 4dp/28dp ends. */
val SupermuxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Builds a fully-specified brand [ColorScheme] from the OKLCH brand palette. Every role
 * is assigned, so the dynamic-color-off "brand" mode is fully branded. `darkColorScheme(...)`
 * is used purely as a builder here — because all roles are supplied explicitly, the
 * light/dark factory defaults never apply.
 *
 * @param c     this mode's brand tones (`supermuxDark` for dark, `supermuxLight` for light)
 * @param other the opposite mode's tones (used only for `inversePrimary`)
 * @param dark  whether this is the dark scheme (a few container tints differ by mode)
 */
private fun buildSupermuxScheme(c: SupermuxColors, other: SupermuxColors, dark: Boolean): ColorScheme {
    val primary = Color(c.primary)
    val onPrimary = Color(c.primaryForeground)
    val background = Color(c.background)
    val foreground = Color(c.foreground)
    val surface = Color(c.card)
    val muted = Color(c.muted)
    val mutedFg = Color(c.mutedForeground)
    val border = Color(c.border)
    val error = Color(c.destructive)
    val warning = Color(c.warning)

    // surfaceContainer ladder ← the existing pane tones (code < chat/header < rail/card < sessionList < muted)
    val scLowest = Color(c.code)
    val scLow = Color(c.chat)        // chat/header collapse here (≈ identical L)
    val scContainer = Color(c.rail)  // rail/card panel tone
    val scHigh = Color(c.sessionList)
    val scHighest = Color(c.muted)

    val primaryContainer = lerp(primary, background, if (dark) 0.78f else 0.85f)
    val onPrimaryContainer = if (dark) primary else lerp(primary, foreground, 0.40f)
    val errorContainer = lerp(error, background, if (dark) 0.80f else 0.86f)
    val onErrorContainer = if (dark) error else lerp(error, foreground, 0.35f)
    val secondary = lerp(primary, mutedFg, 0.55f)
    val secondaryContainer = scHigh
    val tertiary = warning
    val tertiaryContainer = lerp(warning, background, if (dark) 0.80f else 0.86f)
    val onTertiaryContainer = if (dark) warning else lerp(warning, foreground, 0.35f)
    val outlineVariant = lerp(border, background, 0.5f)
    val surfaceBright = if (dark) scHighest else background
    val surfaceDim = if (dark) background else lerp(background, Color.Black, 0.06f)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = Color(other.primary),
        secondary = secondary,
        onSecondary = onPrimary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = foreground,
        tertiary = tertiary,
        onTertiary = Color.Black,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = foreground,
        surface = surface,
        onSurface = foreground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedFg,
        surfaceTint = primary,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = error,
        onError = Color.White,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = border,
        outlineVariant = outlineVariant,
        scrim = Color.Black,
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = scContainer,
        surfaceContainerHigh = scHigh,
        surfaceContainerHighest = scHighest,
        surfaceContainerLow = scLow,
        surfaceContainerLowest = scLowest,
    )
}

/** App-level text-size multiplier bounds (Appearance → Text size). */
const val TEXT_SCALE_MIN = 0.9f
const val TEXT_SCALE_MAX = 1.3f

/**
 * Root theme. Desktop is always the branded OKLCH palette — there is no Material You /
 * dynamic color surface on this platform (see M1 Task 3 brand rule).
 */
@Composable
fun SupermuxTheme(
    appearance: AppearanceMode = AppearanceMode.SYSTEM,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) {
        AppearanceMode.SYSTEM -> isSystemInDarkTheme()
        AppearanceMode.LIGHT -> false
        AppearanceMode.DARK -> true
    }
    val paneTones = if (dark) supermuxDark() else supermuxLight()
    val scheme = if (dark) {
        buildSupermuxScheme(supermuxDark(), supermuxLight(), dark = true)
    } else {
        buildSupermuxScheme(supermuxLight(), supermuxDark(), dark = false)
    }
    val semantics = if (dark) supermuxSemanticsDark() else supermuxSemanticsLight()
    // App-level text scale: multiplies the system fontScale so every sp in the app
    // (type scale AND local fontSize literals) grows together; dp layout is untouched.
    val density = LocalDensity.current
    val scale = textScale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
    val scaledDensity =
        if (scale == 1f) density else Density(density.density, density.fontScale * scale)
    CompositionLocalProvider(
        LocalPanes provides paneTones,
        LocalSemantics provides semantics,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = supermuxTypography(),
            shapes = SupermuxShapes,
            content = content,
        )
    }
}
