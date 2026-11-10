// The machine page's own parts (#97 Step 2, spec §2.4, §10.12, §13): Devices, Shared links, Disk and Limits, in kit
// rows. The copy is the approved prototype's (`~/work/proto-97/step2/step2.js`); the calls are today's actions holders
// (DevicesSettingsActions, ProxiesSettingsActions, WorktreesSettingsActions, UsageActions), unchanged.
//
// Devices: the device asking is "This device" and its Revoke is off (the broker's `current`, S2-1); Revoke on the others
// asks first. Pairing is ONE sheet that shows the code at once (no name step): QR + copyable link + a clock. With remote
// access off on this computer, the link is its local address and says so ("same network").
// Shared links: Copy link / Open / ⋯ (Make public asks, Make private doesn't, Remove asks) and a Share a port sheet.
// Disk: a summary and a Review sheet; folders with changes (or in use) are kept and cannot be picked; Delete asks.
// Limits: each login's windows, warning from 90 %, Refresh, and Open full usage.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.parseChatTs
import dev.supermux.fs.hostParent
import dev.supermux.net.DeviceDto
import dev.supermux.net.ProxyDto
import dev.supermux.net.UsageResponse
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.ProxiesSettingsActions
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitIconButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitItemStatus
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.KitToggle
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitFocusRing
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.usage.UsageActions
import dev.supermux.ui.usage.formatResetEpochSeconds
import dev.supermux.ui.usage.formatResetIso
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.widgets.qrBitmap
import dev.supermux.ui.worktree.formatBytes
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How long a pairing code lives and how often its sheet looks for the device that used it. */
@Immutable
data class PairTimings(val ttlMs: Long = 10 * 60_000L, val pollMs: Long = 2_000L)

/** The prototype's copy for the machine's own parts, as constants and pure helpers so the tests assert the strings. */
object MachinePartsCopy {
    const val DEVICES = "Devices"
    const val THIS_DEVICE = "This device"
    const val REVOKE = "Revoke"
    const val REVOKING = "Revoking…"
    const val PAIR = "Pair a phone or computer"
    const val PAIR_HELP = "Scan a code or open a link on it"
    const val PAIR_TEXT = "Scan this with the supermux app on it, or open the link there."
    const val SAME_NETWORK = " It needs to be on the same network: reaching it from anywhere is off."
    const val PAIR_NOTE = "Treat the link like a password until it’s used."
    const val NEW_CODE = "New code"
    const val COPY_LINK = "Copy link"
    const val COPIED = "Copied"
    const val DONE = "Done"
    const val CANCEL = "Cancel"
    const val TRY_AGAIN = "Try again"
    const val MAKING_CODE = "Making a code…"
    const val CODE_EXPIRED = "This code expired. Make a new one to pair."
    const val LOADING = "Loading…"
    const val NO_DEVICES = "No devices are paired yet."
    const val DEVICES_FAILED = "Couldn’t load the devices."

    /** The name a code's device carries until it is renamed: pairing asks for none (§13). */
    const val PAIR_DEVICE_NAME = "New device"

    const val LINKS = "Shared links"
    const val SHARE = "Share a port…"
    const val SHARE_HELP = "A link to something a chat is running"
    const val LINKS_NOTE = "A link closes when its chat is archived. Making one public asks first."
    const val LINKS_FAILED = "Couldn’t load the shared links."
    const val PRIVATE = "Private: your paired devices"
    const val PUBLIC = "Public: anyone with the link"
    const val OPEN = "Open"
    const val MORE = "More"
    const val MAKE_PUBLIC_MENU = "Make public…"
    const val MAKE_PRIVATE = "Make private"
    const val REMOVE_MENU = "Remove…"
    const val MAKE_PUBLIC = "Make public"
    const val REMOVE = "Remove"
    const val SHARE_TITLE = "Share a port"
    const val CHAT = "Chat"
    const val PORT = "Port"
    const val NAME = "Name"
    const val PORT_ERROR = "A number from 1 to 65535."
    const val NAME_HINT = "Shows in the link. Letters, numbers and dashes."
    const val NAME_PLACEHOLDER = "From the chat’s name"
    const val SHARE_GO = "Share"
    const val SHARING = "Sharing…"
    const val NO_CHATS = "No chats to share from yet."
    const val WORKING = "working"

    const val DISK = "Disk"
    const val WORKTREES = "Chat worktrees"
    const val REVIEW = "Review…"
    const val DISK_NOTE = "Folders agents worked in. Nothing is deleted on its own."
    const val COUNTING = "Counting…"
    const val KEPT = "Kept"
    const val CLEAR = "Clear"
    const val NOTHING_PICKED = "Nothing picked"
    const val EVERY_FOLDER_CHANGED = "Every folder has changes."
    const val DELETE = "Delete"
    const val REPO_MISSING = "Repository missing"

    const val LIMITS = "Limits"
    const val REFRESH = "Refresh"
    const val REFRESHING = "Refreshing…"
    const val OPEN_USAGE = "Open full usage"
    const val NO_LIMITS = "No limits to show yet."
    const val USAGE_UNREAD = "Couldn’t read its usage"
    const val SIGN_IN_EXPIRED = "Sign-in expired"

    const val NOT_HOSTING = "Not hosting. Turn it on above to see this."
    const val STARTING = "Starting…"
    const val NOT_RUNNING = "supermux isn’t running on this computer."

    fun offline(machine: String, lastSeen: String) =
        "$machine is offline" + if (lastSeen.isBlank()) "" else " · last seen $lastSeen"
    fun paired(n: Int) = "$n paired"
    fun revokeTitle(device: String) = "Revoke $device?"
    fun revokeText(machine: String) = "It can’t reach $machine any more, and its open chats close on it. Pair it again to bring it back."
    fun revokeFailed(device: String) = "Couldn’t revoke $device. Try again."
    fun pairText(sameNetwork: Boolean) = PAIR_TEXT + if (sameNetwork) SAME_NETWORK else ""
    fun expires(leftMs: Long): String {
        val s = ((leftMs.coerceAtLeast(0) + 999) / 1000).toInt()
        return "Works once · expires in ${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }
    fun pairUsed(device: String) = "$device is paired. It shows up under Devices."
    fun pairFailed(machine: String) = "Couldn’t make a pairing code on $machine."

    /** "Last seen 2 min ago"; "Connected now" for the device asking; "Not used yet" before its first visit. */
    fun seen(d: DeviceDto, nowMs: Long): String {
        if (d.current) return "Connected now"
        val then = parseChatTs(d.last_seen_at) ?: return "Not used yet"
        val min = ((nowMs - then).coerceAtLeast(0)) / 60_000
        return "Last seen " + when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            min < 48 * 60 -> "yesterday"
            else -> "${min / (24 * 60)} days ago"
        }
    }

