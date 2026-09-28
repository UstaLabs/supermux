package dev.supermux.editor.sample

actual fun platformNowMs(): Double = System.nanoTime() / 1e6

/** Set by the desktop typing driver when it posts a key event. */
@Volatile var lastKeyPostedMs: Double = -1.0

actual fun lastInputEventMs(): Double = lastKeyPostedMs

actual fun insideKeyEvent(): Boolean = false

actual fun lastInputKind(): String = "x"

actual val platformFloatingCursorDrag: ((dx: Double, dy: Double) -> Boolean)? = null

actual val platformRealLspName: String? by lazy { if (dev.supermux.editor.plugins.lsp.ProcessLspTransport.find("clangd") != null) "clangd" else null }

actual fun platformRealLsp(scope: kotlinx.coroutines.CoroutineScope, file: String, text: String): Triple<dev.supermux.editor.plugins.lsp.LspTransport, String, () -> Unit>? {
    val bin = dev.supermux.editor.plugins.lsp.ProcessLspTransport.find("clangd") ?: return null
    val dir = kotlin.io.path.createTempDirectory("editor-sample-lsp").toFile().canonicalFile
    val f = java.io.File(dir, file).also { it.writeText(text) }
    val transport = dev.supermux.editor.plugins.lsp.ProcessLspTransport(listOf(bin, "--log=error"), dir, scope)
    return Triple(transport, "file://" + f.absolutePath, { transport.close(); dir.deleteRecursively() })
}
