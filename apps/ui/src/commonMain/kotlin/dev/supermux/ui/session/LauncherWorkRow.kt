// #152: the launcher's row under the composer, drawn. "Local" / "Worktree" with a menu that says what each means,
// the branch pill (a three-tab picker for a worktree, the folder's branches for Local), and the line under them that
// says where the work lands and where pushes go, before Start. A pointer gets popovers under the pill; touch gets
// bottom sheets. The data is LauncherWork.kt's.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.net.GitBranches
import dev.supermux.net.ProjectFolderState
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.chat.ComposerSheet
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.i18n.timeAgoText
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.widgets.ModalHost
import dev.supermux.util.timeAgo

/** What the row's parts answer to in tests. */
internal object WorkRowTags {
    const val WORK_IN = "launcher_work_in"
    const val WORK_IN_LOCAL = "launcher_work_in_local"
    const val WORK_IN_WORKTREE = "launcher_work_in_worktree"
    const val BRANCH = "launcher_branch"
    const val LOCAL_BRANCH = "launcher_local_branch"
    const val TAB_NEW = "launcher_tab_new"
    const val TAB_EXISTING = "launcher_tab_existing"
    const val BASE = "launcher_base"
    const val NAME = "launcher_branch_name"
    const val USE_NEW = "launcher_use_new_branch"
    const val SEARCH = "launcher_branch_search"
    const val DIRTY = "launcher_local_dirty"
    const val SUMMARY = "launcher_work_summary"
    const val POPOVER = "launcher_work_popover"
    const val SHEET = "launcher_work_sheet"
    const val NOT_REMEMBERED = "launcher_work_not_remembered"
    fun row(name: String) = "launcher_branch_row_$name"
}

private enum class WorkPop { WorkIn, Branch, Local }

/**
 * The row: [pick] as it stands, and every way to change it. [onWorkIn] is a remembered choice (the broker keeps it
 * per project, per user); [onPick] is everything else. [onOpenBranches] fires when a branch list opens (the caller
 * fetches origin once per repo there). [fetchedBase] = that fetch came back, so the line can say so. [legacy] = the
 * broker predates #152: a new branch from a base is all it can make, as before, and the folder's branch stays put.
 */
@Composable
internal fun LauncherWorkRow(
    pointer: Boolean,
    projectName: String,
    projectPath: String,
    pick: WorkPick,
    branches: GitBranches?,
    loading: Boolean,
    folder: ProjectFolderState?,
    prefix: String,
    message: String,
    fetchedBase: Boolean,
    nowMs: Long,
    legacy: Boolean,
    onPick: (WorkPick) -> Unit,
    onWorkIn: (WorkIn) -> Unit,
    onOpenBranches: () -> Unit,
    modifier: Modifier = Modifier,
    /** The broker couldn't keep the Local / Worktree pick: it holds for this session only, and the row says so. */
    notRemembered: Boolean = false,
) {
    var open by remember { mutableStateOf<WorkPop?>(null) }
    val close = { open = null }
    val target = workTarget(pick, branches, folder?.branch, prefix, message)
    Column(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                WorkPill(pointer, open == WorkPop.WorkIn, Modifier.testTag(WorkRowTags.WORK_IN), onClick = { open = WorkPop.WorkIn }) {
                    PillIcon(if (pick.workIn == WorkIn.Local) Icons.Outlined.Folder else Icons.Filled.ContentCopy)
                    Text(localized(if (pick.workIn == WorkIn.Local) Str.launch_work_local else Str.launch_worktree), color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
                if (open == WorkPop.WorkIn) WorkMenu(pointer, width = 340.dp, onDismiss = close) {
                    WorkInMenu(pick.workIn, projectName) { w -> close(); onWorkIn(w) }
                }
            }
            Box(Modifier.weight(1f, fill = false)) {
                if (pick.workIn == WorkIn.Worktree) {
                    WorkPill(pointer, open == WorkPop.Branch, Modifier.testTag(WorkRowTags.BRANCH), onClick = { open = WorkPop.Branch; onOpenBranches() }) {
                        PillIcon(Icons.AutoMirrored.Filled.CallSplit)
                        when (val t = target) {
                            is WorkTarget.NewBranch -> {
                                PillWords(localized(Str.launch_branch_new_from))
                                PillCode(t.base)
                            }
                            is WorkTarget.OnBranch -> PillCode(t.branch)
                            else -> PillWords(localized(Str.launch_branch_choose))
                        }
                    }
                    if (open == WorkPop.Branch) WorkMenu(pointer, width = 460.dp, onDismiss = close) {
                        BranchPicker(pointer, pick, branches, loading, folder?.branch, prefix, message, fetchedBase, nowMs, legacy, onPick, close)
                    }
                } else {
                    WorkPill(pointer, open == WorkPop.Local, Modifier.testTag(WorkRowTags.LOCAL_BRANCH), onClick = { if (!legacy) { open = WorkPop.Local; onOpenBranches() } }) {
                        PillIcon(Icons.AutoMirrored.Filled.CallSplit)
                        when (val t = target) {
                            is WorkTarget.Reuse -> {
                                PillWords(localized(Str.launch_local_in_worktree_for))
                                PillCode(t.branch)
                            }
                            is WorkTarget.Here -> {
                                PillWords(localized(if (t.from != null) Str.launch_local_switch_to else Str.launch_local_on))
                                PillCode(t.branch ?: folder?.detachedSha.orEmpty())
                            }
                            else -> {}
                        }
                    }
                    if (open == WorkPop.Local) WorkMenu(pointer, width = 460.dp, onDismiss = close) {
                        LocalBranchMenu(pointer, pick, branches, folder, nowMs, onPick, close)
                    }
                }
            }
        }
        WorkSummary(target, pick, branches, folder, projectPath, fetchedBase, legacy, Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp))
        if (notRemembered) {
            Text(
                localized(Str.launch_work_not_remembered, projectName),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp).testTag(WorkRowTags.NOT_REMEMBERED),
            )
        }
    }
}