    fun linkTitle(p: ProxyDto) = if (p.port != 0) "${p.domain} · port ${p.port}" else p.domain
    fun linkFrom(p: ProxyDto) = if (p.sessionName.isBlank()) "" else "From “${p.sessionName}” · "
    fun publicTitle(name: String) = "Make $name public?"
    fun publicText(port: Int, machine: String) =
        "Anyone with the link can open port $port on $machine, not only your paired devices. You can make it private again at any time."
    fun publicFailed() = "Couldn’t make the link public. Try again."
    fun privateFailed(name: String) = "Couldn’t make “$name” private."
    fun removeTitle(name: String) = "Remove $name?"
    fun removeText(port: Int, chat: String) =
        "The link stops working." + if (chat.isNotBlank()) " Port $port keeps running in “$chat”." else ""
    fun removeFailed() = "Couldn’t remove the link. Try again."
    fun shareText(machine: String) =
        "A private link to something a chat is running on $machine. Only your paired devices can open it until you make it public."
    fun shareFailed(port: Int) = "Couldn’t share port $port. Try again."
    fun portOk(text: String): Boolean = text.toIntOrNull()?.let { it in 1..65535 } == true

    fun diskSummary(bytes: Long, folders: Int, clean: Int) = "${formatBytes(bytes)} in ${folders(folders)} · " +
        when (clean) {
            0 -> "all have changes"
            1 -> "1 has no changes"
            else -> "$clean have no changes"
        }
    fun folders(n: Int) = if (n == 1) "1 folder" else "$n folders"
    fun diskFailed(machine: String) = "Couldn’t read the worktrees on $machine."
    fun reviewText(bytes: Long, folders: Int, machine: String, shown: Int) =
        "${formatBytes(bytes)} in ${folders(folders)} on $machine. Folders with changes are kept and can’t be picked. " +
            "Biggest first; showing $shown of $folders."
    fun selectClean(n: Int) = "Select the $n without changes"
    fun picked(n: Int, bytes: Long) = "$n picked · ${formatBytes(bytes)}"
    fun deleteN(n: Int) = "Delete $n"
    fun deleteTitle(n: Int) = if (n == 1) "Delete 1 worktree?" else "Delete $n worktrees?"
    fun deleteText(n: Int, bytes: Long) =
        "Deletes ${if (n == 1) "the folder and its branch" else "the folders and their branches"} · ${formatBytes(bytes)}. " +
            "None of them has changes. This can’t be undone."
    fun deleting(done: Int, total: Int) = "Deleting $done of $total…"

    /** "made today", "3 weeks old", "2 months old". */
    fun age(nowMs: Long, mtimeMs: Long): String {
        val days = ((nowMs - mtimeMs).coerceAtLeast(0) / 86_400_000L).toInt()
        return when {
            days < 1 -> "made today"
            days == 1 -> "1 day old"
            days < 14 -> "$days days old"
            days < 60 -> "${days / 7} weeks old"
            else -> "${days / 30} months old"
        }
    }

    /** Why a worktree is kept, or null when it can be picked. */
    fun keptWhy(w: WorktreeSummaryDto): String? {
        val live = w.liveOwners
        return when {
            live.isNotEmpty() -> "In use by ${live.joinToString { it.name }}"
            w.error != null -> "Couldn’t look inside"
            !w.hasChanges -> null
            else -> listOfNotNull(
                w.uncommitted.takeIf { it > 0 }?.let { if (it == 1) "1 change" else "$it changes" },
                w.unmerged?.takeIf { it > 0 }?.let { if (it == 1) "1 unmerged commit" else "$it unmerged commits" },
            ).joinToString(" · ").ifEmpty { "Has changes" }
        }
    }

