package dev.supermux.editor.syntax

/**
 * The app's packaged resources for the native backend: a code-only grammar's tables blob,
 * `editor-syntax/tables/<lang>.sesz` ([NativeBackend.tablesPath]).
 * - JVM and Android: Java resources, through the class loader (the desktop jar, the AAR).
 * - iOS: the app bundle's `editor-syntax/tables/` directory (copied by `:ios`, see native/README.md),
 *   then any extra directories set by the host or the tests.
 */
expect object SyntaxResources {
    /** The bytes of resource [path], or null when there is no such resource. */
    fun read(path: String): ByteArray?
}
