package dev.supermux.editor.spike

internal object Grammars {
    init { System.loadLibrary("editorgrammars") }
    @JvmStatic external fun json(): Long
}

internal actual fun jsonLanguagePointer(): Any = Grammars.json()

internal actual val ktsEncoding = KtsEncoding.UTF8