    fun updated(nowMs: Long, thenMs: Long): String {
        val min = ((nowMs - thenMs).coerceAtLeast(0)) / 60_000
        return "Updated " + when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 24 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}

/** A load in three states: a failed call is never "nothing". */
internal sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data object Failed : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

internal fun <T> Loaded<T>.valueOrNull(): T? = (this as? Loaded.Ready<T>)?.value

private const val AUTO_RETRY_MS = 3_000L
private const val SCAN_POLL_MS = 3_000L
private const val DELETE_CHUNK = 20
/** Rows the review sheet draws (biggest first); "Select the N without changes" still takes every one. */
private const val REVIEW_ROWS = 200

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/** [url] with its origin swapped for [origin]: the pairing link on this computer's local address. */
fun rebaseUrl(url: String, origin: String): String {
    val rest = url.substringAfter("://", url).let { r -> r.indexOf('/').let { if (it < 0) "" else r.substring(it) } }
    return origin.trimEnd('/') + rest
}

/** A section with nothing to show because supermux isn't there to ask: one quiet line. */
@Composable
private fun Quiet(text: String, tag: String) {
    KitNote(text, Modifier.testTag(tag))
}

/** A load that failed: the words in the warning tone and Try again. */
@Composable
private fun FailedLine(text: String, tag: String, retryTag: String, onRetry: () -> Unit) {
    val c = kitColors()
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(KitGlyphs.Warn, contentDescription = null, tint = c.warn, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 12.5.sp, color = c.warn, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        KitTextAction(MachinePartsCopy.TRY_AGAIN, onRetry, Modifier.testTag(retryTag))
    }
}

@Composable
private fun DimWords(text: String, tag: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = kitColors().dim,
        modifier = modifier.padding(top = 8.dp).testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun SheetError(text: String, tag: String) {
    Text(text, fontSize = 12.5.sp, lineHeight = 19.sp, color = kitColors().danger, modifier = Modifier.padding(top = 10.dp).testTag(tag))
}

// ─── Devices ─────────────────────────────────────────────────────────────────────────────────────

/** The Devices section's sheets and calls, kept for the page on display (#89); calls run on the fleet's scope. */
private class DevicesForm {
    var revokeTarget by mutableStateOf<String?>(null)
    var revokeBusy by mutableStateOf(false)
    var revokeError by mutableStateOf<String?>(null)
    var pairOpen by mutableStateOf(false)
    var minting by mutableStateOf(false)
    var mintFailed by mutableStateOf(false)
    var code by mutableStateOf<PairCode?>(null)
    var used by mutableStateOf(false)
    /** Calls that changed the list since the page on display last loaded it. */
    var changed by mutableStateOf(0)
}

/** A minted pairing code: the device it makes, its link, and when it stops working. */
private class PairCode(val device: String, val url: String, val expiresAtMs: Long) {
    /** Revokes the device if nobody used the code in time. */
    var expiry: Job? = null
}

@Composable
internal fun DevicesSection(
    actions: DevicesSettingsActions,
    machine: String,
    quiet: String?,
    pairOrigin: String?,
    timings: PairTimings,
) {
    val form = rememberRetainedOnPage("machine-devices") { DevicesForm() }
    val calls = rememberRetainedScope()
    var state by remember(actions) { mutableStateOf<Loaded<List<DeviceDto>>>(Loaded.Loading) }
    var reload by remember { mutableStateOf(0) }
    val running = quiet == null

    LaunchedEffect(actions, reload, running) {
        if (!running) return@LaunchedEffect
        if (state !is Loaded.Ready) state = Loaded.Loading
        state = actions.devicesLoad()?.let { Loaded.Ready(it) } ?: Loaded.Failed
    }
    // A failed load tries again on its own, so a reconnect recovers without leaving the page.
    LaunchedEffect(actions, state is Loaded.Failed, running) {
        if (!running || state !is Loaded.Failed) return@LaunchedEffect
        while (isActive) {
            delay(AUTO_RETRY_MS)
            val r = actions.devicesLoad() ?: continue
            state = Loaded.Ready(r)
            break
        }
    }
    LaunchedEffect(form, form.changed) {
        if (form.changed == 0) return@LaunchedEffect
        form.changed = 0
        reload++
    }

    val list = state.valueOrNull()
    KitSectionHead(
        MachinePartsCopy.DEVICES,
        Modifier.kitAnchor("devices"),
        note = if (running && list != null) MachinePartsCopy.paired(list.size) else null,
    )
    if (!running) {
        Quiet(quiet!!, "machine_devices_quiet")
        return
    }
    when (state) {
        Loaded.Loading -> DimWords(MachinePartsCopy.LOADING, "devices_settings_loading")
        Loaded.Failed -> FailedLine(MachinePartsCopy.DEVICES_FAILED, "devices_settings_error", "devices_settings_retry") { reload++ }
        is Loaded.Ready -> if (list.isNullOrEmpty()) KitNote(MachinePartsCopy.NO_DEVICES, Modifier.testTag("devices_settings_empty"))
    }
    val now = nowMs()
    KitItemList(Modifier.padding(top = 4.dp)) {
        list.orEmpty().forEach { d ->
            KitItem(
                d.name,
                Modifier.testTag("device_row_${d.name}"),
                subtitle = MachinePartsCopy.seen(d, now),
                mark = KitMark.Glyph(KitGlyphs.Devices),
                titleBadge = if (d.current) MachinePartsCopy.THIS_DEVICE else null,
            ) {
                KitGhostButton(
                    MachinePartsCopy.REVOKE,
                    {
                        form.revokeError = null
                        form.revokeBusy = false
                        form.revokeTarget = d.name
                    },
                    Modifier.testTag("device_revoke_${d.name}"),
                    // The device you are using can't revoke itself (§10.12).
                    enabled = !d.current,
                )
            }
        }
        KitItem(
            MachinePartsCopy.PAIR,
            Modifier.kitAnchor("pair").testTag("devices_add_button"),
            subtitle = MachinePartsCopy.PAIR_HELP,
            mark = KitMark.Add,
            onClick = {
                form.pairOpen = true
                form.used = false
                form.code = null
            },
        )
    }

    form.revokeTarget?.let { target ->
        val close = {
            if (!form.revokeBusy) {
                form.revokeTarget = null
                form.revokeError = null
            }
        }
        KitSheet(
            MachinePartsCopy.revokeTitle(target),
            onDismiss = close,
            description = MachinePartsCopy.revokeText(machine),
            tag = "devices_revoke_dialog",
            buttons = {
                KitGhostButton(MachinePartsCopy.CANCEL, close, Modifier.testTag("devices_revoke_cancel"), enabled = !form.revokeBusy)
                KitGhostButton(
                    if (form.revokeBusy) MachinePartsCopy.REVOKING else MachinePartsCopy.REVOKE,
                    {
                        form.revokeBusy = true
                        form.revokeError = null
                        calls.launch {
                            val ok = actions.deviceRevoke(target)
                            form.revokeBusy = false
                            if (ok) {
                                form.revokeTarget = null
                                // The list comes from the broker again, never a local filter.
                                form.changed++
                            } else {
                                form.revokeError = MachinePartsCopy.revokeFailed(target)
                            }
                        }
                    },
                    Modifier.testTag("devices_revoke_confirm"),
                    enabled = !form.revokeBusy,
                    destructive = true,
                )
            },
        ) {
            form.revokeError?.let { SheetError(it, "devices_revoke_error") }
        }
    }

    if (form.pairOpen) PairSheet(actions, form, machine, pairOrigin, timings, onClose = { form.pairOpen = false })
}

/**
 * One sheet for pairing (§13): the code at once, no name step. It counts down; a code nobody used by then is revoked
 * (even with the sheet closed, on the fleet's scope), and so is the previous one when New code makes another.
 */
@Composable
private fun PairSheet(
    actions: DevicesSettingsActions,
    form: DevicesForm,
    machine: String,
    pairOrigin: String?,
    timings: PairTimings,
    onClose: () -> Unit,
) {
    val calls = rememberRetainedScope()
    val platform = LocalPlatform.current
    var copied by remember(form.code) { mutableStateOf(false) }
    var now by remember { mutableStateOf(nowMs()) }

    /** Revokes [code]'s device unless it was used (it has been seen). */
    suspend fun retireIfUnused(code: PairCode) {
        val seen = actions.devicesLoad()?.firstOrNull { it.name == code.device }?.last_seen_at != null
        if (!seen && actions.deviceRevoke(code.device)) form.changed++
    }

    fun mint() {
        val old = form.code
        form.code = null
        form.used = false
        form.mintFailed = false
        form.minting = true
        calls.launch {
            if (old != null) {
                old.expiry?.cancel()
                retireIfUnused(old)
            }
            val r = actions.deviceAdd(MachinePartsCopy.PAIR_DEVICE_NAME)
            form.minting = false
            if (r == null || r.url.isBlank()) {
                form.mintFailed = true
                return@launch
            }
            val code = PairCode(
                device = r.name.ifBlank { MachinePartsCopy.PAIR_DEVICE_NAME },
                url = pairOrigin?.let { rebaseUrl(r.url, it) } ?: r.url,
                expiresAtMs = nowMs() + timings.ttlMs,
            )
            code.expiry = calls.launch {
                delay(timings.ttlMs)
                retireIfUnused(code)
            }
            form.code = code
            form.changed++
        }
    }

    LaunchedEffect(Unit) { if (form.code == null && !form.minting) mint() }
    val code = form.code
    // The clock, and a look for the device that used the code.
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        while (isActive && !form.used && nowMs() < code.expiresAtMs) {
            now = nowMs()
            delay(minOf(1_000L, (code.expiresAtMs - now).coerceAtLeast(1)))
        }
        now = nowMs()
    }
    LaunchedEffect(code) {
        if (code == null) return@LaunchedEffect
        while (isActive && !form.used && nowMs() < code.expiresAtMs) {
            delay(timings.pollMs)
            val d = actions.devicesLoad()?.firstOrNull { it.name == code.device }
            if (d?.last_seen_at != null) {
                form.used = true
                code.expiry?.cancel()
                form.changed++
            }
        }
    }

    val sameNetwork = pairOrigin != null
    val left = (code?.expiresAtMs ?: 0L) - now
    val expired = code != null && !form.used && left <= 0
    KitSheet(
        MachinePartsCopy.PAIR,
        onDismiss = onClose,
        description = MachinePartsCopy.pairText(sameNetwork),
        tag = "devices_add_dialog",
        buttons = {
            if (!form.used) {
                KitTextAction(MachinePartsCopy.NEW_CODE, ::mint, Modifier.testTag("devices_pair_new"), enabled = !form.minting)
            }
            KitGhostButton(MachinePartsCopy.DONE, onClose, Modifier.testTag("devices_add_dismiss"))
        },
    ) {
        val c = kitColors()
        when {
            form.used && code != null -> Row(
                Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("devices_pair_used"),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(KitGlyphs.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
                Text(MachinePartsCopy.pairUsed(code.device), fontSize = 12.5.sp, color = c.text)
            }
            form.minting -> DimWords(MachinePartsCopy.MAKING_CODE, "devices_pair_minting", Modifier.padding(vertical = 30.dp))
            form.mintFailed -> FailedLine(MachinePartsCopy.pairFailed(machine), "devices_add_error", "devices_pair_retry", ::mint)
            expired -> DimWords(MachinePartsCopy.CODE_EXPIRED, "devices_pair_expired", Modifier.padding(vertical = 30.dp))
            code != null -> Column(Modifier.fillMaxWidth().testTag("devices_pairing_result"), horizontalAlignment = Alignment.CenterHorizontally) {
                val qr = remember(code.url) { runCatching { qrBitmap(code.url, sizePx = 512) }.getOrNull() }
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "Pairing code",
                        modifier = Modifier
                            .padding(top = 16.dp, bottom = 4.dp)
                            .size(168.dp)
                            .clip(RoundedCornerShape(8.dp))
                            // White on both themes, so it scans.
                            .background(Color.White)
                            .padding(10.dp)
                            .testTag("devices_pairing_qr"),
                    )
                }
                Text(
                    MachinePartsCopy.expires(left),
                    fontSize = 12.sp,
                    color = c.dim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("devices_pairing_expires"),
                )
                CopyLine(code.url, copied, "devices_pairing_url", "devices_add_copy") {
                    platform.copyToClipboard(code.url)
                    copied = true
                }
                KitNote(MachinePartsCopy.PAIR_NOTE)
            }
        }
    }
}

