package dev.supermux.ui.chat

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.ui.editor.FilePreviewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules under the collage and the fullscreen viewer, without any UI: which attachments are
 * media, which pictures and clips the chat holds and in what order (the viewer's swipe set), how a
 * collage of N tiles is laid out, and how the viewer moves, re-anchors, reads keys and closes.
 */
class ChatMediaTest {

    private fun att(id: String, kind: String? = null, mime: String? = null, name: String? = null) =
        Attachment(file_id = id, kind = kind, mime = mime, name = name)

    private fun msg(
        id: String,
        direction: String = "outbound",
        text: String? = null,
        attachments: List<Attachment>? = null,
        channel: String? = null,
        from: String? = null,
        error: Boolean = false,
    ) = TimelineItem.Msg(
        LogEntry(
            id = id,
            ts = "2026-11-02T10:00:0${id.last()}Z",
            direction = direction,
            text = text,
            attachments = attachments,
            channel = channel,
            from_session_id = from,
            error = error,
        ),
    )

    private fun keys(items: List<ChatMediaItem>) = items.map { it.key }

    // ── Which attachments are media ─────────────────────────────────────────────────

    @Test fun attachment_kinds_follow_the_chats_own_rule() {
        assertEquals(FilePreviewKind.Image, attachmentMediaKind(att("a", kind = "photo")))
        assertEquals(FilePreviewKind.Image, attachmentMediaKind(att("a", kind = "image")))
        assertEquals(FilePreviewKind.Image, attachmentMediaKind(att("a", mime = "image/png")))
        assertEquals(FilePreviewKind.Video, attachmentMediaKind(att("a", kind = "video")))
        assertEquals(FilePreviewKind.Video, attachmentMediaKind(att("a", kind = "video_note")))
        assertEquals(FilePreviewKind.Video, attachmentMediaKind(att("a", mime = "video/quicktime")))
        assertNull(attachmentMediaKind(att("a", kind = "voice", mime = "audio/ogg")))
        assertNull(attachmentMediaKind(att("a", kind = "document", mime = "application/pdf")))
    }

    // ── The chat-wide set ───────────────────────────────────────────────────────────

    @Test fun the_set_holds_every_messages_media_in_timeline_order() {
        val items = listOf(
            msg("m1", direction = "inbound", attachments = listOf(att("f1", kind = "photo"), att("f2", kind = "video"))),
            msg("m2", text = "Here:\n\n![shot](/repo/a.png)", attachments = listOf(att("f3", kind = "video"))),
            msg("m3", direction = "inbound", attachments = listOf(att("doc", kind = "document", mime = "application/pdf"), att("f4", mime = "image/jpeg"))),
        )
        assertEquals(
            listOf(
                attachmentMediaKey("m1", "f1"),
                attachmentMediaKey("m1", "f2"),
                markdownMediaKey("m2", "/repo/a.png"),
                attachmentMediaKey("m2", "f3"),
                attachmentMediaKey("m3", "f4"),
            ),
            keys(chatMediaItems(items, hostFiles = true, baseDir = "/repo")),
        )
    }

    @Test fun a_replys_markdown_media_comes_before_its_attachments() {
        // The reply's text paints above its attachments, so the swipe order follows the screen.
        val items = listOf(msg("m1", text = "![](/repo/b.mp4)", attachments = listOf(att("f1", kind = "photo"))))
        val set = chatMediaItems(items, hostFiles = true, baseDir = null)
        assertEquals(listOf(markdownMediaKey("m1", "/repo/b.mp4"), attachmentMediaKey("m1", "f1")), keys(set))
        assertEquals(FilePreviewKind.Video, set[0].kind)
        assertEquals(FilePreviewKind.Image, set[1].kind)
    }

    @Test fun only_rows_that_render_markdown_contribute_markdown_media() {
        val md = "![](/repo/a.png)"
        val items = listOf(
            msg("u1", direction = "inbound", text = md), // the user's bubble: inline text, no images
            msg("e1", text = md, error = true), // a turn's error: drawn as text
            msg("s1", direction = "inbound", channel = "inbox", from = SUPERMUX_SENDER_ID, text = md), // supermux notice
            msg("w1", direction = "inbound", text = "📖 Walkthrough ready $md"),
            msg("i1", direction = "inbound", channel = "inbox", from = "sess-7", text = md), // another session's message
            msg("a1", text = md), // an agent reply
        )
        assertEquals(
            listOf(markdownMediaKey("i1", "/repo/a.png"), markdownMediaKey("a1", "/repo/a.png")),
            keys(chatMediaItems(items, hostFiles = true, baseDir = null)),
        )
    }

    @Test fun without_host_files_markdown_paths_are_not_media() {
        // No LocalMarkdownFiles: a host path stays a link line, so it is not in the set either.
        val items = listOf(msg("m1", text = "![](/repo/a.png)", attachments = listOf(att("f1", kind = "photo"))))
        assertEquals(listOf(attachmentMediaKey("m1", "f1")), keys(chatMediaItems(items, hostFiles = false, baseDir = null)))
    }

    @Test fun web_and_non_media_markdown_images_are_skipped_and_relative_paths_resolve() {
        val text = "![](https://example.com/x.png)\n\n![](/repo/notes.pdf)\n\n![clip](shots/b.mp4)"
        val set = chatMediaItems(listOf(msg("m1", text = text)), hostFiles = true, baseDir = "/repo")
        assertEquals(listOf(markdownMediaKey("m1", "/repo/shots/b.mp4")), keys(set))
        val only = set.single() as ChatMediaItem.FromMarkdown
        assertEquals("clip", only.alt)
        assertEquals("b.mp4", only.name)
    }

    @Test fun the_same_attachment_twice_in_one_message_appears_once() {
        // The viewer's pager and strip are keyed: a duplicate key would throw.
        val items = listOf(msg("m1", attachments = listOf(att("f1", kind = "photo"), att("f1", kind = "photo"))))
        assertEquals(listOf(attachmentMediaKey("m1", "f1")), keys(chatMediaItems(items, hostFiles = true, baseDir = null)))
    }

    @Test fun the_same_picture_twice_in_one_reply_appears_once() {
        val items = listOf(msg("m1", text = "![](/repo/a.png)\n\n![](/repo/a.png)"))
        assertEquals(listOf(markdownMediaKey("m1", "/repo/a.png")), keys(chatMediaItems(items, hostFiles = true, baseDir = null)))
    }

    // ── A clip's length ─────────────────────────────────────────────────────────────

    @Test fun a_clips_length_reads_like_a_player() {
        assertEquals("0:07", formatClipDuration(7_000))
        assertEquals("0:59", formatClipDuration(59_999), "a clip shorter than a minute never shows 1:00")
        assertEquals("1:02", formatClipDuration(62_000))
        assertEquals("1:02:03", formatClipDuration(3_723_000))
        assertEquals("0:00", formatClipDuration(400))
    }

    // ── Frames the viewer grabbed ───────────────────────────────────────────────────

    private fun filled(color: androidx.compose.ui.graphics.Color, w: Int = 8, h: Int = 8): androidx.compose.ui.graphics.ImageBitmap {
        val bmp = androidx.compose.ui.graphics.ImageBitmap(w, h)
        androidx.compose.ui.graphics.Canvas(bmp).drawRect(
            0f, 0f, w.toFloat(), h.toFloat(),
            androidx.compose.ui.graphics.Paint().apply { this.color = color },
        )
        return bmp
    }

    @Test fun a_black_or_empty_capture_is_no_poster() {
        // A player that draws outside Compose (a platform view) leaves the capture empty or black.
        assertTrue(looksBlank(androidx.compose.ui.graphics.ImageBitmap(8, 8)))
        assertTrue(looksBlank(filled(androidx.compose.ui.graphics.Color.Black)))
        assertFalse(looksBlank(filled(androidx.compose.ui.graphics.Color(0xFF3366CC))))
    }

    @Test fun a_clips_first_kept_frame_wins_and_the_frames_stay_bounded() {
        ClipFrames.clear()
        val first = filled(androidx.compose.ui.graphics.Color.Red)
        ClipFrames.keep("v", first)
        ClipFrames.keep("v", filled(androidx.compose.ui.graphics.Color.Green))
        assertTrue(ClipFrames.of("v") === first, "the poster does not change under the user once it is shown")
        repeat(ClipFrames.MAX + 5) { ClipFrames.keep("c$it", first) }
        assertNull(ClipFrames.of("v"), "the oldest frames go first")
        assertTrue(ClipFrames.of("c${ClipFrames.MAX + 4}") != null)
        ClipFrames.clear()
    }

    @Test fun a_kept_frame_is_scaled_down_to_a_poster() {
        val big = filled(androidx.compose.ui.graphics.Color.Blue, w = 1920, h = 1080)
        val small = posterSized(big)
        assertEquals(ClipFrames.POSTER_EDGE, small.width)
        assertEquals(ClipFrames.POSTER_EDGE * 1080 / 1920, small.height)
        assertTrue(posterSized(filled(androidx.compose.ui.graphics.Color.Blue, w = 100, h = 60)).width == 100, "never upscaled")
    }

    // ── Collage geometry ────────────────────────────────────────────────────────────

    @Test fun fewer_than_two_is_not_a_collage() {
        assertNull(collageLayout(0))
        assertNull(collageLayout(1))
    }

    @Test fun two_sit_side_by_side() {
        val l = collageLayout(2)!!
        assertEquals(2 to 1, l.columns to l.rows)
        assertEquals(listOf(CollageTile(0, 0, 0), CollageTile(1, 1, 0)), l.tiles)
        assertEquals(0, l.overflow)
    }

    @Test fun three_is_one_big_and_two_small() {
        val l = collageLayout(3)!!
        assertEquals(3 to 2, l.columns to l.rows)
        assertEquals(
            listOf(
                CollageTile(0, column = 0, row = 0, columnSpan = 2, rowSpan = 2),
                CollageTile(1, column = 2, row = 0),
                CollageTile(2, column = 2, row = 1),
            ),
            l.tiles,
        )
    }

    @Test fun four_is_a_two_by_two_grid() {
        val l = collageLayout(4)!!
        assertEquals(2 to 2, l.columns to l.rows)
        assertEquals(listOf(CollageTile(0, 0, 0), CollageTile(1, 1, 0), CollageTile(2, 0, 1), CollageTile(3, 1, 1)), l.tiles)
        assertEquals(0, l.overflow)
    }

    @Test fun more_than_four_put_the_rest_on_the_last_tile() {
        for ((count, hidden) in listOf(5 to 2, 6 to 3, 12 to 9)) {
            val l = collageLayout(count)!!
            assertEquals(4, l.tiles.size, "$count items still draw four tiles")
            assertEquals(hidden, l.overflow, "$count items: the last tile covers itself and the rest")
        }
    }

    // ── Collage orientation from the real sizes (his ruling: orientation only) ──────

    @Test fun a_size_reads_as_landscape_portrait_or_square() {
        assertEquals(MediaShape.Landscape, mediaShape(1920, 1080))
        assertEquals(MediaShape.Portrait, mediaShape(1080, 1920))
        assertEquals(MediaShape.Square, mediaShape(1000, 1000))
        assertEquals(MediaShape.Square, mediaShape(1000, 900), "nearly square is square")
        assertEquals(MediaShape.Square, mediaShape(null, null), "an unknown size keeps the plain grid")
        assertEquals(MediaShape.Square, mediaShape(640, 0))
    }

    @Test fun two_wide_items_stack_as_two_wide_tiles() {
        val l = collageLayout(listOf(MediaShape.Landscape, MediaShape.Landscape))!!
        assertEquals(1 to 2, l.columns to l.rows)
        assertEquals(2f, l.cellAspect)
        assertEquals(listOf(CollageTile(0, 0, 0), CollageTile(1, 0, 1)), l.tiles)
    }

    @Test fun two_tall_mixed_or_square_items_keep_sitting_side_by_side() {
        for (pair in listOf(
            listOf(MediaShape.Portrait, MediaShape.Portrait),
            listOf(MediaShape.Landscape, MediaShape.Portrait),
            listOf(MediaShape.Square, MediaShape.Landscape),
        )) {
            assertEquals(collageLayout(2), collageLayout(pair), "$pair")
        }
    }

    @Test fun three_with_a_wide_first_put_it_big_on_top() {
        val l = collageLayout(listOf(MediaShape.Landscape, MediaShape.Portrait, MediaShape.Square))!!
        assertEquals(2 to 2, l.columns to l.rows)
        assertEquals(
            listOf(CollageTile(0, column = 0, row = 0, columnSpan = 2), CollageTile(1, 0, 1), CollageTile(2, 1, 1)),
            l.tiles,
        )
    }

    @Test fun three_with_a_tall_or_square_first_keep_it_big_on_the_left() {
        assertEquals(collageLayout(3), collageLayout(listOf(MediaShape.Portrait, MediaShape.Landscape, MediaShape.Landscape)))
        assertEquals(collageLayout(3), collageLayout(listOf(MediaShape.Square, MediaShape.Landscape, MediaShape.Landscape)))
    }

    @Test fun four_and_more_keep_the_grid_whatever_their_shapes() {
        val wide = List(6) { MediaShape.Landscape }
        assertEquals(collageLayout(4), collageLayout(wide.take(4)))
        assertEquals(collageLayout(6), collageLayout(wide))
    }

    // ── Viewer navigation ───────────────────────────────────────────────────────────

    @Test fun the_counter_reads_position_of_total() {
        assertEquals("3 / 7", viewerCounter(2, 7))
        assertEquals("1 / 1", viewerCounter(0, 1))
    }

    @Test fun stepping_stops_at_both_ends() {
        assertEquals(0, stepIndex(0, -1, 5))
        assertEquals(4, stepIndex(4, +1, 5))
        assertEquals(3, stepIndex(2, +1, 5))
        assertEquals(1, stepIndex(2, -1, 5))
        assertEquals(0, stepIndex(0, +1, 0))
    }

    @Test fun a_changed_list_keeps_the_open_item_or_clamps() {
        val a = ChatMediaItem.FromAttachment("m1", att("a", kind = "photo"), FilePreviewKind.Image)
        val b = ChatMediaItem.FromAttachment("m2", att("b", kind = "photo"), FilePreviewKind.Image)
        val c = ChatMediaItem.FromAttachment("m3", att("c", kind = "photo"), FilePreviewKind.Image)
        val x = ChatMediaItem.FromAttachment("m0", att("x", kind = "photo"), FilePreviewKind.Image)
        // Older history arrives in front of what is open: the open item stays open.
        assertEquals(2, reanchorIndex(listOf(x, a, b, c), b.key, fallback = 1))
        // The open item is gone: stay where we were, inside the new bounds.
        assertEquals(1, reanchorIndex(listOf(a, c), b.key, fallback = 1))
        assertEquals(0, reanchorIndex(listOf(a), b.key, fallback = 5))
        assertEquals(0, reanchorIndex(emptyList(), b.key, fallback = 2))
    }

    @Test fun desktop_keys_map_to_viewer_commands() {
        val down = KeyEventType.KeyDown
        assertEquals(ViewerCommand.Previous, viewerCommandFor(Key.DirectionLeft, down))
        assertEquals(ViewerCommand.Next, viewerCommandFor(Key.DirectionRight, down))
        assertEquals(ViewerCommand.TogglePlay, viewerCommandFor(Key.Spacebar, down))
        assertEquals(ViewerCommand.ToggleFullscreen, viewerCommandFor(Key.F, down))
        assertEquals(ViewerCommand.Close, viewerCommandFor(Key.Escape, down))
        assertNull(viewerCommandFor(Key.DirectionRight, KeyEventType.KeyUp), "act once, on the press")
        assertNull(viewerCommandFor(Key.F, down, ctrlOrMeta = true), "Cmd/Ctrl+F belongs to the app")
        assertNull(viewerCommandFor(Key.A, down))
    }

    @Test fun swipe_down_closes_only_past_the_distance_or_on_a_fling() {
        val h = 1000f
        val fling = 1500f
        assertTrue(shouldDismissOnRelease(offsetY = 300f, velocityY = 0f, heightPx = h, flingPxPerSecond = fling))
        assertFalse(shouldDismissOnRelease(offsetY = 100f, velocityY = 200f, heightPx = h, flingPxPerSecond = fling))
        assertTrue(shouldDismissOnRelease(offsetY = 100f, velocityY = 2000f, heightPx = h, flingPxPerSecond = fling))
        assertFalse(shouldDismissOnRelease(offsetY = -300f, velocityY = -3000f, heightPx = h, flingPxPerSecond = fling), "only DOWN closes")
    }
}
