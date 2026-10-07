package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.swithun.cmpmermaid.compose.MermaidDiagram
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.MermaidThemePreset
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog

/** Tallest an inline diagram may be before it shrinks (keeping its aspect) — pinch in place, or open it full size. */
private val MaxInlineHeight: Dp = 420.dp

/**
 * A ```mermaid fence drawn natively by cmp-mermaid (Mermaid.js 12 translated to Kotlin, painted on a
 * Compose Canvas — no WebView). Anything it can't draw (a parse error, an unsupported feature, a
 * crash inside the painter) falls back to the plain [FencedCodeBlock], so a bad diagram never costs
 * the reader the source. The diagram zooms and pans in place ([diagramGestures]) so a dense one can be
 * read without leaving the chat; the corner buttons flip to the source, reset the zoom and open the
 * fullscreen viewer.
 */
@Composable
fun MermaidBlock(source: String) {
    var failed by remember(source) { mutableStateOf(false) }
    var showCode by remember(source) { mutableStateOf(false) }
    var fullscreen by remember(source) { mutableStateOf(false) }
    var sceneSize by remember(source) { mutableStateOf<Pair<Float, Float>?>(null) }
    val inline = remember(source) { LightboxTransform() }
    if (failed) {
        FencedCodeBlock(source)
        return
    }
    val theme = rememberMermaidTheme()
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().testTag("mermaid_block")) {
        if (showCode) {
            // The code view has its own copy button in that corner, so the toggle back to the
            // diagram joins its row instead of sitting on top of it.
            FencedCodeBlock(source) {
                CornerButton(Icons.Filled.Code, "Show diagram", true) { showCode = false }
            }
        } else {
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(cs.surfaceContainerLow)
                    .padding(Space.sm),
                contentAlignment = Alignment.Center,
            ) {
                val size = sceneSize
                val density = LocalDensity.current
                // The library's default "Fit" sizing fills a BOUNDED box, and a chat column is not
                // one — so the box takes the scene's own aspect ratio once it is laid out: natural
                // size, shrunk to the bubble width and MaxInlineHeight, never scaled up.
                var fitW = maxWidth
                var fitH = 1.dp
                if (size != null && size.first > 0f && size.second > 0f) {
                    val naturalW = with(density) { size.first.toDp() }
                    val naturalH = with(density) { size.second.toDp() }
                    val scale = minOf(1f, maxWidth / naturalW, MaxInlineHeight / naturalH)
                    fitW = naturalW * scale
                    fitH = naturalH * scale
                }
                val fitPx = with(density) { androidx.compose.ui.geometry.Size(fitW.toPx(), fitH.toPx()) }
                SideEffect { inline.content = fitPx }
                // The gesture/clip area spans the whole column width, not just the fitted diagram,
                // so a zoomed-in diagram spreads into the spare room beside it instead of staying
                // cropped to its fit-size box.
                Box(
                    Modifier.fillMaxWidth().height(fitH).clipToBounds().diagramGestures(inline).testTag("mermaid_inline"),
                    contentAlignment = Alignment.Center,
                ) {
                    MermaidDiagram(
                        source = source,
                        modifier = Modifier.width(fitW).height(fitH).zoomedBy(inline),
                        theme = theme,
                        contentDescription = "Mermaid diagram",
                        onError = { failed = true },
                        onRenderResult = { r ->
                            if (r is GMResult.Ok) sceneSize = r.value.width to r.value.height
                        },
                        pinchToZoom = false,
                    )
                }
            }
        }
        if (!showCode) {
            Row(Modifier.align(Alignment.TopEnd).padding(Space.xs)) {
                CornerButton(Icons.Filled.Code, "Show source", false) { showCode = true }
                if (inline.scale > LightboxTransform.MIN) {
                    CornerButton(Icons.Filled.ZoomOutMap, "Fit diagram", false) { inline.scaleTo(LightboxTransform.MIN) }
                }
                CornerButton(Icons.Filled.OpenInFull, "Open fullscreen", false) { fullscreen = true }
            }
        }
    }
    if (fullscreen) MermaidViewer(source, theme, onDismiss = { fullscreen = false })
}

@Composable
private fun CornerButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = label, tint = if (active) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
    }
}

/**
 * Fullscreen diagram viewer: the inline gestures ([diagramGestures]) plus zoom buttons for a mouse,
 * over the app surface rather than black, since a diagram is drawn in the app's own colours.
 */
