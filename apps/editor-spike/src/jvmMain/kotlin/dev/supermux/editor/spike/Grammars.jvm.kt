package dev.supermux.editor.spike

internal object Grammars {
    init { System.load(System.getProperty("editor.grammars.lib") ?: error("editor.grammars.lib not set")) }
    @JvmStatic external fun json(): Long
}

internal actual fun jsonLanguagePointer(): Any = Grammars.json()

internal actual val ktsEncoding = KtsEncoding.MODIFIED_UTF8
