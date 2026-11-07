// The rules under the chat's media: which attachments are pictures or clips, every one the chat
// holds in the order the viewer swipes through them, a collage's geometry, and the viewer's
// navigation, keys and swipe-to-close. Pure, so each rule is tested without a UI.
package dev.supermux.ui.chat

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.KeyEventType
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.chatTimeLabel
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.ui.MdBlock
import dev.supermux.ui.accounts.isAccountNotice
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.editor.filePreviewKind
import dev.supermux.ui.editor.formatFileSize
import androidx.compose.ui.unit.dp
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.fs.pathLeaf

/** Whether [att] paints as a picture, plays as a clip, or neither (a file chip). */
fun attachmentMediaKind(att: Attachment): FilePreviewKind? {
    val mime = att.mime ?: ""
    return when {
        att.kind == "photo" || att.kind == "image" || mime.startsWith("image/") -> FilePreviewKind.Image
        att.kind == "video" || att.kind == "video_note" || mime.startsWith("video/") -> FilePreviewKind.Video
        else -> null
    }
}

/** One picture or clip the chat shows — an entry of the viewer's swipe set. */
sealed interface ChatMediaItem {
    /** Unique within one chat: the message, plus which of its media. */
    val key: String
    val entryId: String
    val kind: FilePreviewKind

    /** What the viewer's save names it. */
    val name: String

    data class FromAttachment(
        override val entryId: String,
        val att: Attachment,
        override val kind: FilePreviewKind,
    ) : ChatMediaItem {
        override val key: String get() = attachmentMediaKey(entryId, att.file_id)
        override val name: String get() = att.name ?: att.file_id
    }

    /** A host file an agent's reply shows as `![alt](path)`. */
    data class FromMarkdown(
        override val entryId: String,
        val absPath: String,
        val alt: String,
        override val kind: FilePreviewKind,
    ) : ChatMediaItem {
        override val key: String get() = markdownMediaKey(entryId, absPath)
        override val name: String get() = pathLeaf(absPath)
    }
}

fun attachmentMediaKey(entryId: String, fileId: String): String = "$entryId|att|$fileId"

fun markdownMediaKey(entryId: String, absPath: String): String = "$entryId|md|$absPath"

/**
 * How a timeline row draws a message's text. [TimelineItemRow] switches on this, and the swipe set
 * asks it which texts paint their images, so the two can never disagree about what is on screen.
 */
internal enum class MessageBody { Walkthrough, SupermuxNotice, Inbox, ToSubagent, User, AccountNotice, AgentError, AgentReply }

internal fun messageBody(entry: LogEntry): MessageBody {
    val text = entry.text.orEmpty()
    val inbox = entry.channel == "inbox"
    val isUser = entry.direction == "inbound"
    return when {
        // The walkthrough notice is stored inbound: it is checked before anything that would draw
        // an inbound row as the user's.
        text.startsWith("📖 Walkthrough ready") -> MessageBody.Walkthrough
        inbox && entry.from_session_id == SUPERMUX_SENDER_ID -> MessageBody.SupermuxNotice
        inbox -> MessageBody.Inbox
        isUser && entry.subagent_id != null -> MessageBody.ToSubagent
        isUser -> MessageBody.User
        isAccountNotice(text) -> MessageBody.AccountNotice
        entry.error -> MessageBody.AgentError
        else -> MessageBody.AgentReply
    }
}

/** The bodies drawn with [MarkdownBody], whose standalone `![](…)` images paint inline. */
private val MessageBody.paintsMarkdownMedia: Boolean
    get() = this == MessageBody.Inbox || this == MessageBody.AgentReply

/**
 * Every picture and clip in [items], in timeline order — the viewer's swipe set. Within a message,
 * the media its text paints come first, then its attachments, which is how the row stacks them.
 *
 * A markdown image is media only where the chat reads host files ([hostFiles], i.e. a
 * `LocalMarkdownFiles` is provided) and its path resolves (against [baseDir] when relative) to a
 * picture or a clip; a web image keeps opening in the browser. One item per key, so a pager keyed
 * by it can never meet a duplicate.
 */
