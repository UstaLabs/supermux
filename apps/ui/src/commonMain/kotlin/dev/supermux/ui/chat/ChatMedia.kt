// The rules under the chat's media: which attachments are pictures or clips, every one the chat
// holds in the order the viewer swipes through them, a collage's geometry, and the viewer's
// navigation, keys and swipe-to-close. Pure, so each rule is tested without a UI.
package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.ui.MdBlock
import dev.supermux.ui.accounts.isAccountNotice
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.editor.filePreviewKind
import dev.supermux.ui.parseMarkdownBlocks

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
        override val name: String get() = absPath.substringAfterLast('/')
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
)

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
