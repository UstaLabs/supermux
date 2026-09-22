package dev.supermux.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Tablet list chrome (width + collapsed rail). Not a pane layout. */
@Stable
class SidebarState {
    var sidebarCollapsed by mutableStateOf(false)
    private val sidebarWidthState = mutableStateOf(320.dp)
    val sidebarWidth: Dp get() = sidebarWidthState.value
    fun setSidebarWidth(w: Dp) {
        sidebarWidthState.value = w.coerceIn(SIDEBAR_MIN, SIDEBAR_MAX)
    }

    companion object {
        val SIDEBAR_MIN = 220.dp
        val SIDEBAR_MAX = 560.dp
        val Saver: Saver<SidebarState, Any> = listSaver(
            save = { listOf(it.sidebarCollapsed, it.sidebarWidth.value) },
            restore = { parts ->
                SidebarState().apply {
                    sidebarCollapsed = parts.getOrNull(0) as? Boolean ?: false
                    (parts.getOrNull(1) as? Float)?.dp?.let(::setSidebarWidth)
                }
            },
        )
    }
}
