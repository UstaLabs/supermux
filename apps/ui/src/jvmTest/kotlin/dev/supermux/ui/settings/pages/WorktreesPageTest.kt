package dev.supermux.ui.settings.pages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeOrphanPageDto
import dev.supermux.net.WorktreeOrphansDto
import dev.supermux.net.WorktreeOwnerDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.settings.WorktreesSettingsActions
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred

/**
 * The machine's Worktrees page (#97 Step 2; prototype `step2/worktrees.html`): what the machine page's Review sheet
 * did (S2-3, S2-4), now as a page with checkboxes, Sort, Show more and a delete bar that stays at the bottom.
 */
@OptIn(ExperimentalTestApi::class)
class WorktreesPageTest {

    private val server = KitMachine("m2", "studio-mini", local = false, online = true, status = "Running")
    private val offline = KitMachine("m2", "studio-mini", local = false, online = false, status = "Offline", lastSeen = "2 h ago")
    private val day = 86_400_000L

    private fun ComposeUiTest.page(
        worktrees: WorktreesSettingsActions,
        machine: KitMachine = server,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        onBack: () -> Unit = {},
    ) = setPlatformContent(widthClass = widthClass, pointer = widthClass != WindowWidthClass.Compact) {
        CompositionLocalProvider(LocalSettingsMachines provides SettingsMachines(listOf(machine), machine.id) {}) {
            WorktreesPage(worktrees, onBack = onBack)
        }
    }

    private fun ComposeUiTest.await(tag: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { onNodeWithTag(tag).assertExists(); true }.getOrDefault(false) }

    private fun ComposeUiTest.gone(tag: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { onNodeWithTag(tag).assertDoesNotExist(); true }.getOrDefault(false) }

    /** Scrolls the page to [tag] first, unless it is in the delete bar, which stays put. */
    private fun ComposeUiTest.tap(tag: String) {
        await(tag)
        runCatching { onNodeWithTag(tag).performScrollTo() }
        onNodeWithTag(tag).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.has(tag: String, text: String) {
        onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and hasText(text, substring = true, ignoreCase = false),
            useUnmergedTree = true,
        ).onFirst().assertExists()
    }

    private fun ComposeUiTest.hasSoon(tag: String, text: String, timeout: Long = 5_000) =
        waitUntil(timeoutMillis = timeout) { runCatching { has(tag, text); true }.getOrDefault(false) }

    private fun w(
        id: String,
        changes: Int = 0,
        bytes: Long = 1L shl 30,
        live: Boolean = false,
        repo: String = "supermux",
        root: String = "/src/$repo",
        mtime: Long = 0,
        chat: String? = "Chat $id",
    ) = WorktreeSummaryDto(
        id = id, path = "/wt/$repo/$id", repoRoot = root, repoName = repo, branch = "mux/$id",
        owners = listOfNotNull(chat?.let { WorktreeOwnerDto("s-$id", it, if (live) "live" else "archived") }),
        mtime = mtime, uncommitted = changes, unmerged = 0, hasChanges = changes > 0, bytes = bytes,
    )

    private val trees = listOf(w("a"), w("b", bytes = 2L shl 30), w("dirty", changes = 6), w("busy", live = true))

    @Test fun the_page_sums_up_and_locked_rows_keep_their_reason_with_their_checkbox_off() = runComposeUiTest {
        var back = 0
        page(WorktreesSettingsActions(load = { WorktreeListDto("/wt", trees) }), onBack = { back++ })
        await("wt_summary")
        onNodeWithTag("settings_page_title").assertTextEquals("Chat worktrees")
        onNodeWithTag("wt_summary").assertTextEquals("5.0 GB in 4 folders · 2 have no changes")
        has("wt_row_dirty", "6 changed files")
        onNodeWithTag("wt_kept_dirty").assertExists()
        onNodeWithTag("wt_check_dirty").assertIsNotEnabled().assertIsOff()
        has("wt_row_busy", "In use by a running chat")
        onNodeWithTag("wt_check_busy").assertIsNotEnabled()
        onNodeWithTag("wt_check_a").assertIsEnabled().assertIsOff()
        has("wt_row_a", "No changes")
        // Nothing picked: no bar.
        onNodeWithTag("wt_delete_bar").assertDoesNotExist()
        has("settings_page_back", "studio-mini")
        tap("settings_page_back")
        assertEquals(1, back)
    }

