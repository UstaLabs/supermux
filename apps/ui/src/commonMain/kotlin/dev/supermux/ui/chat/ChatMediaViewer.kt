// The ONE fullscreen viewer for the chat's pictures and clips: every one the chat holds, in
// timeline order, opened at the tapped one. Desktop first — ←/→, Space, F, Esc, side arrows and a
// thumbnail strip — with the phone's swipe between items and swipe down to close.
package dev.supermux.ui.chat

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Media
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Sizes
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.launch

/**
 * Opens the chat's fullscreen viewer at the item with this key ([ChatMediaItem.key]). False when the
 * chat's set has no such item (media drawn inside a card the set does not walk): the caller then
 * shows it the way it would outside a chat.
 */
@Stable
fun interface ChatMediaOpener {
    fun open(key: String): Boolean
}

/** The chat panel's viewer; null outside a chat (an archived transcript, a preview, a test). */
val LocalChatMediaViewer = staticCompositionLocalOf<ChatMediaOpener?> { null }

/** The message a timeline row draws, so its media can name themselves in the viewer's set. */
val LocalChatMediaEntry = compositionLocalOf<String?> { null }

/**
 * What a viewer page reads an item through: an attachment-shaped handle, the media seam that serves
 * it, and the decoded picture's memory-cache key (null for a host file, which an agent may rewrite).
 */
internal class MediaHandle(val att: Attachment, val media: AttachmentMedia, val memoryCacheKey: String?)

/** An attachment through the chat's media seam, decoded under the key the timeline row used. */
internal fun attachmentHandle(item: ChatMediaItem.FromAttachment, media: AttachmentMedia): MediaHandle =
    MediaHandle(item.att, media, attachmentImageKey(item.att))

/**
 * The chat's resolver: attachments through its media seam; an agent's markdown host file through
 * [files] (`/fs/raw`), shaped as an attachment named after the file so the same pages, the same
 * save and the same staging (whose demuxer comes from the extension) serve it.
 */
internal fun chatMediaHandle(
    item: ChatMediaItem,
    media: AttachmentMedia,
    files: MarkdownFiles?,
    probeMime: (String) -> String?,
): MediaHandle = when (item) {
    is ChatMediaItem.FromAttachment -> attachmentHandle(item, media)
    is ChatMediaItem.FromMarkdown -> MediaHandle(
        att = Attachment(
            file_id = item.absPath,
            kind = if (item.kind == FilePreviewKind.Video) "video" else "image",
            mime = probeMime(item.name),
            name = item.name,
        ),
        media = AttachmentMedia.fromBytes { path -> files?.load(path)?.getOrNull() },
        memoryCacheKey = null,
    )
}

/** The production player for a viewer page: the inline player's mount, filling the screen. */
internal val ViewerPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit =
    { uri, onError, onTransport -> MountedVideoPlayer(uri, onError = onError, fill = true, onTransport = onTransport) }

/**
 * The viewer, as a fullscreen dialog. [resolve] turns an item into what its page reads;
 * [renderPlayer] mounts a clip (a stub in tests: a headless worker must not load a native backend).
 */
@Composable
internal fun ChatMediaViewer(
    items: List<ChatMediaItem>,
    startKey: String,
    resolve: (ChatMediaItem) -> MediaHandle,
    onDismiss: () -> Unit,
    renderPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit = ViewerPlayer,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChatMediaViewerContent(items, startKey, resolve, onDismiss, renderPlayer)
    }
}

/**
 * The viewer's surface, without the dialog (the dialog wraps it; design shots render it alone).
 *
 *  - A pager of every item. A picture keeps the lightbox's zoom/pan ([LightboxTransform]); while
 *    one is zoomed, a drag pans it instead of turning the page. A clip plays in place once its page
 *    is the settled one — never before, so swiping past a clip downloads nothing.
 *  - "3 / 7", zoom −/+ for a picture, Save, Close across the top.
 *  - A pointer adds ‹ › at the sides and a thumbnail strip along the bottom; keys ←/→, Space
 *    (play/pause), F (the clip full screen) and Esc work everywhere a keyboard does.
 *  - Touch swipes between items and closes on a swipe down.
 */
