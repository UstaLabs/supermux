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
    /**
     * On window re-activation, hand focus back to the page only once AWT has given its own focus owner (the canvas) the
     * focus, instead of at windowActivated (beyond the plan's matrix: `-Dwv2.reactivate=owner`).
     */
    val reactivateOnFocusOwner: Boolean = false,
    /**
     * A press on the surface calls MoveFocus only when the page doesn't already have focus (`-Dwv2.pressFocus=unfocused`;
     * the results' proposed cure for the 81 ms press latency). The default calls it on every press.
     */
    val pressFocusOnlyWhenUnfocused: Boolean = false,
    /** The capture device's D3D11 adapter: 0 the default (the primary display's), 1 high performance (`-Dwv2.adapter=high`). */
    val adapterMode: Int = 0,
    /**
     * The engine plan's three-copy chain (`-Dwv2.chain=3`): GPU -> staging on the capture thread, staging -> the pixels
     * Compose draws from at pull, drawn as an image over those pixels (no copy at draw). The default is the test's five.
     */
    val chain3: Boolean = false,
    /** `-Dwv2.drawImage=off`: pull every frame but draw a plain rectangle, to see what drawing the image costs skiko. */
    val drawImage: Boolean = true,
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
            "dirty=${if (dirtyRegions) "on" else "off"} offset=${if (compensateOffset) "compensate" else "none"}" +
            (if (reactivateOnFocusOwner) " reactivate=owner" else "") +
            (if (pressFocusOnlyWhenUnfocused) " pressFocus=unfocused" else "") +
            (if (adapterMode == 1) " adapter=high" else "") +
            (if (chain3) " chain=3" else "") +
            (if (!drawImage) " drawImage=off" else "")

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
            reactivateOnFocusOwner = System.getProperty("wv2.reactivate", "activated") == "owner",
            pressFocusOnlyWhenUnfocused = System.getProperty("wv2.pressFocus", "always") == "unfocused",
            adapterMode = if (System.getProperty("wv2.adapter", "default") == "high") 1 else 0,
            chain3 = System.getProperty("wv2.chain", "5") == "3",
            drawImage = System.getProperty("wv2.drawImage", "on") != "off",
            dirtyRegions = System.getProperty("wv2.dirty", "on") == "on",
            compensateOffset = System.getProperty("wv2.offset", "none") == "compensate",
            browserArgs = System.getProperty("wv2.args", ""),
            userDataFolder = System.getProperty("wv2.udf", """C:\wv2test\udf"""),
            surfaceWidth = System.getProperty("wv2.surfaceWidth", "0").toInt(),
            surfaceHeight = System.getProperty("wv2.surfaceHeight", "0").toInt(),
        )
    }
}
