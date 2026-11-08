// The ONE fullscreen viewer for the chat's pictures and clips: every one the chat holds, in
// timeline order, opened at the tapped one. Desktop first — ←/→, Space, F, Esc, side arrows, a
// thumbnail strip and a Mac trackpad's two-finger swipes — with the phone's swipe between items
// and swipe down to close.
package dev.supermux.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.media.AttachmentMedia
import dev.supermux.proto.Attachment
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowControlsInsets
import dev.supermux.ui.adaptive.WindowControlsInsets
import dev.supermux.ui.editor.FilePreviewKind
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.Media
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Sizes
import dev.supermux.ui.theme.Space
import dev.supermux.ui.work.rememberDownloads
import dev.supermux.ui.widgets.Dialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    caption: (ChatMediaItem) -> String? = { null },
    renderPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit = ViewerPlayer,
    /** The item on screen, each time it changes: where a rebuilt viewer starts. */
    onCurrentChange: (key: String) -> Unit = {},
) {
    Dialog(onDismissRequest = onDismiss, properties = viewerDialogProperties()) {
        ChatMediaViewerContent(items, startKey, resolve, onDismiss, caption = caption, renderPlayer = renderPlayer, onCurrentChange = onCurrentChange)
    }
}

/**
 * The viewer's surface, without the dialog (the dialog wraps it; design shots render it alone).
 *
 *  - A pager of every item. A picture keeps the lightbox's zoom/pan ([LightboxTransform]); while
 *    one is zoomed, a drag pans it instead of turning the page. A clip plays in place once its page
 *    is the settled one — never before, so swiping past a clip downloads nothing.
 *  - "3 / 7" and who sent it when ([caption]), zoom −/+ for a picture, Save, Close across the top.
 *    Under a pointer the top bar and the side arrows fade after a still moment, back on a move.
 *  - A click or tap on a picture hides the bars (top bar, side arrows, strip) until the next one.
 *  - A pointer adds ‹ › at the sides and a thumbnail strip along the bottom; keys ←/→, Space
 *    (play/pause), F (the clip full screen) and Esc work everywhere a keyboard does.
 *  - Touch swipes between items and closes on a swipe down; so does a Mac trackpad's two-finger
 *    scroll ([viewerTrackpad]), the item following the fingers until they let go.
 */
