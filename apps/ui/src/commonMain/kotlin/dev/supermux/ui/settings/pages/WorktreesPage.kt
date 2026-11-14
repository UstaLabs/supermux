// The machine's chat worktrees (#97 Step 2, the approved prototype `~/work/proto-97/step2/worktrees.html`): the machine
// page's Disk is ONE summary row that opens this sub-page; the page is the list itself.
//
// Totals and Refresh at the top; Sort Size · Last used (the longest unused first); the biggest 18, then Show more. Each
// row has a checkbox: a folder with changes, or in use by a running chat, is locked (its checkbox off) and says why.
// "Select the N without changes" takes every clean folder, below the fold too. Folders whose repository is gone are a
// group of their own: they are never taken by that, and picking one says what deleting it means first. Delete is a bar
// that stays at the bottom: it asks, says how far it got in words, then the list loads again. Nothing is deleted on
// its own. The calls are today's [WorktreesSettingsActions], unchanged.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.fileNameOf
import dev.supermux.fs.hostParent
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.i18n.uppercaseInAppLanguage
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.HostingPhase
import dev.supermux.ui.settings.MachineSub
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.kit.KitCheckbox
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitHairline
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitFocusRing
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.settings.localizedSettingsTitle
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.worktree.formatBytes
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The English the tests pin for the pure copy helpers ([diskSummary], [deleteText], [ago]). The page itself shows the
 * same sentences from the `worktrees_*` resources in the app's language (the composable twins below), so these stay
 * plain functions a test can call without a composition.
 */
object WorktreesCopy {
    const val NONE = "No chat worktrees" // i18n-ok: the English the tests pin; the page reads worktrees_none

    fun folders(n: Int) = if (n == 1) "1 folder" else "$n folders" // i18n-ok: the tests' English (worktrees_folders)

    /** The machine page's Disk row: "18.4 GB in 212 folders · 140 have no changes". */
    fun diskSummary(bytes: Long, folders: Int, clean: Int) =
        if (folders == 0) NONE
        else "${formatBytes(bytes)} in ${folders(folders)} · " + when (clean) { // i18n-ok: the tests' English
            0 -> "all have changes" // i18n-ok: the tests' English
            1 -> "1 has no changes" // i18n-ok: the tests' English
            else -> "$clean have no changes" // i18n-ok: the tests' English
        }

    /** [gone] of the [n] are folders whose repository is gone (picked one at a time); the rest have no changes. */
    fun deleteText(n: Int, bytes: Long, gone: Int = 0) =
        "Deletes ${if (n == 1) "the folder and its branch" else "the folders and their branches"} · ${formatBytes(bytes)}. " + // i18n-ok: the tests' English
            when {
                gone == 0 -> "None of them has changes." // i18n-ok: the tests' English
                gone == n -> (if (n == 1) "It has" else "They have") + " no repository left, so nothing there can be checked." // i18n-ok: the tests' English
                else -> "${folders(gone)} of them ${if (gone == 1) "has" else "have"} no repository left, so nothing there can be checked." // i18n-ok: the tests' English
            } + " This can’t be undone." // i18n-ok: the tests' English

    /** "today", "yesterday", "3 days ago", "3 weeks ago", "2 months ago". */
    fun ago(nowMs: Long, thenMs: Long): String {
        val d = daysAgo(nowMs, thenMs)
        return when {
            d == 0 -> "today" // i18n-ok: the tests' English
            d == 1 -> "yesterday" // i18n-ok: the tests' English
            d < 14 -> "$d days ago" // i18n-ok: the tests' English
            d < 60 -> "${(d / 7.0).roundToInt()} weeks ago" // i18n-ok: the tests' English
            else -> "${(d / 30.0).roundToInt()} months ago" // i18n-ok: the tests' English
        }
    }

    internal fun daysAgo(nowMs: Long, thenMs: Long): Int = ((nowMs - thenMs).coerceAtLeast(0) / 86_400_000L).toInt()

    /** Why a folder can't be picked, or null when it can: in use by a running chat, or changes. */
    internal fun lockReason(w: WorktreeSummaryDto, gone: Boolean = false): LockReason? = when {
        w.liveOwners.isNotEmpty() -> LockReason.InUse
        gone -> null
        w.error != null -> LockReason.CantLook
        !w.hasChanges -> null
        else -> LockReason.Changes(w.uncommitted, w.unmerged ?: 0)
    }

