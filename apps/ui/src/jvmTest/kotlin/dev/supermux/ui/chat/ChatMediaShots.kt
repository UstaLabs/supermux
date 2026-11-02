package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Design shots of the chat's collages and the fullscreen viewer, rendered by the real composables
 * (no window, no player backend: a clip's page draws the real controls over a painted frame).
 * A no-op unless CHAT_MEDIA_SHOTS_DIR is set (`--rerun`: the variable is not a task input):
 *   CHAT_MEDIA_SHOTS_DIR=/tmp/chat-media-shots ./gradlew :ui:jvmTest --tests '*ChatMediaShots*' --rerun
 */
@OptIn(ExperimentalTestApi::class)
class ChatMediaShots {

    /** A photo-like picture: a sky gradient, a sun and two hills, so crops and fits read clearly. */
    private fun scene(w: Int, h: Int, sky: Int, ground: Int, sun: Int): ByteArray {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        val canvas = surface.canvas
        canvas.drawRect(org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()), org.jetbrains.skia.Paint().apply { color = sky })
        // A lighter band toward the horizon, so the picture is not one flat colour.
        canvas.drawRect(
            org.jetbrains.skia.Rect.makeXYWH(0f, h * 0.45f, w.toFloat(), h * 0.55f),
            org.jetbrains.skia.Paint().apply { color = 0xFFFFFFFF.toInt(); alpha = 70 },
        )
        canvas.drawCircle(w * 0.72f, h * 0.3f, minOf(w, h) * 0.12f, org.jetbrains.skia.Paint().apply { color = sun })
        val hill = org.jetbrains.skia.Paint().apply { color = ground }
        canvas.drawCircle(w * 0.25f, h * 1.15f, w * 0.55f, hill)
        canvas.drawCircle(w * 0.85f, h * 1.25f, w * 0.5f, org.jetbrains.skia.Paint().apply { color = ground; alpha = 200 })
        return surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
    }

    private val pictures: Map<String, ByteArray> = mapOf(
        "p1" to scene(1200, 800, 0xFF5B8DEF.toInt(), 0xFF2E7D32.toInt(), 0xFFFFC107.toInt()),
        "p2" to scene(800, 1200, 0xFFEF8354.toInt(), 0xFF6D4C41.toInt(), 0xFFFFF59D.toInt()),
        "p3" to scene(1000, 1000, 0xFF7E57C2.toInt(), 0xFF1B5E20.toInt(), 0xFFFFEB3B.toInt()),
        "p4" to scene(1200, 700, 0xFF26A69A.toInt(), 0xFF33691E.toInt(), 0xFFFFFFFF.toInt()),
        "p5" to scene(900, 1100, 0xFFEC407A.toInt(), 0xFF4E342E.toInt(), 0xFFFFE082.toInt()),
        "p6" to scene(1100, 900, 0xFF42A5F5.toInt(), 0xFF00695C.toInt(), 0xFFFFCA28.toInt()),
        "p7" to scene(1000, 800, 0xFF8D6E63.toInt(), 0xFF263238.toInt(), 0xFFFFAB40.toInt()),
        "p8" to scene(800, 800, 0xFF5C6BC0.toInt(), 0xFF2E7D32.toInt(), 0xFFFFF176.toInt()),
    )

    private fun photo(id: String) = Attachment(file_id = id, kind = "photo", mime = "image/png", name = "$id.png")
    private fun clip(id: String) = Attachment(file_id = id, kind = "video", mime = "video/mp4", name = "$id.mp4")

    private val media = object : AttachmentMedia {
        override fun peek(att: Attachment): ByteArray? = pictures[att.file_id]
        override suspend fun bytes(att: Attachment, refresh: Boolean): ByteArray? = pictures[att.file_id]

        // A clip "plays" through the painted stand-in player below.
        override suspend fun playableUri(att: Attachment): String? = if (att.kind == "video") "file:///shots/${att.file_id}.mp4" else null
    }

    private val conversation = listOf(
        LogEntry(
            id = "in:1", ts = "2026-11-02T09:12:00Z", direction = "inbound", channel = "web",
            text = "Two shots from the phone run", attachments = listOf(photo("p1"), photo("p2")),
        ),
        LogEntry(
            id = "out:1", ts = "2026-11-02T09:12:30Z", direction = "outbound", op = "reply",
            text = "Thanks. Here is the same flow on desktop, plus the recording:",
            attachments = listOf(photo("p3"), clip("v1").copy(duration_ms = 62_000, poster_file_id = "p6"), photo("p4")),
        ),
        LogEntry(
            id = "in:2", ts = "2026-11-02T09:13:10Z", direction = "inbound", channel = "web",
            text = "And the four settings screens", attachments = listOf(photo("p5"), photo("p6"), photo("p7"), photo("p8")),
        ),
        LogEntry(
            id = "out:2", ts = "2026-11-02T09:14:00Z", direction = "outbound", op = "reply",
            text = "All seven from the audit:",
            attachments = listOf(photo("p1"), photo("p2"), clip("v2").copy(duration_ms = 9_000), photo("p3"), photo("p4"), photo("p5"), photo("p6")),
        ),
    )

    private class Shape(val name: String, val widthDp: Int, val heightDp: Int, val compact: Boolean)

    private val shapes = listOf(Shape("phone", 400, 1300, compact = true), Shape("desktop", 900, 1300, compact = false))

    private fun shoot(dir: File, name: String, shape: Shape, theme: AppearanceMode, content: @Composable () -> Unit) {
        runDesktopComposeUiTest(shape.widthDp * 2, shape.heightDp * 2) {
            setPlatformContent(
                pointer = !shape.compact,
                widthClass = if (shape.compact) WindowWidthClass.Compact else WindowWidthClass.Expanded,
                inputMode = if (shape.compact) InputMode.Touch else InputMode.Pointer,
            ) {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    SupermuxTheme(appearance = theme) {
                        Box(Modifier.size(shape.widthDp.dp, shape.heightDp.dp)) { content() }
                    }
                }
            }
            waitForIdle()
            Thread.sleep(400)
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name-${shape.name}-${theme.name.lowercase()}.png"))
        }
    }

    @Test fun shots() {
        val dir = System.getenv("CHAT_MEDIA_SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val session = SessionInfo(id = "s-1", name = "media-demo", workdir = "/home/ahmet/supermux", agent = "claude")
        val items = chatMediaItems(conversation.map { dev.supermux.chat.TimelineItem.Msg(it) }, hostFiles = false, baseDir = null)
        for (theme in listOf(AppearanceMode.DARK, AppearanceMode.LIGHT)) for (shape in shapes) {
            shoot(dir, "chat", shape, theme) {
                ChatPanel(
                    session = session,
                    state = ChatState(messages = conversation),
                    actions = ChatActions(media = media),
                    draft = "",
                    onDraftChange = {},
                    showHeader = !shape.compact,
                )
            }
        }
        val viewerShape = listOf(Shape("phone", 400, 860, compact = true), Shape("desktop", 1280, 800, compact = false))
        for (shape in viewerShape) {
            for ((label, start) in listOf("viewer-image" to items[2], "viewer-video" to items.first { it.kind == dev.supermux.ui.editor.FilePreviewKind.Video })) {
                shoot(dir, label, shape, AppearanceMode.DARK) {
                    ChatMediaViewerContent(
                        items = items,
                        startKey = start.key,
                        resolve = { item -> MediaHandle((item as ChatMediaItem.FromAttachment).att, media, attachmentImageKey(item.att)) },
                        onDismiss = {},
                        renderPlayer = { _, _, onTransport ->
                            val transport = remember { ShotTransport() }
                            DisposableEffect(transport) {
                                onTransport(transport)
                                onDispose { onTransport(null) }
                            }
                            VideoPlayerFrame(transport, fill = true, trailing = VideoTrailing.Fullscreen) { mod ->
                                Box(mod.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E3A5F), Color(0xFF0B1320)))))
                            }
                        },
                    )
                }
            }
        }
    }

    private class ShotTransport : VideoTransport {
        override val isPlaying = false
        override val isLoading = false
        override val sliderPos = 380f
        override val positionText = "0:23"
        override val durationText = "1:02"
        override val muted = false
        override fun togglePlay() = Unit
        override fun seekStart(value: Float) = Unit
        override fun seekFinished() = Unit
        override fun toggleMute() = Unit
        override fun openExternally() = Unit
    }
}