@Composable
internal fun ChatMediaViewerContent(
    items: List<ChatMediaItem>,
    startKey: String,
    resolve: (ChatMediaItem) -> MediaHandle,
    onDismiss: () -> Unit,
    renderPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit = ViewerPlayer,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pointer = LocalPointerAvailable.current
    val platform = LocalPlatform.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(items)
    val dismiss by rememberUpdatedState(onDismiss)
    val pager = rememberPagerState(initialPage = remember { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }) { items.size }

    // The open item, by key: a list that changes under the viewer (older history loads in front, a
    // new message lands) must leave the same picture on screen.
    var currentKey by remember { mutableStateOf(startKey) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page -> latest.getOrNull(page)?.let { currentKey = it.key } }
    }
    LaunchedEffect(items) {
        val at = reanchorIndex(items, currentKey, pager.currentPage)
        if (at != pager.currentPage) pager.scrollToPage(at)
    }

    val transforms = remember { HashMap<String, LightboxTransform>() }
    fun transformOf(key: String) = transforms.getOrPut(key) { LightboxTransform() }
    // Turning the page puts the one left behind back at fit, as every photo viewer does.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val keep = latest.getOrNull(page)?.key
            transforms.forEach { (key, t) -> if (key != keep) t.scaleTo(LightboxTransform.MIN) }
        }
    }
    // The clip on screen, for Space and F. Only a mounted player registers, so it is always the current page's.
    val transports = remember { HashMap<String, VideoTransport>() }

    val page = pager.currentPage.coerceIn(0, items.lastIndex)
    val current = items[page]
    val zoomed = current.kind == FilePreviewKind.Image && transformOf(current.key).scale > LightboxTransform.MIN

    fun go(delta: Int) {
        scope.launch { pager.animateScrollToPage(stepIndex(pager.targetPage, delta, latest.size)) }
    }

    fun jump(index: Int) {
        scope.launch { pager.animateScrollToPage(index) }
    }

    fun save() {
        val item = current
        val handle = resolve(item)
        scope.launch {
            val bytes = handle.media.peek(handle.att) ?: runCatching { handle.media.bytes(handle.att) }.getOrNull()
            if (bytes == null) platform.notices.show("Couldn't download ${item.name}")
            else saveOrOpenAttachment(platform, pointer, item.name, handle.att.mime, bytes)
        }
    }

    val focus = remember { FocusRequester() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val zoomedNow by rememberUpdatedState(zoomed)
    val flingPx = with(density) { DismissFling.toPx() }
    // Swipe down to close is a touch gesture: a desktop mouse drag does nothing here.
    val dismissDrag = if (pointer) Modifier else Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().y
                if (shouldDismissOnRelease(dragY, velocity, size.height.toFloat(), flingPx)) {
                    dismiss()
                } else {
                    scope.launch { animate(dragY, 0f) { value, _ -> dragY = value } }
                }
            },
            onDragCancel = { dragY = 0f },
            onVerticalDrag = { change, dy ->
                if (zoomedNow) return@detectVerticalDragGestures
                tracker.addPosition(change.uptimeMillis, change.position)
                dragY = (dragY + dy).coerceAtLeast(0f)
                change.consume()
            },
        )
    }
    val backdrop = 1f - (if (heightPx > 0f) (dragY / heightPx).coerceIn(0f, 1f) else 0f) * 0.75f

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdrop))
            .onSizeChanged { heightPx = it.height.toFloat() }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val command = viewerCommandFor(event.key, event.type, event.isCtrlPressed || event.isMetaPressed, event.isAltPressed)
                when (command) {
                    ViewerCommand.Previous -> go(-1)
                    ViewerCommand.Next -> go(+1)
                    ViewerCommand.TogglePlay -> transports[currentKey]?.togglePlay()
                    ViewerCommand.ToggleFullscreen -> transports[currentKey]?.toggleFullscreen()
                    ViewerCommand.Close -> dismiss()
                    null -> return@onPreviewKeyEvent false
                }
                true
            }
            .testTag("chat_media_viewer"),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(1f).then(dismissDrag)) {
                HorizontalPager(
                    state = pager,
                    key = { latest.getOrNull(it)?.key ?: "#$it" },
                    userScrollEnabled = !zoomed,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationY = dragY }
                        .testTag("chat_media_viewer_pager"),
                ) { index ->
                    val item = items.getOrNull(index) ?: return@HorizontalPager
                    val handle = remember(item.key) { resolve(item) }
                    when (item.kind) {
                        FilePreviewKind.Image -> ViewerPicture(handle, item.name, transformOf(item.key))
                        FilePreviewKind.Video -> ViewerClip(
                            handle = handle,
                            active = index == pager.settledPage,
                            renderPlayer = renderPlayer,
                            onTransport = { t -> if (t == null) transports.remove(item.key) else transports[item.key] = t },
                        )
                    }
                }
                ViewerTopBar(
                    counter = if (items.size > 1) viewerCounter(page, items.size) else null,
                    picture = current.kind == FilePreviewKind.Image,
                    onZoomOut = { transformOf(current.key).zoomBy(1f / LightboxTransform.STEP) },
                    onZoomIn = { transformOf(current.key).zoomBy(LightboxTransform.STEP) },
                    onSave = ::save,
                    onClose = { dismiss() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (pointer && items.size > 1) {
                    if (page > 0) {
                        SideArrow(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous", "chat_media_viewer_prev", { go(-1) },
                            Modifier.align(Alignment.CenterStart),
                        )
                    }
                    if (page < items.lastIndex) {
                        SideArrow(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next", "chat_media_viewer_next", { go(+1) },
                            Modifier.align(Alignment.CenterEnd),
                        )
                    }
                }
            }
            if (pointer && items.size > 1) ViewerStrip(items, page, resolve, ::jump)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** How fast a downward fling closes the viewer even short of [DISMISS_DISTANCE_FRACTION]. */
private val DismissFling = 1200.dp

/** A picture page: the lightbox's transform and gestures, through the timeline's load + decode. */
@Composable
private fun ViewerPicture(handle: MediaHandle, name: String, transform: LightboxTransform) {
    Box(Modifier.fillMaxSize().viewerImageGestures(transform), contentAlignment = Alignment.Center) {
        when (val image = rememberAttachmentImage(handle.att, handle.media, handle.memoryCacheKey)) {
            is AttachmentImage.Ready -> Image(
                painter = image.decoded.painter,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = transform.scale
                        scaleY = transform.scale
                        translationX = transform.offset.x
                        translationY = transform.offset.y
                    }
                    .testTag("chat_media_viewer_image"),
            )
            AttachmentImage.Failed -> ViewerNotice("Couldn't load this picture")
            AttachmentImage.Loading -> ViewerSpinner()
        }
    }
}