// ── the pills ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WorkPill(pointer: Boolean, open: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (open || hovered) cs.onSurface.copy(alpha = 0.13f).compositeOver(cs.surfaceContainerLow) else cs.surfaceContainerHighest
    Row(
        modifier
            .height(if (pointer) 34.dp else 40.dp)
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null) { haptic.perform(HapticKind.Tick); onClick() }
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        content()
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun PillIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
}

@Composable
private fun PillWords(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
}

@Composable
private fun RowScope.PillCode(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurface, fontFamily = MonoFontFamily, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
}

// ── popover (pointer) / sheet (touch) ─────────────────────────────────────────────────────────────

@Composable
private fun WorkMenu(pointer: Boolean, width: Dp, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (!pointer) {
        ComposerSheet(onDismiss) { Column(Modifier.testTag(WorkRowTags.SHEET)) { content() } }
        return
    }
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    val dark = cs.surface.luminance() < 0.5f
    ModalHost {}
    Popup(
        popupPositionProvider = remember { UnderAnchor(gapPx = 12) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .padding(8.dp)
                .width(width)
                .heightIn(max = 540.dp)
                .shadow(18.dp, shape, clip = false)
                .clip(shape)
                .background(cs.surfaceContainer)
                .then(if (dark) Modifier.border(1.dp, cs.outline, shape) else Modifier)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onDismiss(); true } else false }
                .testTag(WorkRowTags.POPOVER),
        ) { content() }
    }
}

/** Under the pill, its left edge on the pill's; above it only when there is no room below. */
private class UnderAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.left - gapPx).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val h = popupContentSize.height
        val y = when {
            anchorBounds.bottom + h <= windowSize.height -> anchorBounds.bottom - gapPx / 2
            anchorBounds.top - h >= 0 -> anchorBounds.top - h + gapPx / 2
            else -> (windowSize.height - h).coerceAtLeast(0)
        }
        return IntOffset(x, y)
    }
}

/** A menu's middle: in a popover it scrolls between the fixed top and foot; a sheet scrolls as a whole already. */
@Composable
private fun ColumnScope.menuBody(pointer: Boolean): Modifier {
    val scroll = rememberScrollState()
    return if (pointer) Modifier.weight(1f, fill = false).verticalScroll(scroll) else Modifier
}