fun chatMediaItems(items: List<TimelineItem>, hostFiles: Boolean, baseDir: String?): List<ChatMediaItem> {
    val out = ArrayList<ChatMediaItem>()
    val seen = HashSet<String>()
    fun add(item: ChatMediaItem) {
        if (seen.add(item.key)) out.add(item)
    }
    for (item in items) {
        val entry = (item as? TimelineItem.Msg)?.entry ?: continue
        val text = entry.text
        // `![` first: parsing every message's markdown on each timeline change would be waste.
        if (hostFiles && !text.isNullOrBlank() && "![" in text && messageBody(entry).paintsMarkdownMedia) {
            for (block in parseMarkdownBlocks(text)) {
                if (block !is MdBlock.Image) continue
                val abs = resolveMarkdownMediaPath(block.url, baseDir) ?: continue
                val kind = filePreviewKind(abs) ?: continue
                add(ChatMediaItem.FromMarkdown(entry.id, abs, block.alt, kind))
            }
        }
        entry.attachments?.forEach { att ->
            attachmentMediaKind(att)?.let { add(ChatMediaItem.FromAttachment(entry.id, att, it)) }
        }
    }
    return out
}

/** One tile, on a grid of [CollageLayout.columns] × [CollageLayout.rows] square cells. */
data class CollageTile(
    val index: Int,
    val column: Int,
    val row: Int,
    val columnSpan: Int = 1,
    val rowSpan: Int = 1,
)

/**
 * [overflow] > 0: the last tile carries "+[overflow]" — the items not shown as a tile of their own,
 * the one under the label included.
 */
data class CollageLayout(
    val columns: Int,
    val rows: Int,
    val tiles: List<CollageTile>,
    val overflow: Int = 0,
    /** A cell's width over its height. */
    val cellAspect: Float = 1f,
)

/** A picture's or a clip's orientation, from its displayed size. */
enum class MediaShape { Landscape, Portrait, Square }

/** Within this ratio either way a size reads as square (a 1000×900 photo is square). */
private const val SQUARE_TOLERANCE = 1.15f

/** The orientation of a [width]×[height] item; an unknown size reads as square, the plain grid. */
fun mediaShape(width: Int?, height: Int?): MediaShape {
    if (width == null || height == null || width <= 0 || height <= 0) return MediaShape.Square
    val aspect = width.toFloat() / height
    return when {
        aspect > SQUARE_TOLERANCE -> MediaShape.Landscape
        aspect < 1f / SQUARE_TOLERANCE -> MediaShape.Portrait
        else -> MediaShape.Square
    }
}

/**
 * The grid for media of these [shapes]. Orientation only: a shape picks among the fixed
 * arrangements and never sizes a tile. Two wide items stack as two 2:1 tiles; three with a wide
 * first one put it big across the top; everything else is [collageLayout] by count.
 */
fun collageLayout(shapes: List<MediaShape>): CollageLayout? {
    if (shapes.size == 2 && shapes.all { it == MediaShape.Landscape }) {
        return CollageLayout(columns = 1, rows = 2, tiles = listOf(CollageTile(0, 0, 0), CollageTile(1, 0, 1)), cellAspect = 2f)
    }
    if (shapes.size == 3 && shapes[0] == MediaShape.Landscape) {
        return CollageLayout(
            columns = 2,
            rows = 2,
            tiles = listOf(CollageTile(0, column = 0, row = 0, columnSpan = 2), CollageTile(1, 0, 1), CollageTile(2, 1, 1)),
        )
    }
    return collageLayout(shapes.size)
}

/**
 * The grid for [count] media, or null when one (or none) is not a collage: 2 side by side, 3 as one
 * big tile with two small ones stacked beside it, 4 as 2×2, and more as 2×2 with the rest counted
 * on the last tile.
 */
