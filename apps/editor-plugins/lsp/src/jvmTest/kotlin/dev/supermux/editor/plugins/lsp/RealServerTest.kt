package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.autocomplete.AutocompleteConfig
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.Severity
import dev.supermux.editor.plugins.lint.lint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client against a REAL language server over stdio, when the machine has one: `clangd` (the
 * Mac has Xcode's). Skipped elsewhere. Real diagnostics, incremental sync (a fix makes the error go),
 * hover, completion and go-to-definition.
 */
class RealServerTest {
    private val source = """
        #include <stdio.h>
        int add(int a, int b) { return a + b; }
        int main(void) {
          int total = add(1, 2);
          undeclared_x = 3;
          return total;
        }
    """.trimIndent() + "\n"

    @Test fun clangdDiagnosticsSyncHoverCompletionAndDefinition() {
        val bin = ProcessLspTransport.find("clangd")
        Assume.assumeTrue("no clangd on this machine", bin != null)
        val dir = kotlin.io.path.createTempDirectory("lsp-real").toFile().canonicalFile
        val file = File(dir, "main.c").also { it.writeText(source) }
        val uri = file.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking(ui) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val transport = ProcessLspTransport(listOf(bin!!, "--log=error"), dir, scope)
            val client = LspClient(transport, scope, LspClientConfig(rootUri = dir.toURI().toString(), requestTimeoutMs = 20_000))
            val view = EditorView(EditorState.create(source, EditorSelection.cursor(0), extensionOf(autocompletion(AutocompleteConfig(interactionDelay = 0)), lint(), client.plugin(uri, "c"))))
            view.startPlugins(scope)
            suspend fun until(what: String, ms: Long = 30_000, p: () -> Boolean) = withTimeout(ms) { while (!p()) delay(50) }.also { println("REAL-LSP clangd: $what") }
            val t0 = System.nanoTime()
            until("ready") { client.state.value == LspClientState.READY }
            println("REAL-LSP clangd ${client.serverName} encoding=${client.features.value.positionEncoding} sync=${client.features.value.sync}")
            until("an error on undeclared_x") { Lint.diagnostics(view.state).any { it.severity == Severity.ERROR && view.state.sliceDoc(it.from, it.to) == "undeclared_x" } }
            println("REAL-LSP first diagnostics after ${(System.nanoTime() - t0) / 1_000_000} ms")
            // Fix it with an incremental edit: the error goes (the server's text is ours).
            val at = view.state.doc.toString().indexOf("undeclared_x")
            view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "int ")), userEvent = "input.type"))
            until("the error went after the fix") { Lint.diagnostics(view.state).none { it.severity == Severity.ERROR } }
            // Hover on add's use.
            val use = view.state.doc.toString().indexOf("add(1")
            val h = client.documents[uri]!!.hover(view.state, use + 1)
            assertTrue(h != null && client.hoverTexts[h.key.id].orEmpty().contains("add"), "hover: ${h?.let { client.hoverTexts[it.key.id] }}")
            // Definition: F12 on the use goes to line 2's add.
            view.dispatch(TransactionSpec(selection = EditorSelection.cursor(use + 1)))
            assertTrue(client.documents[uri]!!.definition())
            until("definition") { view.state.doc.lineIndexAt(view.state.selection.main.head) == 1 }
            // Completion of "pri" inside main: printf.
            val ret = view.state.doc.toString().indexOf("  return")
            view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(ret, ret, "  pri\n")), selection = EditorSelection.cursor(ret + 5), userEvent = "input.type"))
            Autocomplete.startCompletion.run(view)
            until("completion has printf") { Autocomplete.state(view.state).options.any { it.completion.label.startsWith("printf") } }
            println("REAL-LSP completion: ${Autocomplete.state(view.state).options.size} options")
            client.close()
            transport.close()
            scope.cancel()
        }
        ui.close()
        assertEquals(true, true)
    }
}