/** The prototype's `.copyline`: the link in mono on the field wash, and Copy link at its end. */
@Composable
private fun CopyLine(url: String, copied: Boolean, textTag: String, buttonTag: String, onCopy: () -> Unit) {
    val c = kitColors()
    val shape = RoundedCornerShape(6.dp)
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(c.wash)
            .border(1.dp, c.line, shape)
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            url,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            color = c.dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(textTag),
        )
        KitGhostButton(if (copied) MachinePartsCopy.COPIED else MachinePartsCopy.COPY_LINK, onCopy, Modifier.testTag(buttonTag), enabled = !copied)
    }
}

// ─── Shared links ────────────────────────────────────────────────────────────────────────────────

private class LinksForm {
    var removeTarget by mutableStateOf<ProxyDto?>(null)
    var removeBusy by mutableStateOf(false)
    var removeError by mutableStateOf<String?>(null)
    var publicTarget by mutableStateOf<ProxyDto?>(null)
    var publicBusy by mutableStateOf(false)
    var publicError by mutableStateOf<String?>(null)
    var shareOpen by mutableStateOf(false)
    var chat by mutableStateOf<String?>(null)
    var port by mutableStateOf("")
    var name by mutableStateOf("")
    var shareBusy by mutableStateOf(false)
    var shareError by mutableStateOf<String?>(null)
    var changed by mutableStateOf(0)

    fun resetShare() {
        chat = null
        port = ""
        name = ""
        shareBusy = false
        shareError = null
    }
}

/** A link's tag-safe key (the tags today's Proxies screen used). */
private fun tagSafe(domain: String) = domain.replace(Regex("[^A-Za-z0-9._-]"), "_")

