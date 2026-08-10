package dev.supermux.desktop.editor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct tests of the document layer extracted from EditorState (EditorStateTest.kt keeps exercising
 * the same rules through the coordinator; these pin them at the level that now owns them, with no
 * Compose UI — just a [TestScope] and fake fsRead/fsWrite).
 *
 * The focus is the three M3-T4 divergences hardened for an OVER-THE-NETWORK fsRead, documented in
 * DocumentStore.kt's header: (A) the [DocumentStore.open] in-flight guard, (B) the
 * [DocumentStore.openAtLine] reveal nonce, (C) the [DocumentStore.close] load-cancel — plus the
 * `current` gating [DocumentStore.onOpened] hands the host, which is what keeps the LAST-opened file
 * active regardless of which read returns first.
 *
 * Most tests use `runTest`'s StandardTestDispatcher so two opens can happen BEFORE any load body
 * runs — the whole point of the guards.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentStoreTest {

    /** Records every [DocumentStore.onOpened] callback, mirroring what EditorState does with it. */
    private class Opened {
        val events = mutableListOf<Pair<String, Boolean>>()
        fun install(store: DocumentStore) {
            store.onOpened = { doc, current -> events.add(doc.path to current) }
        }
        val paths get() = events.map { it.first }
    }

    private fun store(
        scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()),
        fsRead: suspend (String) -> Result<String> = { Result.success("body:$it") },
        fsWrite: suspend (String, String) -> Boolean = { _, _ -> true },
    ) = DocumentStore(fsRead = fsRead, fsWrite = fsWrite, scope = scope)

    // ── baseline: a document is created once, keyed by path ────────────────────────────────────

    @Test fun open_creates_the_document_and_reports_it_as_current() {
        val s = store()
        val opened = Opened().also { it.install(s) }

        s.open("a.kt")

        val doc = s.get("a.kt")
        assertNotNull(doc)
        assertEquals("body:a.kt", doc.content)
        assertEquals("body:a.kt", doc.savedContent)
        assertFalse(s.isDirty("a.kt"))
        assertNull(s.loadingPath)
        assertEquals(listOf("a.kt" to true), opened.events)
    }

    @Test fun reopening_an_open_path_reuses_the_same_document_without_a_second_read() {
        var reads = 0
        val s = store(fsRead = { reads++; Result.success("body:$it") })
        val opened = Opened().also { it.install(s) }
        s.open("a.kt")
        val first = s.get("a.kt")
        s.update("a.kt", "edited")

        s.open("a.kt")

        assertEquals(1, reads)                       // the fast path never re-reads…
        assertTrue(first === s.get("a.kt"))          // …and never replaces the buffer
        assertEquals("edited", s.get("a.kt")?.content)
        assertEquals(listOf("a.kt" to true, "a.kt" to true), opened.events)
    }

    // ── (A) in-flight guard: a second open of a still-loading path is a no-op ──────────────────

    @Test fun open_dedupes_two_un_advanced_opens_of_the_same_path() = runTest {
        var reads = 0
        val s = store(scope = this, fsRead = { reads++; Result.success("body:$it") })
        val opened = Opened().also { it.install(s) }

        s.open("a.kt")
        s.open("a.kt") // in-flight guard: loadingPath == "a.kt" → no second load
        assertEquals("a.kt", s.loadingPath)

        advanceUntilIdle()
        assertEquals(1, reads)
        assertEquals(listOf("a.kt" to true), opened.events) // one document, one tab-add for the host
        assertNull(s.loadingPath)
    }

    @Test fun a_failed_load_sets_load_error_only_while_it_still_owns_the_gate() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val s = store(
            scope = this,
            fsRead = { path ->
                if (path == "a.bin") { gateA.await(); Result.failure(RuntimeException("binary")) }
                else Result.success("body:$path")
            },
        )
        s.open("a.bin") // slow failure
        s.open("b.kt")  // takes the gate

        gateA.complete(Unit)
        advanceUntilIdle()
        assertNull(s.loadError) // the superseded failure must not stomp the newer open
        assertNotNull(s.get("b.kt"))
    }

    // ── (C) close cancels an in-flight load; the late result is dropped, never re-added ────────

    @Test fun close_during_an_in_flight_load_drops_the_result_no_resurrect() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this, fsRead = { gate.await(); Result.success("body:$it") })
        val opened = Opened().also { it.install(s) }
        s.open("a.kt")
        assertEquals("a.kt", s.loadingPath)

        s.close("a.kt")
        assertNull(s.loadingPath)

        gate.complete(Unit)
        advanceUntilIdle()
        // The cancel marker is set BEFORE the document lookup, so a close during a COLD open — when
        // there is no document yet, only a loadingPath — still cancels.
        assertNull(s.get("a.kt"))
        assertTrue(opened.events.isEmpty()) // the host is never told to add a tab
        assertNull(s.loadError)
    }

    @Test fun reopen_after_a_close_cancel_loads_normally() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this, fsRead = { gate.await(); Result.success("body:$it") })
        val opened = Opened().also { it.install(s) }
        s.open("a.kt")
        s.close("a.kt")
        s.open("a.kt") // supersedes the cancel

        gate.complete(Unit)
        advanceUntilIdle()
        val doc = s.get("a.kt")
        assertNotNull(doc)
        // BOTH reads land (the reopen un-cancelled the first one), but they resolve to ONE document
        // and only the gate-owning completion is reported current — so the host adds one tab and
        // activates it, exactly as before the split.
        assertEquals("a.kt" to true, opened.events.first())
        assertTrue(opened.events.drop(1).none { it.second })
        assertEquals(setOf("a.kt"), opened.paths.toSet())
    }

    @Test fun close_during_a_reload_consumes_the_cancel_and_drops_the_result() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this)
        s.open("a.txt")
        advanceUntilIdle()
        s.markChanged(listOf("a.txt"))

        val job = launch { s.reload("a.txt") { gate.await(); Result.success("fresh") } }
        advanceUntilIdle()
        assertEquals("a.txt", s.loadingPath)
        s.close("a.txt")

        gate.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertNull(s.get("a.txt"))     // nothing resurrected off a ghost document
        assertTrue(s.isStale("a.txt")) // …so the stale flag was NOT cleared
        assertNull(s.loadError)
        assertNull(s.loadingPath)
        // …and the marker was CONSUMED: a fresh open of the same path loads normally.
        s.open("a.txt")
        advanceUntilIdle()
        assertNotNull(s.get("a.txt"))
    }

    // ── the `current` gating handed to the host (last-opened wins, plus the null fallback) ────

    @Test fun overlapping_cross_path_opens_report_only_the_last_opened_as_current() = runTest {
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val s = store(
            scope = this,
            fsRead = { path ->
                when (path) { "a.kt" -> gateA.await(); "b.kt" -> gateB.await() }
                Result.success("body:$path")
            },
        )
        val opened = Opened().also { it.install(s) }
        s.open("a.kt") // slow
        s.open("b.kt") // opened last → owns the gate

        gateB.complete(Unit)
        advanceUntilIdle()
        gateA.complete(Unit) // A returns out of open order
        advanceUntilIdle()

        // B is the only one reported current; A's late completion still yields a document (so the
        // host can show its tab) but must not claim the active slot.
        assertEquals(listOf("b.kt" to true, "a.kt" to false), opened.events)
        assertNotNull(s.get("a.kt"))
        assertNull(s.loadingPath)
    }

    // ── (B) reveal nonce: a superseded pending-reveal poll is dropped ──────────────────────────

    @Test fun open_at_line_sets_the_reveal_immediately_when_the_document_is_already_open() {
        val s = store()
        s.open("a.kt")

        s.openAtLine("a.kt", 42, 44)

        assertEquals(42 to 44, s.get("a.kt")?.revealLine)
    }

    @Test fun open_at_line_with_a_null_line_opens_without_a_reveal() {
        val s = store()
        s.openAtLine("a.kt", null, null)

        assertNotNull(s.get("a.kt"))
        assertNull(s.get("a.kt")?.revealLine)
    }

    @Test fun open_at_line_drops_a_superseded_reveal_and_applies_the_newest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this, fsRead = { gate.await(); Result.success("body:$it") })

        s.openAtLine("a.kt", 10, null) // poll (nonce 1) waits for a.kt
        s.openAtLine("a.kt", 99, null) // supersedes: nonce 2 (open no-ops via the in-flight guard)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(99 to null, s.get("a.kt")?.revealLine) // newest wins, nonce-1 reveal dropped
    }

    @Test fun open_at_line_poll_gives_up_after_1s_and_never_lands_on_a_later_document() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this, fsRead = { gate.await(); Result.success("body:$it") })

        s.openAtLine("a.kt", 7, null)
        advanceUntilIdle() // 50 × 20ms of virtual time elapse with no document → the poll gives up

        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(s.get("a.kt"))          // the read still lands…
        assertNull(s.get("a.kt")?.revealLine) // …but the abandoned poll never revealed on it
    }

    // ── save / update / reload basics on the extracted store ──────────────────────────────────

    @Test fun save_writes_the_document_and_clears_dirty_and_guards_re_entry() = runTest {
        var writes = 0
        val gate = CompletableDeferred<Unit>()
        val s = store(scope = this, fsWrite = { _, _ -> writes++; gate.await(); true })
        s.open("a.txt")
        advanceUntilIdle()
        s.update("a.txt", "edited")
        assertTrue(s.isDirty("a.txt"))

        val doc = s.get("a.txt")!!
        s.save(doc)
        advanceUntilIdle()
        assertTrue(s.saving)
        s.save(doc) // re-entrant call while saving == true → no second write
        advanceUntilIdle()
        assertEquals(1, writes)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(s.saving)
        assertFalse(s.isDirty("a.txt"))
        assertEquals("edited", doc.savedContent)
    }

    @Test fun reload_refreshes_the_document_and_clears_its_stale_flag() = runTest {
        val s = store(scope = this)
        s.open("a.txt")
        advanceUntilIdle()
        s.update("a.txt", "local edit")
        s.markChanged(listOf("/a.txt")) // leading slash is normalized away
        assertTrue(s.isStale("a.txt"))

        s.reload("a.txt") { Result.success("fresh") }

        assertEquals("fresh", s.get("a.txt")?.content)
        assertEquals("fresh", s.get("a.txt")?.savedContent)
        assertFalse(s.isDirty("a.txt"))
        assertFalse(s.isStale("a.txt"))
        assertNull(s.loadingPath)
    }

    @Test fun reload_only_clears_its_own_loading_gate() = runTest {
        val gateReload = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        val s = store(
            scope = this,
            fsRead = { path -> if (path == "b.kt") gateB.await(); Result.success("body:$path") },
        )
        s.open("a.txt")
        advanceUntilIdle()

        val job = launch { s.reload("a.txt") { gateReload.await(); Result.success("fresh") } }
        advanceUntilIdle()
        s.open("b.kt") // takes over the gate
        assertEquals("b.kt", s.loadingPath)

        gateReload.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals("b.kt", s.loadingPath) // the reload must not stomp b.kt's gate
        assertEquals("fresh", s.get("a.txt")?.content)

        gateB.complete(Unit)
        advanceUntilIdle()
        assertNull(s.loadingPath)
    }

    @Test fun reload_is_a_no_op_for_a_path_with_no_open_document() = runTest {
        val s = store(scope = this)
        s.reload("never-opened.txt") { Result.success("body") }
        assertNull(s.get("never-opened.txt"))
        assertNull(s.loadingPath)
    }

    @Test fun is_dirty_is_false_for_a_path_with_no_open_document() {
        val s = store()
        assertFalse(s.isDirty("never-opened.txt"))
    }

    @Test fun close_on_an_unknown_path_is_a_no_op() {
        val s = store()
        s.open("a.txt")
        s.close("never-opened.txt")
        assertNotNull(s.get("a.txt"))
        assertNull(s.loadingPath)
    }
}