@Composable
private fun MermaidViewer(source: String, theme: MermaidTheme, onDismiss: () -> Unit) {
    val transform = remember { LightboxTransform() }
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(cs.surface)
                .diagramGestures(transform)
                .testTag("mermaid_viewer"),
            contentAlignment = Alignment.Center,
        ) {
            MermaidDiagram(
                source = source,
                modifier = Modifier.fillMaxSize().padding(Space.lg).zoomedBy(transform),
                theme = theme,
                pinchToZoom = false,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(Space.sm)) {
                IconButton(onClick = { transform.zoomBy(1f / LightboxTransform.STEP) }, modifier = Modifier.testTag("mermaid_viewer_zoom_out")) {
                    Icon(Icons.Filled.Remove, contentDescription = "Zoom out", tint = cs.onSurface)
                }
                IconButton(onClick = { transform.zoomBy(LightboxTransform.STEP) }, modifier = Modifier.testTag("mermaid_viewer_zoom_in")) {
                    Icon(Icons.Filled.Add, contentDescription = "Zoom in", tint = cs.onSurface)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurface)
                }
            }
        }
    }
}

private fun Modifier.zoomedBy(t: LightboxTransform): Modifier = graphicsLayer {
    scaleX = t.scale
    scaleY = t.scale
    translationX = t.offset.x
    translationY = t.offset.y
}

/** Mermaid's own light/dark preset re-coloured from the app's Material scheme, on a transparent background. */
@Composable
private fun rememberMermaidTheme(): MermaidTheme {
    val cs = MaterialTheme.colorScheme
    return remember(cs) { mermaidThemeFor(cs) }
}

internal fun mermaidThemeFor(cs: ColorScheme): MermaidTheme {
    val dark = cs.surface.luminance() < 0.5f
    val base = MermaidTheme.preset(if (dark) MermaidThemePreset.Dark else MermaidThemePreset.Default)
    fun hex(c: Color): String = "#" + (c.toArgb().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').let {
        it.substring(2) + it.substring(0, 2) // AARRGGBB -> RRGGBBAA (CSS order)
    }
    val vars = mapOf(
        "background" to "transparent",
        "primaryColor" to hex(cs.secondaryContainer),
        "mainBkg" to hex(cs.secondaryContainer),
        "primaryBorderColor" to hex(cs.outline),
        "nodeBorder" to hex(cs.outline),
        "primaryTextColor" to hex(cs.onSecondaryContainer),
        "nodeTextColor" to hex(cs.onSecondaryContainer),
        "textColor" to hex(cs.onSurface),
        "titleColor" to hex(cs.onSurface),
        "lineColor" to hex(cs.onSurfaceVariant),
        "defaultLinkColor" to hex(cs.onSurfaceVariant),
        "edgeLabelBackground" to hex(cs.surfaceContainerHigh),
        "clusterBkg" to hex(cs.surfaceContainer),
        "clusterBorder" to hex(cs.outlineVariant),
        "clusterText" to hex(cs.onSurface),
        "flowContainerStroke" to hex(cs.outlineVariant),
        "noteBkgColor" to hex(cs.tertiaryContainer),
        "noteBorderColor" to hex(cs.tertiary),
        "noteTextColor" to hex(cs.onTertiaryContainer),
        "relationColor" to hex(cs.onSurfaceVariant),
        "relationLabelBackground" to hex(cs.surfaceContainerHigh),
        "relationLabelColor" to hex(cs.onSurface),
        "pieTitleTextColor" to hex(cs.onSurface),
        "pieLegendTextColor" to hex(cs.onSurface),
        "pieSectionTextColor" to hex(cs.onPrimary),
        "pieStrokeColor" to hex(cs.surface),
        "pieOuterStrokeColor" to hex(cs.outlineVariant),
        "sectionBkgColor" to hex(cs.surfaceContainer),
        "altSectionBkgColor" to hex(cs.surfaceContainerLow),
        "sectionBkgColor2" to hex(cs.surfaceContainerHigh),
        "taskBkgColor" to hex(cs.primary),
        "taskBorderColor" to hex(cs.primary),
        "taskTextColor" to hex(cs.onPrimary),
        "taskTextOutsideColor" to hex(cs.onSurface),
        "taskTextDarkColor" to hex(cs.onSurface),
        "activeTaskBkgColor" to hex(cs.tertiary),
        "activeTaskBorderColor" to hex(cs.tertiary),
        "doneTaskBkgColor" to hex(cs.outline),
        "doneTaskBorderColor" to hex(cs.outline),
        "critBkgColor" to hex(cs.error),
        "critBorderColor" to hex(cs.error),
        "gridColor" to hex(cs.outlineVariant),
        "todayLineColor" to hex(cs.error),
        "vertLineColor" to hex(cs.outline),
    )
    val pie = listOf(cs.primary, cs.tertiary, cs.secondary, cs.error, cs.primaryContainer, cs.tertiaryContainer, cs.outline)
    val themed = when (val r = MermaidTheme.withVariables(base, vars, colorArrays = mapOf())) {
        is GMResult.Ok -> r.value
        is GMResult.Err -> base
    }
    return themed.copy(
        dropShadow = null,
        pie = themed.pie.copy(colors = pie.map { com.swithun.cmpmermaid.core.SceneColor(it.toArgb().toLong() and 0xFFFFFFFFL) }),
    )
}