@Composable
internal fun LinksSection(
    actions: ProxiesSettingsActions,
    machine: String,
    quiet: String?,
    working: List<String>,
) {
    val form = rememberRetainedOnPage("machine-links") { LinksForm() }
    val calls = rememberRetainedScope()
    val scope = rememberCoroutineScope()
    val platform = LocalPlatform.current
    val phone = kitMetrics().phone
    val c = kitColors()
    var state by remember(actions) { mutableStateOf<Loaded<List<ProxyDto>>>(Loaded.Loading) }
    var reload by remember { mutableStateOf(0) }
    var toggleError by remember { mutableStateOf<String?>(null) }
    val running = quiet == null

    LaunchedEffect(actions, reload, running) {
        if (!running) return@LaunchedEffect
        if (state !is Loaded.Ready) state = Loaded.Loading
        state = actions.proxiesLoad()?.let { Loaded.Ready(it) } ?: Loaded.Failed
    }
    LaunchedEffect(actions, state is Loaded.Failed, running) {
        if (!running || state !is Loaded.Failed) return@LaunchedEffect
        while (isActive) {
            delay(AUTO_RETRY_MS)
            val r = actions.proxiesLoad() ?: continue
            state = Loaded.Ready(r)
            break
        }
    }
    LaunchedEffect(form, form.changed) {
        if (form.changed == 0) return@LaunchedEffect
        form.changed = 0
        reload++
    }

    fun setLocally(domain: String, public: Boolean) {
        val now = state.valueOrNull() ?: return
        state = Loaded.Ready(now.map { if (it.domain == domain) it.copy(isPublic = public) else it })
    }

    KitSectionHead(MachinePartsCopy.LINKS, Modifier.kitAnchor("links"))
    if (!running) {
        Quiet(quiet!!, "machine_links_quiet")
        return
    }
    when (state) {
        Loaded.Loading -> DimWords(MachinePartsCopy.LOADING, "proxies_settings_loading")
        Loaded.Failed -> FailedLine(MachinePartsCopy.LINKS_FAILED, "proxies_settings_error", "proxies_settings_retry") { reload++ }
        is Loaded.Ready -> Unit
    }
    toggleError?.let { Text(it, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 6.dp).testTag("proxies_toggle_error")) }
    KitItemList(Modifier.padding(top = 4.dp)) {
        state.valueOrNull().orEmpty().forEach { p -> key(p.domain) {
            val key = tagSafe(p.domain)
            val url = p.url?.takeIf { it.isNotBlank() }
            var menu by remember(p.domain) { mutableStateOf(false) }
            var copied by remember(p.domain, url) { mutableStateOf(false) }
            KitItem(
                MachinePartsCopy.linkTitle(p),
                Modifier.testTag("proxy_row_$key"),
                subtitleRich = buildAnnotatedString {
                    append(MachinePartsCopy.linkFrom(p))
                    if (p.isPublic) withStyle(SpanStyle(color = c.warn)) { append(MachinePartsCopy.PUBLIC) } else append(MachinePartsCopy.PRIVATE)
                },
                mark = KitMark.Glyph(if (p.isPublic) KitGlyphs.Globe else KitGlyphs.Link),
            ) {
                if (url != null) {
                    KitGhostButton(
                        if (copied) MachinePartsCopy.COPIED else MachinePartsCopy.COPY_LINK,
                        {
                            platform.copyToClipboard(url)
                            copied = true
                        },
                        Modifier.testTag("proxy_url_copy_$key"),
                        enabled = !copied,
                    )
                    if (!phone) KitIconButton(KitGlyphs.External, MachinePartsCopy.OPEN, { platform.openUrl(url) }, Modifier.testTag("proxy_url_open_$key"))
                }
                Box {
                    KitIconButton(KitGlyphs.More, MachinePartsCopy.MORE, { menu = true }, Modifier.testTag("proxy_more_$key"))
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (phone && url != null) {
                            MenuItem(KitGlyphs.External, MachinePartsCopy.OPEN, "proxy_menu_open_$key") {
                                menu = false
                                platform.openUrl(url)
                            }
                        }
                        if (p.isPublic) {
                            // Making a link private only narrows who can open it: no question (§13).
                            MenuItem(KitGlyphs.Lock, MachinePartsCopy.MAKE_PRIVATE, "proxy_make_private_$key") {
                                menu = false
                                toggleError = null
                                scope.launch {
                                    if (actions.proxySetPublic(p.domain, false)) {
                                        setLocally(p.domain, false)
                                    } else {
                                        toggleError = MachinePartsCopy.privateFailed(p.domain)
                                        reload++
                                    }
                                }
                            }
                        } else {
                            MenuItem(KitGlyphs.Globe, MachinePartsCopy.MAKE_PUBLIC_MENU, "proxy_make_public_$key") {
                                menu = false
                                form.publicError = null
                                form.publicBusy = false
                                form.publicTarget = p
                            }
                        }
                        MenuItem(KitGlyphs.Trash, MachinePartsCopy.REMOVE_MENU, "proxy_remove_$key", danger = true) {
                            menu = false
                            form.removeError = null
                            form.removeBusy = false
                            form.removeTarget = p
                        }
                    }
                }
            }
        } }
        KitItem(
            MachinePartsCopy.SHARE,
            Modifier.kitAnchor("share").testTag("proxies_expose_button"),
            subtitle = MachinePartsCopy.SHARE_HELP,
            mark = KitMark.Add,
            onClick = {
                form.resetShare()
                form.shareOpen = true
            },
        )
    }
    KitNote(MachinePartsCopy.LINKS_NOTE)

    form.publicTarget?.let { p ->
        val close = {
            if (!form.publicBusy) {
                form.publicTarget = null
                form.publicError = null
            }
        }
        KitSheet(
            MachinePartsCopy.publicTitle(p.domain),
            onDismiss = close,
            description = MachinePartsCopy.publicText(p.port, machine),
            tag = "proxies_public_dialog",
            buttons = {
                KitGhostButton(MachinePartsCopy.CANCEL, close, Modifier.testTag("proxies_public_cancel"), enabled = !form.publicBusy)
                KitGhostButton(
                    MachinePartsCopy.MAKE_PUBLIC,
                    {
                        form.publicBusy = true
                        form.publicError = null
                        calls.launch {
                            val ok = actions.proxySetPublic(p.domain, true)
                            form.publicBusy = false
                            if (ok) {
                                form.publicTarget = null
                                form.changed++
                            } else {
                                form.publicError = MachinePartsCopy.publicFailed()
                            }
                        }
                    },
                    Modifier.testTag("proxies_public_confirm"),
                    enabled = !form.publicBusy,
                    destructive = true,
                )
            },
        ) {
            form.publicError?.let { SheetError(it, "proxies_public_error") }
        }
    }

    form.removeTarget?.let { p ->
        val close = {
            if (!form.removeBusy) {
                form.removeTarget = null
                form.removeError = null
            }
        }
        KitSheet(
            MachinePartsCopy.removeTitle(p.domain),
            onDismiss = close,
            description = MachinePartsCopy.removeText(p.port, p.sessionName),
            tag = "proxies_remove_dialog",
            buttons = {
                KitGhostButton(MachinePartsCopy.CANCEL, close, Modifier.testTag("proxies_remove_cancel"), enabled = !form.removeBusy)
                KitGhostButton(
                    MachinePartsCopy.REMOVE,
                    {
                        form.removeBusy = true
                        form.removeError = null
                        calls.launch {
                            val ok = actions.proxyRemove(p.domain)
                            form.removeBusy = false
                            if (ok) {
                                form.removeTarget = null
                                form.changed++
                            } else {
                                form.removeError = MachinePartsCopy.removeFailed()
                            }
                        }
                    },
                    Modifier.testTag("proxies_remove_confirm"),
                    enabled = !form.removeBusy,
                    destructive = true,
                )
            },
        ) {
            form.removeError?.let { SheetError(it, "proxies_remove_error") }
        }
    }

    if (form.shareOpen) ShareSheet(actions, form, machine, working)
}

@Composable
private fun MenuItem(glyph: ImageVector, text: String, tag: String, danger: Boolean = false, onClick: () -> Unit) {
    val c = kitColors()
    DropdownMenuItem(
        text = { Text(text, color = if (danger) c.danger else c.text) },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        leadingIcon = { Icon(glyph, contentDescription = null, tint = if (danger) c.danger else c.dim, modifier = Modifier.size(15.dp)) },
    )
}

/** "Share a port": a chat (from the machine's live list), a port, and a name for the link. */
@Composable
private fun ShareSheet(actions: ProxiesSettingsActions, form: LinksForm, machine: String, working: List<String>) {
    val calls = rememberRetainedScope()
    val c = kitColors()
    val chats by actions.sessionNames.collectAsState(initial = emptyList())
    val portOk = MachinePartsCopy.portOk(form.port)
    val chat = form.chat?.takeIf { it in chats }
    val canShare = chat != null && portOk && !form.shareBusy
    val close = {
        if (!form.shareBusy) {
            form.shareOpen = false
            form.resetShare()
        }
    }
    fun share() {
        val session = chat ?: return
        val port = form.port.toIntOrNull() ?: return
        form.shareBusy = true
        form.shareError = null
        calls.launch {
            val r = actions.proxyCreate(session, port, form.name.trim().trim('-').ifBlank { null })
            form.shareBusy = false
            if (r == null) {
                form.shareError = MachinePartsCopy.shareFailed(port)
            } else {
                form.shareOpen = false
                form.resetShare()
                form.changed++
            }
        }
    }
    KitSheet(
        MachinePartsCopy.SHARE_TITLE,
        onDismiss = close,
        description = MachinePartsCopy.shareText(machine),
        tag = "proxies_create_dialog",
        // A half-filled form: a stray tap outside does not throw it away.
        dismissOnOutside = false,
        buttons = {
            KitGhostButton(MachinePartsCopy.CANCEL, close, Modifier.testTag("proxies_create_cancel"), enabled = !form.shareBusy)
            KitGhostButton(
                if (form.shareBusy) MachinePartsCopy.SHARING else MachinePartsCopy.SHARE_GO,
                ::share,
                Modifier.testTag("proxies_create_confirm"),
                enabled = canShare,
            )
        },
    ) {
        FieldLabel(MachinePartsCopy.CHAT)
        if (chats.isEmpty()) {
            KitNote(MachinePartsCopy.NO_CHATS)
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState())) {
                dev.supermux.ui.settings.kit.KitHairline()
                chats.forEach { name ->
                    val src = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = src, indication = null, role = Role.RadioButton) { form.chat = name }
                            .kitFocusRing(src, RoundedCornerShape(4.dp))
                            .padding(vertical = 8.dp, horizontal = 2.dp)
                            .testTag("proxies_create_session_item_$name"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (chat == name) {
                            Icon(KitGlyphs.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
                        } else {
                            Box(Modifier.size(14.dp))
                        }
                        Text(name, fontSize = 13.sp, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (name in working) Text(MachinePartsCopy.WORKING, fontSize = 11.5.sp, color = c.faint)
                    }
                    dev.supermux.ui.settings.kit.KitHairline()
                }
            }
        }
        Column(Modifier.padding(top = 12.dp)) {
            KitTextField(
                form.port,
                { form.port = it.filter(Char::isDigit).take(5); form.shareError = null },
                label = MachinePartsCopy.PORT,
                placeholder = "3000",
                error = if (form.port.isNotEmpty() && !portOk) MachinePartsCopy.PORT_ERROR else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                onSubmit = { if (canShare) share() },
                fieldTag = "proxies_create_port",
            )
        }
        Column(Modifier.padding(top = 12.dp)) {
            KitTextField(
                form.name,
                { form.name = it.lowercase().replace(Regex("[^a-z0-9-]"), "-"); form.shareError = null },
                label = MachinePartsCopy.NAME,
                placeholder = MachinePartsCopy.NAME_PLACEHOLDER,
                onSubmit = { if (canShare) share() },
                fieldTag = "proxies_create_domain",
            )
            Text(MachinePartsCopy.NAME_HINT, fontSize = 11.5.sp, color = c.faint, modifier = Modifier.padding(top = 4.dp))
        }
        form.shareError?.let { SheetError(it, "proxies_create_error") }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 12.sp, color = kitColors().dim, modifier = Modifier.padding(top = 12.dp, bottom = 5.dp))
}

