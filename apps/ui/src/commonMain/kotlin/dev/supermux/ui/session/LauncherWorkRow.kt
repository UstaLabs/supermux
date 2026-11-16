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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.ui.graphics.vector.addPathNodes
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
import dev.supermux.net.OpenPr
import dev.supermux.net.OpenPrList
import dev.supermux.net.OpenPrRead
import dev.supermux.net.ProjectFolderState
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.chat.ComposerSheet
import dev.supermux.ui.i18n.PluralKey
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
    const val TAB_PR = "launcher_tab_pr"
    const val PR_SEARCH = "launcher_pr_search"
    const val PR_NOTE = "launcher_pr_note"
    const val PR_LINK_NOTE = "launcher_pr_link_note"
    const val CARD = "launcher_work_card"
    const val CARD_HEAD = "launcher_work_card_head"
    const val CARD_USE_LOCAL = "launcher_card_use_local"
    const val CARD_MOVE = "launcher_card_move"
    const val CARD_NEW_FROM = "launcher_card_new_from"
    const val CARD_OPEN = "launcher_card_open"
    const val CARD_USE_PR = "launcher_card_use_pr"
    const val CARD_KEEP_MINE = "launcher_card_keep_mine"
    fun pr(number: Int) = "launcher_pr_row_$number"
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
    /** Origin's open PRs / MRs (null: not asked, or a broker before #152). No remote: no Pull request tab. */
    prs: OpenPrList? = null,
    /** One PR / MR by #N, !N, N or its URL, for one the list doesn't have. */
    lookupPr: suspend (String) -> OpenPrRead? = { null },
    /** "Open that session", from the card of a branch another session has. */
    onOpenSession: ((String) -> Unit)? = null,
    /** #152 C4: the PR / MR #72's "Check Out Pull Request…" opened the launcher for, and couldn't be used, with its read. */
    prLink: Pair<Int, OpenPrRead?>? = null,
) {
    var open by remember { mutableStateOf<WorkPop?>(null) }
    val close = { open = null }
    val target = workTarget(pick, branches, folder?.branch, prefix, message)
    val conflict = workConflict(pick, target, branches, folder)
    val words = ForgeWords(prs?.forge == "gitlab" || pick.pr?.forge == "gitlab")
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
                    WorkPill(pointer, open == WorkPop.Branch, Modifier.testTag(WorkRowTags.BRANCH), warn = conflict != null, onClick = { open = WorkPop.Branch; onOpenBranches() }) {
                        PillIcon(if (target is WorkTarget.OnPr) PullRequestIcon else Icons.AutoMirrored.Filled.CallSplit)
                        when (val t = target) {
                            is WorkTarget.NewBranch -> {
                                PillWords(localized(Str.launch_branch_new_from))
                                PillCode(t.base)
                            }
                            is WorkTarget.OnBranch -> PillCode(t.branch)
                            is WorkTarget.OnPr -> {
                                PillWords(words.sign + t.pr.number) // i18n-ok: "#123" / "!7", the forge's own mark and a number
                                PillCode(t.pr.head)
                            }
                            else -> PillWords(localized(Str.launch_branch_choose))
                        }
                    }
                    if (open == WorkPop.Branch) WorkMenu(pointer, width = 460.dp, onDismiss = close) {
                        BranchPicker(pointer, pick, branches, loading, folder?.branch, prefix, message, fetchedBase, nowMs, legacy, prs, lookupPr, words, onPick, close)
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
        WorkSummary(target, pick, branches, folder, projectPath, fetchedBase, legacy, prs?.prs.orEmpty(), words, Modifier.padding(top = 10.dp, start = 2.dp, end = 2.dp))
        if (conflict != null) {
            ConflictCard(conflict, pick, projectPath, words, onPick, onWorkIn, onOpenSession, Modifier.padding(top = 12.dp))
        }
        if (notRemembered) {
            Text(
                localized(Str.launch_work_not_remembered, projectName),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp).testTag(WorkRowTags.NOT_REMEMBERED),
            )
        }
        if (prLink != null) {
            val (number, read) = prLink
            Text(
                prLinkNote(number, read, prs?.repo, ForgeWords(read?.forge == "gitlab" || prs?.forge == "gitlab")),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, start = 2.dp).testTag(WorkRowTags.PR_LINK_NOTE),
            )
        }
    }
}

