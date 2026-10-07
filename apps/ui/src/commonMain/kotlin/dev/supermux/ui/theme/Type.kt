// The one type scale for both apps. Geist + Geist Mono ship as Compose Multiplatform resources
// (`Res.font.*`) from this module, replacing Android's `R.font` and desktop's classpath lookup.
//
// The resource `Font(...)` builder is @Composable, so the families are @Composable getters rather
// than top-level vals. Every call site already reads them inside composition (`fontFamily = ...`),
// so this is source-compatible with the two files this replaces.
package dev.supermux.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.geist_bold
import dev.supermux.ui.resources.geist_medium
import dev.supermux.ui.resources.geist_mono_medium
import dev.supermux.ui.resources.geist_mono_regular
import dev.supermux.ui.resources.geist_regular
import dev.supermux.ui.resources.geist_semibold
import org.jetbrains.compose.resources.Font

val GeistFontFamily: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.geist_regular, FontWeight.Normal),
        Font(Res.font.geist_medium, FontWeight.Medium),
        Font(Res.font.geist_semibold, FontWeight.SemiBold),
        Font(Res.font.geist_bold, FontWeight.Bold),
    )

val GeistMonoFontFamily: FontFamily
    @Composable get() = FontFamily(
        Font(Res.font.geist_mono_regular, FontWeight.Normal),
        Font(Res.font.geist_mono_medium, FontWeight.Medium),
    )

/** Historical name for [GeistMonoFontFamily] — kept because both apps read it at ~40 call sites. */
val MonoFontFamily: FontFamily
    @Composable get() = GeistMonoFontFamily

/**
 * Desktop-compact Geist type scale. Hierarchy matches the brand (tight tracking on
 * titles, readable body), but sizes sit ~1–2sp under the Android/M3 phone baseline so
 * the rail + chat feel native next to the web PWA (`text-sm` ≈ 14px) and typical Mac apps.
 *
 * MonoFontFamily is kept for code/terminal contexts (not wired here —
 * apply it locally where needed, e.g. path labels and terminal output).
 */
@Composable
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

// ---------------------------------------------------------------------------------------------
// Android's touch scale, unchanged. The two apps ship DIFFERENT sizes today (desktop is ~1-2sp
// tighter); folding them into one number is a visible design change, so both scales live here and
// each app's theme wrapper keeps the one it renders today. Cluster A2/B picks the size class up
// from LocalWindowWidthClass and this pair collapses into one call.
// ---------------------------------------------------------------------------------------------
/**
 * Calm-Premium Geist type scale. Deliberate hierarchy with tight tracking for
 * display/title styles and generous line-heights for reading text.
 *
 * MonoFontFamily is kept for code/terminal contexts (not wired here —
 * apply it locally where needed, e.g. path labels and terminal output).
 */
@Composable
fun supermuxTouchTypography(): Typography {
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