    /** The folder's own name: the last part of its path. */
    fun folder(w: WorktreeSummaryDto): String =
        fileNameOf(w.path.trimEnd('/', '\\')).ifEmpty { w.id.substringAfter('/') }
}

/** Why a folder's checkbox is off; [lockText] words it. */
internal sealed class LockReason {
    data object InUse : LockReason()
    data object CantLook : LockReason()
    data class Changes(val uncommitted: Int, val unmerged: Int) : LockReason()
}

/** Why a folder didn't delete; [rowErrorText] words it. */
private sealed class RowError {
    data object Unreachable : RowError()
    data class InUse(val by: String) : RowError()
    data class Failed(val why: String?) : RowError()
}

// ─── The copy in the app's language ──────────────────────────────────────────────────────────────

@Composable
private fun foldersText(n: Int): String = localizedPlural(Plurals.worktrees_folders, n, n)

@Composable
private fun cleanText(clean: Int): String =
    if (clean == 0) localized(Str.worktrees_clean_none) else localizedPlural(Plurals.worktrees_clean_count, clean, clean)

/** The machine page's Disk row: "18.4 GB in 212 folders · 140 have no changes". */
@Composable
private fun diskSummaryText(bytes: Long, folders: Int, clean: Int): String =
    if (folders == 0) localized(Str.worktrees_none)
    else localized(Str.worktrees_disk_summary, formatBytes(bytes), foldersText(folders), cleanText(clean))

/** The page's own totals: the clean count only when there is one. */
@Composable
private fun pageSummaryText(bytes: Long, folders: Int, clean: Int): String =
    if (clean == 0) localized(Str.worktrees_size_in, formatBytes(bytes), foldersText(folders))
    else localized(Str.worktrees_disk_summary, formatBytes(bytes), foldersText(folders), cleanText(clean))

@Composable
private fun selectCleanText(n: Int): String = localized(Str.worktrees_select_clean, n)

@Composable
private fun deleteTextLocalized(n: Int, bytes: Long, gone: Int): String {
    val head = localizedPlural(Plurals.worktrees_delete_head, n, formatBytes(bytes))
    val mid = when {
        gone == 0 -> localized(Str.worktrees_delete_none_changed)
        gone == n -> localizedPlural(Plurals.worktrees_delete_all_gone, n)
        else -> localizedPlural(Plurals.worktrees_delete_some_gone, gone, foldersText(gone))
    }
    return "$head $mid ${localized(Str.worktrees_cant_undo)}"
}

/** "today", "yesterday", "3 days ago", "3 weeks ago", "2 months ago". */
@Composable
private fun agoText(nowMs: Long, thenMs: Long): String {
    val d = WorktreesCopy.daysAgo(nowMs, thenMs)
    return when {
        d == 0 -> localized(Str.worktrees_ago_today)
        d == 1 -> localized(Str.worktrees_ago_yesterday)
        d < 14 -> localizedPlural(Plurals.worktrees_ago_days, d, d)
        d < 60 -> (d / 7.0).roundToInt().let { localizedPlural(Plurals.worktrees_ago_weeks, it, it) }
        else -> (d / 30.0).roundToInt().let { localizedPlural(Plurals.worktrees_ago_months, it, it) }
    }
}

@Composable
private fun lockText(reason: LockReason): String = when (reason) {
    LockReason.InUse -> localized(Str.worktrees_in_use)
    LockReason.CantLook -> localized(Str.worktrees_cant_look)
    is LockReason.Changes -> {
        val files = if (reason.uncommitted > 0) localizedPlural(Plurals.worktrees_changed_files, reason.uncommitted, reason.uncommitted) else null
        val commits = if (reason.unmerged > 0) localizedPlural(Plurals.worktrees_unmerged_commits, reason.unmerged, reason.unmerged) else null
        val hasChanges = localized(Str.worktrees_has_changes)
        listOfNotNull(files, commits).joinToString(" · ").ifEmpty { hasChanges }
    }
}

@Composable
private fun rowErrorText(error: RowError, machine: String): String = when (error) {
    RowError.Unreachable -> localized(Str.worktrees_cant_reach, machine)
    is RowError.InUse -> localized(Str.worktrees_in_use_by, error.by)
    is RowError.Failed -> if (error.why == null) localized(Str.worktrees_cant_delete) else localized(Str.worktrees_cant_delete_why, error.why)
}