/** Why the PR / MR the launcher was opened for can't be worked on here: the Pull request tab's words, or no answer. */
private fun prLinkNote(number: Int, read: OpenPrRead?, repo: String?, words: ForgeWords): String = when (read?.status) {
    null -> localized(Str.launch_pr_link_unread, words.label(number))
    "not_open" -> localized(if (read.state == "merged") Str.launch_pr_merged else Str.launch_pr_closed, words.label(number))
    "other_repo" -> localized(Str.launch_pr_other_repo, read.repo.orEmpty())
    "unavailable" -> when (read.reason) {
        "no_cli" -> localized(Str.launch_pr_no_cli, words.cli, words.noun)
        "not_authed" -> localized(Str.launch_pr_not_authed, words.cli, words.noun)
        else -> localized(Str.launch_pr_link_failed, words.label(number), read.message?.takeIf { it.isNotBlank() } ?: read.reason.orEmpty())
    }
    else -> localized(Str.launch_pr_none, words.noun, words.sign + number, repo ?: read.repo.orEmpty())
}

// ── the pills ─────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun WorkPill(pointer: Boolean, open: Boolean, modifier: Modifier = Modifier, warn: Boolean = false, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val bg = if (open || hovered) cs.onSurface.copy(alpha = 0.13f).compositeOver(cs.surfaceContainerLow) else cs.surfaceContainerHighest
    val shape = RoundedCornerShape(Radii.pill)
    Row(
        modifier
            .height(if (pointer) 34.dp else 40.dp)
            .clip(shape)
            .background(bg)
            .then(if (warn) Modifier.border(1.dp, LocalSemantics.current.warning, shape) else Modifier)
            .hoverable(src)
            .clickable(interactionSource = src, indication = null) { haptic.perform(HapticKind.Tick); onClick() }
            .padding(start = 10.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        content()
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
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
    prs: OpenPrList?,
    lookupPr: suspend (String) -> OpenPrRead?,
    words: ForgeWords,
    onPick: (WorkPick) -> Unit,
    close: () -> Unit,
) {
    var tab by remember { mutableStateOf(if (legacy) BranchMode.New else pick.mode) }
    // No remote (or a broker before #152): nothing to open a pull request on, so no tab for one.
    val prTab = !legacy && prs != null && !(prs.status == "unavailable" && prs.reason == "no_remote")
    var query by remember { mutableStateOf("") }
    var baseOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(pick.name) }
    var base by remember { mutableStateOf(pick.base.ifEmpty { defaultBase(branches, folderBranch) }) }
    val (local, remote) = remember(branches) { branchRows(branches) }
    if (!legacy) Tabs(pointer, tab, if (prTab) words.tab else null) { tab = it; query = ""; baseOpen = false }
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
            // One paragraph, as the prototype's: where the base comes from, then where the prefix is set.
            val st = argStyles()
            val origin = remoteName(branches)
            val hint = listOfNotNull(
                origin?.let { styled(st, Str.launch_base_fetched_hint, Arg.Plain(it), Arg.Code(base.substringAfter('/'))) },
                when {
                    legacy -> null
                    prefix.isEmpty() -> styled(st, Str.launch_prefix_off)
                    else -> styled(st, Str.launch_prefix_where, Arg.Code(prefix))
                },
            )
            if (hint.isNotEmpty()) Hint(hint.reduce { a, b -> a + AnnotatedString(" ") + b })
        } else if (tab == BranchMode.PullRequest) {
            PrList(pointer, pick, prs, lookupPr, words, folderBranch, onPick, close)
        } else {
            SearchField(pointer, query, { query = it }, onEnter = {
                (filterRows(local, query) + filterRows(remote, query)).firstOrNull()?.let { r ->
                    onPick(pick.copy(mode = BranchMode.Existing, branch = r.name)); close()
                }
            }, onEscape = close)
            val l = filterRows(local, query)
            val r = filterRows(remote, query)
            if (l.isNotEmpty()) Label(localized(Str.launch_branches_local))
            l.forEach { row ->
                // One git keeps elsewhere is tagged with where; picking it brings the ways out under the row (C3).
                BranchRowItem(row, selected = pick.mode == BranchMode.Existing && pick.branch == row.name, nowMs = nowMs, onClick = {
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
        if (tab == BranchMode.PullRequest) {
            FootText(AnnotatedString(localized(Str.launch_pr_foot, words.noun)))
        } else if (tab == BranchMode.New) {
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
    subtitleRich: AnnotatedString? = null,
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
            if (subtitleRich != null) Text(subtitleRich, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

private enum class TagTone { Plain, Warn, Primary, Ok }

@Composable
private fun Tag(text: String, tone: TagTone = TagTone.Plain) {
    val cs = MaterialTheme.colorScheme
    val warn = LocalSemantics.current.warning
    val (fg, bg) = when (tone) {
        TagTone.Plain -> cs.onSurfaceVariant to cs.surfaceContainerHighest
        TagTone.Warn -> warn to warn.copy(alpha = 0.12f)
        TagTone.Primary -> cs.primary to cs.primary.copy(alpha = 0.12f)
        TagTone.Ok -> LocalSemantics.current.success to LocalSemantics.current.success.copy(alpha = 0.12f)
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
private fun Tabs(pointer: Boolean, current: BranchMode, prTab: String?, onTab: (BranchMode) -> Unit) {
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
        listOfNotNull(
            Triple(BranchMode.New, localized(Str.launch_tab_new), WorkRowTags.TAB_NEW),
            Triple(BranchMode.Existing, localized(Str.launch_tab_existing), WorkRowTags.TAB_EXISTING),
            prTab?.let { Triple(BranchMode.PullRequest, it, WorkRowTags.TAB_PR) },
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
                Text(label, fontSize = 12.5.sp, color = if (on) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
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
private fun SearchField(
    pointer: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onEnter: () -> Unit,
    onEscape: () -> Unit,
    placeholder: String = localized(Str.launch_search_branches),
    tag: String = WorkRowTags.SEARCH,
) {
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
            if (query.isEmpty()) Text(placeholder, fontSize = 13.sp, color = cs.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(tag).onPreviewKeyEvent { e ->
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
    prs: List<OpenPr>,
    words: ForgeWords,
    modifier: Modifier = Modifier,
) {
    val st = argStyles()
    val remote = remoteName(branches)
    val lines = mutableListOf<Pair<ImageVector, AnnotatedString>>()
    fun s(key: StringKey, vararg args: Arg) = styled(st, key, *args)
    fun pushLine(branch: String, upstream: String?, offerPr: Boolean, local: Boolean = false) {
        val open = openPrFrom(branch, prs)
        lines += Icons.Outlined.Cloud to when {
            upstream != null && open != null && local -> s(Str.launch_sum_push_to_open, Arg.Code(upstream), Arg.Plain(words.label(open.number)))
            upstream != null && open != null -> s(Str.launch_sum_push_to_pr, Arg.Code(upstream), Arg.Link(words.label(open.number)))
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
            if (branch != null) pushLine(branch, upstreamOf(branch), offerPr = false, local = true)
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
        is WorkTarget.OnPr -> {
            val pr = target.pr
            val own = if (pr.isFork) s(Str.launch_sum_pr_fork, Arg.Plain(words.noun), Arg.Code(pr.head), Arg.Bold(pr.headRepo)) else s(Str.launch_sum_pr, Arg.Plain(words.noun), Arg.Code(pr.head))
            val ahead = pr.localAhead ?: 0
            val kept = when {
                ahead > 0 && target.diverged == "pr" -> styledPlural(st, Plurals.launch_sum_pr_backup, ahead, Arg.Plain(ahead.toString()), Arg.Code("${target.local}-backup"))
                ahead > 0 && target.diverged == "mine" -> styledPlural(st, Plurals.launch_sum_pr_mine, ahead, Arg.Plain(ahead.toString()))
                else -> null
            }
            lines += Icons.Filled.ContentCopy to if (kept == null) own else buildAnnotatedString { append(own); append(" "); append(kept) }
            val label = words.label(pr.number)
            lines += when {
                pr.isFork && !pr.maintainerCanModify -> Icons.Outlined.Lock to buildAnnotatedString {
                    withStyle(st.bold) { append(localized(Str.launch_sum_review_only)) }
                    append(" ")
                    append(s(Str.launch_sum_pr_review_only, Arg.Bold(pr.author), Arg.Plain(words.sign + pr.number), Arg.Plain(words.noun)))
                }
                pr.isFork -> Icons.Outlined.Cloud to s(Str.launch_sum_pr_push_fork, Arg.Code("${pr.headOwner.orEmpty()}/${pr.head}"), Arg.Link(label))
                else -> Icons.Outlined.Cloud to s(Str.launch_sum_pr_push, Arg.Code("origin/${pr.head}"), Arg.Link(label))
            }
        }
    }
    // One block to a screen reader: where the work lands, then where pushes go.
    Column(modifier.testTag(WorkRowTags.SUMMARY).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { (icon, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The prototype's 16 px icon column, a 14 px icon at its start, centred on the first 18 px line.
                Box(Modifier.width(16.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(14.dp))
                }
                Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The summary when the folder is not a git repository: the agent works right in it. */
@Composable
internal fun NotGitSummary(projectPath: String, modifier: Modifier = Modifier) {
    Row(modifier.testTag(WorkRowTags.SUMMARY).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.width(16.dp)) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        }
        Text(styled(argStyles(), Str.launch_sum_not_git, Arg.Bold(projectPath)), fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── a translated sentence with its placeholders styled: code, bold, a tag ─────────────────────────────

private sealed interface Arg {
    data class Plain(val text: String) : Arg
    /** A pull request's name ("PR #123"), in the link colour. */
    data class Link(val text: String) : Arg
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
private class ArgStyles(val code: SpanStyle, val bold: SpanStyle, val tag: SpanStyle, val link: SpanStyle)

@Composable
private fun argStyles(): ArgStyles {
    val cs = MaterialTheme.colorScheme
    return ArgStyles(
        // The prototype's <code> (launcher.css: `font: 400 12.5px mono`): regular weight even in bold words, in the
        // words' own colour; <b><code> only takes the brighter colour.
        code = SpanStyle(fontFamily = MonoFontFamily, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
        bold = SpanStyle(color = cs.onSurface, fontWeight = FontWeight.Medium),
        tag = SpanStyle(fontSize = 10.5.sp, color = cs.onSurfaceVariant, background = cs.surfaceContainerHighest),
        link = SpanStyle(color = cs.primary),
    )
}

/** [key]'s text in the app's language, each `%N$s` replaced by its [args] entry, styled. */
private fun styled(st: ArgStyles, key: StringKey, vararg args: Arg): AnnotatedString = styledText(st, localized(key), *args)

/** A plural's text for [count], styled the same way. */
private fun styledPlural(st: ArgStyles, key: PluralKey, count: Int, vararg args: Arg): AnnotatedString = styledText(st, localizedPlural(key, count), *args)

private fun styledText(st: ArgStyles, template: String, vararg args: Arg): AnnotatedString {
    val code = st.code
    return buildAnnotatedString {
        var at = 0
        PLACEHOLDER.findAll(template).forEach { m ->
            append(template.substring(at, m.range.first))
            when (val a = args.getOrNull(m.groupValues[1].toInt() - 1)) {
                null -> append(m.value)
                is Arg.Plain -> append(a.text)
                is Arg.Link -> withStyle(st.link) { append(a.text) }
                is Arg.Bold -> withStyle(st.bold) { append(a.text) }
                is Arg.Code -> withStyle(code) { append(a.text) }
                is Arg.CodeBold -> withStyle(code.copy(color = st.bold.color)) { append(a.text) }
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

// ── #152 C2: the Pull request tab (GitLab: Merge request) ─────────────────────────────────────────────

/** How the project's forge names a pull request: "pull request" and "#123" on GitHub, "merge request" and "!7" on GitLab. */
internal class ForgeWords(private val gitlab: Boolean) {
    val noun: String get() = localized(if (gitlab) Str.launch_mr_noun else Str.launch_pr_noun)
    val tab: String get() = localized(if (gitlab) Str.launch_tab_mr else Str.launch_tab_pr)
    val search: String get() = localized(if (gitlab) Str.launch_mr_search else Str.launch_pr_search)
    val openList: String get() = localized(if (gitlab) Str.launch_mr_open_list else Str.launch_pr_open_list)
    val sign: String get() = if (gitlab) "!" else "#"
    /** The CLI that holds the user's sign-in to the forge. */
    val cli: String get() = if (gitlab) "glab" else "gh"
    fun label(n: Int): String = localized(if (gitlab) Str.launch_mr_label else Str.launch_pr_label, n)
}

@Composable
private fun ColumnScope.PrList(
    pointer: Boolean,
    pick: WorkPick,
    prs: OpenPrList?,
    lookupPr: suspend (String) -> OpenPrRead?,
    words: ForgeWords,
    folderBranch: String?,
    onPick: (WorkPick) -> Unit,
    close: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all = prs?.prs.orEmpty()
    val number = parsePrNumber(query)
    val listed = number?.let { n -> all.firstOrNull { it.number == n } }
    // One the list doesn't have (a URL pasted, one past the first 50): read it by number.
    var looked by remember { mutableStateOf<OpenPrRead?>(null) }
    LaunchedEffect(number, listed) {
        looked = null
        if (number != null && listed == null) looked = lookupPr(query.trim())
    }
    val pickPr: (OpenPr) -> Unit = { p -> onPick(pick.copy(mode = BranchMode.PullRequest, pr = p, diverged = null, folderTo = null)); close() }
    val found = looked?.takeIf { it.status == "found" }?.pr
    val shown: List<OpenPr> = when {
        listed != null -> listOf(listed)
        number != null -> listOfNotNull(found)
        else -> query.trim().lowercase().let { q -> if (q.isEmpty()) all else all.filter { "${it.number} ${it.title} ${it.head} ${it.author}".lowercase().contains(q) } }
    }
    SearchField(pointer, query, { query = it }, onEnter = { shown.firstOrNull()?.let(pickPr) }, onEscape = close, placeholder = words.search, tag = WorkRowTags.PR_SEARCH)
    // What can't be listed or found is said, never an empty list.
    val read = looked
    val note: String? = when {
        prs?.status == "unavailable" -> when (prs.reason) {
            "no_cli" -> localized(Str.launch_pr_no_cli, words.cli, words.noun)
            "not_authed" -> localized(Str.launch_pr_not_authed, words.cli, words.noun)
            else -> localized(Str.launch_pr_failed, words.noun, prs.message.orEmpty())
        }
        number != null && listed == null && read != null && read.status != "found" -> when (read.status) {
            "not_open" -> localized(if (read.state == "merged") Str.launch_pr_merged else Str.launch_pr_closed, words.label(number))
            "other_repo" -> localized(Str.launch_pr_other_repo, read.repo.orEmpty())
            else -> localized(Str.launch_pr_none, words.noun, words.sign + number, prs?.repo.orEmpty())
        }
        else -> null
    }
    if (note != null) {
        Text(note, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp).testTag(WorkRowTags.PR_NOTE))
    }
    if (prs?.status == "ok") Label(words.openList, right = prs.repo)
    shown.forEach { p -> PrRowItem(p, selected = pick.mode == BranchMode.PullRequest && pick.pr?.number == p.number, words, folderBranch, onClick = { pickPr(p) }) }
    if (prs == null) Empty(localized(Str.launch_fetching))
}

@Composable
private fun PrRowItem(pr: OpenPr, selected: Boolean, words: ForgeWords, folderBranch: String?, onClick: () -> Unit) {
    val base = argStyles()
    val st = ArgStyles(base.code.copy(fontSize = 11.sp), base.bold, base.tag, base.link)
    val head = if (pr.isFork && !pr.headOwner.isNullOrEmpty()) "${pr.headOwner}:${pr.head}" else pr.head
    MenuRow(
        icon = PullRequestIcon,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.testTag(WorkRowTags.pr(pr.number)),
        title = words.sign + pr.number + " " + pr.title, // i18n-ok: the forge's mark, a number and the PR's own title
        subtitleRich = styled(st, Str.launch_pr_row, Arg.Code(head), Arg.Code(pr.base), Arg.Plain(pr.author)),
        tags = {
            if (pr.draft) Tag(localized(Str.launch_tag_draft))
            if (pr.isFork) Tag(localized(if (pr.maintainerCanModify) Str.launch_tag_fork else Str.launch_tag_fork_review), if (pr.maintainerCanModify) TagTone.Plain else TagTone.Warn)
            if (!pr.isFork && pr.head == folderBranch) Tag(localized(Str.launch_tag_in_folder), TagTone.Warn)
            if ((pr.localAhead ?: 0) > 0) Tag(localized(Str.launch_tag_local_differs), TagTone.Warn)
            when (pr.checks) {
                "ok" -> Tag(localized(Str.launch_tag_checks_ok), TagTone.Ok)
                "bad" -> Tag(localized(Str.launch_tag_checks_bad), TagTone.Warn)
                "run" -> Tag(localized(Str.launch_tag_checks_run))
            }
        },
    )
}

// ── #152 C3: the ways out when git keeps the branch elsewhere, or the PR would replace local commits ──────

@Composable
private fun ConflictCard(
    conflict: WorkConflict,
    pick: WorkPick,
    projectPath: String,
    words: ForgeWords,
    onPick: (WorkPick) -> Unit,
    onWorkIn: (WorkIn) -> Unit,
    onOpenSession: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val st = argStyles()
    val warn = LocalSemantics.current.warning
    val shape = RoundedCornerShape(14.dp)
    val prLabel = pick.pr?.let { words.sign + it.number } // i18n-ok: "#118" / "!7", the forge's mark and a number
    val newFrom: @Composable (String) -> Unit = { branch ->
        CardOption(
            Icons.Filled.Add, styled(st, Str.launch_card_new_from, Arg.Code(branch)),
            AnnotatedString(if (prLabel != null) localized(Str.launch_card_new_from_pr_hint, prLabel) else localized(Str.launch_card_new_from_hint)),
            tag = WorkRowTags.CARD_NEW_FROM,
        ) { onPick(newBranchFrom(pick, branch)) }
    }
    val (head, why) = when (conflict) {
        is WorkConflict.InFolder -> styled(st, Str.launch_card_in_folder, Arg.CodeBold(conflict.branch), Arg.Plain(projectPath)) to localized(Str.launch_card_one_place)
        is WorkConflict.InSession -> styled(st, Str.launch_card_in_session, Arg.CodeBold(conflict.branch), Arg.Plain(conflict.sessionName)) to localized(Str.launch_card_one_place_short)
        is WorkConflict.InWorktree -> styled(st, Str.launch_card_in_worktree, Arg.CodeBold(conflict.branch), Arg.Code(conflict.path)) to localized(Str.launch_card_one_place_short)
        is WorkConflict.LocalAhead -> styledPlural(st, Plurals.launch_card_ahead, conflict.ahead, Arg.Plain(conflict.ahead.toString()), Arg.CodeBold(conflict.branch), Arg.Plain(words.sign + conflict.pr.number)) to
            localized(Str.launch_card_ahead_why, words.noun)
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(warn.copy(alpha = 0.09f).compositeOver(cs.surfaceContainerLow))
            .border(1.dp, warn.copy(alpha = 0.45f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(WorkRowTags.CARD),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = warn, modifier = Modifier.padding(top = 1.dp).size(16.dp))
            Text(head, fontSize = 13.sp, lineHeight = 19.sp, color = cs.onSurface, modifier = Modifier.testTag(WorkRowTags.CARD_HEAD))
        }
        Text(why, fontSize = 12.5.sp, lineHeight = 18.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 2.dp))
        Column(Modifier.padding(start = 24.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (conflict) {
                is WorkConflict.InFolder -> {
                    CardOption(
                        Icons.Outlined.Folder, AnnotatedString(localized(Str.launch_card_use_local)),
                        styled(st, Str.launch_card_use_local_hint, Arg.Plain(projectPath), Arg.Code(conflict.branch)),
                        tag = WorkRowTags.CARD_USE_LOCAL,
                    ) { onWorkIn(WorkIn.Local); onPick(pick.copy(switchTo = null)) }
                    CardOption(
                        Icons.Outlined.SwapHoriz, styled(st, Str.launch_card_move, Arg.Code(conflict.moveTo), Arg.Code(conflict.branch)),
                        AnnotatedString(localized(if (conflict.canMove) Str.launch_card_move_hint else Str.launch_card_move_dirty)),
                        enabled = conflict.canMove, tag = WorkRowTags.CARD_MOVE,
                    ) { onPick(pick.copy(folderTo = conflict.moveTo)) }
                    newFrom(conflict.branch)
                }
                is WorkConflict.InSession -> {
                    if (onOpenSession != null) {
                        CardOption(
                            Icons.AutoMirrored.Filled.OpenInNew, AnnotatedString(localized(Str.launch_card_open, conflict.sessionName)),
                            styled(st, Str.launch_card_open_hint, Arg.Code(conflict.branch)),
                            tag = WorkRowTags.CARD_OPEN,
                        ) { onOpenSession(conflict.sessionId) }
                    }
                    newFrom(conflict.branch)
                }
                is WorkConflict.InWorktree -> newFrom(conflict.branch)
                is WorkConflict.LocalAhead -> {
                    CardOption(
                        Icons.Outlined.SwapHoriz, AnnotatedString(localized(Str.launch_card_use_pr, words.noun)),
                        styledPlural(st, Plurals.launch_card_use_pr_hint, conflict.ahead, Arg.Plain(conflict.ahead.toString()), Arg.Code("${conflict.branch}-backup")),
                        tag = WorkRowTags.CARD_USE_PR,
                    ) { onPick(pick.copy(diverged = "pr")) }
                    CardOption(
                        Icons.AutoMirrored.Filled.CallSplit, AnnotatedString(localized(Str.launch_card_keep_mine)),
                        AnnotatedString(localized(Str.launch_card_keep_mine_hint, words.noun)),
                        tag = WorkRowTags.CARD_KEEP_MINE,
                    ) { onPick(pick.copy(diverged = "mine")) }
                }
            }
        }
    }
}

@Composable
private fun CardOption(icon: ImageVector, title: AnnotatedString, hint: AnnotatedString, enabled: Boolean = true, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(1.dp, if (hovered && enabled) cs.primary else cs.outline, shape)
            .hoverable(src)
            .then(if (enabled) Modifier.clickable(interactionSource = src, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(hint, fontSize = 12.sp, lineHeight = 17.sp, color = cs.onSurfaceVariant)
        }
    }
}

/** The pull request glyph (the prototype's own path): Material has none. */
private val PullRequestIcon: ImageVector by lazy {
    ImageVector.Builder(name = "PullRequest", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M6 3a3 3 0 0 1 1 5.83v6.34A3 3 0 1 1 5 15.17V8.83A3 3 0 0 1 6 3zm0 2a1 1 0 1 0 0 2 1 1 0 0 0 0-2zm0 12a1 1 0 1 0 0 2 " +
                    "1 1 0 0 0 0-2zM13 2v2h1a3 3 0 0 1 3 3v8.17A3 3 0 1 1 15 15.17V7a1 1 0 0 0-1-1h-1v2L9.5 5 13 2zm3 15a1 1 0 1 0 0 2 " +
                    "1 1 0 0 0 0-2z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