/**
 * A clip page. Until it is [active] (the settled page) it is a poster that fetches nothing; then it
 * resolves a playable URI the inline player's way ([resolvePlayableClip]) and mounts the player.
 */
@Composable
private fun ViewerClip(
    handle: MediaHandle,
    active: Boolean,
    renderPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit,
    onTransport: (VideoTransport?) -> Unit,
) {
    if (!active) {
        Box(Modifier.fillMaxSize().testTag("chat_media_viewer_poster")) {
            ClipPoster(handle.att, handle.media, 0, "chat_media_viewer", Sizes.videoPlayGlyph)
        }
        return
    }
    val platform = LocalPlatform.current
    var uri by remember(handle.att.file_id) { mutableStateOf<String?>(null) }
    var failed by remember(handle.att.file_id) { mutableStateOf(false) }
    LaunchedEffect(handle.att.file_id) {
        val clip = resolvePlayableClip(handle.att, handle.media, platform)
        if (clip == null) failed = true else uri = clip.uri
    }
    val located = uri
    when {
        failed -> ViewerNotice("Couldn't play this video here. Save it to watch it.")
        located == null -> ViewerSpinner()
        // The controls sit at the bottom of the frame: keep them clear of a phone's gesture bar.
        else -> Box(Modifier.fillMaxSize().navigationBarsPadding()) {
            // A clip with no phone poster: let the player keep a frame of it for its tiles.
            val keep = handle.att.file_id.takeIf { handle.att.poster_file_id == null }
            androidx.compose.runtime.CompositionLocalProvider(LocalViewerClip provides keep) {
                renderPlayer(located, { failed = true }, onTransport)
            }
        }
    }
}

