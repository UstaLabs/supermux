// Markdown images that live on the HOST rather than the web: `![](./shot.png)` in a README,
// `![](/home/u/p/out.mp4)` in an agent's reply. They are read through the host's own file route
// (`/fs/raw`), so — unlike a remote URL — loading one leaks nothing; an image paints inline and a
// video gets the chat's inline player.
package dev.supermux.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.ContentScale
import dev.supermux.proto.Attachment
import dev.supermux.ui.MdBlock
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.editor.filePreviewKind
import dev.supermux.ui.platform.LocalPlatform

/**
 * Where a markdown surface may read host files from. [baseDir] (absolute) resolves a relative
 * image path — the `.md` file's folder in the preview, the session's workdir in chat; null leaves
 * relative paths as links. [load] reads a file's bytes by absolute path.
 */
class MarkdownFiles(
    val baseDir: String?,
    val load: suspend (absPath: String) -> Result<ByteArray>,
)

/** Null (the default) = no host to read from: local image paths stay link lines. */
val LocalMarkdownFiles = staticCompositionLocalOf<MarkdownFiles?> { null }

private val schemeRegex = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * The absolute host path an image [url] names, or null when it is not a host file: a web or `data:`
 * URL, a bare `#anchor`, or a relative path with no [baseDir]. `file://` is unwrapped, `?query` and
 * `#fragment` are dropped, `%XX` escapes decoded, and `.` / `..` segments collapsed. Pure; tested.
 */
fun resolveMarkdownMediaPath(url: String, baseDir: String?): String? {
    val u = url.trim()
    if (u.isEmpty() || u.startsWith("#")) return null
    val raw = when {
        u.startsWith("file://", ignoreCase = true) -> u.substring("file://".length)
        schemeRegex.containsMatchIn(u) -> return null
        else -> u
    }
    val path = percentDecode(raw.substringBefore('#').substringBefore('?'))
    if (path.isEmpty()) return null
    val joined = when {
        path.startsWith("/") -> path
        baseDir.isNullOrEmpty() || !baseDir.startsWith("/") -> return null
        else -> baseDir.trimEnd('/') + "/" + path
    }
    val out = ArrayList<String>()
    for (seg in joined.split('/')) {
        when (seg) {
            "", "." -> Unit
            ".." -> if (out.isNotEmpty()) out.removeAt(out.lastIndex)
            else -> out.add(seg)
        }
    }
    return "/" + out.joinToString("/")
}

private fun percentDecode(s: String): String {
    if ('%' !in s) return s
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        val hex = if (ch == '%' && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
        if (hex != null) {
            bytes.add(hex.toByte())
            i += 3
        } else {
            ch.toString().encodeToByteArray().forEach(bytes::add)
            i++
        }
    }
    return bytes.toByteArray().decodeToString()
}

/** A host-file image or video at [absPath]; a failed read falls back to the failure link line. */
@Composable
internal fun LocalMarkdownMedia(
    image: MdBlock.Image,
    absPath: String,
    files: MarkdownFiles,
    onFailedClick: () -> Unit,
) {
    val name = absPath.substringAfterLast('/')
    val mime = LocalPlatform.current.files.probeMime(name)
    if (filePreviewKind(absPath) == FilePreviewKind.Video) {
        // The chat's inline player: it reads the bytes only once play is pressed.
        InlineVideo(
            Attachment(file_id = absPath, kind = "video", mime = mime, name = image.alt.ifEmpty { name }),
            loadBytes = { files.load(it).getOrNull() },
        )
        return
    }
    var bytes by remember(absPath) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(absPath) { mutableStateOf(false) }
    LaunchedEffect(absPath, files) {
        files.load(absPath).onSuccess { bytes = it }.onFailure { failed = true }
    }
    val data = bytes
    when {
        failed -> MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = { onFailedClick() })
        data == null -> MdImageLoadingBox("md_image_loading")
        else -> when (val decoded = rememberDecodedImage(data)) {
            DecodedImage.Loading -> MdImageLoadingBox("md_image_loading")
            DecodedImage.Failed -> MarkdownImageLinkLine(image, loadFailed = true, onOpenUrl = { onFailedClick() })
            is DecodedImage.Ready -> {
                var lightbox by remember(absPath) { mutableStateOf(false) }
                ShrinkOnlyImage(
                    width = decoded.width,
                    height = decoded.height,
                    tag = "md_image",
                    onClick = { lightbox = true },
                ) { mod ->
                    Image(
                        painter = decoded.painter,
                        contentDescription = image.alt.ifEmpty { name },
                        contentScale = ContentScale.Fit,
                        modifier = mod,
                    )
                }
                if (lightbox) ImageLightbox(decoded.painter, name, mime, data, onDismiss = { lightbox = false })
            }
        }
    }
}
