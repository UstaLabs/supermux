package dev.supermux.android.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.supermux.android.R

val GeistFontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
    Font(R.font.geist_bold, FontWeight.Bold),
)

val MonoFontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
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
            fontSize = 15.sp,
            letterSpacing = (-0.15).sp,
        ),
        // Assistant prose — generous line height for readability
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 15.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp,
        ),
        // Secondary text
        bodyMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        // Buttons
        labelLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            letterSpacing = 0.sp,
        ),
        // Pills / meta
        labelMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 11.5.sp,
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
