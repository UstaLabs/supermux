// Settings' own glyphs (#97): the prototype's icons.js, one 1.5 stroke on a 24 grid, round ends, no fills — the
// #91 rules (shell/NewTabGlyphs.kt). Tinted like any icon.
package dev.supermux.ui.settings.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

object KitGlyphs {
    val Back by lazy { glyph("back", "M19 12H5", "M11 18l-6-6 6-6") }
    val Chevron by lazy { glyph("chev", "M9.5 6.5l5.5 5.5-5.5 5.5") }
    val ChevronDown by lazy { glyph("chevD", "M6.5 9.5l5.5 5.5 5.5-5.5") }
    val Close by lazy { glyph("x", "M6.5 6.5l11 11M17.5 6.5l-11 11") }
    val Plus by lazy { glyph("plus", "M12 5v14M5 12h14") }
    val Check by lazy { glyph("check", "M5.5 12.5l4 4 9-9") }
    val Search by lazy { glyph("search", circle(11f, 11f, 6f), "M19.5 19.5l-4.2-4.2") }
    val Sliders by lazy {
        glyph("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9", circle(15f, 7f, 2f), circle(9f, 17f, 2f))
    }
    val Keyboard by lazy {
        glyph("keyboard", rect(3f, 6.5f, 18f, 11f, 1.5f), "M7 10h.01M10.3 10h.01M13.6 10h.01M17 10h.01M8 14h8")
    }
    val Mic by lazy {
        glyph("mic", rect(9f, 3.5f, 6f, 11f, 3f), "M5.5 11.5a6.5 6.5 0 0 0 13 0", "M12 18v2.5")
    }
    val Terminal by lazy { glyph("term", "M5 8l4 4-4 4", "M12 16.5h7") }
    val Download by lazy { glyph("download", "M12 4v10.5", "M7.5 10.5L12 15l4.5-4.5", "M5 19.5h14") }
    val Laptop by lazy { glyph("laptop", rect(4.5f, 5f, 15f, 10.5f, 1.5f), "M2.5 19h19") }
    val Server by lazy {
        glyph("server", rect(4f, 4.5f, 16f, 6f, 1.5f), rect(4f, 13.5f, 16f, 6f, 1.5f), "M7.5 7.5h.01M7.5 16.5h.01")
    }
    val Bot by lazy {
        glyph(
            "bot", rect(5f, 7.5f, 14f, 11f, 3f), "M12 4v3.5", "M9.5 12.5h.01M14.5 12.5h.01", "M3 12.5v2M21 12.5v2",
        )
    }
    val Memory by lazy {
        glyph(
            "memory",
            "M10 3.5l1.6 4.4 4.4 1.6-4.4 1.6L10 15.5l-1.6-4.4L4 9.5l4.4-1.6z",
            "M17.5 13.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z",
        )
    }
    val Branch by lazy {
        glyph("branch", circle(7f, 6f, 2f), circle(7f, 18f, 2f), circle(17f, 8f, 2f), "M7 8v8", "M17 10c0 4-10 2-10 6")
    }
    val Code by lazy { glyph("code", "M8.5 7.5L4 12l4.5 4.5M15.5 7.5L20 12l-4.5 4.5") }
    val Devices by lazy {
        glyph("devices", rect(3f, 5f, 12f, 9f, 1.2f), "M1.5 17.5h15", rect(16.5f, 9f, 5.5f, 10f, 1.2f))
    }
    val Link by lazy {
        glyph("link", "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1", "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1")
    }
    val Tree by lazy {
        glyph("tree", "M5 4.5v15", "M5 8.5h5M5 15.5h5", rect(10f, 6f, 9.5f, 5f, 1.2f), rect(10f, 13f, 9.5f, 5f, 1.2f))
    }
    val Shield by lazy { glyph("shield", "M12 3.5l7 3v5c0 4.3-3 7.7-7 9-4-1.3-7-4.7-7-9v-5z") }
    val Flask by lazy {
        glyph("flask", "M9.5 3.5h5M10.5 3.5v5.5L5 18.5a1.5 1.5 0 0 0 1.3 2h11.4a1.5 1.5 0 0 0 1.3-2L13.5 9V3.5", "M7.5 14.5h9")
    }
    val Restart by lazy { glyph("restart", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4") }
    val Disk by lazy {
        glyph(
            "disk", ellipse(12f, 6.5f, 7.5f, 2.5f), "M4.5 6.5v11c0 1.4 3.4 2.5 7.5 2.5s7.5-1.1 7.5-2.5v-11",
            "M4.5 12c0 1.4 3.4 2.5 7.5 2.5s7.5-1.1 7.5-2.5",
        )
    }
    val Gauge by lazy { glyph("gauge", "M4.5 16a7.5 7.5 0 1 1 15 0", "M12 16l3.5-4") }
    val Trash by lazy { glyph("trash", "M5 7h14M10 7V5h4v2", "M7 7l1 12.5h8L17 7") }
    val External by lazy {
        glyph("ext", "M14 4h6v6", "M20 4l-8.5 8.5", "M18 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 18.5v-11A1.5 1.5 0 0 1 5.5 6H10")
    }
    val Globe by lazy {
        glyph(
            "globe", circle(12f, 12f, 8.5f), "M3.5 12h17",
            "M12 3.5c2.4 2.3 3.6 5.1 3.6 8.5s-1.2 6.2-3.6 8.5c-2.4-2.3-3.6-5.1-3.6-8.5s1.2-6.2 3.6-8.5z",
        )
    }
    val User by lazy { glyph("user", circle(12f, 8.5f, 3.5f), "M5 19.5c1.2-3.3 3.8-5 7-5s5.8 1.7 7 5") }
    val Key by lazy { glyph("key", circle(8f, 14f, 4f), "M11 11l8.5-8.5M16 6l2.5 2.5") }
    val Phone by lazy { glyph("phone", rect(7f, 3f, 10f, 18f, 2f), "M11 17.5h2") }
    val Qr by lazy {
        glyph(
            "qr", rect(4f, 4f, 6f, 6f, 1f), rect(14f, 4f, 6f, 6f, 1f), rect(4f, 14f, 6f, 6f, 1f),
            "M14 14h2.5v2.5H14zM17.5 17.5H20V20h-2.5z",
        )
    }

    /** Every glyph, for the build test. */
    val all: List<ImageVector> by lazy {
        listOf(
            Back, Chevron, ChevronDown, Close, Plus, Check, Search, Sliders, Keyboard, Mic, Terminal, Download,
            Laptop, Server, Bot, Memory, Branch, Code, Devices, Link, Tree, Shield, Flask, Restart, Disk, Gauge,
            Trash, External, Globe, User, Key, Phone, Qr,
        )
    }

    private fun glyph(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(
            name = "settings.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        )
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun circle(cx: Float, cy: Float, r: Float) = ellipse(cx, cy, r, r)

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) =
        "M${cx - rx} ${cy}a$rx $ry 0 1 0 ${2 * rx} 0a$rx $ry 0 1 0 ${-2 * rx} 0"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
        val iw = w - 2 * r
        val ih = h - 2 * r
        return "M${x + r} ${y}h${iw}a$r $r 0 0 1 $r ${r}v${ih}a$r $r 0 0 1 ${-r} ${r}" +
            "h${-iw}a$r $r 0 0 1 ${-r} ${-r}v${-ih}a$r $r 0 0 1 $r ${-r}z"
    }
}
