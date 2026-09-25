package dev.supermux.editor.spike

/** The raw TSLanguage pointer in whatever form ktreesitter's `Language(Any)` wants on this platform. */
internal expect fun jsonLanguagePointer(): Any

/**
 * What bytes ktreesitter 0.25.1 REALLY hands tree-sitter for a Kotlin String, whatever InputEncoding
 * says (M0 finding: it never passes UTF-16):
 * - JVM + Android: JNI `GetStringUTFChars`, i.e. MODIFIED UTF-8. Every UTF-16 unit is encoded on its
 *   own, so a surrogate pair is 3 + 3 bytes (not 4) and U+0000 is 2 bytes.
 * - iOS (Kotlin/Native): `String.cstr`, i.e. standard UTF-8 (a pair is 4 bytes). BUT the read
 *   callback reports `result.length` (UTF-16 units) as the byte count, see [KtsHighlighter].
 */
internal enum class KtsEncoding { MODIFIED_UTF8, UTF8 }

internal expect val ktsEncoding: KtsEncoding
