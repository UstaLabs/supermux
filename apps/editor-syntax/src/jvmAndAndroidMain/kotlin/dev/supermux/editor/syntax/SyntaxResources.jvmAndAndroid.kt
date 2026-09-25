package dev.supermux.editor.syntax

actual object SyntaxResources {
    actual fun read(path: String): ByteArray? =
        SyntaxResources::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
}
