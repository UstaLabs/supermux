package dev.supermux.desktop.host

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.MenuScope
import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import dev.supermux.state.FleetStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.nio.file.Path

/** The tray header's leading status dot (spec §States). */
fun dotGlyph(d: Dot): String = when (d) {
    Dot.GREEN -> "🟢"
    Dot.YELLOW -> "🟡"
    Dot.RED -> "🔴"
    Dot.GREY -> "⚪"
}

/** The disabled first tray item: dot + header. */
fun trayHeaderLine(m: TrayModel): String = "${dotGlyph(m.dot)} ${m.header}"

/**
 * What the tray needs from the fleet: the live session count on THIS computer's broker, and the
 * first other host (its name and whether we can reach it) for the not-hosting header.
 */
data class FleetFacts(
    val localSessions: Int = 0,
    val remoteName: String? = null,
    val remoteReachable: Boolean = true,
) {
    companion object {
        val EMPTY = FleetFacts()
    }
}

/**
 * Pure: [localHostId] is the supervisor's broker. Sessions count when their owning record
 * ([sessionHost]: sessionId → recordId) is the fleet host with that hostId; 0 while that host is
 * not in the fleet yet. The "remote" host is the first fleet host whose hostId is not ours.
 */
fun fleetFacts(
    localHostId: String?,
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
): FleetFacts {
    val local = localHostId?.let { id -> hosts.firstOrNull { it.hostId == id } }
    val count = local?.let { h -> sessions.count { sessionHost[it.id] == h.recordId } } ?: 0
    val remote = hosts.firstOrNull { localHostId == null || it.hostId != localHostId }
    return FleetFacts(
        localSessions = count,
        remoteName = remote?.displayLabel,
        remoteReachable = remote?.online ?: true,
    )
}

/** [fleetFacts] kept live. Distinct, so a session's activity does not recompose the app root. */
fun FleetStore.hostingFacts(localHostId: Flow<String?>): Flow<FleetFacts> =
    combine(localHostId, hostViews, sessions, sessionHost) { id, hosts, list, owners ->
        fleetFacts(id, hosts, list, owners)
    }.distinctUntilChanged()

/** Open [file] with the OS default app (the log). Best-effort. */
fun openFile(file: Path) {
    runCatching {
        if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop().open(file.toFile())
    }.onFailure { System.err.println("supermux host: can't open $file: ${it.message}") }
}

/** Quitting with background ON says what happens once, as a notification (spec §States "What Quit says"). */
object BackgroundQuitNotice {
    /** Desktop settings key: set once the notice has been shown. */
    const val SHOWN_KEY = "desktop:backgroundQuitNoticeShown"
    const val TITLE = "supermux"
}

/** What "Quit supermux" / Cmd-Q does. */
sealed interface QuitAction {
    /** Quit now. [notice] is shown as a one-time notification when non-null. */
    data class Now(val notice: String? = null) : QuitAction
    /** Ask first: [text] says what stops. */
    data class Confirm(val text: String) : QuitAction

    companion object {
        /**
         * Pure. Only a running, app-owned broker without the background box gets the confirm
         * dialog; background ON quits at once with its notice (unless [noticeShown]); read-only,
         * not hosting, starting and can't-start quit silently.
         */
        fun of(s: HostingStatus, prefs: HostingPrefs, sessions: Int, noticeShown: Boolean): QuitAction {
            val text = QuitText.of(s, prefs, sessions) ?: return Now()
            return when {
                prefs.background -> Now(text.takeUnless { noticeShown })
                s is HostingStatus.Running -> Confirm(text)
                else -> Now()
            }
        }
    }
}

/** The tray menu (spec §States, "Tray menu"). Hosting off: header, Open, Quit. */
@Composable
fun MenuScope.HostingTrayMenu(
    model: TrayModel,
    background: Boolean,
    onOpen: () -> Unit,
    onShowLog: () -> Unit,
    onRestart: () -> Unit,
    onBackground: (Boolean) -> Unit,
    onQuit: () -> Unit,
) {
    Item(trayHeaderLine(model), enabled = false, onClick = {})
    Item("Open supermux", onClick = onOpen)
    if (model.showLog) Item("Show log", onClick = onShowLog)
    val hostingItems = model.restartLabel != null || model.showKeepRunning
    if (hostingItems) {
        Separator()
        model.restartLabel?.let { label -> Item(label, enabled = model.restartEnabled, onClick = onRestart) }
        if (model.showKeepRunning) {
            CheckboxItem(
                "Keep running in the background",
                checked = background,
                enabled = model.keepRunningEnabled,
                onCheckedChange = onBackground,
            )
        }
    }
    Separator()
    Item("Quit supermux", onClick = onQuit)
}
