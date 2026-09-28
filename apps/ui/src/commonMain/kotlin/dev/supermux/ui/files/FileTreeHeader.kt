// The Files pane's header: where the tree is rooted (breadcrumbs), a way back to the workspace
// root, and two tree-wide actions (collapse all, hard refresh).
package dev.supermux.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.FileSystemService
import dev.supermux.session.inferHomeDir

/** One breadcrumb: its label and the folder it roots the tree at. */
data class Crumb(val label: String, val path: String)

/** The crumbs for [rootPath]; [truncated] is true when leading crumbs were dropped (drawn as `…`). */
data class Breadcrumbs(val truncated: Boolean, val crumbs: List<Crumb>)

/**
 * Split [rootPath] into crumbs. Under [home] the first crumb is `~`; otherwise it is `/`. At most
 * [max] crumbs are kept (the LAST ones — the folder you are in matters more than where it hangs).
 */
fun breadcrumbsOf(rootPath: String, home: String?, max: Int = 3): Breadcrumbs {
    val all = ArrayList<Crumb>()
    val base: String
    if (home != null && home != "/" && isWithin(home, rootPath)) {
        base = home.trimEnd('/')
        all += Crumb("~", base)
    } else {
        base = "/"
        all += Crumb("/", "/")
    }
    val rel = relativeToWorkdir(base, rootPath)
    if (rel != null && rel != ".") {
        var cur = base
        for (seg in rel.split('/').filter { it.isNotEmpty() }) {
            cur = childOf(cur, seg)
            all += Crumb(seg, cur)
        }
    }
    return if (all.size > max) Breadcrumbs(true, all.takeLast(max)) else Breadcrumbs(false, all)
}

@Composable
fun FileTreeHeader(
    view: TreeViewState,
    fileSystem: FileSystemService?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val crumbs = breadcrumbsOf(view.rootPath, inferHomeDir(view.workdir))
    Row(
        modifier.fillMaxWidth().height(32.dp).padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (crumbs.truncated) CrumbSeparator("…")
            crumbs.crumbs.forEachIndexed { i, crumb ->
                if (i > 0 || crumbs.truncated) CrumbSeparator("›")
                val last = i == crumbs.crumbs.lastIndex
                Text(
                    crumb.label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (last) cs.onSurface else cs.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            view.rootPath = crumb.path
                            view.collapseAll()
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .testTag("tree_breadcrumb:${crumb.label}"),
                )
            }
            if (view.rootPath.trimEnd('/') != view.workdir.trimEnd('/')) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = cs.secondaryContainer,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            view.rootPath = view.workdir
                            view.collapseAll()
                        }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .testTag("tree_workspace_chip"),
                ) {
                    Text(
                        "Workspace",
                        fontSize = 11.sp,
                        color = cs.onSecondaryContainer,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
        IconButton(
            onClick = { view.collapseAll() },
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand).testTag("tree_collapse_all"),
        ) {
            Icon(Icons.Filled.UnfoldLess, contentDescription = "Collapse all", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = {
                val fs = fileSystem ?: return@IconButton
                // Every folder the tree is showing: the root plus the open folders under it.
                fs.refresh(view.rootPath)
                view.expanded.filter { isWithin(view.rootPath, it) }.forEach { fs.refresh(it) }
            },
            enabled = fileSystem != null,
            modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand).testTag("tree_refresh"),
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CrumbSeparator(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 1.dp))
}
