@file:JsModule("./syntax-test-setup.mjs")

package dev.supermux.editor.syntax

internal external interface GapMonitor : JsAny {
    fun stop(): Gaps
}

internal external interface Gaps : JsAny {
    /** The longest the thread was held (ms). */
    val max: Double
    val ticks: Int
    val total: Double
    /** The five longest (ms), longest first. */
    val top: String
}

internal external fun startGapMonitor(): GapMonitor