/** Rows the page draws at first, and how many more each Show more adds. */
internal const val FIRST_ROWS = 18
private const val MORE_ROWS = 200
private const val DELETE_CHUNK = 20
private const val SCAN_POLL_MS = 3_000L
/** Folders whose repository is gone, asked for a page at a time. */
private const val ORPHAN_PAGE = 200
/** How long "Deleted 4 folders · 2.1 GB freed" stays in the bar. */
private const val DONE_MS = 4_000L

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

internal enum class WorktreeSort { Size, LastUsed }

/**
 * Groups by repository ROOT, labelled by name; a name shared by several roots gets its parent folder ([unknown] when
 * the root isn't known).
 */
internal fun worktreeGroups(
    rows: List<WorktreeSummaryDto>,
    unknown: String = "unknown location", // i18n-ok: the tests' default; the page passes worktrees_unknown_location
): List<Pair<String, List<WorktreeSummaryDto>>> {
    val byRoot = rows.groupBy { it.repoRoot ?: "name:${it.repoName}" }
    val rootsPerName = byRoot.values.groupBy { it.first().repoName }.mapValues { it.value.size }
    return byRoot.values.map { g ->
        val first = g.first()
        val label = if ((rootsPerName[first.repoName] ?: 1) > 1) {
            "${first.repoName} (${first.repoRoot?.let { hostParent(it) ?: it } ?: unknown})"
        } else {
            first.repoName
        }
        label to g
    }
}

/** A row's repository as it reads on the page: its name, or name (parent folder) when two roots share it. */
private fun repoLabels(rows: List<WorktreeSummaryDto>, unknown: String): Map<String, String> =
    worktreeGroups(rows, unknown).flatMap { (label, g) -> g.map { it.id to label } }.toMap()

// ─── The machine page's Disk row ─────────────────────────────────────────────────────────────────

/** Disk on the machine page: one summary row that opens the Worktrees page ([onOpen]). */
@Composable
internal fun DiskSection(actions: WorktreesSettingsActions, machine: String, quiet: String?, onOpen: () -> Unit) {
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
        // The machine answers from its cache and is still looking: ask again shortly.
        if (r != null && r.scanning) {
            delay(SCAN_POLL_MS)
            reload++
        }
    }

    KitSectionHead(localized(Str.worktrees_disk), Modifier.kitAnchor("disk"))
    if (!running) {
        KitNote(quiet!!, Modifier.testTag("machine_disk_quiet"))
        return
    }
    val list = state.valueOrNull()
    val visible = list?.worktrees.orEmpty().filter { it.id !in removed }
    val sizeOf: (WorktreeSummaryDto) -> Long = { sizes[it.id] ?: it.bytes ?: 0L }
    val folders = visible.size + (list?.orphans?.count ?: 0)
    val bytes = visible.sumOf(sizeOf) + (list?.orphans?.bytes ?: 0L)
    val clean = visible.count { WorktreesCopy.lockReason(it) == null }
    val c = kitColors()
    KitItemList {
        KitItem(
            localized(Str.worktrees_chat_worktrees),
            Modifier.testTag("machine_worktrees"),
            subtitle = when {
                state is Loaded.Failed -> localized(Str.worktrees_disk_failed, machine)
                list == null || (list.scanning && folders == 0) -> localized(Str.worktrees_counting)
                else -> diskSummaryText(bytes, folders, clean)
            },
            subtitleWarn = state is Loaded.Failed,
            mark = KitMark.Glyph(KitGlyphs.Tree),
            onClick = onOpen,
        ) {
            if (state is Loaded.Failed) KitTextAction(localized(Str.worktrees_try_again), { reload++ }, Modifier.testTag("wt_retry"))
            Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
        }
    }
    KitNote(localized(Str.worktrees_disk_note))
}

// ─── The Worktrees page ──────────────────────────────────────────────────────────────────────────

/** The page's picks, sheets and delete, kept for the page on display (#89); the delete runs on the fleet's scope. */
private class WorktreesForm {
    /** The machine's list as last loaded: the bar deletes from the same list the page shows. */
    var state by mutableStateOf<Loaded<WorktreeListDto>>(Loaded.Loading)
    var sort by mutableStateOf(WorktreeSort.Size)
    var shown by mutableStateOf(FIRST_ROWS)
    var selected by mutableStateOf(setOf<String>())
    var confirming by mutableStateOf(false)
    /** The gone-repository folder whose pick is being confirmed. */
    var confirmGone by mutableStateOf<WorktreeSummaryDto?>(null)
    /** (done, total) while a delete runs, and the folders it is deleting. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var deleting by mutableStateOf(setOf<String>())
    /** A delete finished and the list is loading again. */
    var updating by mutableStateOf(false)
    /** (folders, bytes) of "Deleted 4 folders · 2.1 GB freed", for a moment after a delete. */
    var done by mutableStateOf<Pair<Int, Long>?>(null)
    var rowErrors by mutableStateOf(mapOf<String, RowError>())
    var deleted by mutableStateOf(setOf<String>())
    var changed by mutableStateOf(0)
    /** The folders whose repository is gone, as far as they are loaded, and how many there are. */
    var gone by mutableStateOf<List<WorktreeSummaryDto>>(emptyList())
    var goneTotal by mutableStateOf(0)
    var refreshing by mutableStateOf(false)
    var loadedAt by mutableStateOf<Long?>(null)
}

