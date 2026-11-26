// #165: an applet's widget as the broker sends it (GET /applets → widgetDoc): native blocks laid out for every size.
// Read by hand, block by block, so a block type a newer broker adds is skipped instead of failing the whole widget.
// The iOS widget extension reads the same JSON (AppletWidgetDoc.swift mirrors these names).
package dev.supermux.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** The Home screen sizes a layout is given for (the broker fills every one). */
enum class WidgetSize(val key: String) {
    Small("small"), Medium("medium"), Large("large"), XLarge("xlarge"), Wide("wide"), Tall("tall"), Slim("slim");

    companion object {
        /** Android: the layout for a widget [cols]×[rows] cells, as the prototype maps it (2×1 slim … 4×4 large). */
        fun forCells(cols: Int, rows: Int): WidgetSize = when {
            rows <= 1 -> if (cols >= 3) Wide else Slim
            cols <= 2 && rows >= 3 -> Tall
            cols <= 1 -> Tall
            cols <= 3 && rows <= 2 -> Small
            rows <= 2 -> Medium
            else -> Large
        }
    }
}

sealed interface WidgetBlock {
    data class Header(val title: String?, val sub: String?) : WidgetBlock
    data class Stat(val value: String, val label: String?, val trend: String?, val tone: String?, val size: String?) : WidgetBlock
    data class Stats(val items: List<Item>) : WidgetBlock { data class Item(val value: String, val label: String?, val tone: String?) }
    data class Progress(val value: Float, val label: String?, val right: String?) : WidgetBlock
    data class Ring(val value: Float, val text: String?, val sub: String?) : WidgetBlock
    data class Chart(val line: Boolean, val points: List<Double>, val labels: List<String>, val label: String?) : WidgetBlock
    data class ListRows(val title: String?, val rows: List<Row>) : WidgetBlock
    data class Toggles(val grid: Boolean, val items: List<Toggle>) : WidgetBlock
    data class Buttons(val items: List<WidgetButton>) : WidgetBlock
    data class Image(val src: String, val label: String?) : WidgetBlock
    /** A picture of the applet's widget.html part, drawn by the app; [alt] shows until it exists. Graphics only. */
    data class Picture(val part: String, val height: Int, val label: String, val caption: String?, val alt: WidgetBlock?) : WidgetBlock
    data class Text(val text: String) : WidgetBlock
    data class Columns(val cols: List<List<WidgetBlock>>, val weights: List<Float>) : WidgetBlock
    data class Gap(val size: String?) : WidgetBlock
    data object Grow : WidgetBlock

    data class Row(val title: String, val sub: String?, val value: String?, val color: String?, val href: String?, val button: WidgetButton?)
    data class Toggle(val title: String, val on: Boolean, val sub: String?, val action: String, val args: JsonObject?)
}

/** A button: [action] is one of applet.json's widget actions; [args] go with it. */
data class WidgetButton(val title: String, val action: String, val args: JsonObject?, val fill: Boolean)

data class WidgetCircular(val text: String, val ring: Float?, val label: String?, val action: WidgetButton?)
data class WidgetRectangular(val lines: List<String>, val button: WidgetButton?)

