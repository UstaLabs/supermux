// A file tab for a file the text editor cannot hold: an image or a video is PREVIEWED from its bytes
// (`/fs/raw`), anything else binary gets a card that hands the file to the OS (a PDF opens in the
// system viewer). The editor's document store never sees these files.
package dev.supermux.ui.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.DirState
import dev.supermux.fs.FileSystemService
import dev.supermux.fs.pathLeaf
import dev.supermux.proto.Attachment
import dev.supermux.media.AttachmentMedia
import dev.supermux.ui.chat.DecodedImage
import dev.supermux.ui.chat.ImageLightbox
import dev.supermux.ui.chat.InlineVideo
import dev.supermux.ui.chat.rememberDecodedImage
import dev.supermux.ui.chat.saveOrOpenAttachment
import dev.supermux.ui.files.parentOf
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.Space
import dev.supermux.ui.work.handOverAsTapped
import dev.supermux.ui.work.rememberDownloads
import dev.supermux.work.Download
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** What a file tab shows instead of the text editor, decided by the file's extension. */
enum class FilePreviewKind { Image, Video }

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv")

/** The preview for [path], or null for a file the text editor opens (SVG included: it is text). */
fun filePreviewKind(path: String): FilePreviewKind? {
    val ext = pathLeaf(path).substringAfterLast('.', "").lowercase()
    return when (ext) {
        in IMAGE_EXTENSIONS -> FilePreviewKind.Image
        in VIDEO_EXTENSIONS -> FilePreviewKind.Video
        else -> null
    }
}

/** `12 B`, `3.4 KB`, `1.2 MB` — one decimal above a kilobyte. */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    val tenths = kotlin.math.round(v * 10).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[i]}"
}

/**
 * The on-disk identity of [absPath] (mtime + size) from its folder's live snapshot, so a preview
 * reloads when an agent rewrites the file. Null without a host file system (the preview then loads
 * once) or while the folder has not answered.
 */
@Composable
private fun rememberFileVersion(fileSystem: FileSystemService?, absPath: String): String? {
    val dir = parentOf(absPath) ?: return null
    if (fileSystem == null) return null
    DisposableEffect(fileSystem, dir) {
        val sub = fileSystem.subscribe(dir)
        onDispose { sub.close() }
    }
    val state by remember(fileSystem, dir) { fileSystem.dir(dir) }.collectAsState()
    val snap = (state as? DirState.Ready)?.snap ?: return null
    val name = pathLeaf(absPath)
    val entry = snap.entries.firstOrNull { it.name == name } ?: return "gone"
    return "${entry.mtime}:${entry.size}"
}

/**
 * The preview pane for [absPath], a file [filePreviewKind] recognised. [loadBytes] reads it from
 * the host (`/fs/raw`).
 */
@Composable
fun BinaryFilePreview(
    absPath: String,
    kind: FilePreviewKind,
    loadBytes: suspend (String) -> Result<ByteArray>,
    fileSystem: FileSystemService?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val version = rememberFileVersion(fileSystem, absPath)
    var bytes by remember(absPath) { mutableStateOf<ByteArray?>(null) }
    var error by remember(absPath) { mutableStateOf<String?>(null) }
    LaunchedEffect(absPath, version) {
        if (version == "gone") { error = "File not found"; return@LaunchedEffect }
        loadBytes(absPath)
            .onSuccess { bytes = it; error = null }
            .onFailure { error = it.message?.takeIf(String::isNotBlank) ?: "Couldn't load the file" }
    }
    val name = pathLeaf(absPath)
    val mime = LocalPlatform.current.files.probeMime(name)

    Box(
        modifier.fillMaxSize().background(cs.surfaceContainerLowest).testTag("editor_binary_preview"),
        contentAlignment = Alignment.Center,
    ) {
        val data = bytes
        when {
            error != null && data == null -> PreviewMessage(error!!)
            data == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
            kind == FilePreviewKind.Image -> ImagePreview(name, mime, data)
            kind == FilePreviewKind.Video -> Box(Modifier.fillMaxWidth().padding(Space.lg), contentAlignment = Alignment.Center) {
                // The chat's inline player, fed our bytes: `file_id` is only its key and staging name.
                InlineVideo(
                    Attachment(file_id = absPath, kind = "video", mime = mime, size = data.size.toLong(), name = name),
                    media = AttachmentMedia.fromBytes { data },
                )
            }
        }
    }
}

