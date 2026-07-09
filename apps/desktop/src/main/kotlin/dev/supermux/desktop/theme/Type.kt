package dev.supermux.desktop.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

val GeistFontFamily = FontFamily(
    Font(resource = "fonts/geist_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/geist_medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fonts/geist_semibold.ttf", weight = FontWeight.SemiBold),
    Font(resource = "fonts/geist_bold.ttf", weight = FontWeight.Bold),
)

val MonoFontFamily = FontFamily(
    Font(resource = "fonts/geist_mono_regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fonts/geist_mono_medium.ttf", weight = FontWeight.Medium),
)

/**
 * Calm-Premium Geist type scale. Deliberate hierarchy with tight tracking for
 * display/title styles and generous line-heights for reading text.
 *
 * MonoFontFamily is kept for code/terminal contexts (not wired here —
 * apply it locally where needed, e.g. path labels and terminal output).
 */
fun supermuxTypography(): Typography {
    val sans = GeistFontFamily
    return Typography(
        // Screen titles / empty states — tight tracking, SemiBold weight
        headlineSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = (-0.44).sp, // ≈ -0.02em at 22sp
        ),
        // Chat header session name
        titleLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            letterSpacing = (-0.27).sp, // ≈ -0.015em
        ),
        // List row session names
        titleMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            letterSpacing = (-0.16).sp,
        ),
        // Assistant prose — generous line height for readability.
        // Reading styles sit AT the M3 baseline (not below): the compact feel comes from
        // titles/labels, while body text stays legible on low-PPD displays (DeX on glasses).
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        // Secondary text
        bodyMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        // Buttons
        labelLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 0.sp,
        ),
        // Pills / meta
        labelMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 0.sp,
        ),
        // Timestamps / path labels (use MonoFontFamily locally for path/code content)
        labelSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 0.sp,
        ),
        // Remaining styles — keep Geist, retain M3 defaults for size/weight
        displayLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 57.sp),
        displayMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 45.sp),
        displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 36.sp),
        headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 32.sp),
        headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 28.sp),
        titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    )
}
