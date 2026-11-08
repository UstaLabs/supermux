package dev.supermux.webview.wintest

/** CursorChanged → the AWT cursor, read back from the OS over each CSS cursor target (C8). */
suspend fun Harness.cursor() {
    requireFrames()
    val targets = listOf("pointer", "text", "crosshair", "wait", "progress", "not-allowed", "move", "ew-resize", "ns-resize", "custom")
    var passed = 0
    for (css in targets) {
        val r = pageRectOnScreen("c-$css")
        val expected = CursorMapping.expectedFor(css)
        val start = System.nanoTime()
        moveTo(r.x + r.width / 2, r.y + r.height / 2)
        val ok = waitUntil(500, 5) { cursorMatches(WinNative.cursorInfo(), expected) }
        val ms = (System.nanoTime() - start) / 1e6
        if (ok) passed++
        report.check("C-$css", ok, "seen" to WinNative.cursorInfo(), "expected" to expected.name, "ms" to ms)
    }
    val gap = composeGapPoint()
    moveTo(gap.x, gap.y)
    val arrow = waitUntil(500, 5) { WinNative.cursorInfo().startsWith("IDC_ARROW|") }
    report.check("C-back-to-compose", arrow, "seen" to WinNative.cursorInfo())
    report.info("C-summary", "passed" to passed, "of" to targets.size)
}

/** GetCursorInfo's "name|w|h|hotX|hotY" against what the CSS cursor should show. */
fun cursorMatches(info: String, expected: CursorMapping.Expected): Boolean {
    val parts = info.split('|')
    if (parts.size < 5 || parts[0] != expected.name) return false
    val system = expected.sameImageAs
    if (system != null) return parts.drop(1) == WinNative.standardCursorInfo(system).split('|')
    if (expected.name != "custom") return true
    return parts[1] == "32" && parts[2] == "32" && parts[3] == "16" && parts[4] == "16"
}