// ─── Disk ────────────────────────────────────────────────────────────────────────────────────────

private class DiskForm {
    var reviewOpen by mutableStateOf(false)
    var confirming by mutableStateOf(false)
    var selected by mutableStateOf(setOf<String>())
    /** (done, total) while a delete runs. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var rowErrors by mutableStateOf(mapOf<String, String>())
    var deleted by mutableStateOf(setOf<String>())
    var changed by mutableStateOf(0)
}

private fun pickable(w: WorktreeSummaryDto) = MachinePartsCopy.keptWhy(w) == null

/** Groups by repository ROOT, labelled by name; a name shared by several roots gets its parent folder. */
internal fun worktreeGroups(rows: List<WorktreeSummaryDto>): List<Pair<String, List<WorktreeSummaryDto>>> {
    val byRoot = rows.groupBy { it.repoRoot ?: "name:${it.repoName}" }
    val rootsPerName = byRoot.values.groupBy { it.first().repoName }.mapValues { it.value.size }
    return byRoot.values.map { g ->
        val first = g.first()
        val label = if ((rootsPerName[first.repoName] ?: 1) > 1) {
            "${first.repoName} (${first.repoRoot?.let { hostParent(it) ?: it } ?: "unknown location"})"
        } else {
            first.repoName
        }
        label to g
    }
}

@Composable
internal fun DiskSection(actions: WorktreesSettingsActions, machine: String, quiet: String?) {
    val form = rememberRetainedOnPage("machine-disk") { DiskForm() }
    var state by remember(actions) { mutableStateOf<Loaded<WorktreeListDto>>(Loaded.Loading) }
    var reload by remember { mutableStateOf(0) }
    val running = quiet == null
    val sizes = actions.sizes()
    val removed = actions.removedIds()

    LaunchedEffect(actions, reload, running) {
        if (!running) return@LaunchedEffect
        if (state !is Loaded.Ready) state = Loaded.Loading
        val r = actions.load(false)
        state = r?.let { Loaded.Ready(it) } ?: Loaded.Failed
        if (r != null) {
            form.deleted = emptySet()
            // The broker answers from its cache and is still looking: ask again shortly.
            if (r.scanning) {
                delay(SCAN_POLL_MS)
                reload++
            }
        }
    }
    LaunchedEffect(form, form.changed) {
        if (form.changed == 0) return@LaunchedEffect
        form.changed = 0
        reload++
    }

    KitSectionHead(MachinePartsCopy.DISK, Modifier.kitAnchor("disk"))
    if (!running) {
        Quiet(quiet!!, "machine_disk_quiet")
        return
    }
    val list = state.valueOrNull()
    val visible = remember(list, removed, form.deleted) {
        list?.worktrees.orEmpty().filter { it.id !in removed && it.id !in form.deleted }
    }
    val sizeOf: (WorktreeSummaryDto) -> Long = { sizes[it.id] ?: it.bytes ?: 0L }
    val orphans = list?.orphans
    val folders = visible.size + (orphans?.count ?: 0)
    val bytes = visible.sumOf(sizeOf) + (orphans?.bytes ?: 0L)
    val clean = visible.filter(::pickable)
    val subtitle = when {
        state is Loaded.Failed -> MachinePartsCopy.diskFailed(machine)
        list == null || (list.scanning && folders == 0) -> MachinePartsCopy.COUNTING
        else -> MachinePartsCopy.diskSummary(bytes, folders, clean.size)
    }
    KitItemList(Modifier.kitAnchor("worktrees")) {
        KitItem(
            MachinePartsCopy.WORKTREES,
            Modifier.testTag("machine_worktrees"),
            subtitle = subtitle,
            subtitleWarn = state is Loaded.Failed,
            mark = KitMark.Glyph(KitGlyphs.Tree),
        ) {
            if (state is Loaded.Failed) {
                KitTextAction(MachinePartsCopy.TRY_AGAIN, { reload++ }, Modifier.testTag("wt_retry"))
            } else {
                KitGhostButton(
                    MachinePartsCopy.REVIEW,
                    {
                        form.selected = emptySet()
                        form.rowErrors = emptyMap()
                        form.reviewOpen = true
                    },
                    Modifier.testTag("wt_review"),
                    enabled = folders > 0,
                )
            }
        }
    }
    KitNote(MachinePartsCopy.DISK_NOTE)

    if (form.reviewOpen && list != null) ReviewSheet(actions, form, machine, visible, sizeOf, bytes, folders, list)
}

@Composable
private fun ReviewSheet(
    actions: WorktreesSettingsActions,
    form: DiskForm,
    machine: String,
    visible: List<WorktreeSummaryDto>,
    sizeOf: (WorktreeSummaryDto) -> Long,
    bytes: Long,
    folders: Int,
    list: WorktreeListDto,
) {
    val calls = rememberRetainedScope()
    val c = kitColors()
    val now = nowMs()
    val clean = visible.filter(::pickable)
    val cleanIds = clean.map { it.id }.toSet()
    // Biggest first, by the sizes known when the sheet opened: a live size must not move a row under a finger.
    val shown = remember(visible) { visible.sortedByDescending(sizeOf).take(REVIEW_ROWS) }
    val selected = form.selected.filter { it in cleanIds }.toSet()
    val selectedRows = clean.filter { it.id in selected }
    val selectedBytes = selectedRows.sumOf(sizeOf)
    val progress = form.progress
    val busy = progress != null

    fun delete() {
        val ids = selectedRows.map { it.id }
        form.confirming = false
        form.progress = 0 to ids.size
        calls.launch {
            try {
                var done = 0
                // A batch of 20 at a time: the broker deletes one by one, and a big batch would sit silent for minutes.
                for (chunk in ids.chunked(DELETE_CHUNK)) {
                    val res = actions.delete(chunk)
                    if (res == null) {
                        form.rowErrors = form.rowErrors + chunk.associateWith { "Couldn’t reach $machine" }
                        break
                    }
                    val ok = res.filter { it.ok }.map { it.id }.toSet()
                    form.deleted = form.deleted + ok
                    form.selected = form.selected - ok
                    form.rowErrors = form.rowErrors - ok + res.filter { !it.ok }.associate { r ->
                        r.id to if (r.error == "in_use") "In use by ${r.inUseBy.joinToString()}" else "Couldn’t delete it" + (r.error?.let { ": $it" } ?: "")
                    }
                    done += chunk.size
                    form.progress = done to ids.size
                }
            } finally {
                form.progress = null
                if (form.rowErrors.isEmpty()) form.reviewOpen = false
                form.changed++
            }
        }
    }

    if (form.confirming) {
        KitSheet(
            MachinePartsCopy.deleteTitle(selectedRows.size),
            onDismiss = { form.confirming = false },
            description = MachinePartsCopy.deleteText(selectedRows.size, selectedBytes),
            tag = "wt_delete_dialog",
            buttons = {
                KitGhostButton(MachinePartsCopy.CANCEL, { form.confirming = false }, Modifier.testTag("wt_delete_cancel"))
                KitGhostButton(MachinePartsCopy.DELETE, ::delete, Modifier.testTag("wt_delete_confirm"), destructive = true)
            },
        )
        return
    }

    KitSheet(
        MachinePartsCopy.WORKTREES,
        onDismiss = { if (!busy) form.reviewOpen = false },
        description = if (busy) null else MachinePartsCopy.reviewText(bytes, folders, machine, shown.size),
        tag = "wt_review_sheet",
        wide = true,
        buttons = {
            if (!busy) {
                Text(
                    if (selectedRows.isEmpty()) MachinePartsCopy.NOTHING_PICKED else MachinePartsCopy.picked(selectedRows.size, selectedBytes),
                    fontSize = 12.5.sp,
                    color = c.dim,
                    modifier = Modifier.testTag("wt_picked"),
                )
                KitGhostButton(MachinePartsCopy.CANCEL, { form.reviewOpen = false }, Modifier.testTag("wt_cancel"))
                KitGhostButton(
                    if (selectedRows.isEmpty()) MachinePartsCopy.DELETE else MachinePartsCopy.deleteN(selectedRows.size),
                    { form.confirming = true },
                    Modifier.testTag("wt_delete"),
                    enabled = selectedRows.isNotEmpty(),
                    destructive = true,
                )
            }
        },
    ) {
        if (progress != null) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(KitGlyphs.Trash, contentDescription = null, tint = c.dim, modifier = Modifier.size(15.dp))
                Text(
                    MachinePartsCopy.deleting(progress.first, progress.second),
                    fontSize = 12.5.sp,
                    color = c.text,
                    modifier = Modifier.testTag("wt_delete_progress").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            return@KitSheet
        }
        Box(Modifier.padding(top = 12.dp)) {
            when {
                clean.isEmpty() -> KitNote(MachinePartsCopy.EVERY_FOLDER_CHANGED)
                selected.size == clean.size -> KitTextAction(MachinePartsCopy.CLEAR, { form.selected = emptySet() }, Modifier.testTag("wt_select_clean"))
                else -> KitTextAction(MachinePartsCopy.selectClean(clean.size), { form.selected = cleanIds }, Modifier.testTag("wt_select_clean"))
            }
        }
        Column(Modifier.padding(top = 12.dp).fillMaxWidth()) {
            dev.supermux.ui.settings.kit.KitHairline()
            worktreeGroups(shown).forEach { (label, rows) ->
                Text(
                    label.uppercase(),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.63.sp,
                    color = c.faint,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).testTag("wt_group_$label"),
                )
                rows.forEach { w -> WorktreeItem(w, sizeOf(w), now, w.id in selected, form.rowErrors[w.id]) { on -> form.selected = if (on) form.selected + w.id else form.selected - w.id } }
            }
            list.orphans.takeIf { it.count > 0 }?.let { o ->
                KitItem(
                    MachinePartsCopy.REPO_MISSING,
                    Modifier.testTag("wt_orphans"),
                    subtitle = "${MachinePartsCopy.folders(o.count)} · ${formatBytes(o.bytes)}",
                    mark = KitMark.Glyph(KitGlyphs.Lock),
                    muted = true,
                ) { KitItemStatus(MachinePartsCopy.KEPT) }
            }
        }
    }
}

