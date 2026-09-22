package dev.supermux.state

import dev.supermux.state.HostStore
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.PairingPayload
import dev.supermux.net.HostIdentity
import dev.supermux.net.PairClaimResult
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [FleetStore] add-host + merge tests, driven WITHOUT a live broker via the injectable claim/probe
 * seams and an appFactory that builds `connectOnInit = false` [HostStore]s (no sockets).
 * Mirrors the intent of Android AppViewModel's addHost/addHostByUrl identity-mismatch guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreTest {

    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private var idSeq = 0
    private fun store(vararg h: PairedHost) =
        PairedHostStore(FakePersistence(h.toMutableList())) { "rec-${idSeq++}" }

    /** A FleetStore whose per-host apps never open a socket, with injectable claim/probe. */
    private fun fleet(
        store: PairedHostStore,
        scope: TestScope,
        claim: (suspend (String, String, String) -> PairClaimResult?)? = null,
        probe: (suspend (String) -> HostIdentity?)? = null,
    ) = FleetStore(
        store = store,
        scope = scope,
        deps = testDeps(),
        appFactory = { url, token, onConn -> HostStore(url, token, scope, testDeps(), connectOnInit = false, onConnectionChange = onConn) },
        claimOverride = claim,
        hostProbeOverride = probe,
    )

    private fun payload(hostId: String) = PairingPayload(
        v = 1, action = "pair", hostId = hostId, name = "New Box",
        relayUrl = "https://h-$hostId.relay.supermux.dev", claimSecret = "s3cret",
    )

    @Test fun addHost_persistsWhenClaimedIdentityMatches() = runTest {
        val s = store()
        val f = fleet(s, this, claim = { _, _, _ -> PairClaimResult(host = HostIdentity(hostId = "habc", name = "Box"), deviceToken = "tok") })
        val res = f.addHost(payload("habc"), "my-laptop")
        assertTrue(res is AddHostResult.Added, "expected Added, got $res")
        assertEquals(1, s.list().size)
        assertEquals("habc", s.list()[0].hostId)
        assertEquals("tok", s.list()[0].token)
        f.close()
    }

    @Test fun addHost_abortsOnHostIdMismatch() = runTest {
        val s = store()
        val f = fleet(s, this, claim = { _, _, _ -> PairClaimResult(host = HostIdentity(hostId = "EVIL"), deviceToken = "tok") })
        val res = f.addHost(payload("habc"), "my-laptop")
        assertTrue(res is AddHostResult.Error, "a returned hostId ≠ the scanned one must abort")
        assertTrue(s.list().isEmpty(), "nothing must be persisted on an identity mismatch")
        f.close()
    }

    @Test fun addHost_errorsWhenClaimReturnsNoToken() = runTest {
        val s = store()
        val f = fleet(s, this, claim = { _, _, _ -> PairClaimResult(host = HostIdentity(hostId = "habc"), deviceToken = "") })
        assertTrue(f.addHost(payload("habc"), "x") is AddHostResult.Error)
        assertTrue(s.list().isEmpty())
        f.close()
    }

    @Test fun addHostByUrl_needsClaimWhenSecretlessClaimFails() = runTest {
        val s = store()
        val f = fleet(s, this,
            probe = { HostIdentity(hostId = "habc", name = "Tailnet Mac") },
            claim = { _, _, _ -> null }, // already set up → no secretless claim
        )
        val res = f.addHostByUrl("my-mac.tailnet.ts.net", "laptop")
        assertTrue(res is AddHostResult.NeedsClaim, "expected NeedsClaim, got $res")
        assertTrue(s.list().isEmpty())
        f.close()
    }

    @Test fun addHostByUrl_rejectsNonHostUrl() = runTest {
        val s = store()
        val f = fleet(s, this, probe = { null }) // GET /host fails → not a supermux host
        assertTrue(f.addHostByUrl("https://example.com", "laptop") is AddHostResult.Error)
        f.close()
    }

    @Test fun mergedSessionsAndOwnerReflectBothHosts() = runTest(UnconfinedTestDispatcher()) {
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val f = fleet(s, this)
        // Drive each host's HostStore reducer directly (no socket) → its sessions flow emits →
        // FleetStore folds them into the merged list.
        f.appForRecord("h1")!!.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s1", name = "s1", workdir = "/w", agent = "claude"))))
        f.appForRecord("h2")!!.reduce(ServerFrame.Snapshot(sessions = listOf(SessionInfo(id = "s2", name = "s2", workdir = "/w", agent = "claude"))))
        assertEquals(setOf("s1", "s2"), f.sessions.value.map { it.id }.toSet())
        assertEquals("h1", f.sessionHost.value["s1"])
        assertEquals("h2", f.sessionHost.value["s2"])
        assertEquals("h1", f.appFor("s1")?.let { app -> if (app === f.appForRecord("h1")) "h1" else "?" })
        f.close()
    }

    @Test fun forgetHostDropsItAndRebuildsActive() = runTest(UnconfinedTestDispatcher()) {
        val s = store(
            PairedHost(recordId = "h1", displayName = "A", token = "t", relayUrl = "https://h-a.relay.supermux.dev"),
            PairedHost(recordId = "h2", displayName = "B", token = "t", relayUrl = "https://h-b.relay.supermux.dev"),
        )
        val f = fleet(s, this)
        f.setActiveHost("h1")
        f.forgetHost("h1")
        assertEquals(listOf("h2"), s.list().map { it.recordId })
        assertEquals("h2", f.activeHost.value)
        assertTrue(f.hostViews.value.map { it.recordId } == listOf("h2"))
        f.close()
    }

    /**
     * The browser host authenticates with an HttpOnly cookie, so its record carries NO bearer
     * token — `ambientAuth` says the transport already carries the credential. `sync` must dial it
     * anyway, while a plain blank-token record still means "not configured yet" and is skipped.
     */
    @Test fun syncDialsAnAmbientAuthHostWithoutAToken_butSkipsABlankTokenOne() = runTest {
        val dialed = mutableListOf<Pair<String, String>>()
        val s = store(
            PairedHost(recordId = "web", displayName = "Browser", token = "", ambientAuth = true,
                directUrl = "https://box.example"),
            PairedHost(recordId = "unset", displayName = "Unconfigured", token = "",
                directUrl = "https://other.example"),
        )
        val f = FleetStore(
            store = s,
            scope = this,
            deps = testDeps(),
            appFactory = { url, token, onConn ->
                dialed += url to token
                HostStore(url, token, this@runTest, testDeps(), connectOnInit = false, onConnectionChange = onConn)
            },
        )
        assertEquals(listOf("https://box.example" to ""), dialed,
            "only the ambient-credential host may be dialled with a blank token")
        assertTrue(f.appForRecord("unset") == null, "a blank-token, non-ambient host stays undialled")
        f.close()
    }
}
