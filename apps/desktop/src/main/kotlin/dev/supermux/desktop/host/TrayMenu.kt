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

    /**
     * Its own one-time key for the keep-awake version ([QuitText.BACKGROUND_AWAKE]): someone who
     * saw the plain notice still learns, once, that the computer stays awake.
     */
    const val SHOWN_AWAKE_KEY = "desktop:backgroundQuitAwakeNoticeShown"

    /** The key that records [QuitAction.of]'s notice for this quit. */
    fun keyFor(keepsAwake: Boolean): String = if (keepsAwake) SHOWN_AWAKE_KEY else SHOWN_KEY
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
         * [keepsAwake]: the broker that keeps running also keeps this computer awake ("Keep this
         * computer awake" on and holding), so the notice says so.
         */
        fun of(
            s: HostingStatus,
            sessions: Int,
            quitStopsBroker: Boolean,
            noticeShown: Boolean,
            keepsAwake: Boolean = false,
        ): QuitAction = when {
            s !is HostingStatus.Running || s.readOnly -> Now()
            quitStopsBroker -> Confirm(QuitText.stops(sessions))
            else -> Now((if (keepsAwake) QuitText.BACKGROUND_AWAKE else QuitText.BACKGROUND).takeUnless { noticeShown })
        }

        /** Background on and the broker holds keep-awake: it stays awake after the app quits. */
        fun keepsAwake(background: Boolean, keepAwake: dev.supermux.net.KeepAwakeState?): Boolean =
            background && keepAwake?.enabled == true && keepAwake.active
    }
}

/** One tray menu row, as a pure model ([trayMenuItems]) so the menu's shape is testable without AWT. */
sealed interface TrayItem {
    data class Header(val text: String) : TrayItem
    data class Action(val id: TrayAction, val label: String, val enabled: Boolean = true) : TrayItem
    data class Checkbox(
        val label: String,
        val checked: Boolean,
        val enabled: Boolean,
        val id: TrayToggle = TrayToggle.BACKGROUND,
    ) : TrayItem
    data object Separator : TrayItem
}

enum class TrayAction { OPEN, SHOW_LOG, RESTART, QUIT }

enum class TrayToggle { BACKGROUND, KEEP_AWAKE, LID_CLOSED }

/**
 * The tray's keep-awake checkmarks ("Also on battery" stays in Settings, so the tray isn't crowded).
 * [keepAwake] null hides "Keep this computer awake" (the broker hasn't said, or is older);
 * [lidClosed] null hides "Even with the lid closed" (not a Mac laptop).
 */
data class TrayPower(
    val keepAwake: Boolean? = null,
    val keepAwakeEnabled: Boolean = true,
    val lidClosed: Boolean? = null,
    val lidEnabled: Boolean = true,
) {
    companion object {
        val NONE = TrayPower()
        const val KEEP_AWAKE = "Keep this computer awake"
        const val LID_CLOSED = "Even with the lid closed"

        /** Pure: what the tray shows from the broker's state and the local facts. */
        fun of(
            keepAwake: dev.supermux.net.KeepAwakeState?,
            isMac: Boolean,
            hasBattery: Boolean?,
            lid: LidStatus,
        ): TrayPower = TrayPower(
            keepAwake = keepAwake?.enabled,
            keepAwakeEnabled = keepAwake?.supported ?: false,
            // ON only while something holds (LidStatus.on), never just because the choice is saved.
            lidClosed = if (isMac && hasBattery == true) lid.on else null,
            lidEnabled = !lid.busy && lid.homeSupported,
        )
    }
}

/** The tray menu (spec §States, "Tray menu"). Hosting off: header, Open, Quit. */
fun trayMenuItems(model: TrayModel, background: Boolean, power: TrayPower = TrayPower.NONE): List<TrayItem> = buildList {
    add(TrayItem.Header(trayHeaderLine(model)))
    add(TrayItem.Action(TrayAction.OPEN, "Open supermux"))
    if (model.showLog) add(TrayItem.Action(TrayAction.SHOW_LOG, "Show log"))
    if (model.restartLabel != null || model.showKeepRunning) {
        add(TrayItem.Separator)
        model.restartLabel?.let { add(TrayItem.Action(TrayAction.RESTART, it, model.restartEnabled)) }
        if (model.showKeepRunning) {
            add(TrayItem.Checkbox("Keep running in the background", background, model.keepRunningEnabled))
            power.keepAwake?.let {
                add(TrayItem.Checkbox(TrayPower.KEEP_AWAKE, it, power.keepAwakeEnabled, TrayToggle.KEEP_AWAKE))
            }
            power.lidClosed?.let {
                add(TrayItem.Checkbox(TrayPower.LID_CLOSED, it, power.lidEnabled, TrayToggle.LID_CLOSED))
            }
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
    power: TrayPower,
    onAction: (TrayAction) -> Unit,
    onToggle: (TrayToggle, Boolean) -> Unit,
) {
    for (item in trayMenuItems(model, background, power)) {
        when (item) {
            is TrayItem.Header -> Item(item.text, enabled = false, onClick = {})
            is TrayItem.Action -> Item(item.label, enabled = item.enabled, onClick = { onAction(item.id) })
            is TrayItem.Checkbox ->
                CheckboxItem(item.label, checked = item.checked, enabled = item.enabled, onCheckedChange = { onToggle(item.id, it) })
            TrayItem.Separator -> Separator()
        }
    }
}
