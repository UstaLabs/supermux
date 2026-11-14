// The Settings page frame and the anchors search jumps to (#97).
package dev.supermux.ui.settings.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * The rows of one page that search can land on. [target] is the anchor the page was opened for (folds holding it
 * open themselves); [flashing] is the one currently tinted.
 */
@Stable
class KitAnchors(target: String? = null) {
    var target by mutableStateOf(target)
    var flashing by mutableStateOf<String?>(null)

    /** Snapshot state, so a page can wait for a row that loads late. */
    internal val requesters = mutableStateMapOf<String, BringIntoViewRequester>()
}

/** Null outside a [KitPage]: a row's anchor is then only a semantics tag. */
val LocalKitAnchors = staticCompositionLocalOf<KitAnchors?> { null }

/** The anchor a row carries, for tests and for the search index check. */
val KitAnchorKey = SemanticsPropertyKey<String>("KitAnchor")
var SemanticsPropertyReceiver.kitAnchor by KitAnchorKey

/** How long a page waits for the row it was opened for: a machine's rows arrive when it answers. */
private const val ANCHOR_WAIT_MS = 3_000L

/** How long a row found by search stays tinted. */
private const val FLASH_MS = 1_500L

/**
 * Marks a row search can land on: it registers for [KitPage]'s jump, carries [key] in semantics, and is tinted
 * for ~1.5 s when it is the one search opened the page for (the #91 rule: only tints ease).
 */
@Composable
fun Modifier.kitAnchor(key: String): Modifier {
    val anchors = LocalKitAnchors.current
    val c = kitColors()
    val requester = remember { BringIntoViewRequester() }
    if (anchors != null) {
        DisposableEffect(anchors, key) {
            anchors.requesters[key] = requester
            onDispose { if (anchors.requesters[key] === requester) anchors.requesters.remove(key) }
        }
    }
    val lit = anchors?.flashing == key
    val tint by animateColorAsState(
        if (lit) c.flash else c.flash.copy(alpha = 0f),
        if (lit) snap() else tween(900),
        label = "kit-flash", // i18n-ok: animation debug label
    )
    return this
        .semantics { kitAnchor = key }
        .bringIntoViewRequester(requester)
        .drawBehind { drawRect(tint) }
}

/**
 * One Settings page. Wide: an optional "‹ back" text action (sub-pages), the title, the [scope] line, the [lede],
 * then the rows, at most 660 dp wide. Phone: the title is the top bar's, so only scope, lede and rows. Opened for
 * an [anchor], it waits (up to 3 s) for that row to load, scrolls it into view, flashes it and calls
 * [onAnchorShown] once — also when the row never came, then without a flash.
 */
@Composable
fun KitPage(
    title: String,
    modifier: Modifier = Modifier,
    scope: (@Composable () -> Unit)? = null,
    lede: String? = null,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
    tag: String? = null,
    /** A glyph before the wide title (the machine page's laptop or server). */
    titleGlyph: ImageVector? = null,
    /** How wide the page's column grows: the prototype's `.page`; a list page (`.page.wide`) is wider. */
    maxWidth: Dp = 660.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = kitColors()
    val m = kitMetrics()
    val anchors = remember { KitAnchors() }
    val shown by rememberUpdatedState(onAnchorShown)
    val pageScope = rememberCoroutineScope()
    LaunchedEffect(anchor) {
        anchors.target = anchor
        if (anchor == null) return@LaunchedEffect
        // A fold holding the row opens on `target`; rows that load late register when they arrive. The wait is a
        // delay, not withTimeoutOrNull: Compose's desktop dispatcher times delays on its frame clock but timeouts
        // on the wall clock.
        val row = merge(
            snapshotFlow { anchors.requesters[anchor] }.filterNotNull(),
            flow<BringIntoViewRequester?> {
                delay(ANCHOR_WAIT_MS)
                emit(null)
            },
        ).first()
        if (row != null) {
            // Two frames, so the row is laid out: on a page that has just opened, the first frame comes before the
            // first layout.
            withFrameNanos { }
            withFrameNanos { }
            row.bringIntoView()
            anchors.flashing = anchor
            // The fade runs in the page's scope, not this effect's: the hub clears its anchor in onAnchorShown, which
            // restarts this effect and would cancel the fade half way, leaving the row lit.
            pageScope.launch {
                delay(FLASH_MS)
                if (anchors.flashing == anchor) anchors.flashing = null
            }
        }
        shown()
    }
    CompositionLocalProvider(LocalKitAnchors provides anchors) {
        Column(
            modifier
                .fillMaxSize()
                .background(c.pane)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        ) {
            Column(
                Modifier
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .padding(start = m.pagePadH, end = m.pagePadH, top = m.pagePadTop, bottom = 60.dp)
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            ) {
                if (!m.phone && backLabel != null && onBack != null) {
                    KitTextAction(
                        backLabel,
                        onBack,
                        Modifier.padding(bottom = 10.dp).testTag("settings_page_back"),
                        leading = KitGlyphs.Back,
                    )
                }
                if (!m.phone) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (titleGlyph != null) {
                            Icon(titleGlyph, contentDescription = null, tint = c.text, modifier = Modifier.size(20.dp))
                        }
                        Text(
                            title,
                            fontSize = m.pageTitle,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = (-0.19).sp,
                            color = c.text,
                            modifier = Modifier.semantics { heading() }.testTag("settings_page_title"),
                        )
                    }
                }
                scope?.invoke()
                if (lede != null) Text(lede, fontSize = 12.5.sp, lineHeight = 19.sp, color = c.dim, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}