@Composable
private fun ImagePreview(name: String, mime: String, data: ByteArray) {
    val cs = MaterialTheme.colorScheme
    var lightbox by remember { mutableStateOf(false) }
    when (val decoded = rememberDecodedImage(data)) {
        DecodedImage.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
        DecodedImage.Failed -> PreviewMessage("Couldn't decode this image")
        is DecodedImage.Ready -> {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Inside: never blown up past its own size, shrunk to fit the pane.
                Image(
                    painter = decoded.painter,
                    contentDescription = name,
                    contentScale = ContentScale.Inside,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(Space.lg)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { lightbox = true }
                        .testTag("editor_image_preview"),
                )
                Text(
                    "${decoded.width} × ${decoded.height} · ${formatFileSize(data.size.toLong())}",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = Space.sm),
                )
            }
            if (lightbox) ImageLightbox(decoded.painter, name, mime, data, onDismiss = { lightbox = false })
        }
    }
}

@Composable
private fun PreviewMessage(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(Space.xl),
    )
}

/**
 * A binary file with no in-app preview (the text reader refused it): its name, and a button that
 * hands the file to the OS — open in the system viewer on a phone, save-and-open on a desktop.
 *
 * With [download] (#95) the button downloads the file as one of the fleet's downloads: closing the
 * tab lets go of it and it carries on, waiting in the activity bar; still open when it finishes,
 * the FILE is handed over at once, as a tap always did (R1). The button says "Downloading…" while
 * it runs. Without one, or when it answers null (a host that keeps no downloads), the bytes path
 * stays: the whole file read on this tab's coroutine.
 */
@Composable
fun BinaryFileCard(
    absPath: String,
    loadBytes: suspend (String) -> Result<ByteArray>,
    modifier: Modifier = Modifier,
    download: ((String) -> Pair<String, DownloadSource>?)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val pointer = LocalPointerAvailable.current
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloads()
    var busy by remember(absPath) { mutableStateOf(false) }
    // The download this card started last, by its key: a host file's key is new with every start
    // (the editor does not know the file's version), so the card keeps its own.
    var key by remember(absPath) { mutableStateOf<String?>(null) }
    // This card waits for that download: one tap, one hand-over.
    var waiting by remember(absPath) { mutableStateOf(false) }
    val running by remember(downloads, key) { downloads.items.map { all -> all.isRunning(key) } }
        .collectAsState(downloads.items.value.isRunning(key))
    val name = pathLeaf(absPath)
    Column(
        modifier.fillMaxSize().padding(Space.xl).testTag("editor_binary_card"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterVertically),
    ) {
        Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(40.dp))
        Text(name, color = cs.onSurface, fontSize = 14.sp, textAlign = TextAlign.Center)
        Text("Binary file — no text to show", color = cs.onSurfaceVariant, fontSize = 12.sp)
        FilledTonalButton(
            enabled = !busy && !waiting && !running,
            onClick = {
                val started = download?.invoke(absPath)
                if (started != null) {
                    val (startedKey, source) = started
                    key = startedKey
                    waiting = true
                    // The card's scope holds only the claim: closing the tab lets go, the download goes on.
                    scope.launch {
                        try {
                            downloadHostFile(downloads, startedKey, source, name, platform, pointer)
                        } finally {
                            waiting = false
                        }
                    }
                    return@FilledTonalButton
                }
                busy = true
                scope.launch {
                    loadBytes(absPath)
                        .onSuccess { saveOrOpenAttachment(platform, pointer, name, platform.files.probeMime(name), it) }
                        .onFailure { platform.notices.show(it.message?.takeIf(String::isNotBlank) ?: "Couldn't load the file") }
                    busy = false
                }
            },
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).testTag("editor_binary_open"),
        ) {
            Text(
                when {
                    running -> "Downloading…"
                    pointer -> "Save and open…"
                    else -> "Open"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Whether the download [key] is listed and still running. */
private fun List<Download>.isRunning(key: String?): Boolean =
    any { it.key == key && it.state is DownloadState.Running }

/**
 * Start (or join) the host file [name]'s download [key] in [downloads] and, while this caller is
 * still here when it finishes, hand it over as a tap does (R1). Leaving releases the claim: the
 * download carries on and waits in the bar. A failure while it waits is said here, in the host's
 * words, as the bytes path said it; the bar's Cancel is no failure.
 */
private suspend fun downloadHostFile(
    downloads: DownloadCenter,
    key: String,
    source: DownloadSource,
    name: String,
    platform: Platform,
    pointer: Boolean,
) {
    val claim = downloads.start(key, name, platform.files.probeMime(name), DownloadPurpose.File, source)
    try {
        val file = claim.await()
        val item = downloads.items.value.firstOrNull { it.key == key }
        val state = item?.state
        when {
            file == null -> if (state is DownloadState.Failed) platform.notices.show(state.message)
            item != null -> handOverAsTapped(platform, pointer, downloads, item)
        }
    } finally {
        claim.release()
    }
}
