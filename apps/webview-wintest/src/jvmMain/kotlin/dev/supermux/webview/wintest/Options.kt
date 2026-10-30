package dev.supermux.webview.wintest

/**
 * One run's knobs, from `-Dwv2.*` system properties (vm.sh app passes them). The focus hand-off variants let one build
 * try every configuration of the plan's matrix.
 */
data class TestOptions(
    val dll: String,
    val scenario: String,
    val outDir: String,
    /** WebView2's parent window: 0 the AWT frame, 1 skiko's canvas, 2 our own 1×1 child of the canvas. */
    val parentMode: Int,
    /** How many trips through the AWT event queue MoveFocus waits after a press (lets AWT finish its own focus work). */
    val focusDefer: Int,
    /** After AWT's own focus request, also SetFocus the canvas through its input queue if the OS focus isn't there. */
    val forceFocusReturn: Boolean,
    val dirtyRegions: Boolean,
    val compensateOffset: Boolean,
    val browserArgs: String,
    val userDataFolder: String,
    /** A fixed surface size in pixels (the 720p and 1080p runs); 0 fills the window. */
    val surfaceWidth: Int,
    val surfaceHeight: Int,
) {
    val parentName: String
        get() = when (parentMode) {
            1 -> "canvas"
            2 -> "host"
            else -> "frame"
        }

    val label: String
        get() = "parent=$parentName focusDefer=$focusDefer returnFocus=${if (forceFocusReturn) "force" else "awt"} " +
            "dirty=${if (dirtyRegions) "on" else "off"} offset=${if (compensateOffset) "compensate" else "none"}"

    companion object {
        fun fromSystemProperties(): TestOptions = TestOptions(
            dll = System.getProperty("wv2.dll", """C:\wv2test\native\out\wv2capture.dll"""),
            scenario = System.getProperty("wv2.scenario", "manual"),
            outDir = System.getProperty("wv2.out", """C:\wv2test\results"""),
            parentMode = when (System.getProperty("wv2.parent", "frame")) {
                "canvas" -> 1
                "host" -> 2
                else -> 0
            },
            focusDefer = System.getProperty("wv2.focusDefer", "0").toInt(),
            forceFocusReturn = System.getProperty("wv2.returnFocus", "awt") == "force",
            dirtyRegions = System.getProperty("wv2.dirty", "on") == "on",
            compensateOffset = System.getProperty("wv2.offset", "none") == "compensate",
            browserArgs = System.getProperty("wv2.args", ""),
            userDataFolder = System.getProperty("wv2.udf", """C:\wv2test\udf"""),
            surfaceWidth = System.getProperty("wv2.surfaceWidth", "0").toInt(),
            surfaceHeight = System.getProperty("wv2.surfaceHeight", "0").toInt(),
        )
    }
}