@Composable
private fun WorktreeItem(w: WorktreeSummaryDto, size: Long, now: Long, picked: Boolean, error: String?, onPick: (Boolean) -> Unit) {
    val c = kitColors()
    val title = w.owners.firstOrNull()?.name ?: w.branch ?: w.id.substringAfter('/')
    val why = MachinePartsCopy.keptWhy(w)
    val head = "${formatBytes(size)} · ${MachinePartsCopy.age(now, w.mtime)} · "
    KitItem(
        title,
        Modifier.testTag("wt_row_${w.id}"),
        subtitleRich = buildAnnotatedString {
            append(head)
            if (why != null) withStyle(SpanStyle(color = c.warn)) { append(why) } else append("no changes")
        },
        mark = KitMark.Glyph(if (why != null) KitGlyphs.Lock else KitGlyphs.Tree),
        muted = why != null,
        below = if (error == null) null else {
            { Text(error, fontSize = 12.sp, color = c.danger, modifier = Modifier.testTag("wt_error_${w.id}")) }
        },
    ) {
        if (why != null) {
            KitItemStatus(MachinePartsCopy.KEPT, modifier = Modifier.testTag("wt_kept_${w.id}"))
        } else {
            KitToggle(picked, onPick, "Pick $title", Modifier.testTag("wt_check_${w.id}"))
        }
    }
}

// ─── Limits ──────────────────────────────────────────────────────────────────────────────────────

/** One login's limits: its windows, or why there are none to show. */
@Immutable
data class LimitLogin(
    val key: String,
    val mark: String,
    val title: String,
    val windows: List<LimitWindow> = emptyList(),
    val note: String? = null,
    val noteWarn: Boolean = false,
)

@Immutable
data class LimitWindow(val label: String, val percent: Int, val reset: String) {
    /** The prototype's warning tone: from 90 %. */
    val warn: Boolean get() = percent >= 90
}

private fun pct(v: Double) = v.roundToInt().coerceIn(0, 100)

private fun codexWindowLabel(seconds: Double?, label: String): String = when (seconds?.roundToInt()) {
    18_000 -> "5-hour"
    604_800 -> "Weekly"
    else -> label.ifBlank { "Usage" }
}

/**
 * Pure: every login's windows from the Usage data — the system login first, then the logins added beside it ("Claude
 * Code · your login" only when there are others to tell it from). Providers billed per token, or never signed in,
 * have no limits and are left out.
 */
