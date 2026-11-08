package dev.supermux.webview.wpetest

/** One run's options, from `-Dwpe.*` (in-container.sh passes the first five; scenarios take the rest from lab.sh run). */
data class LabOptions(
    val scenario: String,
    val outDir: String,
    val stamp: String,
    val host: String,
    val nativeLib: String,
    /** WEBKIT_SKIA_ENABLE_CPU_RENDERING: the web process paints on the CPU (F13). */
    val cpu: Boolean,
    /** WebKit on GLib's global main context instead of a private one. */
    val globalContext: Boolean,
    /** The window, and the surface filling it, in dp (px at Xvfb's 96 dpi). */
    val width: Int,
    val height: Int,
    /** Multiplies the density for WPE's scale: 2 renders a 2× buffer into the same surface (damage-scale checks). */
    val scale: Double,
    /** What damage coordinates are multiplied by (1: buffer pixels). The variant if S5 finds them logical. */
    val damageScale: Double,
    /** Multiplies wheel deltas: the variant if L4 finds the direction inverted. */
    val scrollSign: Double,
    /** How long a timed scenario measures. */
    val seconds: Int,
) {
    val initFlags: Int
        get() = WpeNative.INIT_NO_SANDBOX or (if (cpu) WpeNative.INIT_CPU else 0) or
            (if (globalContext) WpeNative.INIT_GLOBAL_CONTEXT else 0)

    /** For the result lines: the variant this run is. */
    val variant: List<Pair<String, Any?>>
        get() = listOf("cpu" to cpu, "global_context" to globalContext, "scale" to scale)

    companion object {
        fun fromSystem(): LabOptions = LabOptions(
            scenario = prop("wpe.scenario", "smoke"),
            outDir = prop("wpe.out", "results"),
            stamp = prop("wpe.stamp", "now"),
            host = prop("wpe.host", "unknown"),
            nativeLib = prop("wpe.native", "native/out/libwvlab.so"),
            cpu = prop("wpe.cpu", "false").toBoolean(),
            globalContext = prop("wpe.globalContext", "false").toBoolean(),
            width = prop("wpe.width", "1280").toInt(),
            height = prop("wpe.height", "720").toInt(),
            scale = prop("wpe.scale", "1").toDouble(),
            damageScale = prop("wpe.damageScale", "1").toDouble(),
            scrollSign = prop("wpe.scrollSign", "1").toDouble(),
            seconds = prop("wpe.seconds", "10").toInt(),
        )

        private fun prop(name: String, default: String): String = System.getProperty(name)?.takeIf { it.isNotBlank() } ?: default
    }
}
