package dev.supermux.editor.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.decorationsFacet

/** One styled span of a line, in the line's own offsets. */
@Immutable
data class LineSpan(val start: Int, val end: Int, val style: SpanStyle)

/** What a line's layout depends on besides the configuration: its text and its styled spans. */
@Immutable
private data class LineKey(val text: String, val spans: List<LineSpan>, val noWrap: Boolean = false, val widgets: List<Float> = emptyList())

/** The configuration every cached layout was measured under; a change clears the cache. */
@Immutable
private data class LayoutSignature(
    val fontFamily: FontFamily,
    val fontSizeSp: Float,
    val lineHeightFactor: Float,
    val density: Float,
    val fontScale: Float,
    val wrapWidthPx: Int?,
    val tabSize: Int,
    val tokens: Map<String, SpanStyle>,
    val classStyles: Map<String, SpanStyle>,
    val foreground: androidx.compose.ui.graphics.Color,
)

/**
 * The layouts of document lines, measured with Compose's [TextMeasurer] and kept in an LRU.
 *
 * A line's text is styled by the `Mark` decorations that intersect it, from EVERY range set in
 * `state.facet(decorationsFacet)` (plus the surface's own, [layout]'s `extra`): each mark's classes
 * resolve through the [EditorTheme] ([EditorTheme.styleOf]); marks without a drawn class add
 * nothing. Styles merge in facet order and the later one wins per attribute.
 *
 * Tabs are real characters in the layout, drawn as a placeholder as wide as the way to the next
 * tab stop, so every document offset is a layout offset (no display mapping anywhere).
 * Ligatures are off: a caret must be placeable between the `-` and `>` of `->`.
 *
 * Cached by (line text, spans relative to the line) under one [LayoutSignature] (font, size,
 * density, wrap width, tab size, token styles); [configure] with a different one empties it.
 */
