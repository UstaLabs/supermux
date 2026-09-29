package dev.supermux.editor.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.editor.compose.resources.Res
import dev.supermux.editor.compose.resources.jetbrains_mono_bold
import dev.supermux.editor.compose.resources.jetbrains_mono_italic
import dev.supermux.editor.compose.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * The monospace face this package ships: JetBrains Mono 2.304 as a Compose resource inside the
 * artifact (OFL-1.1, see `THIRD-PARTY-NOTICES.md`). The same approach and the same reason as
 * terminal-compose's `packagedTerminalFontFamily`: in the browser skiko has no system fonts at all,
 * so `FontFamily.Monospace` silently falls back to a proportional face there. Shipping the file is
 * the only answer that does not depend on the host.
 *
 * Regular, Bold (headings, `fontWeight` token styles) and Italic (comments, emphasis). CJK, emoji
 * and other scripts outside the face fall back to the platform's fonts.
 *
 * While the resource is loading (web, Android), Compose returns a placeholder that resolves to the
 * platform default; the surface re-measures when the real face arrives (the family is part of every
 * layout cache key). On the JVM it loads synchronously.
 */
@Composable
fun packagedEditorFontFamily(): FontFamily {
    val regular = Font(Res.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal)
    val bold = Font(Res.font.jetbrains_mono_bold, FontWeight.Bold, FontStyle.Normal)
    val italic = Font(Res.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic)
    // Remembered: the family is compared by value in every layout cache key, and a fresh instance
    // per recomposition would empty the caches continuously.
    return remember(regular, bold, italic) { FontFamily(regular, bold, italic) }
}
