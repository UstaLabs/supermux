// File-type badges for the Files tree: a short label on a coloured 16×16 box, chosen by extension.
// Folders don't use a badge — the row draws a Folder / FolderOpen icon instead.
package dev.supermux.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class FileBadge(val label: String, val color: Color)

private val Neutral = Color(0xFF8B8B85)

private val byExtension: Map<String, FileBadge> = buildMap {
    fun put(vararg exts: String, label: String, argb: Long) { for (e in exts) put(e, FileBadge(label, Color(argb))) }
    put("kt", "kts", label = "K", argb = 0xFF7F52FF)
    put("ts", "tsx", label = "TS", argb = 0xFF3178C6)
    put("js", "mjs", "cjs", label = "JS", argb = 0xFFE8C44A)
    put("json", label = "{}", argb = 0xFFE2A03F)
    put("md", label = "MD", argb = 0xFF5B6B7F)
    put("swift", label = "S", argb = 0xFFF05138)
    put("py", label = "PY", argb = 0xFF3776AB)
    put("rs", label = "RS", argb = 0xFFCE422B)
    put("go", label = "GO", argb = 0xFF00ADD8)
    put("java", label = "J", argb = 0xFFB07219)
    put("html", label = "<>", argb = 0xFFE34C26)
    put("css", "scss", label = "#", argb = 0xFF563D7C)
    put("yml", "yaml", "toml", label = "⚙", argb = 0xFF6D8086)
    put("sh", "zsh", "bash", label = "$", argb = 0xFF4EAA25)
    put("gradle", label = "G", argb = 0xFF02303A)
    put("png", "jpg", "jpeg", "gif", "svg", "webp", label = "▣", argb = 0xFFA074C4)
    put("lock", label = "L", argb = 0xFF8B8B85)
}

private val FolderBadge = FileBadge("▸", Neutral)
private val Dot = FileBadge("·", Neutral)

/** The badge for an entry named [name]. Dotfiles get "•"; unknown extensions their first letter. */
fun fileBadge(name: String, isDir: Boolean): FileBadge {
    if (isDir) return FolderBadge
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else null
    byExtension[ext]?.let { return it }
    if (name.startsWith(".")) return FileBadge("•", Neutral)
    val first = ext?.firstOrNull { it.isLetterOrDigit() } ?: return Dot
    return FileBadge(first.uppercase(), Neutral)
}

@Composable
internal fun FileBadgeBox(badge: FileBadge, alpha: Float, modifier: Modifier = Modifier) {
    Box(
        // Decorative: the row already speaks the file name.
        modifier.clearAndSetSemantics {}.size(16.dp).background(badge.color.copy(alpha = badge.color.alpha * alpha), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            badge.label,
            color = Color.White.copy(alpha = alpha),
            fontSize = if (badge.label.length > 1) 8.sp else 9.sp,
            lineHeight = 9.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
        )
    }
}
