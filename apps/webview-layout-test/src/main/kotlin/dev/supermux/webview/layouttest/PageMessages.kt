package dev.supermux.webview.layouttest

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What the test page (TestPage.kt) and the native glue report, one JSON object per message. */
sealed interface PageEvent {
    /** The page's first frame is on screen: two animation frames after load. */
    data object Painted : PageEvent

    /** A pointerdown in the page, in CSS px of its viewport. */
    data class Down(val x: Float, val y: Float, val target: String) : PageEvent

    data class Scrolled(val y: Float) : PageEvent

    /** The page's text box now holds [value]. */
    data class Input(val value: String) : PageEvent

    /** requestAnimationFrame intervals in ms, sent every 500 ms. */
    data class Frames(val intervalsMillis: List<Double>) : PageEvent

    /** The page's text box as [left, top, width, height] in CSS px of the viewport, and the viewport's size. */
    data class Layout(val textArea: List<Float>, val viewportWidth: Float, val viewportHeight: Float) : PageEvent

    /** From the glue: whether the WKWebView is reachable from the window's accessibility tree, and in how many nodes. */
    data class Ax(val reaches: Boolean, val nodes: Int, val bridge: Boolean) : PageEvent

    data class Unknown(val raw: String) : PageEvent
}

object PageMessages {
    /** Never throws: anything malformed is [PageEvent.Unknown]. */
    fun parse(raw: String): PageEvent =
        runCatching { parseObject(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: PageEvent.Unknown(raw)

    private fun parseObject(o: JsonObject): PageEvent? {
        fun primitive(key: String): JsonPrimitive? = o[key] as? JsonPrimitive
        fun number(key: String): Float? = primitive(key)?.floatOrNull
        fun text(key: String): String? = primitive(key)?.contentOrNull
        return when (text("t")) {
            "painted" -> PageEvent.Painted
            "down" -> PageEvent.Down(number("x") ?: return null, number("y") ?: return null, text("target").orEmpty())
            "scroll" -> PageEvent.Scrolled(number("y") ?: return null)
            "input" -> PageEvent.Input(text("value") ?: return null)
            "frames" -> PageEvent.Frames((o["i"] as? JsonArray ?: return null).map { (it as JsonPrimitive).doubleOrNull ?: return null })
            "layout" -> {
                val textArea = (o["ta"] as? JsonArray ?: return null).map { (it as JsonPrimitive).floatOrNull ?: return null }
                if (textArea.size != 4) return null
                PageEvent.Layout(textArea, number("vw") ?: return null, number("vh") ?: return null)
            }
            "ax" -> PageEvent.Ax(primitive("reaches")?.flag() ?: return null, primitive("nodes")?.intOrNull ?: 0, primitive("bridge")?.flag() ?: false)
            else -> null
        }
    }
}

/** A JSON boolean, also when written as a number (NSJSONSerialization writes a C int as 0 or 1). */
private fun JsonPrimitive.flag(): Boolean? = booleanOrNull ?: intOrNull?.let { it != 0 }

/** The glue's view of the window ([Native.windowInfo]). Lengths in points. */
data class WindowInfo(
    val windowNumber: Long,
    val backingScale: Double,
    val contentWidth: Double,
    val contentHeight: Double,
    /** The title bar's height: a window screenshot has it above the content. */
    val titlebar: Double,
    /** The window's display's maximum refresh rate (NSScreen.maximumFramesPerSecond). */
    val maxFps: Int,
    val screen: String,
    val isKey: Boolean,
    val isActive: Boolean,
    /** Whether any part of the window is visible (NSWindowOcclusionStateVisible). Hidden windows are throttled. */
    val visible: Boolean,
    /** The current keyboard input source's id (TIS), e.g. com.apple.keylayout.Turkish. */
    val inputSource: String,
    /** CGPreflightPostEventAccess(): whether this process may post HID events. Information only (F14). */
    val canPostEvents: Boolean,
    val macos: String,
) {
    val nominalFrameMillis: Double get() = 1000.0 / maxFps.coerceAtLeast(1)

    /** Typed ASCII goes through an input method (Japanese, Chinese…): the typing probes can't be trusted then. */
    val inputMethodActive: Boolean get() = "inputmethod" in inputSource.lowercase()

    companion object {
        fun parse(raw: String): WindowInfo {
            val o = Json.parseToJsonElement(raw).jsonObject
            fun p(key: String): JsonPrimitive = o.getValue(key).jsonPrimitive
            val content = o.getValue("content").jsonArray
            return WindowInfo(
                windowNumber = p("windowNumber").longOrNull ?: 0L,
                backingScale = p("backingScale").doubleOrNull ?: 1.0,
                contentWidth = content[0].jsonPrimitive.doubleOrNull ?: 0.0,
                contentHeight = content[1].jsonPrimitive.doubleOrNull ?: 0.0,
                titlebar = p("titlebar").doubleOrNull ?: 0.0,
                maxFps = p("maxFps").intOrNull ?: 60,
                screen = p("screen").contentOrNull.orEmpty(),
                isKey = p("isKey").flag() ?: false,
                isActive = p("isActive").flag() ?: false,
                visible = p("visible").flag() ?: false,
                inputSource = p("inputSource").contentOrNull.orEmpty(),
                canPostEvents = p("canPostEvents").flag() ?: false,
                macos = p("macos").contentOrNull.orEmpty(),
            )
        }
    }
}
