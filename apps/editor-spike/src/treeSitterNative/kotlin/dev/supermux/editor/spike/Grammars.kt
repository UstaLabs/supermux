package dev.supermux.editor.spike

/** The raw TSLanguage pointer in whatever form ktreesitter's `Language(Any)` wants on this platform. */
internal expect fun jsonLanguagePointer(): Any

/**
 * What bytes ktreesitter 0.25.1 REALLY hands tree-sitter for a Kotlin String, whatever InputEncoding
 * says (M0 finding: it never passes UTF-16):
 * - JVM (HotSpot): JNI `GetStringUTFChars`, i.e. MODIFIED UTF-8. Every UTF-16 unit is encoded on
 *   its own, so a surrogate pair is 3 + 3 bytes (not 4) and U+0000 is 2 bytes.
 * - Android (ART, API 35 emulator): the same JNI call, but ART emits a surrogate pair as ONE 4-byte
 *   UTF-8 sequence, so what tree-sitter sees is standard UTF-8 (40 bytes for SAMPLE, JVM: 42).
 * - iOS (Kotlin/Native): `String.cstr`, i.e. standard UTF-8. BUT the read callback reports
 *   `result.length` (UTF-16 units) as the byte count, see [KtsHighlighter].
 */
internal enum class KtsEncoding { MODIFIED_UTF8, UTF8, UTF8_LENGTH_IN_UNITS }

internal expect val ktsEncoding: KtsEncoding