@Composable
private fun ViewerSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(MdImageDimens.SpinnerSize), color = Color.White, strokeWidth = MdImageDimens.SpinnerStroke)
    }
}

@Composable
private fun ViewerNotice(text: String) {
    Box(Modifier.fillMaxSize().padding(Space.xl), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Counter on the left; zoom (a picture), Save and Close on the right, over a top scrim. */
@Composable
private fun ViewerTopBar(
    counter: String?,
    picture: Boolean,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
            .statusBarsPadding()
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (counter != null) {
            Text(
                counter,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = Space.sm).testTag("chat_media_viewer_counter"),
            )
        }
        Spacer(Modifier.weight(1f))
        if (picture) {
            ViewerButton(Icons.Filled.Remove, "Zoom out", "chat_media_viewer_zoom_out", onZoomOut)
            ViewerButton(Icons.Filled.Add, "Zoom in", "chat_media_viewer_zoom_in", onZoomIn)
        }
        ViewerButton(Icons.Filled.Download, "Save", "chat_media_viewer_save", onSave)
        ViewerButton(Icons.Filled.Close, "Close", "chat_media_viewer_close", onClose)
    }
}

/**
 * A white glyph on a translucent disc. Never focusable: focus stays on the viewer, so ←/→ and
 * Space keep reaching it after a click (and Space can never re-click the last button).
 */
@Composable
private fun ViewerButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Sizes.iconButton)
            .clip(CircleShape)
            .focusProperties { canFocus = false }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
    }
}

@Composable
private fun SideArrow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .padding(Space.md)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .focusProperties { canFocus = false }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

/**
 * The desktop strip: every item as a small tile, the current one outlined and bright. A clip's tile
 * is its poster — the strip downloads no clip either. Keeps the current tile in view.
 */
@Composable
private fun ViewerStrip(
    items: List<ChatMediaItem>,
    current: Int,
    resolve: (ChatMediaItem) -> MediaHandle,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        if (current <= first || current >= last) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    val shape = RoundedCornerShape(Radii.sm)
    LazyRow(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .padding(vertical = Space.sm)
            .testTag("chat_media_viewer_strip"),
        horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = Space.md),
    ) {
        itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
            val selected = i == current
            Box(
                Modifier
                    .size(Media.viewerThumbSize)
                    .clip(shape)
                    .border(2.dp, if (selected) Color.White else Color.Transparent, shape)
                    .graphicsLayer { alpha = if (selected) 1f else 0.55f }
                    .focusProperties { canFocus = false }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onPick(i) }
                    .testTag("chat_media_viewer_thumb:$i"),
                contentAlignment = Alignment.Center,
            ) {
                when (item.kind) {
                    FilePreviewKind.Image -> StripPicture(remember(item.key) { resolve(item) })
                    FilePreviewKind.Video -> {
                        val handle = remember(item.key) { resolve(item) }
                        Box(Modifier.fillMaxSize()) {
                            val poster = handle.att.poster_file_id
                            val frame = if (poster == null) ClipFrames.of(handle.att.file_id) else null
                            poster?.let { PosterPicture(it, handle.media, Modifier.fillMaxSize()) }
                            frame?.let { Image(BitmapPainter(it), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            VideoPosterTile(glyph = 18.dp, background = if (poster != null || frame != null) Color.Transparent else VideoPoster.Background)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StripPicture(handle: MediaHandle) {
    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2C))) {
        val image = rememberAttachmentImage(handle.att, handle.media, handle.memoryCacheKey)
        if (image is AttachmentImage.Ready) {
            Image(image.decoded.painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
