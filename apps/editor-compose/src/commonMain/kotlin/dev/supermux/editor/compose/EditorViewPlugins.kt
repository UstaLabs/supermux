package dev.supermux.editor.compose

import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus

/**
 * A plugin's per-view runtime (CM6's `ViewPlugin`): the part of a plugin that does WORK over time
 * rather than hold data: autocompletion asking its sources, an LSP client syncing the document.
 * State stays in state fields (data); an instance only reads [ViewPluginHost.target]'s state and
 * dispatches, and runs coroutines on [ViewPluginHost.scope] (the UI thread's; cancelled when the
 * instance is destroyed).
 *
 * One instance per view per plugin value (by identity) in [viewPluginsFacet], created when the view
 * starts its plugins (a composed `Editor`: [EditorView.startPlugins]) or when a reconfigure adds the
 * plugin, destroyed when a reconfigure removes it, when [EditorView.setState] replaces the state (the
 * new state's plugins are created fresh: another document), or when the view stops.
 */
fun interface ViewPlugin {
    fun create(host: ViewPluginHost): ViewPluginInstance
}

/** What a [ViewPlugin] instance gets: the command API and a UI-thread scope of its own. */
interface ViewPluginHost {
    val target: CommandTarget
    val scope: CoroutineScope
}

/** A running [ViewPlugin]. */
interface ViewPluginInstance {
    /** After every transaction of the view (before its listeners), with the new state in `tr.state`. */
    fun update(tr: Transaction) {}

    /** The plugin is removed or the view stops: stop the work ([ViewPluginHost.scope] is cancelled after). */
    fun destroy() {}
}

/** Every plugin's [ViewPlugin], highest precedence first. */
val viewPluginsFacet: Facet<ViewPlugin, List<ViewPlugin>> = Facet.list("viewPlugins")

/** The running instances of one view's [viewPluginsFacet]. */
internal class ViewPlugins(private val target: EditorView, private val parent: CoroutineScope) {
    internal class Running(val plugin: ViewPlugin, val instance: ViewPluginInstance, val job: Job)

    private var running: List<Running> = emptyList()
    private var plugins: List<ViewPlugin> = emptyList()

    /** Sync the instances with [state]'s plugins; returns the ones it created (they start FROM [state]). */
    fun sync(state: EditorState): Set<Running> {
        val want = state.facet(viewPluginsFacet)
        if (want === plugins) return emptySet()
        plugins = want
        val keep = running.filter { r -> want.any { it === r.plugin } }
        for (r in running) if (keep.none { it === r }) stop(r)
        val next = ArrayList<Running>(want.size)
        val created = HashSet<Running>()
        for (p in want) {
            next += keep.firstOrNull { it.plugin === p } ?: (start(p)?.also { created += it } ?: continue)
        }
        running = next
        return created
    }

    private fun start(p: ViewPlugin): Running? {
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = parent + job
        val host = object : ViewPluginHost {
            override val target: CommandTarget get() = this@ViewPlugins.target
            override val scope: CoroutineScope = scope
        }
        val inst = target.guarded("view plugin create", null) { p.create(host) } ?: run { job.cancel(); return null }
        return Running(p, inst, job)
    }

    private fun stop(r: Running) {
        target.guarded("view plugin destroy", Unit) { r.instance.destroy() }
        r.job.cancel()
    }

    fun update(tr: Transaction) {
        // An instance created by this transaction (a reconfigure adding its plugin) started from its
        // NEW state: it never gets the transaction itself (CM6), or it would count its changes twice.
        val created = if (tr.reconfigured) sync(tr.state) else emptySet()
        for (r in running) if (r !in created) target.guarded("view plugin update", Unit) { r.instance.update(tr) }
    }

    /** Another state (a document switch): every instance goes, the new state's start fresh. */
    fun replaced(state: EditorState) {
        stopAll()
        sync(state)
    }

    fun stopAll() {
        for (r in running) stop(r)
        running = emptyList()
        plugins = emptyList()
    }

    /** The view stopped its plugins: every instance goes, and [parent] (the view's own child scope) with them. */
    fun dispose() {
        stopAll()
        parent.cancel()
    }
}
