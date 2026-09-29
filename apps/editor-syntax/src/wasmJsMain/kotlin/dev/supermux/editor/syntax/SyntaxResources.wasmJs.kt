package dev.supermux.editor.syntax

/**
 * The web's resources: the loader's in-memory map, filled by fetches ([WasmBackend.ensureLanguage]
 * fetches a code-only grammar's tables into it; the browser tests fetch theirs before any test runs).
 */
actual object SyntaxResources {
    actual fun read(path: String): ByteArray? = loaderResourceLatin1(path)?.latin1Bytes()
}
