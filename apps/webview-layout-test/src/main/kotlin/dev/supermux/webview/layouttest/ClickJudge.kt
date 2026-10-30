package dev.supermux.webview.layouttest

/** Which side of the window took an input: Compose (AWT's content view) or the page (the WKWebView). */
enum class Side { Compose, Page }

/** A synthetic click at ([x], [y]) in window px, and the side that should take it. */
data class ClickProbe(val name: String, val x: Float, val y: Float, val expected: Side)

/** What a side reported receiving, at [atMillis] (System.currentTimeMillis when it arrived). */
data class Received(val side: Side, val what: String, val atMillis: Long)

data class ClickVerdict(val probe: ClickProbe, val got: Set<Side>, val what: List<String>) {
    val pass: Boolean get() = got == setOf(probe.expected)

    fun detail(): String {
        val sides = if (got.isEmpty()) "nothing" else got.sorted().joinToString("+")
        return "expected ${probe.expected}, got $sides" + if (what.isEmpty()) "" else " (${what.joinToString()})"
    }
}

object ClickJudge {
    /** Who received what in [from] .. [from] + [windowMillis]: the click [probe] made at [from]. */
    fun judge(probe: ClickProbe, received: List<Received>, from: Long, windowMillis: Long = 700): ClickVerdict {
        val inWindow = received.filter { it.atMillis in from..(from + windowMillis) }
        return ClickVerdict(probe, inWindow.mapTo(LinkedHashSet()) { it.side }, inWindow.map { "${it.side}:${it.what}" })
    }
}