fun collageLayout(count: Int): CollageLayout? = when {
    count < 2 -> null
    count == 2 -> CollageLayout(columns = 2, rows = 1, tiles = listOf(CollageTile(0, 0, 0), CollageTile(1, 1, 0)))
    count == 3 -> CollageLayout(
        columns = 3,
        rows = 2,
        tiles = listOf(
            CollageTile(0, column = 0, row = 0, columnSpan = 2, rowSpan = 2),
            CollageTile(1, column = 2, row = 0),
            CollageTile(2, column = 2, row = 1),
        ),
    )
    else -> CollageLayout(
        columns = 2,
        rows = 2,
        tiles = List(4) { CollageTile(it, column = it % 2, row = it / 2) },
        overflow = if (count > 4) count - 3 else 0,
    )
}

/** "3 / 7": the viewer's place in the chat's media. */
fun viewerCounter(index: Int, count: Int): String = "${index + 1} / $count"

/** One step through [count] items, stopping at either end rather than wrapping around. */
fun stepIndex(index: Int, delta: Int, count: Int): Int = if (count <= 0) 0 else (index + delta).coerceIn(0, count - 1)

/**
 * Where the open item is after the list changed under the viewer (older history loaded in front, a
 * new message landed): the same item when it is still there, else [fallback] kept inside bounds.
 */
fun reanchorIndex(items: List<ChatMediaItem>, currentKey: String?, fallback: Int): Int {
    if (items.isEmpty()) return 0
    val at = items.indexOfFirst { it.key == currentKey }
    return if (at >= 0) at else fallback.coerceIn(0, items.lastIndex)
}

enum class ViewerCommand { Previous, Next, TogglePlay, ToggleFullscreen, Close }

/**
 * The viewer's keys: ←/→ move, Space plays/pauses a clip, F puts it full screen, Esc closes. Only on
 * the press, and never with Ctrl/⌘/Alt held — those chords belong to the app (⌘F is find).
 */
fun viewerCommandFor(key: Key, type: KeyEventType, ctrlOrMeta: Boolean = false, alt: Boolean = false): ViewerCommand? {
    if (type != KeyEventType.KeyDown || ctrlOrMeta || alt) return null
    return when (key) {
        Key.DirectionLeft -> ViewerCommand.Previous
        Key.DirectionRight -> ViewerCommand.Next
        Key.Spacebar -> ViewerCommand.TogglePlay
        Key.F -> ViewerCommand.ToggleFullscreen
        Key.Escape -> ViewerCommand.Close
        else -> null
    }
}

/** How far down a released drag must have gone to close the viewer, as a share of its height. */
const val DISMISS_DISTANCE_FRACTION = 0.22f

/** Touch swipe-down-to-close: let go far enough down, or flung down fast enough. Up never closes. */
fun shouldDismissOnRelease(offsetY: Float, velocityY: Float, heightPx: Float, flingPxPerSecond: Float): Boolean =
    offsetY > 0f && (offsetY > heightPx * DISMISS_DISTANCE_FRACTION || velocityY > flingPxPerSecond)

/** A clip's length the way a player shows it: `0:07`, `1:02`, `1:02:03`. */
fun formatClipDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

/**
 * Frames the viewer grabbed from clips as they played: a clip with no poster from its phone shows
 * its own picture on its tiles from then on (while the app runs). Keyed by the clip's file id. The
 * first frame kept stays — a poster must not change under the user — and the oldest go once [MAX]
 * are kept; each is at most [POSTER_EDGE] px on its long side, so all of them stay a few MB.
 */
internal object ClipFrames {
    const val MAX = 64
    const val POSTER_EDGE = 480

    private val frames = mutableStateMapOf<String, ImageBitmap>()
    private val order = ArrayDeque<String>()

    fun of(fileId: String): ImageBitmap? = frames[fileId]

