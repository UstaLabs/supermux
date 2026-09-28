package dev.supermux.editor.plugins.search

/** A phone or tablet browser (touch, no fine pointer): the big targets. */
internal actual val touchFirst: Boolean by lazy { coarsePointer() }

private fun coarsePointer(): Boolean = js("(typeof matchMedia === 'function') && matchMedia('(pointer: coarse)').matches")
