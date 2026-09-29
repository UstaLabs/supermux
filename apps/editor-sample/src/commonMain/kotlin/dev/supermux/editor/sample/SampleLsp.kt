package dev.supermux.editor.sample

import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.autocomplete.registerWidgets
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.lint.registerWidgets
import dev.supermux.editor.plugins.lsp.LspClient
import dev.supermux.editor.plugins.lsp.LspClientConfig
import dev.supermux.editor.plugins.lsp.LspTransport
import dev.supermux.editor.plugins.lsp.fake.FakeLspServer
import dev.supermux.editor.plugins.lsp.registerWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A language server for one sample document: the in-process fake (every platform: M4c's toy
 * language server) or, on the desktop when the Mac has one, a real server over stdio ([platformRealLsp]).
 * [extension] is autocompletion + lint + the client's plugin; [registerWidgets] their popups and panels.
 */
class SampleLsp(val client: LspClient, private val uri: String, private val languageId: String, private val onClose: () -> Unit) {
    /** The last message the client reported (a server's showMessage, a failed request). */
    var lastMessage: String = ""

    val extension: Extension get() = extensionOf(autocompletion(), lint(), client.plugin(uri, languageId))

    fun registerWidgets(registry: WidgetRegistry) {
        Autocomplete.registerWidgets(registry)
        Lint.registerWidgets(registry)
        client.registerWidgets(registry)
    }

    fun close(scope: CoroutineScope) {
        scope.launch {
            client.close()
            onClose()
        }
    }

    companion object {
        fun fake(scope: CoroutineScope, uri: String): SampleLsp {
            val server = FakeLspServer(scope)
            var self: SampleLsp? = null
            val client = LspClient(server.transport, scope, LspClientConfig(rootUri = "file:///sample", onMessage = { _, m -> self?.lastMessage = m }))
            return SampleLsp(client, uri, "toy") {}.also { self = it }
        }

        /** The desktop's real server for [file] ([platformRealLsp]), or null where there is none. */
        fun real(scope: CoroutineScope, file: String, text: String, languageId: String): SampleLsp? {
            val (transport, uri, close) = platformRealLsp(scope, file, text) ?: return null
            var self: SampleLsp? = null
            val client = LspClient(transport, scope, LspClientConfig(rootUri = uri.substringBeforeLast('/'), onMessage = { _, m -> self?.lastMessage = m }))
            return SampleLsp(client, uri, languageId, close).also { self = it }
        }
    }
}

/**
 * A real language server for the sample's [file] (its [text] written to a scratch directory first):
 * the transport, the document's URI and how to stop it. The desktop only (clangd over stdio when the
 * machine has it); null elsewhere.
 */
expect fun platformRealLsp(scope: CoroutineScope, file: String, text: String): Triple<LspTransport, String, () -> Unit>?

/** The real server [platformRealLsp] would start ("clangd"), or null (none on this machine or platform). */
expect val platformRealLspName: String?

/** The LSP demo document (the fake server's toy language; coloured as Kotlin). */
val LSP_DEMO_TEXT: String = """
// LSP demo: the in-process fake language server (a toy language).
// Try: type `total.` (completion after a dot), hover a name (mouse) or the "hover" chip,
// F12 on `add`, Shift-F12 for its references, F2 to rename, F8 / Mod-Shift-m for the problems,
// Shift-Alt-f to format (the tab and trailing spaces below), type `add(` for signature help,
// Ctrl-Space then `for` for a snippet (Tab moves between its fields).
fun add(a, b) {
    return a + b
}

fun scale(value, factor) {
	return value * factor   
}

val total = add(1, 2)
val big = scale(total, 10)
// TODO: replace this marker (a warning with a quick fix)
val oops = error
""".trimStart()

/** The real-LSP demo document (C, for clangd). */
val REAL_LSP_TEXT: String = """
#include <stdio.h>

/* A real language server (clangd) over stdio: completion, hover, F12, F2, Shift-Alt-f, diagnostics. */
int add(int a, int b) { return a + b; }

int main(void) {
    int total = add(1, 2);
    printf("%d\n", total);
    undeclared_value = 3;
    return 0;
}
""".trimStart()
