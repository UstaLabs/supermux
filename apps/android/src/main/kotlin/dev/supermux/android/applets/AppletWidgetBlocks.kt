// #165: an applet's widget drawn from its native blocks (WidgetDoc), in Glance: the layout for the shape the launcher
// gave it (2×1 … 4×4), the system's widget colours with the applet's accent, 48 dp tap targets, rows that open the
// applet at their item and buttons and toggles that run the applet's actions.
package dev.supermux.android.applets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.CheckboxDefaults
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as UnitColorProvider
import dev.supermux.net.WidgetApplet
import dev.supermux.net.WidgetBlock
import dev.supermux.net.WidgetButton
import dev.supermux.net.WidgetDoc
import dev.supermux.net.WidgetSize
import dev.supermux.net.widgetPictureFile
import java.io.File

/** The shapes the launcher picks from: Glance draws each once and shows the best fit (2×1, 2×2, 4×1, 4×2, 2×4, 4×4). */
internal val WIDGET_SHAPES: Map<DpSize, WidgetSize> = linkedMapOf(
    DpSize(110.dp, 40.dp) to WidgetSize.Slim,
    DpSize(110.dp, 110.dp) to WidgetSize.Small,
    DpSize(250.dp, 40.dp) to WidgetSize.Wide,
    DpSize(250.dp, 110.dp) to WidgetSize.Medium,
    DpSize(110.dp, 250.dp) to WidgetSize.Tall,
    DpSize(250.dp, 250.dp) to WidgetSize.Large,
)

/** What the blocks need beyond themselves. */
internal class BlockScope(
    val context: Context,
    val hostId: String,
    val applet: WidgetApplet,
    val doc: WidgetDoc,
    val size: WidgetSize,
    val accent: Color,
    val dark: Boolean,
    val icon: Bitmap?,
    /** The width blocks get here, inside the widget's padding (a column's share inside columns). */
    val width: Dp,
)

private const val PAD = 16

/** The whole widget for [applet]'s document, at the size Glance is drawing. */
@Composable
internal fun DocWidget(context: Context, hostId: String, applet: WidgetApplet, doc: WidgetDoc, icon: Bitmap?, dark: Boolean) {
    val drawn = LocalSize.current
    val size = WIDGET_SHAPES[drawn] ?: WidgetSize.forCells(if (drawn.width >= 250.dp) 4 else 2, if (drawn.height >= 250.dp) 4 else if (drawn.height >= 110.dp) 2 else 1)
    val accent = applet.accent?.let(::parseColor) ?: Color(0xFF4B6BEA)
    val s = BlockScope(context, hostId, applet, doc, size, accent, dark, icon, drawn.width - (PAD * 2).dp)
    val open = openAppletAt(context, hostId, applet, doc.href)
    Column(widgetFrame().padding(PAD.dp).clickable(open)) {
        Blocks(s, doc.layout(size, System.currentTimeMillis()))
    }
}

@Composable
private fun ColumnScope.Blocks(s: BlockScope, blocks: List<WidgetBlock>) {
    for (b in blocks.take(10)) Block(s, b)
}

