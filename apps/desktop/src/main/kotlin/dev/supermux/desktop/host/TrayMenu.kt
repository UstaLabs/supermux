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
 * Pure: [localHostId] is the supervisor's broker. This computer's record is the fleet host with that
 * hostId, else one with a loopback direct URL ([loopbackRecordIds]). Sessions count when their
 * owning record ([sessionHost]: sessionId → recordId) is that record; 0 while it is not in the fleet.
 * The "remote" host is the first fleet host that is neither: after hosting is turned off the user's
 * own former "This computer" must not read as the remote.
 */
fun fleetFacts(
    localHostId: String?,
    hosts: List<HostView>,
    sessions: List<SessionInfo>,
    sessionHost: Map<String, String>,
    loopbackRecordIds: Set<String> = emptySet(),
): FleetFacts {
    fun isLocal(h: HostView) = (localHostId != null && h.hostId == localHostId) || h.recordId in loopbackRecordIds
    val local = localHostId?.let { id -> hosts.firstOrNull { it.hostId == id } }
        ?: hosts.firstOrNull { it.recordId in loopbackRecordIds }
    val count = local?.let { h -> sessions.count { sessionHost[it.id] == h.recordId } } ?: 0
    val remote = hosts.firstOrNull { !isLocal(it) }
    return FleetFacts(
        localSessions = count,
        remoteName = remote?.displayLabel,
        remoteReachable = remote?.online ?: true,
    )
}

/** [fleetFacts] kept live. Distinct, so a session's activity does not recompose the app root. */
fun FleetStore.hostingFacts(localHostId: Flow<String?>): Flow<FleetFacts> =
    combine(localHostId, hostViews, sessions, sessionHost) { id, hosts, list, owners ->
        // Records change only with hostViews (add/forget/rename), so reading them here stays current.
        val loopback = store.list().filter { isLoopbackUrl(it.directUrl) }.map { it.recordId }.toSet()
        fleetFacts(id, hosts, list, owners, loopback)
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
         * Pure. [quitStopsBroker] ([HostSupervisor.quitStopsBroker]) is whether quitting stops the
         * broker: a running, app-owned broker that stops gets the confirm dialog; one that keeps
         * running (a service, the XDG stand-in) quits at once with the one-time notice (unless
         * [noticeShown]); read-only, not hosting, starting, can't-start quit at once, silently.
         */
        fun of(s: HostingStatus, sessions: Int, quitStopsBroker: Boolean, noticeShown: Boolean): QuitAction = when {
            s !is HostingStatus.Running || s.readOnly -> Now()
            quitStopsBroker -> Confirm(QuitText.stops(sessions))
            else -> Now(QuitText.BACKGROUND.takeUnless { noticeShown })
        }
    }
}

/** One tray menu row, as a pure model ([trayMenuItems]) so the menu's shape is testable without AWT. */
sealed interface TrayItem {
    data class Header(val text: String) : TrayItem
    data class Action(val id: TrayAction, val label: String, val enabled: Boolean = true) : TrayItem
    data class Checkbox(val label: String, val checked: Boolean, val enabled: Boolean) : TrayItem
    data object Separator : TrayItem
}

enum class TrayAction { OPEN, SHOW_LOG, RESTART, QUIT }

/** The tray menu (spec §States, "Tray menu"). Hosting off: header, Open, Quit. */
fun trayMenuItems(model: TrayModel, background: Boolean): List<TrayItem> = buildList {
    add(TrayItem.Header(trayHeaderLine(model)))
    add(TrayItem.Action(TrayAction.OPEN, "Open supermux"))
    if (model.showLog) add(TrayItem.Action(TrayAction.SHOW_LOG, "Show log"))
    if (model.restartLabel != null || model.showKeepRunning) {
        add(TrayItem.Separator)
        model.restartLabel?.let { add(TrayItem.Action(TrayAction.RESTART, it, model.restartEnabled)) }
        if (model.showKeepRunning) {
            add(TrayItem.Checkbox("Keep running in the background", background, model.keepRunningEnabled))
        }
    }
    add(TrayItem.Separator)
    add(TrayItem.Action(TrayAction.QUIT, "Quit supermux"))
}

/** Renders [trayMenuItems]. */
@Composable
fun MenuScope.HostingTrayMenu(
    model: TrayModel,
    background: Boolean,
    onAction: (TrayAction) -> Unit,
    onBackground: (Boolean) -> Unit,
) {
    for (item in trayMenuItems(model, background)) {
        when (item) {
            is TrayItem.Header -> Item(item.text, enabled = false, onClick = {})
            is TrayItem.Action -> Item(item.label, enabled = item.enabled, onClick = { onAction(item.id) })
            is TrayItem.Checkbox ->
                CheckboxItem(item.label, checked = item.checked, enabled = item.enabled, onCheckedChange = onBackground)
            TrayItem.Separator -> Separator()
        }
    }
}