    fun keep(fileId: String, frame: ImageBitmap) {
        if (fileId in frames) return
        frames[fileId] = frame
        order.addLast(fileId)
        while (order.size > MAX) frames.remove(order.removeFirst())
    }

    fun clear() {
        frames.clear()
        order.clear()
    }
}

/**
 * True when a capture has nothing to show: a player that draws outside Compose (a platform view)
 * leaves it empty or black, and that must not become a clip's poster. Samples an 8×8 grid.
 */
internal fun looksBlank(frame: ImageBitmap): Boolean {
    if (frame.width <= 0 || frame.height <= 0) return true
    val px = IntArray(1)
    val stepX = maxOf(1, frame.width / 8)
    val stepY = maxOf(1, frame.height / 8)
    var y = stepY / 2
    while (y < frame.height) {
        var x = stepX / 2
        while (x < frame.width) {
            frame.readPixels(px, startX = x, startY = y, width = 1, height = 1)
            val c = px[0]
            val alpha = (c ushr 24) and 0xff
            val light = ((c shr 16) and 0xff) + ((c shr 8) and 0xff) + (c and 0xff)
            if (alpha > 16 && light > 24) return false
            x += stepX
        }
        y += stepY
    }
    return true
}

/** [frame] scaled down so its long side is at most [ClipFrames.POSTER_EDGE] px; never scaled up. */
internal fun posterSized(frame: ImageBitmap): ImageBitmap {
    val edge = maxOf(frame.width, frame.height)
    if (edge <= ClipFrames.POSTER_EDGE) return frame
    val width = frame.width * ClipFrames.POSTER_EDGE / edge
    val height = frame.height * ClipFrames.POSTER_EDGE / edge
    val out = ImageBitmap(width, height)
    Canvas(out).drawImageRect(
        frame,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(frame.width, frame.height),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(width, height),
        paint = Paint().apply { filterQuality = FilterQuality.Medium },
    )
    return out
}

// ── A file and a single picture in a message ────────────────────────────────────────────────────

/** The families a file card's badge is coloured by. */
internal enum class FileFamily(val color: Color) {
    Pdf(Color(0xFFE5484D)),
    Document(Color(0xFF3E63DD)),
    Sheet(Color(0xFF30A46C)),
    Slides(Color(0xFFF76B15)),
    Archive(Color(0xFFAD7F58)),
    Audio(Color(0xFF8E4EC6)),
    Video(Color(0xFFD6409F)),
    Image(Color(0xFF12A594)),
    Code(Color(0xFF687076)),
    Other(Color(0xFF8B8D98)),
}

private val DOCUMENT_EXT = setOf("doc", "docx", "rtf", "odt", "pages")
private val SHEET_EXT = setOf("xls", "xlsx", "csv", "tsv", "numbers", "ods")
private val SLIDES_EXT = setOf("ppt", "pptx", "key", "odp")
private val ARCHIVE_EXT = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar")
private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "aac", "ogg", "oga", "opus", "flac")
private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "webm", "mkv", "avi")
private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff")
private val CODE_EXT = setOf(
    "txt", "md", "json", "yaml", "yml", "toml", "xml", "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rs", "go", "swift", "sh", "sql", "log", "diff", "patch",
)

/** A file's family: its extension first, its MIME type after. */
internal fun fileFamily(extension: String?, mime: String?): FileFamily {
    val ext = extension?.lowercase()
    val m = mime?.lowercase().orEmpty()
    return when {
        ext == "pdf" || m == "application/pdf" -> FileFamily.Pdf
        ext in DOCUMENT_EXT -> FileFamily.Document
        ext in SHEET_EXT -> FileFamily.Sheet
        ext in SLIDES_EXT -> FileFamily.Slides
        ext in ARCHIVE_EXT || m == "application/zip" -> FileFamily.Archive
        ext in AUDIO_EXT || m.startsWith("audio/") -> FileFamily.Audio
        ext in VIDEO_EXT || m.startsWith("video/") -> FileFamily.Video
        ext in IMAGE_EXT || m.startsWith("image/") -> FileFamily.Image
        ext in CODE_EXT || m.startsWith("text/") -> FileFamily.Code
        else -> FileFamily.Other
    }
}

