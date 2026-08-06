// Ported from apps/android/src/main/kotlin/dev/supermux/android/shell/ShellLayout.kt —
// keep in sync until a shared UI module exists. Pure Compose runtime + kotlinx.serialization, so
// this file is a verbatim copy of the Android original except for the package name.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class PaneVisibility(
    val chat: Boolean = true,
    val editor: Boolean = false,
    val terminal: Boolean = false,
    val display: Boolean = false,
) {
    val hasWork: Boolean get() = editor || terminal || display
    fun normalized(): PaneVisibility = if (!chat && !hasWork) copy(chat = true) else this
}

@Serializable
data class ShellSnapshot(
    val sidebarCollapsed: Boolean,
    val sidebarWidthDp: Float,
    val chatFraction: Float,
    val workDisplayFraction: Float,
    val editorTermFraction: Float,
    val panes: Map<String, PaneVisibility>,
    val native: Map<String, Boolean>,
)

@Stable
class ShellLayout {
    var sidebarCollapsed by mutableStateOf(false)

    // NOTE: these four use a private backing MutableState + read-only computed property (rather
    // than `by mutableStateOf(...); private set`) because the latter's auto-generated private JVM
    // setter (e.g. `setChatFraction`) collides with the hand-written public function of the same
    // name below — a "platform declaration clash" the Kotlin compiler rejects outright.
    private val sidebarWidthState = mutableStateOf(320.dp)
    val sidebarWidth: Dp get() = sidebarWidthState.value
    private val chatFractionState = mutableStateOf(0.5f)
    val chatFraction: Float get() = chatFractionState.value
    private val workDisplayFractionState = mutableStateOf(0.5f)
    val workDisplayFraction: Float get() = workDisplayFractionState.value
    private val editorTermFractionState = mutableStateOf(0.5f)
    val editorTermFraction: Float get() = editorTermFractionState.value

    fun setSidebarWidth(w: Dp) { sidebarWidthState.value = w.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX) }
    fun setChatFraction(f: Float) { chatFractionState.value = f.coerceIn(CHAT_MIN, CHAT_MAX) }
    fun setWorkDisplayFraction(f: Float) { workDisplayFractionState.value = f.coerceIn(WORKDISP_MIN, WORKDISP_MAX) }
    fun setEditorTermFraction(f: Float) { editorTermFractionState.value = f.coerceIn(EDITORTERM_MIN, EDITORTERM_MAX) }

    private val panes = mutableStateMapOf<String, PaneVisibility>()
    private val native = mutableStateMapOf<String, Boolean>()

    fun panesFor(id: String): PaneVisibility = panes[id] ?: PaneVisibility()
    fun setPanes(id: String, v: PaneVisibility) { panes[id] = v.normalized() }
    fun nativeView(id: String): Boolean = native[id] ?: false
    fun setNativeView(id: String, on: Boolean) { native[id] = on }

    fun toggleChat(id: String)     = setPanes(id, panesFor(id).let { it.copy(chat = !it.chat) })
    fun toggleEditor(id: String)   = setPanes(id, panesFor(id).let { it.copy(editor = !it.editor) })
    fun toggleTerminal(id: String) = setPanes(id, panesFor(id).let { it.copy(terminal = !it.terminal) })
    fun toggleDisplay(id: String)  = setPanes(id, panesFor(id).let { it.copy(display = !it.display) })

    fun prune(liveIds: Set<String>) {
        (panes.keys - liveIds).toList().forEach { panes.remove(it) }
        (native.keys - liveIds).toList().forEach { native.remove(it) }
    }

    fun snapshot() = ShellSnapshot(
        sidebarCollapsed, sidebarWidth.value, chatFraction, workDisplayFraction, editorTermFraction,
        panes.toMap(), native.toMap(),
    )
    fun restore(s: ShellSnapshot) {
        sidebarCollapsed = s.sidebarCollapsed
        setSidebarWidth(s.sidebarWidthDp.dp)
        setChatFraction(s.chatFraction); setWorkDisplayFraction(s.workDisplayFraction); setEditorTermFraction(s.editorTermFraction)
        panes.clear(); panes.putAll(s.panes)
        native.clear(); native.putAll(s.native)
    }

    companion object {
        val SIDEBAR_MIN = 220.dp; val SIDEBAR_MAX = 560.dp
        const val CHAT_MIN = 0.2f; const val CHAT_MAX = 0.8f
        const val WORKDISP_MIN = 0.25f; const val WORKDISP_MAX = 0.75f
        const val EDITORTERM_MIN = 0.2f; const val EDITORTERM_MAX = 0.8f
        private val json = Json { ignoreUnknownKeys = true }
        val Saver: Saver<ShellLayout, String> = Saver(
            save = { json.encodeToString(ShellSnapshot.serializer(), it.snapshot()) },
            restore = { ShellLayout().apply { restore(json.decodeFromString(ShellSnapshot.serializer(), it)) } },
        )
    }
}
