package dev.supermux.host

import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sess(id: String, name: String = id) =
    SessionInfo(id = id, name = name, workdir = "/w/$name", agent = "claude")

private fun log(id: String, text: String) =
    LogEntry(id = id, ts = "2026-07-11T00:00:00Z", direction = "in", text = text)

class SessionKeyTest {
    @Test fun composesAndParsesRoundTrip() {
        val k = SessionKey.of("recA", "sess1")
        assertEquals("recA${SessionKey.SEP}sess1", k.asString())
        assertEquals(k, SessionKey.parse(k.asString()))
        assertEquals(SessionKey.key("recA", "sess1"), k.asString())
    }

    @Test fun parseRejectsBareId() {
        assertNull(SessionKey.parse("just-a-bare-session-id"))
    }

    @Test fun sameSessionOnDifferentHostsHasDistinctKeys() {
        // The crux: one broker-local sessionId, two hosts → two DIFFERENT composite identities.
        assertNotEquals(SessionKey.key("recA", "S"), SessionKey.key("recB", "S"))
    }

    // ── The headline requirement: two hosts with the SAME sessionId both appear + route independently ──

    @Test fun collidingSessionsBothAppearAsDistinctRows() {
        val byHost = mapOf("recA" to listOf(sess("S", "A-side")), "recB" to listOf(sess("S", "B-side")))
        val rows = mergeFleetRows(order = listOf("recA", "recB"), sessionsByHost = byHost)
        assertEquals(2, rows.size)                                   // neither hides the other
        assertEquals(listOf("recA", "recB"), rows.map { it.recordId })
        assertEquals(
            listOf(SessionKey.key("recA", "S"), SessionKey.key("recB", "S")),
            rows.map { it.key },                                     // distinct host-qualified identities
        )
        assertEquals(listOf("A-side", "B-side"), rows.map { it.session.name })
    }

    @Test fun collidingSessionsOwnerIsFirstHostInOrder() {
        val byHost = mapOf("recA" to listOf(sess("S")), "recB" to listOf(sess("S")))
        val ownersAB = fleetOwners(mergeFleetRows(listOf("recA", "recB"), byHost))
        assertEquals("recA", ownersAB["S"])                          // first host owns the bare id
        val ownersBA = fleetOwners(mergeFleetRows(listOf("recB", "recA"), byHost))
        assertEquals("recB", ownersBA["S"])                          // order is authoritative
    }

    @Test fun perSessionStateRoutesIndependentlyByCompositeKey() {
        // Each host's frames land under ITS OWN composite key — never merged into a single bare id.
        val msgByKey = mapOf(
            SessionKey.key("recA", "S") to listOf(log("m1", "hello from A")),
            SessionKey.key("recB", "S") to listOf(log("m2", "hello from B")),
        )
        // From recA's chat (recA owns S), only A's message is surfaced — B's never contaminates it.
        val ownerA = mapOf("S" to "recA")
        assertEquals(listOf("hello from A"), SessionKey.flatten(msgByKey, ownerA)["S"]!!.map { it.text })
        // Flip ownership → recB's chat sees only B's message.
        val ownerB = mapOf("S" to "recB")
        assertEquals(listOf("hello from B"), SessionKey.flatten(msgByKey, ownerB)["S"]!!.map { it.text })
    }

    @Test fun flattenDropsNonOwnerStateEvenWhenOwnerHasNone() {
        // recA owns S but has produced no state yet; recB (collision) has state. The owner's chat must
        // stay EMPTY — B's data must not leak in just because A hasn't spoken. (Anti-contamination.)
        val msgByKey = mapOf(SessionKey.key("recB", "S") to listOf(log("m", "B only")))
        assertNull(SessionKey.flatten(msgByKey, owner = mapOf("S" to "recA"))["S"])
    }

    @Test fun flattenFallsBackToFirstWhenNoOwnerRecorded() {
        val byKey = mapOf(SessionKey.key("recX", "S") to 1, SessionKey.key("recY", "S") to 2)
        assertEquals(1, SessionKey.flatten(byKey, owner = emptyMap())["S"]) // first-seen wins, deterministic
    }

    @Test fun flattenSetReducesToBareIds() {
        val keys = setOf(SessionKey.key("recA", "S1"), SessionKey.key("recB", "S2"))
        assertEquals(setOf("S1", "S2"), SessionKey.flattenSet(keys))
    }

    @Test fun mergedSessionsDedupesByOwnerPreservingOrder() {
        val byHost = mapOf(
            "recA" to listOf(sess("S1"), sess("Scommon")),
            "recB" to listOf(sess("Scommon"), sess("S2")),
        )
        val rows = mergeFleetRows(listOf("recA", "recB"), byHost)
        assertEquals(4, rows.size)                                   // model keeps all four (both appear)
        val merged = mergedSessions(rows)
        assertEquals(listOf("S1", "Scommon", "S2"), merged.map { it.id }) // UI list dedupes to 3, in order
    }

    @Test fun nonCollidingFleetIsUnchangedByHostQualification() {
        // Guard the common case: distinct ids across hosts flatten 1:1, no behavior change.
        val byHost = mapOf("recA" to listOf(sess("a1")), "recB" to listOf(sess("b1")))
        val rows = mergeFleetRows(listOf("recA", "recB"), byHost)
        val owner = fleetOwners(rows)
        val msgByKey = mapOf(
            SessionKey.key("recA", "a1") to listOf(log("x", "A")),
            SessionKey.key("recB", "b1") to listOf(log("y", "B")),
        )
        val flat = SessionKey.flatten(msgByKey, owner)
        assertEquals(setOf("a1", "b1"), flat.keys)
        assertEquals("A", flat["a1"]!!.single().text)
        assertEquals("B", flat["b1"]!!.single().text)
        assertTrue(mergedSessions(rows).map { it.id }.containsAll(listOf("a1", "b1")))
    }
}
