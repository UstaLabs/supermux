package dev.supermux.editor.syntax

private class TestResourceAnchor

actual fun testResource(path: String): ByteArray =
    (TestResourceAnchor::class.java.getResourceAsStream("/$path")
        ?: error("test resource /$path is missing: run native/build.sh gen on the Mac"))
        .use { it.readBytes() }

internal actual fun useTestAppResources() = Unit // the tables are class-loader resources already

internal actual fun goldenUpdateDir(): String? = System.getProperty("editor.syntax.updateGolden")

internal actual fun writeTextFile(path: String, text: String) {
    java.io.File(path).apply { parentFile.mkdirs() }.writeText(text)
}
