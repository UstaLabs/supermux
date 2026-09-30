package dev.supermux.desktop.host

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.settings.PairQrContent
import dev.supermux.desktop.settings.PairQrCopy
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.PairingPayload
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings ▸ Hosting's pairing seams: trust-on-first-connect is stored at once; "Pair a device…" is side-effect-free. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class HostingPairingTest {
    private class FakePersistence(var hosts: List<PairedHost> = emptyList()) : HostPersistence {
        override fun loadAll() = hosts
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts }
    }

    private var seq = 0
    private fun store(vararg h: PairedHost) = PairedHostStore(FakePersistence(h.toList())) { "rec-${seq++}" }
    private val hostId = "abcdefghijklmnopqrstuvwxyz"

    @Test fun aTofuTokenIsHandedOnBeforeTheSecretIsMinted_evenWhenMintingFails() = runTest {
        val events = mutableListOf<String>()
        val c = DesktopHostBootstrap.mintClaimWith(
            existingToken = null,
            secretless = { events += "secretless"; "tok" },
            mintSecret = { events += "mint:$it"; null },
            relay = { events += "relay"; null },
            onNewToken = { events += "saved:$it" },
        )
        assertNull(c)
        assertEquals(listOf("secretless", "saved:tok", "mint:tok"), events)
    }

    @Test fun anExistingTokenIsNeverReplacedOrSaved() = runTest {
        val events = mutableListOf<String>()
        val c = DesktopHostBootstrap.mintClaimWith(
            existingToken = "old",
            secretless = { events += "secretless"; "tok" },
            mintSecret = { "secret" },
            relay = { "https://h-x.relay.supermux.dev" },
            onNewToken = { events += "saved:$it" },
        )
        assertEquals(HostClaim("old", "secret", "https://h-x.relay.supermux.dev"), c)
        assertTrue(events.isEmpty())
    }

    @Test fun withNoSecretlessPathAndNoTokenNothingIsCalled() = runTest {
        var calls = 0
        val c = DesktopHostBootstrap.mintClaimWith(null, secretless = null, mintSecret = { calls++; "s" }, relay = { calls++; null })
        assertNull(c)
        assertEquals(0, calls)
    }

    @Test fun theTofuSaverStoresThisComputerAndRefreshesTheFleet() {
        val s = store()
        var refreshed = 0
        val save = DesktopHostBootstrap.tofuSaver(s, "Mac", "http://127.0.0.1:9898", { hostId }) { refreshed++ }
        save("tok")
        val rec = s.list().single()
        assertEquals("tok", rec.token)
        assertEquals(hostId, rec.hostId)
        assertEquals("http://127.0.0.1:9898", rec.directUrl)
        assertEquals(1, refreshed)
    }

    @Test fun theTofuSaverWaitsForAHostId() {
        val s = store()
        var refreshed = 0
        DesktopHostBootstrap.tofuSaver(s, "Mac", "http://127.0.0.1:9898", { null }) { refreshed++ }("tok")
        assertTrue(s.list().isEmpty())
        assertEquals(0, refreshed)
    }

    @Test fun thisComputerIsTheHostIdMatchElseALoopbackRecord() {
        val other = PairedHost(recordId = "r1", hostId = "other", displayName = "B", token = "t1", directUrl = "http://10.0.0.2:9898")
        val loop = PairedHost(recordId = "r2", hostId = null, displayName = "A", token = "t2", directUrl = "http://127.0.0.1:9898")
        val mine = PairedHost(recordId = "r3", hostId = hostId, displayName = "C", token = "t3")
        assertEquals("r3", DesktopHostBootstrap.thisComputerRecord(listOf(other, loop, mine), hostId)?.recordId)
        assertEquals("r2", DesktopHostBootstrap.thisComputerRecord(listOf(other, loop), hostId)?.recordId)
        assertNull(DesktopHostBootstrap.thisComputerRecord(listOf(other), hostId))
    }

    @Test fun pairOnlyWithoutATokenMakesNoModelAndNoCall() = runTest {
        var mints = 0
        val m = DesktopHostBootstrap.pairOnlyModel(
            scope = this, hostName = "Mac", token = null, hostId = { hostId }, directUrl = { "http://192.168.1.2:9898" },
            mint = { mints++; null }, qrOf = { ImageBitmap(1, 1) },
        )
        assertNull(m)
        assertEquals(0, mints)
    }

    @Test fun pairOnlyMintsWithTheTokenAndPutsTheLanAddressInTheQr() = runTest(UnconfinedTestDispatcher()) {
        val minted = mutableListOf<String>()
        val m = assertNotNull(
            DesktopHostBootstrap.pairOnlyModel(
                scope = this, hostName = "Mac", token = "tok", hostId = { hostId }, directUrl = { "http://192.168.1.2:9898" },
                mint = { minted += it; HostClaim(it, "secret") }, qrOf = { ImageBitmap(1, 1) },
            ),
        )
        m.prepare()
        val ready = assertIs<HostWizardUiState.Ready>(m.state.value)
        assertEquals(listOf("tok"), minted)
        val payload = assertNotNull(PairingPayload.parse(ready.payloadJson))
        assertEquals("http://192.168.1.2:9898", payload.directUrl)
        m.finish(keepAlive = false) // a no-op: nothing to re-pair, no keep-alive change
    }

    @Test fun theDialogSaysPairThisComputerFirstWithoutAToken() = runComposeUiTest {
        setContent { PairQrContent(state = null, needsPairing = true, onRefresh = {}, onClose = {}) }
        onNodeWithTag("hosting_pair_needs_pairing").assertTextEquals(PairQrCopy.NEEDS_PAIRING)
        onNodeWithTag("hosting_pair_qr").assertDoesNotExist()
    }

    @Test fun aReadyCodeSaysWhenItExpiresAndCanBeRefreshed() = runComposeUiTest {
        var refreshes = 0
        setContent {
            PairQrContent(
                state = HostWizardUiState.Ready("{}", ImageBitmap(1, 1), relayEnabled = false),
                needsPairing = false, onRefresh = { refreshes++ }, onClose = {},
            )
        }
        onNodeWithTag("hosting_pair_expires").assertTextEquals(PairQrCopy.EXPIRES)
        onNodeWithTag("hosting_pair_refresh").performClick()
        assertEquals(1, refreshes)
    }
}
