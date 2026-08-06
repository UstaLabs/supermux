// Desktop type scale — started as a port of apps/android/.../theme/Type.kt (Geist hierarchy)
// but deliberately COMPACT vs mobile: chat/web desktop targets ~13–14sp body, not M3's 16sp
// phone baseline. Android keeps the larger scale for touch readability.
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
 * Desktop-compact Geist type scale. Hierarchy matches the brand (tight tracking on
 * titles, readable body), but sizes sit ~1–2sp under the Android/M3 phone baseline so
 * the rail + chat feel native next to the web PWA (`text-sm` ≈ 14px) and typical Mac apps.
 *
 * MonoFontFamily is kept for code/terminal contexts (not wired here —
 * apply it locally where needed, e.g. path labels and terminal output).
 */
fun supermuxTypography(): Typography {
    val sans = GeistFontFamily
    return Typography(
        // Screen titles / empty states
        headlineSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            letterSpacing = (-0.36).sp, // ≈ -0.02em at 18sp
        ),
        // Chat header session name
        titleLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            letterSpacing = (-0.22).sp,
        ),
        // List row session names (when using typography)
        titleMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            letterSpacing = (-0.13).sp,
        ),
        // Assistant prose — primary chat reading size (web text-sm parity)
        bodyLarge = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.sp,
        ),
        // Secondary text
        bodyMedium = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Normal,
            fontSize = 13.sp,
            lineHeight = 18.sp,
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
            fontSize = 11.sp,
            letterSpacing = 0.sp,
        ),
        // Timestamps / path labels
        labelSmall = TextStyle(
            fontFamily = sans,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.sp,
        ),
        // Remaining styles — keep Geist; scale display/headline down slightly for desktop chrome
        displayLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 48.sp),
        displayMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 40.sp),
        displaySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 32.sp),
        headlineLarge = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 28.sp),
        headlineMedium = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 24.sp),
        titleSmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
        bodySmall = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp),
    )
}
