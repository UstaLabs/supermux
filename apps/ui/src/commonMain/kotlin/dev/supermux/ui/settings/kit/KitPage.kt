// The Settings page frame and the anchors search jumps to (#97).
package dev.supermux.ui.settings.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The rows of one page that search can land on. [target] is the anchor the page was opened for (folds holding it
 * open themselves); [flashing] is the one currently tinted.
 */
@Stable
class KitAnchors(target: String? = null) {
    var target by mutableStateOf(target)
    var flashing by mutableStateOf<String?>(null)
    internal val requesters = mutableMapOf<String, BringIntoViewRequester>()
}

/** Null outside a [KitPage]: a row's anchor is then only a semantics tag. */
val LocalKitAnchors = staticCompositionLocalOf<KitAnchors?> { null }

/** The anchor a row carries, for tests and for the search index check. */
val KitAnchorKey = SemanticsPropertyKey<String>("KitAnchor")
var SemanticsPropertyReceiver.kitAnchor by KitAnchorKey

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
        label = "kit-flash",
    )
    return this
        .semantics { kitAnchor = key }
        .bringIntoViewRequester(requester)
        .drawBehind { drawRect(tint) }
}

/**
 * One Settings page. Wide: an optional "‹ back" text action (sub-pages), the title, the [scope] line, the [lede],
 * then the rows, at most 660 dp wide. Phone: the title is the top bar's, so only scope, lede and rows. Opened for
 * an [anchor], it scrolls that row into view, flashes it and calls [onAnchorShown] once.
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = kitColors()
    val m = kitMetrics()
    val anchors = remember { KitAnchors() }
    val shown by rememberUpdatedState(onAnchorShown)
    LaunchedEffect(anchor) {
        anchors.target = anchor
        if (anchor == null) return@LaunchedEffect
        // Two frames: the rows compose, and a fold that holds the anchor opens and lays out.
        withFrameNanos { }
        withFrameNanos { }
        anchors.requesters[anchor]?.bringIntoView()
        anchors.flashing = anchor
        shown()
        delay(1500)
        anchors.flashing = null
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
                    .widthIn(max = 660.dp)
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
                    Text(
                        title,
                        fontSize = m.pageTitle,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.19).sp,
                        color = c.text,
                        modifier = Modifier.testTag("settings_page_title"),
                    )
                }
                scope?.invoke()
                if (lede != null) Text(lede, fontSize = 12.5.sp, lineHeight = 19.sp, color = c.dim, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}