// ── Local / Worktree ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.WorkInMenu(current: WorkIn, projectName: String, onPick: (WorkIn) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        MenuRow(
            icon = Icons.Outlined.Folder, selected = current == WorkIn.Local, onClick = { onPick(WorkIn.Local) },
            modifier = Modifier.testTag(WorkRowTags.WORK_IN_LOCAL),
            title = localized(Str.launch_work_local), subtitle = localized(Str.launch_work_local_hint), wrap = true,
            tags = { if (current == WorkIn.Local) CheckTag() },
        )
        MenuRow(
            icon = Icons.Filled.ContentCopy, selected = current == WorkIn.Worktree, onClick = { onPick(WorkIn.Worktree) },
            modifier = Modifier.testTag(WorkRowTags.WORK_IN_WORKTREE),
            title = localized(Str.launch_worktree), subtitle = localized(Str.launch_work_worktree_hint), wrap = true,
            tags = { if (current == WorkIn.Worktree) CheckTag() },
        )
    }
    MenuFoot { FootText(styled(argStyles(), Str.launch_work_remembered, Arg.Bold(projectName))) }
}

// ── the worktree's branch picker: New branch / Existing branch ───────────────────────────────────────

@Composable
private fun ColumnScope.BranchPicker(
    pointer: Boolean,
    pick: WorkPick,
    branches: GitBranches?,
    loading: Boolean,
    folderBranch: String?,
    prefix: String,
    message: String,
    fetchedBase: Boolean,
    nowMs: Long,
    legacy: Boolean,
    onPick: (WorkPick) -> Unit,
    close: () -> Unit,
) {
    var tab by remember { mutableStateOf(if (legacy) BranchMode.New else pick.mode) }
    var query by remember { mutableStateOf("") }
    var baseOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(pick.name) }
    var base by remember { mutableStateOf(pick.base.ifEmpty { defaultBase(branches, folderBranch) }) }
    val (local, remote) = remember(branches) { branchRows(branches) }
    if (!legacy) Tabs(pointer, tab) { tab = it; query = ""; baseOpen = false }
    Column(menuBody(pointer).padding(bottom = 6.dp)) {
        if (tab == BranchMode.New) {
            Label(localized(Str.launch_base))
            val baseRow = (local + remote).firstOrNull { it.name == base }
            Field(Modifier.testTag(WorkRowTags.BASE).clickable { baseOpen = !baseOpen; query = "" }) {
                PillIcon(Icons.AutoMirrored.Filled.CallSplit)
                Text(baseLabel(base, baseRow, branches), fontFamily = MonoFontFamily, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (baseRow?.isDefault == true) Tag(localized(Str.launch_tag_default))
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            if (baseOpen) {
                SearchField(pointer, query, { query = it }, onEnter = { filterRows(local + remote, query).firstOrNull()?.let { base = it.name; baseOpen = false } }, onEscape = close)
                Column(
                    Modifier.padding(horizontal = 10.dp, vertical = 2.dp).fillMaxWidth().heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(9.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    filterRows(local + remote, query).forEach { r ->
                        BranchRowItem(r, selected = r.name == base, nowMs = nowMs, onClick = { base = r.name; baseOpen = false })
                    }
                }
            }
            if (!legacy) Label(localized(Str.launch_branch_name))
            if (!legacy) Field {
                Text(prefix, fontFamily = MonoFontFamily, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (name.isEmpty()) Text(localized(Str.launch_branch_name_auto), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    BasicTextField(
                        value = name,
                        onValueChange = { name = it.replace(" ", "-") },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.5.sp, fontFamily = MonoFontFamily),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth().testTag(WorkRowTags.NAME).onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Enter, Key.NumPadEnter -> { onPick(pick.copy(mode = BranchMode.New, base = base, name = name.trim())); close(); true }
                                Key.Escape -> { close(); true }
                                else -> false
                            }
                        },
                    )
                }
            }
            val origin = remoteName(branches)
            if (origin != null) Hint(styled(argStyles(), Str.launch_base_fetched_hint, Arg.Plain(origin), Arg.Code(base.substringAfter('/'))))
        } else {
            SearchField(pointer, query, { query = it }, onEnter = {
                (filterRows(local, query) + filterRows(remote, query)).firstOrNull { !it.held }?.let { r ->
                    onPick(pick.copy(mode = BranchMode.Existing, branch = r.name)); close()
                }
            }, onEscape = close)
            val l = filterRows(local, query)
            val r = filterRows(remote, query)
            if (l.isNotEmpty()) Label(localized(Str.launch_branches_local))
            l.forEach { row ->
                // Git keeps a branch in one place: one open in the folder or another worktree can't be this worktree's.
                BranchRowItem(row, selected = pick.mode == BranchMode.Existing && pick.branch == row.name, nowMs = nowMs, enabled = !row.held, onClick = {
                    onPick(pick.copy(mode = BranchMode.Existing, branch = row.name)); close()
                })
            }
            if (r.isNotEmpty()) Label(localized(Str.launch_branches_remote), right = remoteName(branches))
            r.forEach { row ->
                BranchRowItem(row, selected = pick.mode == BranchMode.Existing && pick.branch == row.name, nowMs = nowMs, onClick = {
                    onPick(pick.copy(mode = BranchMode.Existing, branch = row.name)); close()
                })
            }
            if (l.isEmpty() && r.isEmpty()) Empty(if (loading) localized(Str.launch_fetching) else if (query.isBlank()) localized(Str.launch_no_branches) else localized(Str.launch_no_branch_match, query))
        }
    }
    MenuFoot {
        if (tab == BranchMode.New) {
            val shown = if (name.isBlank()) autoBranchName(prefix, message) else prefix + name.trim()
            val foot = if (legacy) styled(argStyles(), Str.launch_sum_new_unnamed, Arg.Code(base)) else styled(argStyles(), Str.launch_new_branch_from, Arg.CodeBold(shown), Arg.Code(base))
            FootText(foot, Modifier.weight(1f))
            FootButton(localized(Str.launch_use_new_branch), Modifier.testTag(WorkRowTags.USE_NEW)) {
                onPick(pick.copy(mode = BranchMode.New, base = base, name = name.trim())); close()
            }
        } else {
            FootText(AnnotatedString(localized(Str.launch_existing_foot)))
        }
    }
}

