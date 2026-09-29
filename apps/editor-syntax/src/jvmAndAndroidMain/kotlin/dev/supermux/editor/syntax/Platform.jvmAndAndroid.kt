package dev.supermux.editor.syntax

internal actual val PLATFORM_PARSE_SLICE_MICROS: Long = 50_000

// The worker runs on a dispatcher of its own: it never needs to hand the thread back mid-parse.
internal actual val platformSliceYield: (suspend () -> Unit)? = null