/** The extension a file name shows, lower case: what follows its last dot, if short enough to be one. */
internal fun fileExtension(name: String?): String? {
    if (name == null) return null
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return null
    return name.substring(dot + 1).lowercase().takeIf { ext -> ext.length <= 5 && ext.all { it.isLetterOrDigit() } }
}

/** A file card's badge text: up to four letters of the extension; null draws the kind's glyph. */
internal fun fileBadgeLabel(name: String?): String? = fileExtension(name)?.uppercase()?.take(4)

/** A file card's second line: its type and size, whichever are known ("PDF · 28.2 KB"). */
internal fun fileMetaLine(name: String?, mime: String?, size: Long?): String =
    listOfNotNull(
        fileExtension(name)?.uppercase() ?: mimeLabel(mime),
        size?.takeIf { it > 0 }?.let(::formatFileSize),
    ).joinToString(" · ")

private fun mimeLabel(mime: String?): String? = when {
    mime == null -> null
    mime.startsWith("audio/") -> "Audio"
    mime.startsWith("video/") -> "Video"
    mime.startsWith("image/") -> "Image"
    else -> null
}

/** Where a single picture's inline frame cuts it, when it is more extreme than the frame allows. */
internal enum class PictureCrop { Top, Center }

/** A single picture's inline frame, and how the picture is cut to it (null: it shows whole). */
internal data class PictureFrameSize(val width: Dp, val height: Dp, val crop: PictureCrop?)

/** Tallest (1:2) and widest (5:2) a single picture shows whole inline; past these it is cut. */
internal const val PICTURE_TALLEST = 0.5f
internal const val PICTURE_WIDEST = 2.5f

/**
 * The inline frame of one [pixelWidth]×[pixelHeight] picture at [density]: never bigger than the
 * picture itself, at most [maxWidth]×[maxHeight], and no more extreme than 1:2 or 5:2. Past those the
 * frame keeps the limit and the picture is cut to it: a long screenshot to its top, where it starts,
 * and a panorama to its middle. The viewer still shows every pixel.
 */
internal fun pictureFrame(pixelWidth: Int, pixelHeight: Int, maxWidth: Dp, maxHeight: Dp, density: Float): PictureFrameSize {
    if (pixelWidth <= 0 || pixelHeight <= 0 || density <= 0f) return PictureFrameSize(maxWidth, maxHeight, null)
    val w = pixelWidth / density
    val h = pixelHeight / density
    val aspect = w / h
    val crop = when {
        aspect < PICTURE_TALLEST -> PictureCrop.Top
        aspect > PICTURE_WIDEST -> PictureCrop.Center
        else -> null
    }
    // The part of the picture the frame shows, at the picture's own size.
    val shownW = if (crop == PictureCrop.Center) h * PICTURE_WIDEST else w
    val shownH = if (crop == PictureCrop.Top) w / PICTURE_TALLEST else h
    val scale = minOf(1f, maxWidth.value / shownW, maxHeight.value / shownH)
    return PictureFrameSize((shownW * scale).dp, (shownH * scale).dp, crop)
}

/**
 * Who sent a picture or clip and when, the way the viewer says it under its counter: "You · 13:53",
 * else the sending session's name for a message from another session, else [agentName]. Null when
 * the message has no readable time.
 */
internal fun mediaCaption(entry: LogEntry, agentName: String, nowMs: Long): String? {
    val time = chatTimeLabel(entry.ts, nowMs) ?: return null
    val who = when {
        entry.channel == "inbox" -> entry.from_session_name ?: agentName
        entry.direction == "inbound" -> "You"
        else -> agentName
    }
    return "$who · $time"
}
