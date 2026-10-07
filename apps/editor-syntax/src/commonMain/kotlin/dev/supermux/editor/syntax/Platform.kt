package dev.supermux.editor.syntax

/** [SyntaxLimits.parseSliceMicros]' default here: 50 ms natively, 8 ms on the web (a frame is 16 ms). */
internal expect val PLATFORM_PARSE_SLICE_MICROS: Long

/**
 * How the syntax worker gives its thread away between parse slices: null natively (the worker
 * has a thread of its own); on the web, where it runs on the UI thread, one turn of the event loop.
 */
internal expect val platformSliceYield: (suspend () -> Unit)?