/**
 * The machine's chat worktrees. [onBack] returns to the machine page (the hub's `onClose` on a sub-page); wide shows it
 * as "‹ ‹machine›". [hosting] is this computer's on its own page (desktop): while it isn't hosting, the page is one
 * quiet line instead of the list, as the machine page's parts are; another machine's is while it is offline.
 */
@Composable
fun WorktreesPage(
    actions: WorktreesSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    hosting: ThisComputerHosting? = null,
) {
    val machine = LocalSettingsMachines.current?.current
    val name = if (hosting != null) machine?.name?.takeIf { machine.local } ?: MachineCopy.THIS_COMPUTER
    else machine?.name ?: localized(Str.machine_fallback_name)
    val up = if (hosting != null) hosting.state.phase == HostingPhase.Running else machine?.online ?: true
    val offline = machineQuiet(hosting, up, name, machine?.lastSeen.orEmpty())
    val form = rememberRetainedOnPage("machine-worktrees") { WorktreesForm() }
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            KitPage(
                localizedSettingsTitle(SettingsPlace(SettingsSection.Machine, MachineSub.WORKTREES)),
                backLabel = name,
                onBack = onBack,
                tag = "worktrees_page",
                maxWidth = 740.dp,
            ) {
                if (offline != null) KitNote(offline, Modifier.testTag("wt_quiet")) else WorktreesList(actions, form, name)
            }
        }
        if (offline == null) DeleteBar(actions, form, name)
    }
}

/** What the list shows and the bar deletes, worked out once per frame from the load and the picks. */
private class WorktreesView(
    val list: WorktreeListDto?,
    val visible: List<WorktreeSummaryDto>,
    val gone: List<WorktreeSummaryDto>,
    val sizeOf: (WorktreeSummaryDto) -> Long,
    val clean: List<WorktreeSummaryDto>,
    val selectedRows: List<WorktreeSummaryDto>,
    val selectedGone: Int,
) {
    val cleanIds = clean.map { it.id }.toSet()
    val selectedBytes = selectedRows.sumOf(sizeOf)
}

@Composable
private fun rememberView(actions: WorktreesSettingsActions, form: WorktreesForm, list: WorktreeListDto?): WorktreesView {
    val sizes = actions.sizes()
    val removed = actions.removedIds()
    val visible = list?.worktrees.orEmpty().filter { it.id !in removed && it.id !in form.deleted }
    val gone = form.gone.filter { it.id !in removed && it.id !in form.deleted }
    val sizeOf: (WorktreeSummaryDto) -> Long = { sizes[it.id] ?: it.bytes ?: 0L }
    val clean = visible.filter { WorktreesCopy.lockReason(it) == null }
    val goneIds = gone.filter { WorktreesCopy.lockReason(it, gone = true) == null }.map { it.id }.toSet()
    val cleanIds = clean.map { it.id }.toSet()
    val selectedRows = (clean + gone).filter { it.id in form.selected && (it.id in cleanIds || it.id in goneIds) }
    return WorktreesView(list, visible, gone, sizeOf, clean, selectedRows, selectedRows.count { it.id in goneIds })
}