data class WidgetDoc(
    val href: String?,
    /** "full": the whole widget is one picture (the escape hatch); [label] is what VoiceOver says. */
    val fullSnapshot: Boolean,
    val label: String?,
    private val layouts: Map<WidgetSize, List<WidgetBlock>>,
    val circular: WidgetCircular,
    val rectangular: WidgetRectangular,
    val inline: String,
    /** Later layouts, each from its time on (epoch ms), oldest first. */
    val timeline: List<Pair<Long, Map<WidgetSize, List<WidgetBlock>>>>,
) {
    /** The blocks [size] shows at [nowMs] (a timeline entry that has begun overrides it). */
    fun layout(size: WidgetSize, nowMs: Long = 0L): List<WidgetBlock> =
        timeline.lastOrNull { it.first <= nowMs && size in it.second }?.second?.get(size) ?: layouts[size].orEmpty()

    /** The times a widget must be redrawn at without a reload (timeline entries still ahead). */
    fun changesAfter(nowMs: Long): List<Long> = timeline.map { it.first }.filter { it > nowMs }

    /** Every picture part [size] can show, with its height ([Picture]), for the app to draw. */
    fun pictures(size: WidgetSize): List<WidgetBlock.Picture> = pictureSlots(size).map { it.first }

    /** Each picture [size] shows with the share of the widget's inner width it gets (a column's share inside columns). */
    fun pictureSlots(size: WidgetSize): List<Pair<WidgetBlock.Picture, Float>> {
        val out = mutableListOf<Pair<WidgetBlock.Picture, Float>>()
        fun walk(bs: List<WidgetBlock>, share: Float) {
            for (b in bs) when (b) {
                is WidgetBlock.Picture -> out += b to share
                is WidgetBlock.Columns -> { val t = b.weights.sum().takeIf { it > 0f } ?: 1f; b.cols.forEachIndexed { i, c -> walk(c, share * b.weights[i] / t) } }
                else -> {}
            }
        }
        walk(layouts[size].orEmpty(), 1f)
        timeline.forEach { walk(it.second[size].orEmpty(), 1f) }
        return out.distinctBy { it.first.part }
    }

    companion object {
        /** The document in [json] (the broker's widgetDoc), or null when it isn't one. */
        fun parse(json: JsonElement?): WidgetDoc? {
            val o = json as? JsonObject ?: return null
            val l = o["layouts"] as? JsonObject ?: return null
            val layouts = sizes(l)
            if (layouts.isEmpty()) return null
            val c = l["circular"] as? JsonObject
            val r = l["rectangular"] as? JsonObject
            return WidgetDoc(
                href = o.str("href"),
                fullSnapshot = o.str("snapshot") == "full",
                label = o.str("label"),
                layouts = layouts,
                circular = WidgetCircular(c?.str("text") ?: "", c?.num("ring")?.toFloat(), c?.str("label"), (c?.get("action") as? JsonObject)?.let { a -> a.str("action")?.let { WidgetButton("", it, a["args"] as? JsonObject, false) } }),
                rectangular = WidgetRectangular((r?.get("lines") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(), button(r?.get("button"))),
                inline = (l["inline"] as? JsonPrimitive)?.contentOrNull ?: "",
                timeline = (o["timeline"] as? JsonArray).orEmpty().mapNotNull { t ->
                    val e = t as? JsonObject ?: return@mapNotNull null
                    val at = e.str("at")?.let { dev.supermux.chat.parseIso8601Millis(it) } ?: return@mapNotNull null
                    val ls = sizes(e["layouts"] as? JsonObject ?: return@mapNotNull null)
                    if (ls.isEmpty()) null else at to ls
                }.sortedBy { it.first },
            )
        }

        private fun sizes(l: JsonObject): Map<WidgetSize, List<WidgetBlock>> =
            WidgetSize.entries.mapNotNull { s -> (l[s.key] as? JsonArray)?.let { s to blocks(it) } }.toMap()

        private fun blocks(a: JsonArray): List<WidgetBlock> = a.mapNotNull { block(it) }

        private fun block(e: JsonElement?): WidgetBlock? {
            val b = e as? JsonObject ?: return null
            return when (b.str("type")) {
                "header" -> WidgetBlock.Header(b.str("title"), b.str("sub"))
                "stat" -> b.str("value")?.let { WidgetBlock.Stat(it, b.str("label"), b.str("trend"), b.str("tone"), b.str("size")) }
                "stats" -> WidgetBlock.Stats(b.objs("items").mapNotNull { i -> i.str("value")?.let { WidgetBlock.Stats.Item(it, i.str("label"), i.str("tone")) } })
                "progress" -> WidgetBlock.Progress(b.frac("value"), b.str("label"), b.str("right"))
                "ring" -> WidgetBlock.Ring(b.frac("value"), b.str("text"), b.str("sub"))
                "chart" -> WidgetBlock.Chart(
                    b.str("kind") == "line",
                    (b["points"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.doubleOrNull },
                    (b["labels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                    b.str("label"),
                ).takeIf { it.points.size >= 2 }
                "list" -> WidgetBlock.ListRows(b.str("title"), b.objs("rows").mapNotNull { r ->
                    r.str("title")?.let { WidgetBlock.Row(it, r.str("sub"), r.str("value"), r.str("color"), r.str("href"), button(r["button"])) }
                })
                "toggles" -> WidgetBlock.Toggles(b.bool("grid"), b.objs("items").mapNotNull { t ->
                    val title = t.str("title") ?: return@mapNotNull null
                    val action = t.str("action") ?: return@mapNotNull null
                    WidgetBlock.Toggle(title, t.bool("on"), t.str("sub"), action, t["args"] as? JsonObject)
                })
                "buttons" -> WidgetBlock.Buttons(b.objs("items").mapNotNull { button(it) })
                "image" -> b.str("src")?.let { WidgetBlock.Image(it, b.str("label")) }
                "html" -> {
                    val part = b.str("part") ?: return null
                    val label = b.str("label") ?: return null
                    WidgetBlock.Picture(part, b.num("height")?.toInt() ?: 60, label, b.str("caption"), block(b["alt"]))
                }
                "text" -> b.str("text")?.let { WidgetBlock.Text(it) }
                "columns" -> {
                    val cols = (b["cols"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonArray)?.let(::blocks) }
                    if (cols.isEmpty()) null else WidgetBlock.Columns(cols, (b["w"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toFloat() }?.takeIf { it.size == cols.size } ?: cols.map { 1f })
                }
                "gap" -> WidgetBlock.Gap(b.str("size"))
                "grow" -> WidgetBlock.Grow
                else -> null
            }
        }

        private fun button(e: JsonElement?): WidgetButton? {
            val b = e as? JsonObject ?: return null
            val title = b.str("title") ?: return null
            val action = b.str("action") ?: return null
            return WidgetButton(title, action, b["args"] as? JsonObject, b.str("style") == "fill")
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.doubleOrNull != null }?.contentOrNull?.takeIf { it.isNotEmpty() }
        private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
        private fun JsonObject.frac(k: String): Float = (num(k) ?: 0.0).coerceIn(0.0, 1.0).toFloat()
        private fun JsonObject.objs(k: String): List<JsonObject> = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }
}

/** A picture's file beside the snapshot: one per host, applet, size, part and look. */
fun widgetPictureFile(hostId: String, applet: String, size: WidgetSize, part: String, dark: Boolean): String =
    "pic-${widgetFileKey("$hostId/$applet/${size.key}/$part/${if (dark) "dark" else "light"}")}.png"