class LineLayouts(
    private val measurer: TextMeasurer,
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val cache = LinkedHashMap<LineKey, TextLayoutResult>()
    private var signature: LayoutSignature? = null
    private var theme: EditorTheme? = null
    private var style = TextStyle.Default
    private var wrapWidth: Int? = null
    private var tabSize = 4
    private var fontSizePx = 13f
    private var density: Density = Density(1f)
    private val resolved = HashMap<Set<String>, SpanStyle?>()

    /** Height of one visual line, in pixels (a whole number, so rows never drift apart). */
    var lineHeightPx: Float = 19f
        private set

    /** The advance of one monospace cell, in pixels. */
    var charWidthPx: Float = 8f
        private set

    /** Layouts measured so far (a test and benchmark hook: a cache hit does not count). */
    var measureCount: Int = 0
        private set

    /** The widest line measured under the current configuration (horizontal scrolling). */
    var maxLineWidth: Float = 0f
        private set

    /** A line too long to measure whole reports its nominal width here. */
    fun noteWidth(width: Float) { if (width > maxLineWidth) maxLineWidth = width }

    /** The wrap width in pixels, or null when lines do not wrap. */
    val wrapWidthPx: Int? get() = wrapWidth

    val size: Int get() = cache.size

    /** Bumped whenever [configure] dropped every layout: heights measured before it are stale. */
    var generation: Int = 0
        private set

    /** Set the configuration; a different one clears every cached layout. Cheap when unchanged. */
    fun configure(theme: EditorTheme, density: Density, wrapWidthPx: Int?, tabSize: Int = 4) {
        val sig = LayoutSignature(
            theme.fontFamily, theme.fontSizeSp, theme.lineHeightFactor, density.density, density.fontScale,
            wrapWidthPx, tabSize, theme.tokens, theme.classStyles, theme.foreground,
        )
        if (sig == signature) return
        signature = sig
        this.theme = theme
        wrapWidth = wrapWidthPx
        this.tabSize = tabSize
        this.density = density
        cache.clear()
        resolved.clear()
        maxLineWidth = 0f
        generation++
        fontSizePx = with(density) { theme.fontSizeSp.sp.toPx() }
        lineHeightPx = kotlin.math.round(fontSizePx * theme.lineHeightFactor).coerceAtLeast(1f)
        style = TextStyle(
            color = theme.foreground,
            fontFamily = theme.fontFamily,
            fontSize = theme.fontSizeSp.sp,
            lineHeight = with(density) { lineHeightPx.toSp() },
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            fontFeatureSettings = "liga 0, calt 0",
        )
        val probe = measurer.measure(AnnotatedString("0".repeat(100)), style, softWrap = false, density = density)
        charWidthPx = probe.getLineRight(0) / 100f
    }

    /** The layout of 0-based [line] of [state]'s document, styled by its decorations and [extra]. */
    fun layout(state: EditorState, line: Int, extra: RangeSet<Decoration>? = null): TextLayoutResult {
        val doc = state.doc
        val from = doc.lineStart(line)
        val to = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        val text = doc.slice(from, to)
        val spans = ArrayList<LineSpan>()
        for (set in state.facet(decorationsFacet)) collect(set, from, to, spans)
        if (extra != null) collect(extra, from, to, spans)
        return layout(text, spans)
    }

    /**
     * The layout of document range [from, to) of ONE line, never wrapped: a piece of a line too long
     * to lay out whole (see [Geometry]). Cached like a line, by the piece's own text and spans.
     */
    fun layoutRange(state: EditorState, from: Int, to: Int, extra: RangeSet<Decoration>? = null): TextLayoutResult {
        val spans = ArrayList<LineSpan>()
        for (set in state.facet(decorationsFacet)) collect(set, from, to, spans)
        if (extra != null) collect(extra, from, to, spans)
        return layout(state.doc.slice(from, to), spans, noWrap = true)
    }

    /**
     * The layout of a visual row made of [parts] (a line with inline widgets or replaced ranges, a
     * fold's row): each text part's document text, and one U+FFFC per widget covered by a
     * placeholder [widgetWidth] pixels wide. Its offsets are layout offsets ([LineMap]).
     */
    internal fun layoutParts(state: EditorState, parts: List<LinePart>, extra: RangeSet<Decoration>?, widgetWidth: (LinePart) -> Float): TextLayoutResult {
        val doc = state.doc
        val sb = StringBuilder()
        val spans = ArrayList<LineSpan>()
        val widths = ArrayList<Float>()
        val widgetAt = ArrayList<Int>()
        for (p in parts) {
            if (p.isText) {
                val base = sb.length
                sb.append(doc.slice(p.from, p.to))
                val local = ArrayList<LineSpan>()
                for (set in state.facet(decorationsFacet)) collect(set, p.from, p.to, local)
                if (extra != null) collect(extra, p.from, p.to, local)
                for (sp in local) spans += LineSpan(sp.start + base, sp.end + base, sp.style)
            } else if (p.widget != null) {
                widgetAt += sb.length
                widths += widgetWidth(p)
                sb.append(WIDGET_CHAR)
            }
        }
        return layout(sb.toString(), spans, noWrap = false, widgets = widths, widgetAt = widgetAt)
    }

    /** The layout of [text] (one line, no line break) with [spans]. */
    fun layout(text: String, spans: List<LineSpan>, noWrap: Boolean = false, widgets: List<Float> = emptyList(), widgetAt: List<Int> = emptyList()): TextLayoutResult {
        val key = LineKey(text, spans, noWrap, widgets)
        cache.remove(key)?.let { cache[key] = it; return it }
        val result = measure(text, spans, noWrap, widgets, widgetAt)
        cache[key] = result
        while (cache.size > capacity) cache.remove(cache.keys.first())
        return result
    }

    private fun collect(set: RangeSet<Decoration>, from: Int, to: Int, out: MutableList<LineSpan>) {
        if (set.isEmpty) return
        for (r in set.between(from, to)) {
            val mark = r.value as? Decoration.Mark ?: continue
            val a = maxOf(r.from, from)
            val b = minOf(r.to, to)
            if (b <= a) continue
            val s = resolve(mark.classes) ?: continue
            out += LineSpan(a - from, b - from, s)
        }
    }

    private fun resolve(classes: Set<String>): SpanStyle? {
        if (resolved.containsKey(classes)) return resolved[classes]
        val t = theme
        var out: SpanStyle? = null
        for (c in classes) {
            val s = t?.styleOf(c) ?: if (c == EditorTheme.COMPOSITION_CLASS) COMPOSITION_STYLE else null
            if (s != null) out = out?.merge(s) ?: s
        }
        resolved[classes] = out
        return out
    }

    private fun measure(text: String, spans: List<LineSpan>, noWrap: Boolean, widgets: List<Float> = emptyList(), widgetAt: List<Int> = emptyList()): TextLayoutResult {
        DrawGuard.check("a line layout")
        measureCount++
        val annotated = AnnotatedString(text, spanStyles = spans.map { AnnotatedString.Range(it.style, it.start, it.end) })
        val tabs = if (text.indexOf('\t') < 0) emptyList() else tabPlaceholders(text)
        // A widget: its character covered by a box as wide as the widget (a font size tall, so the
        // row keeps its height; the widget itself is placed over the whole row).
        val placeholders = if (widgets.isEmpty()) tabs else (tabs + widgets.indices.map { i ->
            AnnotatedString.Range(Placeholder((widgets[i] / fontSizePx).em, 1.em, PlaceholderVerticalAlign.TextCenter), widgetAt[i], widgetAt[i] + 1)
        }).sortedBy { it.start }
        val w = if (noWrap) null else wrapWidth
        val result = measurer.measure(
            text = annotated,
            style = style,
            softWrap = w != null,
            placeholders = placeholders,
            constraints = if (w != null) Constraints(maxWidth = maxOf(1, w)) else Constraints(),
            density = density,
        )
        val width = if (w != null) result.size.width.toFloat() else (0 until result.lineCount).maxOf { result.getLineRight(it) }
        if (!noWrap && width > maxLineWidth) maxLineWidth = width
        return result
    }

    /** One placeholder per tab, as wide as the columns to the next tab stop (code points count one column). */
    private fun tabPlaceholders(text: String): List<AnnotatedString.Range<Placeholder>> {
        val out = ArrayList<AnnotatedString.Range<Placeholder>>()
        val cellEm = charWidthPx / fontSizePx
        var col = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\t') {
                val cols = tabSize - col % tabSize
                out += AnnotatedString.Range(Placeholder((cols * cellEm).em, 1.em, PlaceholderVerticalAlign.TextCenter), i, i + 1)
                col += cols
            } else if (!c.isLowSurrogate() && c.category != CharCategory.NON_SPACING_MARK) {
                col++
            }
            i++
        }
        return out
    }

    companion object {
        const val DEFAULT_CAPACITY = 3000

        /** The character a widget takes in a row's layout (an object replacement character). */
        const val WIDGET_CHAR = '\uFFFC'
        private val COMPOSITION_STYLE = SpanStyle(textDecoration = TextDecoration.Underline)
    }
}
