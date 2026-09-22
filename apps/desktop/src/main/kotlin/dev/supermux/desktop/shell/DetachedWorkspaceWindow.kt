// One extra OS window showing a slice of a workspace's layout (desktop).
//
// The panes and their wiring are the SHARED `ui/shell/windows/ExtraWindowPanes.kt` — this file is
// just desktop's strip chrome and its tear-out, which lands in another `Window {}` of `Main.kt`.
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.WorkspacePanesBind
import dev.supermux.ui.shell.windows.ExtraWindowPanes
import dev.supermux.ui.shell.windows.RegistryShellWindows
import dev.supermux.ui.shell.windows.WindowHost
import dev.supermux.ui.shell.windows.tearOutTabFrom

@Composable
internal fun DetachedWorkspaceWindow(
    host: WindowHost,
    bind: WorkspacePanesBind,
    ui: ShellUiState,
) {
    ExtraWindowPanes(
        hostId = host.id,
        bind = bind,
        ui = ui,
        modifier = Modifier.fillMaxSize().testTag("workspace_layout_host_extra"),
        onTearOutTab = { viewId ->
            val registry = (ui.windows as? RegistryShellWindows)?.registry ?: return@ExtraWindowPanes
            tearOutTabFrom(registry, bind, viewId)
        },
        stripChrome = DesktopStripChrome,
    )
}
