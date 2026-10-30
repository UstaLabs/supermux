package dev.supermux.webview.engine.windows

import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.OwnNavigation
import dev.supermux.webview.engine.linux.RecordingClient
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Wv2NavigationTest {
    private val main = WinGlue.NAV_MAIN_FRAME
    private fun kind(k: Int) = (k + 1) shl 4
    private val newDocument = main or kind(WinGlue.KIND_NEW_DOCUMENT)
    private val client = RecordingClient()
    private val open = NavigationSnapshot(released = false, client = client)

    @Test fun theEnginesOwnLoadIsClaimedOnceAndItsRedirectsGoToTheWebOnly() {
        val own = AtomicReference<OwnNavigation?>(OwnNavigation.Load("https://example.com/"))
        assertEquals(WinGlue.OWN, Wv2Navigation.decide(newDocument, "https://example.com/", open, own))
        assertEquals(OwnNavigation.Redirecting(NavigationKind.Other), own.get())
        // L1: the server's redirect of the same navigation, to the web: followed, not asked.
        assertEquals(WinGlue.OWN, Wv2Navigation.decide(newDocument or WinGlue.NAV_REDIRECT, "https://www.example.com/", open, own))
        // Anywhere else: refused, and the engine ends the load with BlockedByPolicy.
        assertEquals(WinGlue.REFUSE_OWN, Wv2Navigation.decide(newDocument or WinGlue.NAV_REDIRECT, "ms-settings:privacy", open, own))
        assertEquals(emptyList(), client.asked, "no navigation the app started is the client's to decide")
    }

    @Test fun whatThePageStartsIsAskedWithItsGesture() {
        client.navigation = NavigationDecision.Allow
        val own = AtomicReference<OwnNavigation?>(null)
        assertEquals(WinGlue.ALLOW, Wv2Navigation.decide(newDocument or WinGlue.NAV_USER, "https://b.example/", open, own))
        client.navigation = NavigationDecision.Block
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(newDocument, "https://c.example/", open, own))
        assertEquals(
            listOf<Any>(NavigationRequest("https://b.example/", isMainFrame = true, isUserGesture = true),
                NavigationRequest("https://c.example/", isMainFrame = true, isUserGesture = false)),
            client.asked,
        )
    }

    @Test fun aSubframeIsAskedAsOne() {
        client.navigation = NavigationDecision.Allow
        assertEquals(WinGlue.ALLOW, Wv2Navigation.decide(kind(WinGlue.KIND_NEW_DOCUMENT), "https://ads.example/", open, AtomicReference(null)))
        assertEquals(false, (client.asked.single() as NavigationRequest).isMainFrame)
    }

    @Test fun historyAndReloadsArentAsked() {
        val own = AtomicReference<OwnNavigation?>(null)
        assertEquals(WinGlue.ALLOW, Wv2Navigation.decide(main or kind(WinGlue.KIND_BACK_OR_FORWARD), "https://a.example/", open, own))
        assertEquals(WinGlue.ALLOW, Wv2Navigation.decide(main or kind(WinGlue.KIND_RELOAD), "https://a.example/", open, own))
        assertEquals(emptyList(), client.asked)
    }

    @Test fun theFloorIsTheEnginesAlone() {
        client.navigation = NavigationDecision.Allow
        val own = AtomicReference<OwnNavigation?>(null)
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(newDocument or WinGlue.NAV_USER, Wv2Navigation.FLOOR_URL, open, own), "whatever the client says")
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(main or kind(WinGlue.KIND_BACK_OR_FORWARD), Wv2Navigation.FLOOR_URL, open, own), "nor through history")
        own.set(OwnNavigation.Floor)
        assertEquals(WinGlue.OWN, Wv2Navigation.decide(newDocument, Wv2Navigation.FLOOR_URL, open, own))
        assertNull(own.get(), "claimed")
    }

    @Test fun aReleasedEngineOrAFailingClientRefuses() {
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(newDocument, "https://a.example/", NavigationSnapshot(true, client), AtomicReference(null)))
        val throwing = NavigationSnapshot(false, object : dev.supermux.webview.engine.EngineClient by client {
            override fun onNavigation(request: NavigationRequest): NavigationDecision = error("boom")
        })
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(newDocument, "https://a.example/", throwing, AtomicReference(null)))
    }

    @Test fun aLoadThatReplacesTheMarkerMeanwhileIsDecidedAgain() {
        // D9: the EDT's load() sets a new marker between the read and the claim: the claim fails and the decision runs again.
        // (AtomicReference.compareAndSet is final: the race comes in through the claim the decision is given.)
        val own = AtomicReference<OwnNavigation?>(OwnNavigation.Load("https://old.example/"))
        var raced = false
        val claim = { expected: OwnNavigation?, next: OwnNavigation? ->
            if (!raced) {
                raced = true
                own.set(OwnNavigation.Load("https://new.example/"))
                false
            } else {
                own.compareAndSet(expected, next)
            }
        }
        client.navigation = NavigationDecision.Block
        assertEquals(WinGlue.REFUSE, Wv2Navigation.decide(newDocument, "https://old.example/", open, own::get, claim), "no longer the engine's own")
        assertEquals(OwnNavigation.Load("https://new.example/"), own.get())
    }
}
