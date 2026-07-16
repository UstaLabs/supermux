package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the framework-free fleet-view helpers (spec §5), the desktop mirror of
 * `apps/android/.../host/FleetModelTest.kt`: deterministic badge colors, compact labels, offline
 * "last seen" buckets, host filtering, and the per-host → merged session fold. No Compose/broker
 * needed — this is the same pure logic every client shares.
 */
class FleetModelTest {

    // ── hostColorIndex ─────────────────────────────────────────────────────────
    @Test fun colorIndex_isDeterministicAndInRange() {
        repeat(200) { i ->
            val seed = "record-$i"
            val a = hostColorIndex(seed)
            val b = hostColorIndex(seed)
            assertEquals(a, b, "same seed must yield the same slot")
            assertTrue(a in 0 until HOST_PALETTE_SIZE, "slot $a out of palette range")
        }
    }

    @Test fun colorIndex_spreadsAcrossPalette() {
        val distinct = (0 until 8).map { hostColorIndex("record-$it") }.toSet()
        assertTrue(distinct.size >= 4, "expected variety across the palette, got $distinct")
    }

    @Test fun colorIndex_emptySeed_isZero() {
        assertEquals(0, hostColorIndex(""))
    }

    // ── hostShortLabel ─────────────────────────────────────────────────────────
    @Test fun shortLabel_firstTokenCapped() {
        assertEquals("MacBook", hostShortLabel("MacBook"))
        assertEquals("Host", hostShortLabel("This host"))
        assertEquals("Ahmet's", hostShortLabel("This computer (Ahmet's MacBook Air)"))
        assertEquals("Ahmet-MBP", hostShortLabel("Ahmet-MBP"))
        assertEquals("host", hostShortLabel("   "))
        assertEquals("aaaaaaaaaaaaaa", hostShortLabel("aaaaaaaaaaaaaaaaaaaa")) // capped at 14
    }

    // ── formatLastSeen ─────────────────────────────────────────────────────────
    @Test fun lastSeen_buckets() {
        val now = 1_000_000_000_000L
        assertEquals("", formatLastSeen(now, 0L))
        assertEquals("just now", formatLastSeen(now, now - 5_000L))
        assertEquals("5m ago", formatLastSeen(now, now - 5 * 60_000L))
        assertEquals("2h ago", formatLastSeen(now, now - 2 * 3_600_000L))
        assertEquals("3d ago", formatLastSeen(now, now - 3 * 86_400_000L))
    }

    @Test fun lastSeen_futureClampsToJustNow() {
        val now = 1_000L
        assertEquals("just now", formatLastSeen(now, now + 10_000L))
    }

    // ── HostView derivations ────────────────────────────────────────────────────
    @Test fun hostView_prefersHostIdForColorStability() {
        val a = HostView("record-A", "habc", "MacBook", online = true)
        val b = HostView("record-B", "habc", "MacBook", online = false)
        assertEquals(a.colorIndex, b.colorIndex)
        assertEquals("MacBook", a.shortLabel)
    }

    // ── filterSessions ──────────────────────────────────────────────────────────
    private fun s(id: String) = SessionInfo(id = id, name = id, workdir = "/w", agent = "claude")

    @Test fun filter_nullReturnsAll() {
        val sessions = listOf(s("a"), s("b"))
        assertEquals(sessions, filterSessions(sessions, mapOf("a" to "h1", "b" to "h2"), null))
    }

    @Test fun filter_byRecordId() {
        val sessions = listOf(s("a"), s("b"), s("c"))
        val owner = mapOf("a" to "h1", "b" to "h2", "c" to "h1")
        assertEquals(listOf("a", "c"), filterSessions(sessions, owner, "h1").map { it.id })
        assertEquals(listOf("b"), filterSessions(sessions, owner, "h2").map { it.id })
    }

    @Test fun filter_unknownHostFallsBackToAll() {
        // A filter for a host no session belongs to (forgotten while selected) must not blank the
        // list — fall back to All rather than showing nothing.
        val sessions = listOf(s("a"), s("b"))
        assertEquals(sessions, filterSessions(sessions, mapOf("a" to "h1", "b" to "h1"), "gone"))
    }

    // ── mergeSessions (per-host buckets → one merged list + owner map, store order) ──
    @Test fun merge_flattensInStoreOrderAndTagsOwner() {
        val merged = mergeSessions(
            order = listOf("h1", "h2"),
            sessionsByHost = mapOf("h2" to listOf(s("b")), "h1" to listOf(s("a"))),
        )
        // Store order (h1 then h2) wins over the map's iteration order.
        assertEquals(listOf("a", "b"), merged.sessions.map { it.id })
        assertEquals(mapOf("a" to "h1", "b" to "h2"), merged.sessionHost)
    }

    @Test fun merge_dedupesGloballyUniqueIdKeepingFirstOwner() {
        // A session id seen under two hosts (shouldn't happen, but must not double-render): the
        // first host in store order owns it.
        val merged = mergeSessions(
            order = listOf("h1", "h2"),
            sessionsByHost = mapOf("h1" to listOf(s("dup")), "h2" to listOf(s("dup"), s("b"))),
        )
        assertEquals(listOf("dup", "b"), merged.sessions.map { it.id })
        assertEquals("h1", merged.sessionHost["dup"])
    }

    @Test fun merge_includesHostsNotInOrderAfterOrdered() {
        // A bucket whose host isn't in the store order (just-forgotten, cached) still surfaces,
        // after the ordered hosts.
        val merged = mergeSessions(
            order = listOf("h1"),
            sessionsByHost = mapOf("h1" to listOf(s("a")), "hX" to listOf(s("z"))),
        )
        assertEquals(listOf("a", "z"), merged.sessions.map { it.id })
    }

    // ── hostViewsFrom (store + online map → the fleet as the chips render it) ──
    @Test fun hostViews_deriveOnlineAndLastSeenFromStoreAndLiveMap() {
        val hosts = listOf(
            PairedHost(recordId = "h1", hostId = "a", displayName = "Mac", token = "t", lastSeenAt = 1_000L),
            PairedHost(recordId = "h2", hostId = "b", displayName = "Pi", token = "t", lastSeenAt = 2_000L),
        )
        val views = hostViewsFrom(hosts, mapOf("h1" to true))
        assertEquals(listOf("h1", "h2"), views.map { it.recordId })
        assertTrue(views[0].online)
        assertTrue(!views[1].online)
        assertEquals(1_000L, views[0].lastSeenAt)
    }
}
