package dev.supermux.desktop.platform

import dev.supermux.ui.platform.PageDevice
import dev.supermux.ui.platform.pageAccepts
import java.nio.file.Path

/**
 * What a web page in a Browser tab reaches on a desktop (#157). Its downloads land in the app's own downloads folder,
 * beside the media cache, for the downloads list to open or save; its file inputs get the native open dialog; the OS
 * asks for the camera and mic itself (macOS's TCC) when the engine first uses them.
 */
internal class DesktopPageDevice(private val probeMime: (String) -> String) : PageDevice {
    override val downloadsDir: String by lazy {
        val os = DesktopOs.current
        val base = mediaCacheBase(os, System::getenv, dev.supermux.desktop.host.SystemOsEnv.home.toString())
        val dir = if (os == DesktopOs.WINDOWS) {
            Path.of(base, "supermux-desktop", "cache", "downloads")
        } else {
            Path.of(base, "supermux-desktop", "downloads")
        }
        runCatching { ownerOnlyDir(dir) }.onFailure { println("[downloads] cannot prepare $dir: $it") }
        dir.toString()
    }

    override suspend fun pickForPage(acceptTypes: List<String>, multiple: Boolean, title: String): List<String>? =
        DesktopFileDialogs.open(title, acceptTypes, multiple) { name -> pageAccepts(acceptTypes, name, probeMime(name)) }
}

/**
 * A page input's [accept] list as the pattern Windows' open dialog filters by: `*.png;*.jpg;*.pdf`. Null, so nothing is
 * hidden, where any entry has no extensions to stand for it (`application/x-custom`) or there is no list.
 */
internal fun windowsPattern(accept: List<String>): String? {
    val wanted = accept.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return null
    val extensions = wanted.map { a ->
        when {
            a.startsWith(".") -> listOf(a.drop(1))
            a.endsWith("/*") -> MIME_FAMILIES[a.dropLast(2)] ?: return null
            else -> MIME_EXTENSIONS[a] ?: return null
        }
    }.flatten().distinct()
    return extensions.joinToString(";") { "*.$it" }
}

private val MIME_FAMILIES = mapOf(
    "image" to listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic", "heif", "avif"),
    "video" to listOf("mp4", "mov", "m4v", "webm", "mkv", "avi"),
    "audio" to listOf("mp3", "wav", "m4a", "aac", "ogg", "oga", "flac", "opus", "weba"),
    "text" to listOf("txt", "md", "csv", "tsv", "html", "htm", "css", "js", "json", "xml"),
)

private val MIME_EXTENSIONS = mapOf(
    "application/pdf" to listOf("pdf"),
    "application/json" to listOf("json"),
    "application/zip" to listOf("zip"),
    "application/xml" to listOf("xml"),
    "text/plain" to listOf("txt"),
    "text/csv" to listOf("csv"),
    "text/html" to listOf("html", "htm"),
    "image/png" to listOf("png"),
    "image/jpeg" to listOf("jpg", "jpeg"),
    "image/gif" to listOf("gif"),
    "image/webp" to listOf("webp"),
    "image/svg+xml" to listOf("svg"),
)