/** The base as the field shows it: a branch origin also has reads as origin's, the one the copy starts from. */
private fun baseLabel(base: String, row: BranchRow?, branches: GitBranches?): String {
    if (row == null || !row.local) return base
    val up = row.upstream ?: return base
    return if (branches?.remote?.any { it.name == up } == true) up else base
}

// ── Local: the folder's branches ──────────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.LocalBranchMenu(
    pointer: Boolean,
    pick: WorkPick,
    branches: GitBranches?,
    folder: ProjectFolderState?,
    nowMs: Long,
    onPick: (WorkPick) -> Unit,
    close: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val (local, remote) = remember(branches) { branchRows(branches) }
    val current = folder?.branch
    val dirty = folder?.clean == false
    // With changes, only the folder's own branch and the ones another session's worktree has stay pickable. A branch in a
    // worktree no session owns can't be switched to (git keeps a branch in one place).
    fun usable(r: BranchRow) = r.openIn?.kind != "external" && (!dirty || r.name == current || r.inSession)
    Column(menuBody(pointer).padding(bottom = 6.dp)) {
        if (dirty) DirtyNote(folder?.changed ?: 0)
        SearchField(pointer, query, { query = it }, onEnter = {
            (filterRows(local, query) + filterRows(remote, query)).firstOrNull { usable(it) }?.let { r ->
                onPick(pick.copy(switchTo = r.name.takeIf { it != current })); close()
            }
        }, onEscape = close)
        Label(localized(Str.launch_local_switch_folder_to))
        filterRows(local + remote, query).sortedWith(BRANCH_ORDER).forEach { r ->
            val isCurrent = r.name == current
            BranchRowItem(
                r,
                selected = (pick.switchTo ?: current) == r.name,
                nowMs = nowMs,
                enabled = usable(r),
                current = isCurrent,
                inWorktreeOf = if (r.inSession) r.openIn?.name else null,
                onClick = { onPick(pick.copy(switchTo = r.name.takeIf { !isCurrent })); close() },
            )
        }
    }
    MenuFoot { FootText(AnnotatedString(localized(Str.launch_local_foot))) }
}

