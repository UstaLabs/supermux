package dev.supermux.android.host

import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for the framework-free fleet-view helpers (spec §5): deterministic badge colors,
 * compact labels, offline "last seen" buckets, and host filtering. No Android/Context needed
 * (mirrors [HostMetaCodecTest]).
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
        // Sequential recordIds (the real shape — UUIDs / "record-N") must not collapse to one color;
        // this FNV-1a spreads them across most of the palette (verified: record-0..7 hit all 6 slots).
        val distinct = (0 until 8).map { hostColorIndex("record-$it") }.toSet()
        assertTrue(distinct.size >= 4, "expected variety across the palette, got $distinct")
    }

    @Test fun colorIndex_emptySeed_isZero() {
        assertEquals(0, hostColorIndex(""))
    }

    // ── hostShortLabel ─────────────────────────────────────────────────────────
    @Test fun shortLabel_firstTokenCapped() {
        assertEquals("MacBook", hostShortLabel("MacBook"))
        assertEquals("This", hostShortLabel("This host"))
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
        // Same durable hostId, different recordId (e.g. re-paired) → same color slot.
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
}
