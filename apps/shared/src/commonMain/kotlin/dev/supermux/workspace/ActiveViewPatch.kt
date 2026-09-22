package dev.supermux.workspace

import dev.supermux.net.PatchWorkspaceBody

/** PATCH used by [dev.supermux.state.HostStore.setActiveView] — never includes a layout. */
fun activeViewPatchBody(activeViewId: String): PatchWorkspaceBody =
    PatchWorkspaceBody(name = null, layout = null, activeViewId = activeViewId)
