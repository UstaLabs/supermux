package dev.supermux.editor.syntax

// The browser tests read the files syntax-test-setup.mjs fetched before any test ran (the goldens,
// the test tables, the app's tables), from the loader's resource map, as SyntaxResources does.
actual fun testResource(path: String): ByteArray {
    setupFailure?.let { error("syntax-test-setup.mjs failed: $it") }
    return SyntaxResources.read(path) ?: error("test resource $path is missing: not in syntax-test-resources.json")
}

internal actual fun useTestAppResources() {
    setupFailure?.let { error("syntax-test-setup.mjs failed: $it") }
}

internal actual fun goldenUpdateDir(): String? = null

internal actual fun writeTextFile(path: String, text: String): Unit = error("goldens are written on the JVM only")
