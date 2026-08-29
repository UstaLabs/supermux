package dev.supermux.android.workspace

import dev.supermux.net.PatchWorkspaceBody
import dev.supermux.proto.ViewDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.collectViewIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class PhoneTabModel(
    val viewIds: List<String>,
    val selectedId: String?,
)

/** Flattened phone tabs: document-order view ids, selected = activeViewId or first. */
fun phoneTabModel(layout: LayoutNode?, activeViewId: String?): PhoneTabModel {
    val ids = layout?.let { collectViewIds(it) } ?: emptyList()
    val selected = when {
        activeViewId != null && activeViewId in ids -> activeViewId
        else -> ids.firstOrNull()
    }
    return PhoneTabModel(ids, selected)
}

/** PATCH used by [dev.supermux.android.AppViewModel.setActiveView] — never includes a layout. */
fun activeViewPatchBody(activeViewId: String): PatchWorkspaceBody =
    PatchWorkspaceBody(name = null, layout = null, activeViewId = activeViewId)

fun phoneAddKinds(): List<NewViewKind> = listOf(
    NewViewKind.TERMINAL,
    NewViewKind.EDITOR,
    NewViewKind.DIFF,
    NewViewKind.DISPLAY,
)

fun addViewState(kind: NewViewKind, nowMillis: Long = 0L): JsonObject = when (kind) {
    NewViewKind.TERMINAL -> buildJsonObject {
        put("scope", JsonPrimitive("workspace"))
        put("terminalId", JsonPrimitive("t" + nowMillis.toString().takeLast(6)))
    }
    NewViewKind.EDITOR -> buildJsonObject { put("mode", JsonPrimitive("tree")) }
    NewViewKind.DIFF -> buildJsonObject { put("mode", JsonPrimitive("diff")) }
    NewViewKind.DISPLAY -> buildJsonObject { put("displayId", JsonPrimitive("")) }
    NewViewKind.CHAT -> buildJsonObject { }
}

fun closeNeedsConfirm(kind: String): Boolean = kind == "terminal" || kind == "display"

fun closeNeedsConfirm(view: ViewDto): Boolean = closeNeedsConfirm(view.kind)