@Composable
private fun ColumnScope.Block(s: BlockScope, b: WidgetBlock) {
    val fg = GlanceTheme.colors.onSurface
    val muted = GlanceTheme.colors.onSurfaceVariant
    when (b) {
        is WidgetBlock.Header -> Row(GlanceModifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val bmp = s.icon ?: AppletIcons.bitmap(null, s.applet.title, 96, inset = 0f, circle = true, letter = 0.42f)
            Image(ImageProvider(bmp), contentDescription = null, modifier = GlanceModifier.size(22.dp))
            Spacer(GlanceModifier.width(8.dp))
            Text(b.title ?: s.applet.title, maxLines = 1, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg), modifier = GlanceModifier.defaultWeight())
            if (b.sub != null && s.width > 150.dp) Text(b.sub!!, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = muted))
        }
        is WidgetBlock.Stat -> Column {
            val big = when (b.size) { "sm" -> 24.sp; "lg" -> 44.sp; else -> 34.sp }
            Text(b.value, maxLines = 1, style = TextStyle(fontSize = big, fontWeight = FontWeight.Bold, color = if (b.trend == null) tone(b.tone) ?: fg else fg))
            b.label?.let { Text(it, maxLines = 2, style = TextStyle(fontSize = 13.sp, color = muted)) }
            b.trend?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tone(b.tone) ?: muted)) }
        }
        is WidgetBlock.Stats -> Row(GlanceModifier.fillMaxWidth()) {
            b.items.take(4).forEachIndexed { i, it ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Column(GlanceModifier.defaultWeight().background(GlanceTheme.colors.secondaryContainer).cornerRadius(16.dp).padding(horizontal = 9.dp, vertical = 7.dp)) {
                    Text(it.value, maxLines = 1, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = tone(it.tone) ?: GlanceTheme.colors.onSecondaryContainer))
                    it.label?.let { l -> Text(l, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSecondaryContainer)) }
                }
            }
        }
        is WidgetBlock.Progress -> Column(GlanceModifier.fillMaxWidth()) {
            Row(GlanceModifier.fillMaxWidth()) {
                Text(b.label ?: "", maxLines = 1, style = TextStyle(fontSize = 12.sp, color = muted), modifier = GlanceModifier.defaultWeight())
                b.right?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = muted)) }
            }
            Spacer(GlanceModifier.height(5.dp))
            LinearProgressIndicator(b.value, GlanceModifier.fillMaxWidth().height(6.dp), color = UnitColorProvider(s.accent), backgroundColor = GlanceTheme.colors.secondaryContainer)
        }
        is WidgetBlock.Ring -> Box(GlanceModifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val px = dp(s.context, 64)
            Image(ImageProvider(ringBitmap(px, b.value, s.accent, s.dark)), contentDescription = null, modifier = GlanceModifier.size(64.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                b.text?.let { Text(it, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = fg)) }
                b.sub?.let { Text(it, style = TextStyle(fontSize = 11.sp, color = muted)) }
            }
        }
        is WidgetBlock.Chart -> Column(GlanceModifier.fillMaxWidth()) {
            b.label?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = muted)) }
            val h = 40
            Image(
                ImageProvider(chartBitmap(dp(s.context, s.width.value.toInt()), dp(s.context, h), b, s.accent)),
                contentDescription = b.label, contentScale = ContentScale.FillBounds,
                modifier = GlanceModifier.fillMaxWidth().height(h.dp),
            )
        }
        is WidgetBlock.ListRows -> Column(GlanceModifier.fillMaxWidth()) {
            b.title?.let { Text(it.uppercase(), maxLines = 1, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = muted), modifier = GlanceModifier.padding(bottom = 2.dp)) }
            b.rows.take(rowsFor(s.size)).forEach { r ->
                Row(GlanceModifier.fillMaxWidth().height(48.dp).clickable(openAppletAt(s.context, s.hostId, s.applet, r.href ?: s.doc.href)), verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(dotBitmap(dp(s.context, 8), r.color?.let(::parseColor) ?: s.accent)), contentDescription = null, modifier = GlanceModifier.size(8.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight()) {
                        // A row with a button gives its title two lines and drops the meta line (#165 review).
                        Text(r.title, maxLines = if (r.button != null) 2 else 1, style = TextStyle(fontSize = 13.sp, color = fg))
                        if (r.button == null) r.sub?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = muted)) }
                    }
                    r.value?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = muted)) }
                    r.button?.let { Spacer(GlanceModifier.width(6.dp)); ActionPill(s, it, false) }
                }
            }
        }
        is WidgetBlock.Toggles -> Column(GlanceModifier.fillMaxWidth()) {
            for (t in b.items.take(if (s.size == WidgetSize.Small || s.size == WidgetSize.Medium) 3 else 6)) {
                Row(GlanceModifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    CheckBox(
                        checked = t.on,
                        onCheckedChange = runAction(s, WidgetButton(t.title, t.action, t.args, false)),
                        text = t.title,
                        colors = CheckboxDefaults.colors(checkedColor = UnitColorProvider(s.accent), uncheckedColor = muted),
                        style = TextStyle(fontSize = 13.sp, color = if (t.on) muted else fg, textDecoration = if (t.on) TextDecoration.LineThrough else TextDecoration.None),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    t.sub?.let { Text(it, style = TextStyle(fontSize = 11.sp, color = muted)) }
                }
            }
        }
        is WidgetBlock.Buttons -> Row(GlanceModifier.fillMaxWidth()) {
            b.items.forEachIndexed { i, it ->
                if (i > 0) Spacer(GlanceModifier.width(6.dp))
                Box(GlanceModifier.defaultWeight()) { ActionPill(s, it, true) }
            }
        }
        is WidgetBlock.Picture -> {
            val bmp = picture(s, b)
            if (bmp == null) {
                b.alt?.let { Block(s, it) }
            } else {
                b.caption?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 11.sp, color = muted)) }
                Image(
                    ImageProvider(bmp), contentDescription = b.label, contentScale = ContentScale.FillBounds,
                    modifier = GlanceModifier.fillMaxWidth().height((b.height - if (b.caption != null) 17 else 0).dp).cornerRadius(10.dp),
                )
            }
        }
        is WidgetBlock.Image -> {} // Not drawn on Android yet: an image needs fetching with the host's key.
        is WidgetBlock.Text -> Text(b.text, maxLines = 2, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg), modifier = GlanceModifier.padding(vertical = 3.dp))
        is WidgetBlock.Columns -> Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
            val total = b.weights.sum().takeIf { it > 0f } ?: 1f
            val gap = 14
            b.cols.forEachIndexed { i, col ->
                if (i > 0) Spacer(GlanceModifier.width(gap.dp))
                val w = (s.width - (gap * (b.cols.size - 1)).dp) * (b.weights[i] / total)
                Column(GlanceModifier.width(w).fillMaxSize()) { Blocks(BlockScope(s.context, s.hostId, s.applet, s.doc, s.size, s.accent, s.dark, s.icon, w), col) }
            }
        }
        is WidgetBlock.Gap -> Spacer(GlanceModifier.height(when (b.size) { "s" -> 5.dp; "l" -> 12.dp; else -> 8.dp }))
        WidgetBlock.Grow -> Spacer(GlanceModifier.defaultWeight())
    }
}

