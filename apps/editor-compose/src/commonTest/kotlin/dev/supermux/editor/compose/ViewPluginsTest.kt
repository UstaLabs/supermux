package dev.supermux.editor.compose

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewPluginsTest {
    private class Log {
        val events = ArrayList<String>()
        var job: Job? = null
    }

    private fun plugin(name: String, log: Log) = ViewPlugin { host ->
        log.events += "create $name"
        log.job = host.scope.launch { awaitCancellation() }
        object : ViewPluginInstance {
            override fun update(tr: dev.supermux.editor.core.Transaction) { log.events += "update $name ${tr.state.doc.length}" }
            override fun destroy() { log.events += "destroy $name" }
        }
    }

    @Test fun createdOnStartUpdatedPerTransactionDestroyedOnStop() {
        val log = Log()
        val view = EditorView(EditorState.create("ab", extensions = viewPluginsFacet.of(plugin("a", log))))
        val stop = view.startPlugins(CoroutineScope(Dispatchers.Unconfined))
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x"))))
        stop()
        assertEquals(listOf("create a", "update a 3", "destroy a"), log.events)
        assertTrue(log.job!!.isCancelled, "its scope is cancelled")
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x"))))
        assertEquals(3, log.events.size, "stopped: no more updates")
    }

    @Test fun aReconfigureAddsAndRemovesInstancesKeepingTheOthers() {
        val la = Log(); val lb = Log()
        val a = plugin("a", la); val b = plugin("b", lb)
        val c = Compartment()
        val view = EditorView(EditorState.create("", extensions = extensionOf(viewPluginsFacet.of(a), c.of(extensionOf()))))
        view.startPlugins(CoroutineScope(Dispatchers.Unconfined))
        view.dispatch(TransactionSpec(effects = listOf(c.reconfigure(viewPluginsFacet.of(b)))))
        view.dispatch(TransactionSpec(effects = listOf(c.reconfigure(extensionOf()))))
        assertEquals(listOf("create a", "update a 0", "update a 0"), la.events, "a kept throughout")
        assertEquals(listOf("create b", "destroy b"), lb.events, "created by a transaction: not handed that transaction")
    }

    @Test fun aPluginEnabledByAnEditingTransactionStartsFromItsResult() {
        val log = Log()
        val c = Compartment()
        val view = EditorView(EditorState.create("ab", extensions = c.of(extensionOf())))
        view.startPlugins(CoroutineScope(Dispatchers.Unconfined))
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "xyz")), effects = listOf(c.reconfigure(viewPluginsFacet.of(plugin("a", log))))))
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "1"))))
        assertEquals(listOf("create a", "update a 6"), log.events, "the enabling edit is part of its start, only later ones are updates")
    }

    @Test fun anotherStateStartsFresh() {
        val log = Log()
        val p = plugin("a", log)
        val view = EditorView(EditorState.create("", extensions = viewPluginsFacet.of(p)))
        view.startPlugins(CoroutineScope(Dispatchers.Unconfined))
        view.setState(EditorState.create("other", extensions = viewPluginsFacet.of(p)))
        assertEquals(listOf("create a", "destroy a", "create a"), log.events)
    }

    @Test fun aThrowingPluginNeverBreaksTheView() {
        val view = EditorView(EditorState.create("", extensions = viewPluginsFacet.of(ViewPlugin {
            object : ViewPluginInstance { override fun update(tr: dev.supermux.editor.core.Transaction) = error("boom") }
        })))
        view.startPlugins(CoroutineScope(Dispatchers.Unconfined))
        view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "x"))))
        assertEquals("x", view.state.doc.toString())
    }
}