@Composable
private fun DirtyNote(changed: Int) {
    val warn = LocalSemantics.current.warning
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(warn.copy(alpha = 0.09f).compositeOver(MaterialTheme.colorScheme.surfaceContainer))
            .border(1.dp, warn.copy(alpha = 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(WorkRowTags.DIRTY)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Column {
                Text(localizedPlural(Plurals.launch_local_dirty, changed, changed), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(localized(Str.launch_local_dirty_why), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

// ── rows, tags, fields ────────────────────────────────────────────────────────────────────────────

@Composable
private fun BranchRowItem(
    row: BranchRow,
    selected: Boolean,
    nowMs: Long,
    enabled: Boolean = true,
    current: Boolean = false,
    inWorktreeOf: String? = null,
    onClick: () -> Unit,
) {
    val by = if (inWorktreeOf != null) {
        localized(Str.launch_local_works_in, inWorktreeOf)
    } else if (row.author.isNotEmpty() && row.at > 0) {
        localized(Str.launch_branch_by, row.author, timeAgoText(timeAgo(nowMs, row.at)))
    } else {
        null
    }
    MenuRow(
        icon = Icons.AutoMirrored.Filled.CallSplit,
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.testTag(WorkRowTags.row(row.name)),
        code = row.shown,
        subtitle = by,
        tags = {
            if (current) Tag(localized(Str.launch_tag_current), TagTone.Primary)
            if (row.isDefault) Tag(localized(Str.launch_tag_default))
            when {
                inWorktreeOf != null -> Tag(localized(Str.launch_tag_has_worktree))
                row.inFolder && !current -> Tag(localized(Str.launch_tag_in_folder), TagTone.Warn)
                row.inSession -> Tag(localized(Str.launch_tag_in_session), TagTone.Warn)
                row.openIn?.kind == "external" -> Tag(localized(Str.launch_tag_in_worktree), TagTone.Warn)
            }
            if (!row.local) Tag(localized(Str.launch_tag_remote))
            if (row.ahead > 0) Tag("↑${row.ahead}") // i18n-ok: an arrow and a count
        },
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    title: String? = null,
    code: String? = null,
    subtitle: String? = null,
    wrap: Boolean = false,
    tags: @Composable () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    // A row under a pointer is a menu row; under a thumb it is a little taller.
    val pointer = LocalPointerAvailable.current
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = when {
        selected -> cs.primary.copy(alpha = 0.12f)
        hovered && enabled -> cs.onSurface.copy(alpha = 0.05f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .background(bg)
            .hoverable(src)
            .then(if (enabled) Modifier.clickable(interactionSource = src, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = if (pointer) 14.dp else 16.dp, vertical = if (pointer) 7.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (code != null) Text(code, fontFamily = MonoFontFamily, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 11.5.sp,
                    color = cs.onSurfaceVariant,
                    maxLines = if (wrap) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { tags() }
    }
}

private enum class TagTone { Plain, Warn, Primary }

@Composable
private fun Tag(text: String, tone: TagTone = TagTone.Plain) {
    val cs = MaterialTheme.colorScheme
    val warn = LocalSemantics.current.warning
    val (fg, bg) = when (tone) {
        TagTone.Plain -> cs.onSurfaceVariant to cs.surfaceContainerHighest
        TagTone.Warn -> warn to warn.copy(alpha = 0.12f)
        TagTone.Primary -> cs.primary to cs.primary.copy(alpha = 0.12f)
    }
    Text(
        text,
        fontSize = 10.5.sp,
        lineHeight = 16.sp,
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp),
    )
}

@Composable
private fun CheckTag() {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.clip(RoundedCornerShape(5.dp)).background(cs.primary.copy(alpha = 0.12f)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun Tabs(pointer: Boolean, current: BranchMode, onTab: (BranchMode) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainerHighest)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            Triple(BranchMode.New, Str.launch_tab_new, WorkRowTags.TAB_NEW),
            Triple(BranchMode.Existing, Str.launch_tab_existing, WorkRowTags.TAB_EXISTING),
        ).forEach { (mode, label, tag) ->
            val on = mode == current
            val shape = RoundedCornerShape(8.dp)
            Box(
                Modifier
                    .weight(1f)
                    .height(if (pointer) 30.dp else 36.dp)
                    .clip(shape)
                    .then(if (on) Modifier.background(cs.surfaceContainerLowest).border(1.dp, cs.outline, shape) else Modifier)
                    .clickable { onTab(mode) }
                    .testTag(tag),
                contentAlignment = Alignment.Center,
            ) {
                Text(localized(label), fontSize = 12.5.sp, color = if (on) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Label(text: String, right: String? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (right != null) Text(right, fontSize = 11.sp, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Field(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outline, shape)
            .then(modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun SearchField(pointer: Boolean, query: String, onQuery: (String) -> Unit, onEnter: () -> Unit, onEscape: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val shape = RoundedCornerShape(9.dp)
    Row(
        Modifier
            .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 2.dp)
            .fillMaxWidth()
            .height(34.dp)
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, cs.outline, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(localized(Str.launch_search_branches), fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(WorkRowTags.SEARCH).onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Enter, Key.NumPadEnter -> { onEnter(); true }
                        Key.Escape -> { if (query.isNotEmpty()) onQuery("") else onEscape(); true }
                        else -> false
                    }
                },
            )
        }
    }
    // A pointer types straight away; a phone does not pop its keyboard over the list.
    if (pointer) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun Hint(text: AnnotatedString) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp))
}

@Composable
private fun Empty(text: String) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp))
}

@Composable
private fun MenuFoot(content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
    Row(
        Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun FootText(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun FootButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.height(32.dp).clip(RoundedCornerShape(Radii.pill)).background(cs.primary).clickable(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary, maxLines = 1)
    }
}

// ── the line under the row: where the work lands, and where pushes go ───────────────────────────────

@Composable
private fun WorkSummary(
    target: WorkTarget?,
    pick: WorkPick,
    branches: GitBranches?,
    folder: ProjectFolderState?,
    projectPath: String,
    fetchedBase: Boolean,
    /** A broker before #152 names the new branch itself (mux/<session>), so no name is promised here. */
    legacy: Boolean,
    modifier: Modifier = Modifier,
) {
    val st = argStyles()
    val remote = remoteName(branches)
    val lines = mutableListOf<Pair<ImageVector, AnnotatedString>>()
    fun s(key: StringKey, vararg args: Arg) = styled(st, key, *args)
    fun pushLine(branch: String, upstream: String?, offerPr: Boolean) {
        lines += Icons.Outlined.Cloud to when {
            upstream != null -> s(Str.launch_sum_push_to, Arg.Code(upstream))
            remote == null -> AnnotatedString(localized(Str.launch_sum_no_remote))
            offerPr -> s(Str.launch_sum_first_push_pr, Arg.Code("$remote/$branch"))
            else -> s(Str.launch_sum_first_push, Arg.Code("$remote/$branch"))
        }
    }
    fun upstreamOf(branch: String?) = branches?.local?.firstOrNull { it.name == branch }?.upstream
    when (target) {
        null -> lines += Icons.Outlined.Info to AnnotatedString(localized(Str.launch_sum_pick_branch))
        is WorkTarget.Reuse -> {
            lines += Icons.Filled.ContentCopy to s(Str.launch_sum_reuse, Arg.Code(target.branch), Arg.Plain(target.sessionName), Arg.Code(folder?.branch.orEmpty()))
            pushLine(target.branch, upstreamOf(target.branch), offerPr = false)
        }
        is WorkTarget.Here -> {
            val from = target.from
            val branch = target.branch
            lines += Icons.Outlined.Folder to when {
                from != null && branch != null -> s(Str.launch_sum_switch, Arg.Bold(projectPath), Arg.Code(from), Arg.Code(branch))
                branch != null -> s(Str.launch_sum_here, Arg.Bold(projectPath), Arg.Code(branch))
                else -> s(Str.launch_sum_here_plain, Arg.Bold(projectPath))
            }
            if (branch != null) pushLine(branch, upstreamOf(branch), offerPr = false)
        }
        is WorkTarget.NewBranch -> {
            val start = baseLabel(target.base, branchRows(branches).let { (l, r) -> (l + r).firstOrNull { it.name == target.base } }, branches)
            val from = if (fetchedBase && start != target.base) Arg.CodeThen(start, " " + localized(Str.launch_sum_fetched)) else Arg.Code(start)
            if (legacy) {
                lines += Icons.Filled.ContentCopy to s(Str.launch_sum_new_unnamed, from)
            } else {
                lines += Icons.Filled.ContentCopy to s(
                    Str.launch_sum_new,
                    if (target.auto) Arg.CodeTagged(target.name, localized(Str.launch_sum_named_tag)) else Arg.Code(target.name),
                    from,
                )
                pushLine(target.name, null, offerPr = true)
            }
        }
        is WorkTarget.OnBranch -> {
            val track = target.track
            lines += Icons.Filled.ContentCopy to if (track != null) {
                s(Str.launch_sum_existing_track, Arg.Code(target.branch), Arg.Code(track))
            } else {
                s(Str.launch_sum_existing, Arg.Code(target.branch))
            }
            pushLine(target.branch, track ?: upstreamOf(target.branch), offerPr = false)
        }
    }
    // One block to a screen reader: where the work lands, then where pushes go.
    Column(modifier.testTag(WorkRowTags.SUMMARY).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The summary when the folder is not a git repository: the agent works right in it. */
@Composable
internal fun NotGitSummary(projectPath: String, modifier: Modifier = Modifier) {
    Row(modifier.testTag(WorkRowTags.SUMMARY).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Text(styled(argStyles(), Str.launch_sum_not_git, Arg.Bold(projectPath)), fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── a translated sentence with its placeholders styled: code, bold, a tag ─────────────────────────────

private sealed interface Arg {
    data class Plain(val text: String) : Arg
    data class Bold(val text: String) : Arg
    data class Code(val text: String) : Arg
    data class CodeBold(val text: String) : Arg
    /** A branch name and, after it, a small tag ("named from your message"). */
    data class CodeTagged(val text: String, val tag: String) : Arg
    /** A branch name and plain words after it (" (fetched just now)"). */
    data class CodeThen(val text: String, val then: String) : Arg
}

private val PLACEHOLDER = Regex("%(\\d+)\\$[ds]")

/** How placeholders look: read once in composition, so [styled] itself is a plain function. */
private class ArgStyles(val code: SpanStyle, val bold: SpanStyle, val tag: SpanStyle)

@Composable
private fun argStyles(): ArgStyles {
    val cs = MaterialTheme.colorScheme
    return ArgStyles(
        code = SpanStyle(fontFamily = MonoFontFamily, fontSize = 12.5.sp, color = cs.onSurface),
        bold = SpanStyle(color = cs.onSurface, fontWeight = FontWeight.Medium),
        tag = SpanStyle(fontSize = 10.5.sp, color = cs.onSurfaceVariant, background = cs.surfaceContainerHighest),
    )
}

/** [key]'s text in the app's language, each `%N$s` replaced by its [args] entry, styled. */
private fun styled(st: ArgStyles, key: StringKey, vararg args: Arg): AnnotatedString {
    val template = localized(key)
    val code = st.code
    return buildAnnotatedString {
        var at = 0
        PLACEHOLDER.findAll(template).forEach { m ->
            append(template.substring(at, m.range.first))
            when (val a = args.getOrNull(m.groupValues[1].toInt() - 1)) {
                null -> append(m.value)
                is Arg.Plain -> append(a.text)
                is Arg.Bold -> withStyle(st.bold) { append(a.text) }
                is Arg.Code -> withStyle(code) { append(a.text) }
                is Arg.CodeBold -> withStyle(code.copy(fontWeight = FontWeight.Medium)) { append(a.text) }
                is Arg.CodeTagged -> {
                    withStyle(code) { append(a.text) }
                    append(" ")
                    withStyle(st.tag) { append(" ${a.tag} ") }
                }
                is Arg.CodeThen -> {
                    withStyle(code) { append(a.text) }
                    append(a.then)
                }
            }
            at = m.range.last + 1
        }
        append(template.substring(at))
    }
}
