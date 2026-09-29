package dev.supermux.editor.sample

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.plugins.autocomplete.Autocomplete
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.Severity
import dev.supermux.editor.plugins.lsp.LspClientState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertTrue

/** The sample's LSP demo against the fake server, on real threads (the worker parse included). */
class LspDemoTest {
    @Test fun theKotlinFileOpensWithTheFakeServer() {
        val text = runBlocking { SampleFiles.kotlin() }
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking(ui) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val lsp = SampleLsp.fake(scope, "file:///sample/HostStore.kt")
            val view = EditorView(EditorState.create(text, EditorSelection.cursor(0), lsp.extension))
            view.startPlugins(scope)
            val t0 = System.nanoTime()
            withTimeout(10_000) { while (lsp.client.state.value != LspClientState.READY || lsp.client.messagesReceived < 2) delay(20) }
            println("LSP-SAMPLE HostStore.kt (${text.length / 1024} KB) ready + diagnostics in ${(System.nanoTime() - t0) / 1_000_000} ms, ${Lint.diagnostics(view.state).size} problems")
            lsp.client.close()
            scope.cancel()
        }
        ui.close()
    }

    @Test fun theDemoGetsDiagnosticsAndCompletion() {
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        runBlocking(ui) {
            val scope = CoroutineScope(coroutineContext + SupervisorJob())
            val lsp = SampleLsp.fake(scope, "file:///sample/demo.toy")
            val at = LSP_DEMO_TEXT.indexOf("val big") - 1
            val view = EditorView(EditorState.create(LSP_DEMO_TEXT, EditorSelection.cursor(at), lsp.extension))
            view.startPlugins(scope)
            suspend fun until(p: () -> Boolean) = withTimeout(10_000) { while (!p()) delay(20) }
            until { lsp.client.state.value == LspClientState.READY }
            until { Lint.diagnostics(view.state).size == 2 }
            assertTrue(Lint.diagnostics(view.state).map { it.severity }.toSet() == setOf(Severity.WARNING, Severity.ERROR))
            view.typeText("total.")
            until { Autocomplete.state(view.state).options.any { it.completion.label == "length" } }
            lsp.client.close()
            scope.cancel()
        }
        ui.close()
    }
}
