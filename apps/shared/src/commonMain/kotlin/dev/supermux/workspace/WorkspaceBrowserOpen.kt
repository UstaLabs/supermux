// Where a link opens in a workspace (#105): the tab that already shows it, else a new Browser tab beside the others,
// else a new group split to the right of the chat it came from. The same shape as WorkspaceFileOpen.kt, sharing its
// placement (placeNewView).
package dev.supermux.workspace

import dev.supermux.browser.BrowserViewState
import dev.supermux.browser.LinkRoute
import dev.supermux.browser.OpenBrowserTab
import dev.supermux.browser.routeLink
import dev.supermux.proto.ViewDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject

/** What opening a link should do. Pure, so the rules are testable on their own. */
sealed interface BrowserOpenPlan {
    /** A tab already shows this very link: bring it forward. */
    data class Focus(val viewId: String, val groupId: String) : BrowserOpenPlan

    /** The workspace has Browser tabs: the new one joins the first group holding one. */
    data class AddToGroup(val groupId: String) : BrowserOpenPlan

    /** No Browser tab yet: split the group the link came from, the new group to the RIGHT. */
    data class SplitFrom(val groupId: String) : BrowserOpenPlan

    /** The system's browser or handler: a modified click, mail, phone, texts. */
    data class System(val url: String) : BrowserOpenPlan

    /** Never opened: javascript:, file:, the app's own scheme, relative links. */
    data object Refused : BrowserOpenPlan

    /** No group to put it in: an empty, unreadable layout. */
    data object Nowhere : BrowserOpenPlan
}

fun ViewDto.isBrowserView(): Boolean = kind == "browser"

/** The first group in document order holding a Browser tab, or null when the workspace has none. */
fun firstGroupWithBrowserView(node: LayoutNode, views: Map<String, ViewDto>): String? = when (node) {
    is LayoutNode.Group -> node.id.takeIf { node.viewIds.any { id -> views[id]?.isBrowserView() == true } }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { firstGroupWithBrowserView(it, views) }
}

/**
 * Decide where [url] opens. [sourceViewId] is the view it came from (the chat it was tapped in), used only when there is
 * no Browser tab to join; [modified] is a ⌘/Ctrl-click. [scope] is the window that asked, as for files.
 */
fun planBrowserOpen(
    tree: LayoutNode,
    views: Map<String, ViewDto>,
    url: String,
    sourceViewId: String?,
    modified: Boolean = false,
    scope: LayoutNode = tree,
): BrowserOpenPlan {
    val tabs = views.values.filter { it.isBrowserView() && groupIdOf(scope, it.id) != null }
        .map { OpenBrowserTab(it.id, (BrowserViewState.from(it.state) as? BrowserViewState.Page)?.url) }
    return when (val route = routeLink(url, tabs, modified)) {
        LinkRoute.Refused -> BrowserOpenPlan.Refused
        is LinkRoute.System -> BrowserOpenPlan.System(route.url)
        is LinkRoute.Focus -> BrowserOpenPlan.Focus(route.tabId, groupIdOf(scope, route.tabId)!!)
        is LinkRoute.NewTab -> {
            firstGroupWithBrowserView(scope, views)?.let { return BrowserOpenPlan.AddToGroup(it) }
            val source = sourceViewId?.let { groupIdOf(scope, it) } ?: firstGroupId(scope) ?: return BrowserOpenPlan.Nowhere
            if (viewIdsOfGroup(scope, source).isEmpty()) BrowserOpenPlan.AddToGroup(source) else BrowserOpenPlan.SplitFrom(source)
        }
    }
}

/** Opens links in one workspace: its Browser tabs, or the system's browser. Read the layout and views at call time. */
class WorkspaceBrowserOpener(
    private val workspaceId: String,
    private val treeOf: () -> LayoutNode,
    /** Every view the workspace has, provisional ones included, so a second click finds the first one's tab. */
    private val viewsOf: () -> Map<String, ViewDto>,
    private val edit: ((LayoutNode) -> LayoutNode) -> Unit,
    private val provisional: MutableMap<String, ViewDto>,
    /** POST a `browser` view with the id we drew: the id the broker created, null when it refused. */
    private val post: suspend (id: String, state: JsonObject, groupId: String) -> String?,
    private val openSystem: (url: String) -> Unit,
    private val scope: CoroutineScope,
    private val newId: () -> String,
) {
    fun open(url: String, sourceViewId: String? = null, modified: Boolean = false, onPlaced: (viewId: String) -> Unit = {}) {
        when (val plan = planBrowserOpen(treeOf(), viewsOf(), url, sourceViewId, modified)) {
            is BrowserOpenPlan.Focus -> {
                edit { setActiveViewInGroup(it, plan.groupId, plan.viewId) }
                onPlaced(plan.viewId)
            }
            is BrowserOpenPlan.AddToGroup -> place(url, plan.groupId, split = false, onPlaced)
            is BrowserOpenPlan.SplitFrom -> place(url, plan.groupId, split = true, onPlaced)
            is BrowserOpenPlan.System -> openSystem(plan.url)
            BrowserOpenPlan.Refused -> Unit
            BrowserOpenPlan.Nowhere -> println("[WorkspaceBrowserOpener] no group to open a link into — layout is empty")
        }
    }

    private fun place(url: String, groupId: String, split: Boolean, onPlaced: (viewId: String) -> Unit) {
        // A link the broker would refuse as a tab's page (over 4096 characters) still opens, in the system's browser.
        val state = BrowserViewState.page(url)?.toJson() ?: return openSystem(url)
        placeNewView(
            id = newId(), kind = "browser", state = state, groupId = groupId, split = split, workspaceId = workspaceId,
            provisional = provisional, edit = edit, post = post, scope = scope, newId = newId,
            onPlaced = onPlaced, onWithdrawn = {},
        )
    }
}