fun limitLogins(u: UsageResponse?, now: Long): List<LimitLogin> {
    if (u == null) return emptyList()
    val out = mutableListOf<LimitLogin>()
    fun hasAccounts(agent: String) = u.accounts.any { it.agent == agent && it.quota }
    fun systemTitle(agent: String, name: String) = if (hasAccounts(agent)) "$name · your login" else name
    fun unread(key: String, mark: String, title: String, agent: String) {
        if (u.errors.containsKey(agent)) {
            val expired = u.errorCodes[agent] == "account_expired"
            out += LimitLogin(key, mark, title, note = if (expired) MachinePartsCopy.SIGN_IN_EXPIRED else MachinePartsCopy.USAGE_UNREAD, noteWarn = expired)
        }
    }
    fun claude(c: dev.supermux.net.ClaudeUsage) = listOf(
        LimitWindow("5-hour", pct(c.fiveHour.used), formatResetIso(c.fiveHour.resetsAt, now)),
        LimitWindow("Weekly", pct(c.sevenDay.used), formatResetIso(c.sevenDay.resetsAt, now)),
    )
    fun codex(c: dev.supermux.net.CodexUsage) = c.windows.map {
        LimitWindow(codexWindowLabel(it.windowSeconds, it.label), pct(it.used), formatResetEpochSeconds(it.resetsAt, now))
    }
    fun cursor(c: dev.supermux.net.CursorUsage) = listOf(LimitWindow("Monthly", pct(c.totalPercentUsed), formatResetIso(c.billingCycleEnd, now)))
    fun grok(g: dev.supermux.net.GrokUsage) = listOf(
        LimitWindow(if (g.periodType == "weekly") "Weekly" else "Monthly", pct(g.percentUsed), formatResetIso(g.billingPeriodEnd, now)),
    )

    val title = mapOf("claude" to "Claude Code", "codex" to "Codex", "cursor" to "Cursor", "grok" to "Grok")
    val mark = mapOf("claude" to "C", "codex" to "X", "cursor" to "Cu", "grok" to "G")
    for (agent in listOf("claude", "codex", "cursor", "grok")) {
        val t = systemTitle(agent, title.getValue(agent))
        val m = mark.getValue(agent)
        val windows = when (agent) {
            "claude" -> u.claude?.let(::claude)
            "codex" -> u.codex?.let(::codex)
            "cursor" -> u.cursor?.let(::cursor)
            else -> u.grok?.let(::grok)
        }
        if (windows != null) out += LimitLogin(agent, m, t, windows) else unread(agent, m, t, agent)
        u.accounts.filter { it.agent == agent && it.quota }.forEach { a ->
            val at = "${title.getValue(agent)} · ${a.label.ifBlank { a.id }}"
            val aw = when (agent) {
                "claude" -> a.claude?.let(::claude)
                "codex" -> a.codex?.let(::codex)
                "cursor" -> a.cursor?.let(::cursor)
                else -> a.grok?.let(::grok)
            }
            out += when {
                aw != null -> LimitLogin("$agent:${a.id}", m, at, aw)
                a.errorCode == "account_expired" -> LimitLogin("$agent:${a.id}", m, at, note = MachinePartsCopy.SIGN_IN_EXPIRED, noteWarn = true)
                else -> LimitLogin("$agent:${a.id}", m, at, note = MachinePartsCopy.USAGE_UNREAD)
            }
        }
    }
    return out
}

/** The newest time any provider's numbers were read, epoch ms. */
private fun newestFetch(u: UsageResponse): Long? =
    (u.fetchedAt.values + u.accounts.map { it.fetchedAt }).mapNotNull { s -> s?.let { it.toLongOrNull() ?: parseChatTs(it) } }.maxOrNull()

@Composable
internal fun LimitsSection(usage: UsageActions?, quiet: String?, onOpenUsage: () -> Unit) {
    val c = kitColors()
    val scope = rememberCoroutineScope()
    val running = quiet == null
    var latest by remember(usage) { mutableStateOf<UsageResponse?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var loaded by remember(usage) { mutableStateOf(false) }
    val snap = usage?.snapshot?.collectAsState()?.value
    LaunchedEffect(snap) { if (snap != null) latest = snap }
    LaunchedEffect(usage, running) {
        if (usage == null || !running) return@LaunchedEffect
        if (latest == null) usage.load()?.let { latest = it }
        loaded = true
    }
    val shown = latest
    KitSectionHead(
        MachinePartsCopy.LIMITS,
        Modifier.kitAnchor("limits"),
        noteContent = if (running && usage != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (refreshing) {
                        Text(MachinePartsCopy.REFRESHING, fontSize = 11.5.sp, color = c.faint, modifier = Modifier.testTag("machine_limits_refreshing"))
                    } else {
                        shown?.let(::newestFetch)?.let {
                            Text(MachinePartsCopy.updated(nowMs(), it) + " · ", fontSize = 11.5.sp, color = c.faint, modifier = Modifier.testTag("machine_limits_updated"))
                        }
                        val src = remember { MutableInteractionSource() }
                        Text(
                            MachinePartsCopy.REFRESH,
                            fontSize = 11.5.sp,
                            color = c.dim,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable(interactionSource = src, indication = null, role = Role.Button) {
                                    refreshing = true
                                    scope.launch {
                                        try {
                                            usage.refresh()?.let { latest = it }
                                        } finally {
                                            refreshing = false
                                        }
                                    }
                                }
                                .kitFocusRing(src, RoundedCornerShape(3.dp))
                                .testTag("machine_limits_refresh"),
                        )
                    }
                }
            }
        } else {
            null
        },
    )
    if (!running) {
        Quiet(quiet!!, "machine_limits_quiet")
        return
    }
    if (usage == null) return
    val logins = remember(shown) { limitLogins(shown, nowMs()) }
    when {
        shown == null && !loaded -> DimWords(MachinePartsCopy.LOADING, "machine_limits_loading")
        logins.isEmpty() -> KitNote(MachinePartsCopy.NO_LIMITS, Modifier.testTag("machine_limits_empty"))
        else -> KitItemList(Modifier.padding(top = 4.dp)) {
            logins.forEach { l ->
                KitItem(
                    l.title,
                    Modifier.testTag("machine_limit_${l.key}"),
                    subtitle = l.note,
                    subtitleWarn = l.noteWarn,
                    mark = KitMark.Letters(l.mark),
                    below = if (l.windows.isEmpty()) null else {
                        { l.windows.forEach { w -> LimitWindowRow(w, "machine_limit_${l.key}_${w.label}") } }
                    },
                )
            }
        }
    }
    Row(Modifier.padding(top = 10.dp)) {
        KitTextAction(MachinePartsCopy.OPEN_USAGE, onOpenUsage, Modifier.testTag("machine_open_usage"), leading = KitGlyphs.External)
    }
}

/** "5-hour · resets in 3h 12m   18 %  ▬▬▭▭": the warning tone from 90 %. */
@Composable
private fun LimitWindowRow(w: LimitWindow, tag: String) {
    val c = kitColors()
    val tone = if (w.warn) c.warn else c.dim
    Row(
        Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            listOf(w.label, w.reset).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 12.sp,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text("${w.percent}%", fontSize = 12.sp, color = tone, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 32.dp))
        Box(Modifier.width(90.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth(w.percent / 100f).height(3.dp).background(if (w.warn) c.warn else c.accent))
        }
    }
}