    @Test fun a_row_reads_folder_repository_branch_chat_and_last_used() = runComposeUiTest {
        val now = System.currentTimeMillis()
        page(WorktreesSettingsActions(load = {
            WorktreeListDto("/wt", listOf(w("docs-0-18", mtime = now - 3 * day - 1000, chat = "Docs pass"), w("spike", mtime = now - 63 * day, chat = null)))
        }))
        await("wt_row_spike")
        has("wt_row_docs-0-18", "docs-0-18")
        has("wt_row_docs-0-18", "supermux · mux/docs-0-18")
        has("wt_row_docs-0-18", "“Docs pass” · Last used 3 days ago · No changes")
        has("wt_row_docs-0-18", "1.0 GB")
        has("wt_row_spike", "No chat · Last used 2 months ago")
    }

    @Test fun select_without_changes_then_delete_asks_and_deletes_in_chunks_with_progress() = runComposeUiTest {
        val many = (1..45).map { w("c$it", bytes = 1L shl 20) } + w("dirty", changes = 1)
        var list = many
        val batches = mutableListOf<List<String>>()
        page(WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", list) },
            delete = { ids ->
                batches += ids
                kotlinx.coroutines.delay(150)
                list = list.filter { it.id !in ids }
                ids.map { WorktreeDeleteResultDto(it, ok = true) }
            },
        ))
        await("wt_select_clean")
        onNodeWithTag("wt_select_clean").assertTextEquals("Select the 45 without changes")
        tap("wt_select_clean")
        onNodeWithTag("wt_select_clean").assertTextEquals("Clear")
        onNodeWithTag("wt_check_c1").assertIsOn()
        // Below the fold too: only 18 rows are drawn, the bar counts all 45.
        onNodeWithTag("wt_delete").assertTextEquals("Delete 45 folders · 45 MB")
        tap("wt_delete")
        await("wt_delete_dialog")
        has("wt_delete_dialog", "Delete 45 folders?")
        has("wt_delete_dialog", "Deletes the folders and their branches · 45 MB. None of them has changes. This can’t be undone.")
        assertEquals(emptyList(), batches)
        tap("wt_delete_confirm")
        await("wt_delete_progress")
        has("wt_delete_progress", "Deleting ")
        await("wt_paused")
        has("wt_paused", "Deleting. The list is paused until it’s done.")
        hasSoon("wt_delete_words", "Deleted 45 folders · 45 MB freed", timeout = 8_000)
        assertEquals(listOf(20, 20, 5), batches.map { it.size })
        hasSoon("wt_summary", "1.0 GB in 1 folder")
        await("wt_every_changed")
        has("wt_every_changed", "Every folder has changes or is in use.")
    }

    @Test fun picking_by_hand_shows_clear_beside_select_and_the_bar_clears_too() = runComposeUiTest {
        page(WorktreesSettingsActions(load = { WorktreeListDto("/wt", trees) }))
        await("wt_check_a")
        onNodeWithTag("wt_clear").assertDoesNotExist()
        tap("wt_check_a")
        onNodeWithTag("wt_check_a").assertIsOn()
        onNodeWithTag("wt_select_clean").assertTextEquals("Select the 2 without changes")
        await("wt_clear")
        onNodeWithTag("wt_delete").assertTextEquals("Delete 1 folder · 1.0 GB")
        tap("wt_bar_clear")
        onNodeWithTag("wt_check_a").assertIsOff()
        gone("wt_delete_bar")
        tap("wt_check_b")
        tap("wt_clear")
        gone("wt_delete_bar")
    }

    @Test fun a_failed_delete_keeps_the_row_with_its_error() = runComposeUiTest {
        page(WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", trees) },
            delete = { ids -> ids.map { WorktreeDeleteResultDto(it, ok = false, error = "in_use", inUseBy = listOf("Chat a")) } },
        ))
        tap("wt_check_a")
        tap("wt_delete")
        tap("wt_delete_confirm")
        await("wt_error_a")
        onNodeWithTag("wt_error_a").assertTextEquals("In use by Chat a")
        onNodeWithTag("wt_row_a").assertExists()
    }

    @Test fun sort_is_size_first_and_last_used_puts_the_longest_unused_first() = runComposeUiTest {
        val now = System.currentTimeMillis()
        page(WorktreesSettingsActions(load = {
            WorktreeListDto("/wt", listOf(
                w("big", bytes = 3L shl 30, mtime = now - 2 * day),
                w("mid", bytes = 2L shl 30, mtime = now - 90 * day),
                w("small", bytes = 1L shl 30, mtime = now - 30 * day),
            ))
        }))
        await("wt_row_small")
        fun top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue(top("wt_row_big") < top("wt_row_mid") && top("wt_row_mid") < top("wt_row_small"), "biggest first")
        tap("wt_sort_used")
        assertTrue(top("wt_row_mid") < top("wt_row_small") && top("wt_row_small") < top("wt_row_big"), "longest unused first")
        tap("wt_sort_size")
        assertTrue(top("wt_row_big") < top("wt_row_mid"))
    }

    @Test fun the_list_shows_the_biggest_18_then_show_more() = runComposeUiTest {
        page(WorktreesSettingsActions(load = { WorktreeListDto("/wt", (1..25).map { w("r$it", bytes = it.toLong() shl 20) }) }))
        await("wt_more_note")
        has("wt_more_note", "Showing 18 of 25 folders.")
        onNodeWithTag("wt_row_r25").assertExists()
        onNodeWithTag("wt_row_r8").assertExists()
        onNodeWithTag("wt_row_r7").assertDoesNotExist()
        tap("wt_show_more")
        await("wt_row_r1")
        onNodeWithTag("wt_more_note").assertDoesNotExist()
    }

    // ── Folders whose repository is gone (S2-4) ──

    private fun goneW(id: String, live: Boolean = false) =
        w(id, bytes = 1L shl 20, live = live).copy(error = "repo gone", hasChanges = true, unmerged = null)

    @Test fun a_gone_repository_folder_is_picked_on_its_own_after_a_confirm_and_never_by_select_clean() = runComposeUiTest {
        val gone = listOf(goneW("g0"), goneW("g1"), goneW("g2", live = true))
        val pages = mutableListOf<Pair<Int, Int>>()
        val batches = mutableListOf<List<String>>()
        page(WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a"), w("dirty", changes = 2)), orphans = WorktreeOrphansDto(count = 3, bytes = 3L shl 20, sized = 3)) },
            orphans = { offset, limit -> synchronized(pages) { pages += offset to limit }; WorktreeOrphanPageDto(gone, total = 3) },
            delete = { ids -> batches += ids; ids.map { WorktreeDeleteResultDto(it, ok = true) } },
        ))
        await("wt_row_g0")
        onNodeWithTag("wt_summary").assertTextEquals("2.0 GB in 5 folders · 1 has no changes")
        assertEquals(listOf(0 to 200), synchronized(pages) { pages.toList() })
        has("wt_orphans", "REPOSITORY MISSING")
        has("wt_orphans", "· 3 folders · 3 MB")
        has("wt_row_g0", "Can’t check for changes")
        // A running chat still on it: locked.
        onNodeWithTag("wt_check_g2").assertIsNotEnabled()
        has("wt_row_g2", "In use by a running chat")
        // "Select the 1 without changes" takes only the clean folder.
        tap("wt_select_clean")
        onNodeWithTag("wt_check_g0").assertIsOff()
        onNodeWithTag("wt_delete").assertTextEquals("Delete 1 folder · 1.0 GB")
        // Picking a gone one asks first; Cancel leaves it out.
        tap("wt_check_g0")
        await("wt_gone_dialog")
        has("wt_gone_dialog", "Select “g0”?")
        has("wt_gone_dialog", "The repository is gone; this deletes the folder.")
        tap("wt_gone_cancel")
        gone("wt_gone_dialog")
        onNodeWithTag("wt_delete").assertTextEquals("Delete 1 folder · 1.0 GB")
        tap("wt_check_g0")
        tap("wt_gone_confirm")
        gone("wt_gone_dialog")
        onNodeWithTag("wt_delete").assertTextEquals("Delete 2 folders · 1.0 GB")
        // Unpicking needs no confirm.
        tap("wt_check_g0")
        onNodeWithTag("wt_gone_dialog").assertDoesNotExist()
        onNodeWithTag("wt_delete").assertTextEquals("Delete 1 folder · 1.0 GB")
        tap("wt_check_g0")
        tap("wt_gone_confirm")
        gone("wt_gone_dialog")
        tap("wt_delete")
        await("wt_delete_dialog")
        has("wt_delete_dialog", "1 folder of them has no repository left, so nothing there can be checked.")
        tap("wt_delete_confirm")
        hasSoon("wt_delete_words", "Deleted 2 folders")
        assertEquals(listOf(listOf("a", "g0")), batches)
    }

    @Test fun more_gone_folders_load_a_page_at_a_time() = runComposeUiTest {
        val pages = mutableListOf<Int>()
        page(WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a")), orphans = WorktreeOrphansDto(count = 3)) },
            orphans = { offset, _ ->
                synchronized(pages) { pages += offset }
                if (offset == 0) WorktreeOrphanPageDto(listOf(goneW("g0"), goneW("g1")), total = 3) else WorktreeOrphanPageDto(listOf(goneW("g2")), total = 3)
            },
        ))
        await("wt_row_g1")
        onNodeWithTag("wt_orphans_more").assertTextEquals("Show more (1 left)")
        tap("wt_orphans_more")
        await("wt_row_g2")
        onNodeWithTag("wt_orphans_more").assertDoesNotExist()
        assertEquals(listOf(0, 2), synchronized(pages) { pages.toList() })
    }

    @Test fun a_live_size_does_not_move_a_row() = runComposeUiTest {
        var sizes by mutableStateOf(mapOf<String, Long>())
        page(WorktreesSettingsActions(
            load = { WorktreeListDto("/wt", listOf(w("a", bytes = 3L shl 20), w("b", bytes = 2L shl 20), w("c", bytes = 1L shl 20))) },
            sizes = { sizes },
        ))
        await("wt_row_c")
        fun top(tag: String) = onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"))
        sizes = mapOf("c" to (10L shl 20))
        waitForIdle()
        assertTrue(top("wt_row_a") < top("wt_row_b") && top("wt_row_b") < top("wt_row_c"), "a size frame must not move rows")
        has("wt_row_c", "10 MB")
    }

    // ── Empty, offline, refresh ──

    @Test fun no_worktrees_is_the_empty_state_with_refresh() = runComposeUiTest {
        page(WorktreesSettingsActions(load = { WorktreeListDto("/wt") }))
        await("wt_empty")
        onNodeWithTag("wt_empty").assertTextEquals("No chat worktrees on this machine.")
        onNodeWithTag("wt_summary").assertTextEquals("Nothing on disk")
        onNodeWithTag("wt_refresh").assertExists()
        onNodeWithTag("wt_select_clean").assertDoesNotExist()
    }

    @Test fun an_offline_machine_is_one_quiet_line_and_nothing_is_asked() = runComposeUiTest {
        val loads = AtomicInteger(0)
        page(WorktreesSettingsActions(load = { loads.incrementAndGet(); WorktreeListDto("/wt", trees) }), machine = offline)
        await("wt_quiet")
        onNodeWithTag("wt_quiet").assertTextEquals("studio-mini is offline · last seen 2 h ago")
        onNodeWithTag("wt_delete_bar").assertDoesNotExist()
        assertEquals(0, loads.get())
    }

    @Test fun refresh_asks_the_machine_to_look_again_and_says_so_in_words() = runComposeUiTest {
        val asked = mutableListOf<Boolean>()
        val gate = CompletableDeferred<Unit>()
        page(WorktreesSettingsActions(load = { refresh ->
            synchronized(asked) { asked += refresh }
            if (refresh) gate.await()
            WorktreeListDto("/wt", trees)
        }))
        await("wt_refresh")
        has("wt_updated", "Updated just now")
        tap("wt_refresh")
        await("wt_refreshing")
        onNodeWithTag("wt_refreshing").assertTextEquals("Refreshing…")
        gate.complete(Unit)
        await("wt_refresh")
        assertEquals(listOf(false, true), synchronized(asked) { asked.toList() })
    }

    @Test fun a_failed_load_says_so_and_try_again_recovers() = runComposeUiTest {
        val n = AtomicInteger(0)
        page(WorktreesSettingsActions(load = { if (n.incrementAndGet() == 1) null else WorktreeListDto("/wt", trees) }))
        await("wt_failed")
        has("wt_failed", "Couldn’t read the worktrees on studio-mini.")
        tap("wt_retry")
        await("wt_summary")
    }

    @Test fun on_a_phone_the_bar_still_deletes() = runComposeUiTest {
        val batches = mutableListOf<List<String>>()
        page(
            WorktreesSettingsActions(load = { WorktreeListDto("/wt", trees) }, delete = { ids -> batches += ids; ids.map { WorktreeDeleteResultDto(it, ok = true) } }),
            widthClass = WindowWidthClass.Compact,
        )
        tap("wt_select_clean")
        onNodeWithTag("wt_delete").assertTextEquals("Delete 2 folders · 3.0 GB")
        tap("wt_delete")
        tap("wt_delete_confirm")
        hasSoon("wt_delete_words", "Deleted 2 folders · 3.0 GB freed")
        assertEquals(listOf(listOf("a", "b")), batches.map { it.sorted() })
    }

    // ── Copy ──

    @Test fun the_delete_ask_says_what_a_gone_repository_means() {
        assertEquals(
            "Deletes the folder and its branch · 1 MB. It has no repository left, so nothing there can be checked. This can’t be undone.",
            WorktreesCopy.deleteText(1, 1L shl 20, gone = 1),
        )
        assertEquals(
            "Deletes the folders and their branches · 2 MB. They have no repository left, so nothing there can be checked. This can’t be undone.",
            WorktreesCopy.deleteText(2, 2L shl 20, gone = 2),
        )
        assertEquals(
            "Deletes the folders and their branches · 3 MB. 2 folders of them have no repository left, so nothing there can be checked. This can’t be undone.",
            WorktreesCopy.deleteText(3, 3L shl 20, gone = 2),
        )
    }

    @Test fun last_used_reads_as_words() {
        assertEquals("today", WorktreesCopy.ago(day / 2, 0))
        assertEquals("yesterday", WorktreesCopy.ago(day + 1, 0))
        assertEquals("3 days ago", WorktreesCopy.ago(3 * day, 0))
        assertEquals("3 weeks ago", WorktreesCopy.ago(21 * day, 0))
        assertEquals("2 months ago", WorktreesCopy.ago(63 * day, 0))
    }

    @Test fun the_disk_row_sums_up_and_says_when_there_are_none() {
        assertEquals("18.4 GB in 212 folders · 140 have no changes", WorktreesCopy.diskSummary((18.4 * (1L shl 30)).toLong() + 1, 212, 140))
        assertEquals("No chat worktrees", WorktreesCopy.diskSummary(0, 0, 0))
        assertEquals("1.0 GB in 1 folder · all have changes", WorktreesCopy.diskSummary(1L shl 30, 1, 0))
    }

    @Test fun same_named_repositories_on_windows_show_their_parent_folders() {
        val groups = worktreeGroups(
            listOf(w("x", repo = "repo", root = "C:\\src\\a\\repo"), w("y", repo = "repo", root = "C:\\src\\b\\repo")),
        )
        assertEquals(listOf("repo (C:\\src\\a)", "repo (C:\\src\\b)"), groups.map { it.first })
    }

    @Test fun repositories_group_by_root_and_same_names_are_told_apart() {
        val groups = worktreeGroups(
            listOf(w("a", repo = "app", root = "/home/x/one/app"), w("b", repo = "app", root = "/home/x/two/app"), w("c", repo = "web")),
        )
        assertEquals(listOf("app (/home/x/one)", "app (/home/x/two)", "web"), groups.map { it.first })
    }
}