@Composable
private fun WorktreesList(actions: WorktreesSettingsActions, form: WorktreesForm, machine: String) {
    val c = kitColors()
    val calls = rememberRetainedScope()
    var reload by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(false) }
    // "Updated 2 min ago" moves on by itself.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }

    LaunchedEffect(actions, reload) {
        if (form.state !is Loaded.Ready) form.state = Loaded.Loading
        val r = actions.load(refresh)
        refresh = false
        form.state = r?.let { Loaded.Ready(it) } ?: Loaded.Failed
        if (r == null) {
            form.refreshing = false
            form.updating = false
            return@LaunchedEffect
        }
        form.deleted = emptySet()
        form.loadedAt = nowMs()
        if (r.orphans.count > 0) {
            actions.orphans(0, ORPHAN_PAGE)?.let { page ->
                form.gone = page.worktrees
                form.goneTotal = page.total
            }
        } else {
            form.gone = emptyList()
            form.goneTotal = 0
        }
        // The machine answers from its cache and is still looking: ask again shortly.
        if (r.scanning) {
            delay(SCAN_POLL_MS)
            reload++
        } else {
            form.refreshing = false
            form.updating = false
        }
    }
    LaunchedEffect(form, form.changed) {
        if (form.changed == 0) return@LaunchedEffect
        form.changed = 0
        reload++
    }

    val list = form.state.valueOrNull()
    if (list == null) {
        if (form.state is Loaded.Failed) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp).testTag("wt_failed"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(KitGlyphs.Warn, contentDescription = null, tint = c.warn, modifier = Modifier.size(15.dp))
                Text(localized(Str.worktrees_disk_failed, machine), fontSize = 12.5.sp, color = c.warn, modifier = Modifier.weight(1f))
                KitTextAction(localized(Str.worktrees_try_again), { reload++ }, Modifier.testTag("wt_retry"))
            }
        } else {
            Text(localized(Str.worktrees_counting), fontSize = 13.sp, color = c.dim, modifier = Modifier.padding(top = 12.dp).testTag("wt_counting"))
        }
        return
    }
    val v = rememberView(actions, form, list)
    val folders = v.visible.size + list.orphans.count
    val bytes = v.visible.sumOf(v.sizeOf) + list.orphans.bytes
    val busy = form.progress != null || form.updating

    fun doRefresh() {
        if (form.refreshing) return
        form.refreshing = true
        refresh = true
        reload++
    }

    // ── Totals and Refresh ──
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (folders == 0) localized(Str.worktrees_nothing_on_disk) else pageSummaryText(bytes, folders, v.clean.size),
            fontSize = 13.sp,
            color = if (folders == 0) c.dim else c.text,
            modifier = Modifier.weight(1f).testTag("wt_summary"),
        )
        if (form.refreshing) {
            Text(localized(Str.worktrees_refreshing), fontSize = 13.sp, color = c.dim, modifier = Modifier.testTag("wt_refreshing"))
        } else {
            val at = form.loadedAt
            if (at != null && folders > 0) {
                val updated = key(tick, at) { MachinePartsCopy.updatedText(nowMs(), at) }
                Text("$updated · ", fontSize = 13.sp, color = c.dim, modifier = Modifier.testTag("wt_updated"))
            }
            KitTextAction(localized(Str.worktrees_refresh), ::doRefresh, Modifier.testTag("wt_refresh"))
        }
    }

    if (folders == 0) {
        Text(
            localized(Str.worktrees_empty),
            fontSize = 13.sp,
            color = c.faint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 44.dp, bottom = 30.dp).testTag("wt_empty"),
        )
        return
    }

    // ── Select / Clear, and Sort ──
    val allClean = v.cleanIds.isNotEmpty() && form.selected.containsAll(v.cleanIds)
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> Text(localized(Str.worktrees_paused), fontSize = 12.5.sp, color = c.dim, modifier = Modifier.testTag("wt_paused"))
                v.clean.isEmpty() -> Text(localized(Str.worktrees_every_folder_changed), fontSize = 12.sp, color = c.dim, modifier = Modifier.testTag("wt_every_changed"))
                allClean -> KitTextAction(localized(Str.common_clear), { form.selected = emptySet() }, Modifier.testTag("wt_select_clean"))
                else -> {
                    KitTextAction(selectCleanText(v.clean.size), {
                        // Every clean folder, below the fold too; a gone repository's folder picked by hand stays picked.
                        form.selected = form.selected + v.cleanIds
                    }, Modifier.testTag("wt_select_clean"))
                    if (v.selectedRows.isNotEmpty()) DimAction(localized(Str.common_clear), "wt_clear") { form.selected = emptySet() }
                }
            }
        }
        SortControl(form.sort) { form.sort = it }
    }

    // ── The folders, biggest (or longest unused) first ──
    // Sorted by the sizes known when the list loaded: a live size must not move a row under a finger.
    val snapshot = remember(v.visible, list) { v.visible.associate { it.id to (it.bytes ?: 0L) } }
    val order = remember(v.visible, form.sort, snapshot) { sorted(v.visible, form.sort) { snapshot[it.id] ?: 0L } }
    val shown = order.take(form.shown)
    val unknownLocation = localized(Str.worktrees_unknown_location)
    val labels = remember(v.visible, v.gone, unknownLocation) { repoLabels(v.visible + v.gone, unknownLocation) }
    val now = remember(tick, form.loadedAt) { nowMs() }

    Column(Modifier.fillMaxWidth().testTag("wt_list")) {
        shown.forEachIndexed { i, w ->
            val rowError = form.rowErrors[w.id]
            WorktreeRow(
                w, labels[w.id] ?: w.repoName, v.sizeOf(w), now,
                locked = WorktreesCopy.lockReason(w),
                gone = false,
                picked = w.id in form.selected && w.id in v.cleanIds,
                going = w.id in form.deleting,
                error = if (rowError != null) rowErrorText(rowError, machine) else null,
                enabled = !busy,
                last = i == shown.lastIndex,
            ) { on -> form.selected = if (on) form.selected + w.id else form.selected - w.id }
        }
    }
    if (shown.size < order.size) {
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("wt_more_note"),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localized(Str.worktrees_showing, shown.size, order.size), fontSize = 12.sp, color = c.faint)
            KitTextAction(localized(Str.worktrees_show_more), { form.shown += MORE_ROWS }, Modifier.testTag("wt_show_more"))
        }
    }

    // ── Folders whose repository is gone: their own group; never taken by "Select the N without changes" ──
    list.orphans.takeIf { it.count > 0 }?.let { o ->
        Row(Modifier.padding(top = 26.dp, bottom = 6.dp).testTag("wt_orphans"), verticalAlignment = Alignment.Bottom) {
            Text(
                localized(Str.worktrees_repo_missing).uppercaseInAppLanguage(),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.63.sp,
                color = c.faint,
            )
            Text(
                " · ${foldersText(o.count)} · ${formatBytes(o.bytes)}",
                fontSize = 11.5.sp,
                color = c.faint,
            )
        }
        Text(
            localized(Str.worktrees_gone_note, selectCleanText(v.clean.size)),
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = c.dim,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val goneOrder = sorted(v.gone, form.sort, v.sizeOf)
        Column(Modifier.fillMaxWidth()) {
            goneOrder.forEachIndexed { i, w ->
                val locked = WorktreesCopy.lockReason(w, gone = true)
                val rowError = form.rowErrors[w.id]
                WorktreeRow(
                    w, labels[w.id] ?: w.repoName, v.sizeOf(w), now,
                    locked = locked,
                    gone = true,
                    picked = w.id in form.selected && locked == null,
                    going = w.id in form.deleting,
                    error = if (rowError != null) rowErrorText(rowError, machine) else null,
                    enabled = !busy,
                    last = i == goneOrder.lastIndex,
                ) { on -> if (on) form.confirmGone = w else form.selected = form.selected - w.id }
            }
        }
        val left = form.goneTotal - form.gone.size
        if (form.gone.isNotEmpty() && left > 0) {
            KitTextAction(
                localized(Str.worktrees_more_gone, left),
                {
                    calls.launch {
                        val page = actions.orphans(form.gone.size, ORPHAN_PAGE) ?: return@launch
                        form.gone = form.gone + page.worktrees
                        form.goneTotal = page.total
                    }
                },
                Modifier.padding(top = 8.dp).testTag("wt_orphans_more"),
            )
        }
    }

    form.confirmGone?.let { w ->
        KitSheet(
            localized(Str.worktrees_gone_title, WorktreesCopy.folder(w)),
            onDismiss = { form.confirmGone = null },
            description = localized(Str.worktrees_gone_text),
            tag = "wt_gone_dialog",
            buttons = {
                KitGhostButton(localized(Str.worktrees_cancel), { form.confirmGone = null }, Modifier.testTag("wt_gone_cancel"))
                KitGhostButton(
                    localized(Str.worktrees_select_it),
                    {
                        form.selected = form.selected + w.id
                        form.confirmGone = null
                    },
                    Modifier.testTag("wt_gone_confirm"),
                )
            },
        ) {
            Text(
                localized(Str.worktrees_gone_detail, formatBytes(v.sizeOf(w)), agoText(now, w.mtime)),
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                color = c.dim,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun sorted(rows: List<WorktreeSummaryDto>, sort: WorktreeSort, size: (WorktreeSummaryDto) -> Long) = when (sort) {
    WorktreeSort.Size -> rows.sortedByDescending(size)
    // The longest unused first.
    WorktreeSort.LastUsed -> rows.sortedBy { it.mtime }
}

/** "Sort Size · Last used": the chosen one in the text tone. */
@Composable
private fun SortControl(sort: WorktreeSort, onSort: (WorktreeSort) -> Unit) {
    val c = kitColors()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(localized(Str.worktrees_sort), fontSize = 12.sp, color = c.faint)
        SortOption(localized(Str.worktrees_size), sort == WorktreeSort.Size, "wt_sort_size") { onSort(WorktreeSort.Size) }
        Text("·", fontSize = 12.sp, color = c.faint)
        SortOption(localized(Str.worktrees_last_used), sort == WorktreeSort.LastUsed, "wt_sort_used") { onSort(WorktreeSort.LastUsed) }
    }
}

@Composable
private fun SortOption(text: String, on: Boolean, tag: String, onClick: () -> Unit) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        fontSize = 12.sp,
        color = if (on || hovered) c.text else c.faint,
        modifier = Modifier
            .clickable(interactionSource = src, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .kitFocusRing(src, shape)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

/** A text action in the dim tone (the prototype's quiet Clear). */
@Composable
private fun DimAction(text: String, tag: String, onClick: () -> Unit) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Text(
        text,
        fontSize = 12.5.sp,
        color = if (hovered) c.text else c.dim,
        modifier = Modifier
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .kitFocusRing(src, RoundedCornerShape(4.dp))
            .padding(horizontal = 2.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

/**
 * One folder: its checkbox, name and size; repository · branch; chat · last used · whether it has changes. A [locked]
 * folder's checkbox is off and its reason is in the warning tone; a [gone] repository's folder says it can't be checked.
 * The whole row picks with a pointer; the checkbox is the keyboard's and the screen reader's.
 */
@Composable
private fun WorktreeRow(
    w: WorktreeSummaryDto,
    repo: String,
    bytes: Long,
    now: Long,
    locked: LockReason?,
    gone: Boolean,
    picked: Boolean,
    going: Boolean,
    error: String?,
    enabled: Boolean,
    last: Boolean,
    onPick: (Boolean) -> Unit,
) {
    val c = kitColors()
    val m = kitMetrics()
    val folder = WorktreesCopy.folder(w)
    val pickable = locked == null && enabled
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val line = c.line
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val px = 1.dp.toPx()
                drawRect(line, size = androidx.compose.ui.geometry.Size(size.width, px))
                if (last) drawRect(line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - px), size = androidx.compose.ui.geometry.Size(size.width, px))
            }
            .background(
                when {
                    picked -> c.wash
                    pickable && hovered -> c.hover
                    else -> Color.Transparent
                },
            )
            .hoverable(src, enabled = pickable)
            .then(if (pickable) Modifier.pointerInput(picked) { detectTapGestures { onPick(!picked) } } else Modifier)
            .alpha(if (going) 0.45f else 1f)
            .padding(start = 4.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)
            .testTag("wt_row_${w.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(22.dp).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            KitCheckbox(
                checked = picked && locked == null,
                onCheckedChange = onPick,
                label = localized(Str.worktrees_select_folder, folder),
                modifier = Modifier.testTag("wt_check_${w.id}"),
                enabled = pickable,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    folder,
                    fontSize = m.label,
                    color = if (locked != null) c.dim else c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatBytes(bytes), fontSize = 12.5.sp, color = if (locked != null) c.dim else c.text, maxLines = 1)
            }
            Text(
                buildAnnotatedString {
                    append(repo)
                    w.branch?.let { b ->
                        append(" · ")
                        withStyle(SpanStyle(fontFamily = MonoFontFamily, fontSize = 11.5.sp)) { append(b) }
                    }
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = c.dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            val chat = w.owners.firstOrNull()?.name
            val noChat = localized(Str.worktrees_no_chat)
            val lastUsed = localized(Str.worktrees_row_last_used, agoText(now, w.mtime))
            val lockedText = if (locked != null) lockText(locked) else null
            val cantCheck = localized(Str.worktrees_cant_check)
            val noChanges = localized(Str.worktrees_no_changes)
            Text(
                buildAnnotatedString {
                    if (chat != null) append("“$chat”") else withStyle(SpanStyle(color = c.faint)) { append(noChat) }
                    append(" · ")
                    append(lastUsed)
                    append(" · ")
                    when {
                        lockedText != null -> withStyle(SpanStyle(color = c.warn)) { append(lockedText) }
                        gone -> withStyle(SpanStyle(color = c.warn)) { append(cantCheck) }
                        else -> append(noChanges)
                    }
                },
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = c.dim,
                modifier = Modifier.padding(top = 2.dp).then(if (locked != null) Modifier.testTag("wt_kept_${w.id}") else Modifier),
            )
            if (error != null) {
                Text(error, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(top = 2.dp).testTag("wt_error_${w.id}"))
            }
        }
    }
}

/**
 * The bar that stays at the bottom while folders are picked: Clear and "Delete N folders · X GB". Delete asks first,
 * then the bar says how far it got, and that the list is loading again, in words.
 */
@Composable
private fun DeleteBar(actions: WorktreesSettingsActions, form: WorktreesForm, machine: String) {
    val c = kitColors()
    val m = kitMetrics()
    val calls = rememberRetainedScope()
    // The bar needs the same picks the list shows; the list is loaded there, the gone folders kept on the form.
    val v = rememberView(actions, form, form.state.valueOrNull())
    val progress = form.progress

    LaunchedEffect(form.done) {
        if (form.done == null) return@LaunchedEffect
        delay(DONE_MS)
        form.done = null
    }

    fun delete() {
        val rows = v.selectedRows
        val ids = rows.map { it.id }
        form.confirming = false
        form.done = null
        form.deleting = ids.toSet()
        form.progress = 0 to ids.size
        calls.launch {
            var freed = 0L
            var gone = 0
            try {
                var done = 0
                // A batch of 20 at a time: the machine deletes one by one, and a big batch would sit silent for minutes.
                for (chunk in ids.chunked(DELETE_CHUNK)) {
                    val res = actions.delete(chunk)
                    if (res == null) {
                        form.rowErrors = form.rowErrors + chunk.associateWith { RowError.Unreachable }
                        break
                    }
                    val ok = res.filter { it.ok }.map { it.id }.toSet()
                    freed += rows.filter { it.id in ok }.sumOf(v.sizeOf)
                    gone += ok.size
                    form.deleted = form.deleted + ok
                    form.gone = form.gone.filter { it.id !in ok }
                    form.selected = form.selected - ok
                    form.rowErrors = form.rowErrors - ok + res.filter { !it.ok }.associate { r ->
                        r.id to if (r.error == "in_use") RowError.InUse(r.inUseBy.joinToString()) else RowError.Failed(r.error)
                    }
                    done += chunk.size
                    form.progress = done to ids.size
                }
            } finally {
                form.progress = null
                form.deleting = emptySet()
                if (gone > 0) form.done = gone to freed
                form.updating = true
                form.changed++
            }
        }
    }

    val words = when {
        progress != null -> localized(Str.worktrees_deleting, progress.first, progress.second)
        form.updating -> localized(Str.worktrees_updating)
        else -> form.done?.let { (n, freed) -> localized(Str.worktrees_deleted, foldersText(n), formatBytes(freed)) }
    }
    val picked = v.selectedRows
    if (words == null && picked.isEmpty()) return

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.pane)
            .testTag("wt_delete_bar"),
    ) {
        KitHairline()
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = if (m.phone) 18.dp else 34.dp, end = if (m.phone) 18.dp else 34.dp, top = 10.dp, bottom = if (m.phone) 16.dp else 12.dp)
                .widthIn(max = 672.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (words != null) {
                Text(
                    words,
                    fontSize = 12.5.sp,
                    color = c.dim,
                    modifier = Modifier.testTag(if (progress != null) "wt_delete_progress" else "wt_delete_words")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                DimAction(localized(Str.common_clear), "wt_bar_clear") { form.selected = emptySet() }
                Spacer(Modifier.weight(1f))
                KitGhostButton(
                    localized(Str.worktrees_delete_n, foldersText(picked.size), formatBytes(v.selectedBytes)),
                    { form.confirming = true },
                    Modifier.testTag("wt_delete"),
                    destructive = true,
                )
            }
        }
    }

    if (form.confirming && picked.isNotEmpty()) {
        KitSheet(
            localized(Str.worktrees_delete_title, foldersText(picked.size)),
            onDismiss = { form.confirming = false },
            description = deleteTextLocalized(picked.size, v.selectedBytes, v.selectedGone),
            tag = "wt_delete_dialog",
            buttons = {
                KitGhostButton(localized(Str.worktrees_cancel), { form.confirming = false }, Modifier.testTag("wt_delete_cancel"))
                KitGhostButton(localized(Str.worktrees_delete), ::delete, Modifier.testTag("wt_delete_confirm"), destructive = true)
            },
        )
    }
}
