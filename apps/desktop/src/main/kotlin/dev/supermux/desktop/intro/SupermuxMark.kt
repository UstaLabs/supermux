// The supermux logo mark, ported from assets/logo/supermux.svg so the first-run intro (and any
// future brand surface) can draw the REAL mark with Compose paths instead of shipping a raster.
// The SVG is font-glyph output: three filled contours inside a 1015×1015 viewBox, y-flipped
// through nested group transforms — applyMarkTransform() reproduces that exact transform stack.
package dev.supermux.desktop.intro

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.Offset

object SupermuxMark {
    /** SVG viewBox edge length (the mark is square). */
    const val VIEW_BOX = 1015f

    // In viewBox space (after the group transforms below) the mark occupies x[165,865] y[70,945];
    // its visual center is ≈ (515, 507.5) — within 1% of the box center, so centering the box
    // centers the mark. Gradient endpoints should use these bounds, not the raw glyph coords.
    val GRADIENT_START = Offset(165f, 70f)
    val GRADIENT_END = Offset(865f, 945f)

    // The three contour `d` attributes, copied verbatim from assets/logo/supermux.svg (top bar,
    // middle bar, bottom bar incl. its inner arrow sub-contour).
    private val DATA = listOf(
        "M2880 8741 c-80 -16 -142 -67 -182 -149 l-33 -67 0 -384 0 -384 180 -84 c471 -221 866 -413 1772 -863 l982 -488 91 -89 c137 -131 239 -293 288 -458 13 -44 14 -266 13 -1730 -1 -924 -4 -1694 -7 -1710 l-5 -31 218 -104 c348 -165 369 -173 424 -167 84 10 149 59 192 146 21 45 22 58 27 416 3 204 5 1176 5 2160 l0 1790 -22 76 c-46 158 -130 303 -212 368 -22 17 -70 44 -108 60 -37 16 -225 106 -418 201 -192 95 -431 212 -530 260 -572 278 -858 416 -920 445 -66 30 -221 104 -735 350 -118 57 -359 174 -535 260 -280 137 -402 188 -440 184 -5 -1 -26 -4 -45 -8z",
        "M1702 7854 c-83 -41 -133 -114 -150 -220 -6 -32 -9 -242 -8 -468 l1 -409 370 -179 c780 -377 1362 -663 1965 -964 566 -282 631 -317 689 -370 137 -125 245 -295 293 -461 l23 -78 -4 -1720 -4 -1720 287 -138 c270 -129 290 -137 347 -137 73 0 123 23 170 79 75 86 72 59 79 806 4 369 9 1368 11 2220 l3 1550 -27 80 c-38 113 -112 239 -182 311 -56 57 -81 72 -410 236 -373 187 -1832 909 -2100 1038 -88 43 -317 155 -510 250 -472 232 -593 290 -639 306 -63 23 -144 18 -204 -12z",
        "M230 7051 c-113 -35 -195 -143 -220 -291 -8 -46 -10 -685 -8 -2190 l3 -2125 28 -78 c49 -141 92 -210 183 -299 33 -32 75 -68 94 -81 19 -13 224 -115 455 -227 231 -112 557 -270 725 -353 400 -196 1226 -601 1510 -739 124 -61 367 -180 540 -265 873 -431 845 -419 936 -394 76 21 162 110 183 191 6 20 12 953 16 2210 l7 2175 -27 90 c-36 123 -84 217 -153 297 -91 107 55 29 -1278 683 -451 222 -858 421 -905 443 -107 50 -843 410 -1454 711 -258 128 -488 236 -510 241 -47 11 -90 11 -125 1z m3217 -2765 c104 -49 137 -149 88 -266 -49 -117 -364 -817 -550 -1225 -101 -220 -229 -501 -284 -624 -135 -296 -144 -313 -179 -333 -17 -10 -50 -18 -76 -18 -38 0 -52 6 -82 33 l-36 32 -104 475 c-114 516 -116 520 -192 559 -20 10 -116 42 -212 71 -437 131 -690 210 -709 222 -25 15 -61 84 -61 116 0 30 18 74 42 99 12 13 80 46 152 74 72 28 203 80 291 114 172 68 1040 402 1345 517 102 39 248 95 325 124 77 29 151 54 165 54 14 0 48 -11 77 -24z",
    )

    /** Parsed once; raw SVG glyph coordinates — only meaningful inside [applyMarkTransform]. */
    val paths: List<Path> by lazy { DATA.map { PathParser().parsePathString(it).toPath() } }

    /**
     * One [PathMeasure] per path for stroke "draw-on" trims. Compose's PathMeasure only walks the
     * FIRST contour of a path — for the bottom bar (which carries the inner arrow as a second
     * contour) that means the trim sweeps the outer bar and the arrow pops in with the fill.
     */
    val measures: List<PathMeasure> by lazy {
        paths.map { PathMeasure().apply { setPath(it, false) } }
    }

    /**
     * Reproduces the SVG group stack — translate(165 70) → translate(0 875) → scale(0.1 -0.1) —
     * preceded by fitting the 1015-unit viewBox into a [sizePx] square at [topLeft]. After this,
     * drawing coordinates are viewBox units (e.g. [GRADIENT_START]/[GRADIENT_END]) and one pixel
     * of screen width equals 1f / totalScale path units, where totalScale = (sizePx/VIEW_BOX)*0.1f.
     */
    fun DrawTransform.applyMarkTransform(sizePx: Float, topLeft: Offset) {
        translate(topLeft.x, topLeft.y)
        scale(sizePx / VIEW_BOX, sizePx / VIEW_BOX, Offset.Zero)
        translate(165f, 70f)
        translate(0f, 875f)
        scale(0.1f, -0.1f, Offset.Zero)
    }
}