@Composable
internal fun ChatMediaViewerContent(
    items: List<ChatMediaItem>,
    startKey: String,
    resolve: (ChatMediaItem) -> MediaHandle,
    onDismiss: () -> Unit,
    caption: (ChatMediaItem) -> String? = { null },
    renderPlayer: @Composable (uri: String, onError: () -> Unit, onTransport: (VideoTransport?) -> Unit) -> Unit = ViewerPlayer,
    onCurrentChange: (key: String) -> Unit = {},
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val pointer = LocalPointerAvailable.current
    val windowControls = LocalWindowControlsInsets.current
    val platform = LocalPlatform.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloads()
    val latest by rememberUpdatedState(items)
    val dismiss by rememberUpdatedState(onDismiss)
    val pager = rememberPagerState(initialPage = remember { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }) { items.size }

    // The open item, by key: a list that changes under the viewer (older history loads in front, a
    // new message lands) must leave the same picture on screen.
    var currentKey by remember { mutableStateOf(startKey) }
    val currentChanged by rememberUpdatedState(onCurrentChange)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage }.collect { page ->
            latest.getOrNull(page)?.let {
                currentKey = it.key
                currentChanged(it.key)
            }
        }
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
            val couldNot = { platform.notices.show("Couldn't download ${item.name}") }
            // The FILE, through the fleet's downloads: closing the viewer lets go of it, and it waits in the bar.
            if (startDownload(downloads, handle.media, handle.att, platform, pointer, onFailed = couldNot)) return@launch
            // No download here (a host file, a preview): the bytes, as before.
            val bytes = handle.media.peek(handle.att) ?: runCatching { handle.media.bytes(handle.att) }.getOrNull()
            if (bytes == null) couldNot()
            else saveOrOpenAttachment(platform, pointer, item.name, handle.att.mime, bytes)
        }
    }

    val focus = remember { FocusRequester() }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val zoomedNow by rememberUpdatedState(zoomed)
    val pictureNow by rememberUpdatedState(current.kind == FilePreviewKind.Image)
    val flingPx = with(density) { DismissFling.toPx() }

    // Closing by a pull: the picture slides on down and fades, and the dialog goes only once the
    // trackpad is quiet. Closed mid-coast, the momentum still arriving would scroll the chat behind.
    var leaving by remember { mutableStateOf(false) }
    var outOfView by remember { mutableStateOf(false) }
    var padQuiet by remember { mutableStateOf(true) }
    var leaveFade by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(outOfView, padQuiet) { if (outOfView && padQuiet) dismiss() }
    // A pad that never says it is quiet still lets the viewer go.
    LaunchedEffect(outOfView) {
        if (outOfView) {
            delay(LEAVE_QUIET_TIMEOUT_MS)
            dismiss()
        }
    }
    fun leaveDown(velocity: Float) {
        if (leaving) return
        leaving = true
        scope.launch {
            launch { animate(1f, 0f, animationSpec = tween(LEAVE_MS)) { value, _ -> leaveFade = value } }
            animate(dragY, maxOf(heightPx, dragY), animationSpec = tween(LEAVE_MS, easing = FastOutLinearInEasing)) { value, _ -> dragY = value }
            outOfView = true
        }
    }

    // Under a pointer the chrome (top bar, side arrows) fades after a still moment and comes back on
    // any move, as Photos and Quick Look do. It stays while the pointer rests on it.
    var pointerMoves by remember { mutableIntStateOf(0) }
    val chromeHover = remember { MutableInteractionSource() }
    val hoveringChrome by chromeHover.collectIsHoveredAsState()
    var chromeShown by remember { mutableStateOf(true) }
    LaunchedEffect(pointerMoves, hoveringChrome) {
        chromeShown = true
        if (pointer && !hoveringChrome) {
            delay(CHROME_IDLE_MS)
            chromeShown = false
        }
    }
    val chromeAlpha by animateFloatAsState(if (chromeShown) 1f else 0f, animationSpec = tween(CHROME_FADE_MS), label = "viewer chrome")
    val chrome = Modifier.graphicsLayer { alpha = chromeAlpha }.hoverable(chromeHover)

    // A click or tap on a picture hides the bars until the next one, as photo viewers do: the top
    // bar, the side arrows and the strip. A double click still zooms and a swipe still moves on.
    var barsHidden by remember { mutableStateOf(false) }
    val toggleBars = remember { { barsHidden = !barsHidden } }

    val trackpad = platform.caps.trackpadMomentum
    // A Mac trackpad's two-finger swipes, which the stock pager judges by the momentum's dying tail.
    val trackpadSwipes = if (!trackpad) Modifier else {
        var pullBack by remember { mutableStateOf<Job?>(null) }
        Modifier.viewerTrackpad(
            pager = pager,
            // ⌘/Ctrl + scroll zooms a picture and a zoomed picture pans: those stay the page's own.
            owns = remember {
                { event: PointerEvent ->
                    val chord = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                    !zoomedNow && !(chord && pictureNow)
                }
            },
            onPull = remember {
                { dy: Float ->
                    if (!leaving) {
                        pullBack?.cancel()
                        padQuiet = false
                        dragY = (dragY + dy).coerceAtLeast(0f)
                    }
                }
            },
            onPullLetGo = remember {
                { velocity: Float ->
                    if (shouldDismissOnRelease(dragY, velocity, heightPx, flingPx)) {
                        leaveDown(velocity)
                    } else {
                        pullBack = scope.launch {
                            animate(dragY, 0f, initialVelocity = velocity, animationSpec = SwipeSettleSpec) { value, _ -> dragY = value }
                        }
                    }
                }
            },
            onQuiet = remember { { padQuiet = true } },
        )
    }
    // Swipe down to close is a touch gesture: a desktop mouse drag does nothing here.
    val dismissDrag = if (pointer) Modifier else Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().y
                if (shouldDismissOnRelease(dragY, velocity, size.height.toFloat(), flingPx)) {
                    leaveDown(velocity)
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
            .graphicsLayer { alpha = leaveFade }
            .background(Color.Black.copy(alpha = backdrop))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter) pointerMoves++
                    }
                }
            }
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
            Box(Modifier.fillMaxWidth().weight(1f).then(dismissDrag).then(trackpadSwipes)) {
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
                        FilePreviewKind.Image -> ViewerPicture(handle, item.name, transformOf(item.key), trackpad, onTap = toggleBars)
                        FilePreviewKind.Video -> ViewerClip(
                            handle = handle,
                            active = index == pager.settledPage,
                            renderPlayer = renderPlayer,
                            onTransport = { t -> if (t == null) transports.remove(item.key) else transports[item.key] = t },
                        )
                    }
                }
                val counter = if (items.size > 1) viewerCounter(page, items.size) else null
                val onZoomOut = { transformOf(current.key).zoomBy(1f / LightboxTransform.STEP) }
                val onZoomIn = { transformOf(current.key).zoomBy(LightboxTransform.STEP) }
                // The general overload: inside this Box the Column's own one would be picked.
                androidx.compose.animation.AnimatedVisibility(!barsHidden, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(BARS_MS)), exit = fadeOut(tween(BARS_MS))) {
                    if (windowControls.band > 0.dp) {
                        ViewerTitleBar(
                            controls = windowControls,
                            counter = counter,
                            caption = caption(current),
                            picture = current.kind == FilePreviewKind.Image,
                            onZoomOut = onZoomOut,
                            onZoomIn = onZoomIn,
                            onSave = ::save,
                            onClose = { dismiss() },
                            modifier = chrome,
                        )
                    } else {
                        ViewerTopBar(
                            counter = counter,
                            caption = caption(current),
                            picture = current.kind == FilePreviewKind.Image,
                            onZoomOut = onZoomOut,
                            onZoomIn = onZoomIn,
                            onSave = ::save,
                            onClose = { dismiss() },
                            modifier = chrome,
                        )
                    }
                }
                if (pointer && items.size > 1) {
                    androidx.compose.animation.AnimatedVisibility(!barsHidden && page > 0, Modifier.align(Alignment.CenterStart), enter = fadeIn(tween(BARS_MS)), exit = fadeOut(tween(BARS_MS))) {
                        SideArrow(MediaIcons.ChevronLeft, "Previous", "chat_media_viewer_prev", { go(-1) }, chrome)
                    }
                    androidx.compose.animation.AnimatedVisibility(!barsHidden && page < items.lastIndex, Modifier.align(Alignment.CenterEnd), enter = fadeIn(tween(BARS_MS)), exit = fadeOut(tween(BARS_MS))) {
                        SideArrow(MediaIcons.ChevronRight, "Next", "chat_media_viewer_next", { go(+1) }, chrome)
                    }
                }
            }
            if (pointer && items.size > 1) {
                // The strip folds away with the bars, and the picture grows into its room.
                AnimatedVisibility(!barsHidden, enter = fadeIn(tween(BARS_MS)) + expandVertically(tween(BARS_MS)), exit = fadeOut(tween(BARS_MS)) + shrinkVertically(tween(BARS_MS))) {
                    ViewerStrip(items, page, resolve, ::jump)
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** How fast a downward fling closes the viewer even short of [DISMISS_DISTANCE_FRACTION]. */
private val DismissFling = 1200.dp

/** A still pointer for this long fades the viewer's chrome. */
private const val CHROME_IDLE_MS = 2_500L

/** The bars' fade (and the strip's fold) when a click hides or brings them back. */
private const val BARS_MS = 200
private const val CHROME_FADE_MS = 220

/** A pull that closes the viewer slides it out over this long. */
private const val LEAVE_MS = 200

/** The longest a closing viewer waits for the trackpad's momentum to stop. */
private const val LEAVE_QUIET_TIMEOUT_MS = 1_500L

/** A picture page: the lightbox's transform and gestures, through the timeline's load + decode. */
@Composable
private fun ViewerPicture(handle: MediaHandle, name: String, transform: LightboxTransform, trackpad: Boolean, onTap: () -> Unit) {
    Box(Modifier.fillMaxSize().viewerImageGestures(transform, trackpad, onTap), contentAlignment = Alignment.Center) {
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

/**
 * The top bar where the window's own controls float over the content (a Mac's traffic lights,
 * Windows' caption buttons): the window's title band itself, a bar clear of those controls, with
 * "3 / 7 · You · 13:53" on the left and zoom (a picture), Save and Close on the right.
 */
@Composable
private fun ViewerTitleBar(
    controls: WindowControlsInsets,
    counter: String?,
    caption: String?,
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
            .height(controls.band)
            .background(ViewerChrome.Bar)
            .drawBehind {
                val y = size.height - 0.5f
                drawLine(ViewerChrome.Hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            .padding(start = controls.start + Space.xs, end = controls.end + Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (counter != null) {
            Text(
                counter,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.testTag("chat_media_viewer_counter"),
            )
        }
        if (counter != null && caption != null) {
            Text("·", color = ViewerChrome.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Space.xs))
        }
        if (caption != null) {
            Text(
                caption,
                color = ViewerChrome.Muted,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("chat_media_viewer_caption"),
            )
        }
        Spacer(Modifier.weight(1f))
        if (picture) {
            BarButton(MediaIcons.ZoomOut, "Zoom out", "chat_media_viewer_zoom_out", onZoomOut)
            BarButton(MediaIcons.ZoomIn, "Zoom in", "chat_media_viewer_zoom_in", onZoomIn)
        }
        BarButton(MediaIcons.Save, "Save", "chat_media_viewer_save", onSave)
        BarButton(MediaIcons.Close, "Close", "chat_media_viewer_close", onClose)
    }
}

/** A title-bar button: the glyph alone, a soft square behind it under the pointer, as a Mac toolbar's. */
@Composable
private fun BarButton(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(ViewerChrome.BarButton)
            .clip(RoundedCornerShape(ViewerChrome.BarButtonCorner))
            .focusProperties { canFocus = false }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(ViewerChrome.BarIcon))
    }
}

/**
 * The top bar where nothing of the window's floats over the content (a phone, a browser): the
 * counter with who sent it when under it on the left, zoom (a picture), Save and Close on the
 * right, over a top scrim.
 */
@Composable
private fun ViewerTopBar(
    counter: String?,
    caption: String?,
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
        if (counter != null || caption != null) {
            Column(Modifier.padding(start = Space.sm)) {
                if (counter != null) {
                    Text(
                        counter,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("chat_media_viewer_counter"),
                    )
                }
                if (caption != null) {
                    Text(
                        caption,
                        color = ViewerChrome.Muted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.testTag("chat_media_viewer_caption"),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (picture) {
            ViewerButton(MediaIcons.ZoomOut, "Zoom out", "chat_media_viewer_zoom_out", onZoomOut)
            ViewerButton(MediaIcons.ZoomIn, "Zoom in", "chat_media_viewer_zoom_in", onZoomIn)
        }
        ViewerButton(MediaIcons.Save, "Save", "chat_media_viewer_save", onSave)
        ViewerButton(MediaIcons.Close, "Close", "chat_media_viewer_close", onClose)
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
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(ViewerChrome.ButtonIcon))
    }
}

/** ‹ or › at a side: a thin chevron on a translucent disc with a faint rim. */
@Composable
private fun SideArrow(icon: ImageVector, label: String, tag: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .padding(Space.md)
            .size(ViewerChrome.ArrowDisc)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, ViewerChrome.Hairline, CircleShape)
            .focusProperties { canFocus = false }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(ViewerChrome.ArrowIcon))
    }
}

/** The viewer's chrome palette: it always sits on black, so it does not follow the app theme. */
private object ViewerChrome {
    val Bar = Color(0xFF1C1C1E).copy(alpha = 0.94f)
    val Hairline = Color.White.copy(alpha = 0.1f)
    val Muted = Color.White.copy(alpha = 0.65f)
    val BarButton = 28.dp
    val BarButtonCorner = 6.dp
    val BarIcon = 17.dp
    val ButtonIcon = 22.dp
    val ArrowDisc = 40.dp
    val ArrowIcon = 22.dp
}

/**
 * The desktop strip: every item as a small tile. The current one sits in the middle, a little
 * bigger and outlined in the accent colour, and the strip glides to keep it there as the viewer
 * moves on. A clip's tile is its poster: the strip downloads no clip either.
 */
@Composable
private fun ViewerStrip(
    items: List<ChatMediaItem>,
    current: Int,
    resolve: (ChatMediaItem) -> MediaHandle,
    onPick: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    LaunchedEffect(current) { state.animateScrollToItem(current) }
    val shape = RoundedCornerShape(Radii.sm)
    val accent = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .navigationBarsPadding()
            .padding(vertical = Space.sm)
            .testTag("chat_media_viewer_strip"),
    ) {
        // Half the strip less half the current tile on each side: any tile, the first and the last
        // too, scrolls to the middle.
        val side = ((maxWidth - Media.viewerThumbCurrent) / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().height(Media.viewerThumbCurrent),
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(Media.viewerThumbGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { i, item ->
                val selected = i == current
                val size by animateDpAsState(
                    if (selected) Media.viewerThumbCurrent else Media.viewerThumbSize,
                    animationSpec = tween(STRIP_MS),
                    label = "strip tile",
                )
                val dim by animateFloatAsState(if (selected) 1f else 0.6f, animationSpec = tween(STRIP_MS), label = "strip tile alpha")
                Box(
                    Modifier
                        .size(size)
                        .graphicsLayer { alpha = dim }
                        .clip(shape)
                        .border(2.dp, if (selected) accent else Color.Transparent, shape)
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
}

/** How long a strip tile takes to grow into the current one, or shrink back. */
private const val STRIP_MS = 220

@Composable
private fun StripPicture(handle: MediaHandle) {
    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2C))) {
        val image = rememberAttachmentImage(handle.att, handle.media, handle.memoryCacheKey)
        if (image is AttachmentImage.Ready) {
            Image(image.decoded.painter, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
