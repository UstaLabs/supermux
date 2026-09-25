package dev.supermux.editor.spike

import dev.supermux.editor.spike.grammars.tree_sitter_json
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun jsonLanguagePointer(): Any = tree_sitter_json()!!

internal actual val ktsEncoding = KtsEncoding.UTF8