/** A button: 40 dp to see, 48 dp to hit. */
@Composable
private fun ActionPill(s: BlockScope, b: WidgetButton, wide: Boolean) {
    val fill = b.fill
    Box(
        GlanceModifier.height(48.dp).let { if (wide) it.fillMaxWidth() else it }.clickable(runAction(s, b)).semantics { contentDescription = b.title },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            GlanceModifier.height(40.dp).let { if (wide) it.fillMaxWidth() else it }.cornerRadius(20.dp)
                .background(if (fill) UnitColorProvider(s.accent) else GlanceTheme.colors.secondaryContainer).padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(b.title, maxLines = 1, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (fill) UnitColorProvider(Color.White) else UnitColorProvider(s.accent)))
        }
    }
}

private fun rowsFor(size: WidgetSize): Int = when (size) { WidgetSize.Slim, WidgetSize.Wide -> 1; WidgetSize.Small, WidgetSize.Medium -> 2; WidgetSize.Tall -> 4; else -> 5 }

@Composable
private fun tone(t: String?): androidx.glance.unit.ColorProvider? = when (t) {
    "up", "ok" -> ColorProvider(day = Color(0xFF2E8B4A), night = Color(0xFF7BD68F))
    "down", "bad" -> ColorProvider(day = Color(0xFFC9302C), night = Color(0xFFFF8A80))
    "warn" -> ColorProvider(day = Color(0xFFB86A00), night = Color(0xFFFFB74D))
    else -> null
}

/** The run of a widget action as a tap: parameters for [WidgetActionCallback]. */
internal fun runAction(s: BlockScope, b: WidgetButton): Action = actionRunCallback<WidgetActionCallback>(
    actionParametersOf(
        WidgetActionCallback.HOST to s.hostId,
        WidgetActionCallback.APPLET to s.applet.name,
        WidgetActionCallback.TITLE to s.applet.title,
        WidgetActionCallback.ACTION to b.action,
        WidgetActionCallback.ARGS to (b.args?.toString() ?: "{}"),
        WidgetActionCallback.CONFIRM to (b.action in s.applet.confirm),
    ),
)

private fun picture(s: BlockScope, b: WidgetBlock.Picture): Bitmap? {
    val name = s.applet.pictures["${s.size.key}/${b.part}/${if (s.dark) "dark" else "light"}"] ?: widgetPictureFile(s.hostId, s.applet.name, s.size, b.part, s.dark)
    val f = File(AppletWidgetStore.dir(s.context), name)
    return if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
}

internal fun parseColor(hex: String): Color? = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

private fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)

private fun ringBitmap(px: Int, value: Float, color: Color, dark: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val stroke = px * 0.11f
    val r = RectF(stroke / 2, stroke / 2, px - stroke / 2, px - stroke / 2)
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
    p.color = if (dark) 0x33FFFFFF else 0x1F000000
    c.drawArc(r, 0f, 360f, false, p)
    p.color = color.toArgb()
    c.drawArc(r, -90f, 360f * value.coerceIn(0f, 1f), false, p)
    return bmp
}

private fun chartBitmap(w: Int, h: Int, b: WidgetBlock.Chart, color: Color): Bitmap {
    val bmp = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val max = b.points.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
    val n = b.points.size
    if (b.line) {
        val path = Path()
        b.points.forEachIndexed { i, v ->
            val x = i * (w - 1f) / (n - 1)
            val y = 3 + (1 - (v / max).toFloat()) * (h - 6)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path(path).apply { lineTo(w.toFloat(), h.toFloat()); lineTo(0f, h.toFloat()); close() }
        c.drawPath(fill, Paint(p).apply { alpha = 46 })
        c.drawPath(path, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = h * 0.06f })
    } else {
        val gap = 2f * (w / 300f).coerceAtLeast(1f)
        val bw = (w - gap * (n - 1)) / n
        b.points.forEachIndexed { i, v ->
            val bh = ((v / max).toFloat() * h).coerceAtLeast(2f)
            p.alpha = if (i < n - 1) 150 else 255
            c.drawRoundRect(RectF(i * (bw + gap), h - bh, i * (bw + gap) + bw, h.toFloat()), 3f, 3f, p)
        }
    }
    return bmp
}

private fun dotBitmap(px: Int, color: Color): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
    return bmp
}

private fun Color.toArgb(): Int = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
