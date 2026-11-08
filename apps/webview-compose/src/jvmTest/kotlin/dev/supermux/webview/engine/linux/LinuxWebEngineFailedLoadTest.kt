package dev.supermux.webview.engine.linux

import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * iOS's failed-load rules on WPE (final review, item 3): a retry of content that never committed loads it again, such
 * content saves no state, and the engine's own navigation that the rules refuse is an error. WPE doesn't say which frame
 * a navigation is for at its action (W4): the main frame's load-failed says the refusal was the main frame's.
 */
class LinuxWebEngineFailedLoadTest {
    private val offline = WebContent.Url("https://offline.example/")

    /** The view's calls that start a document: loads, WebKit's reload, restores. */
    private fun FakeWpeView.starts(): List<String> =
        calls.filter { it.startsWith("loadUri") || it.startsWith("loadHtml") || it == "navigate ${WpeNative.GO_RELOAD}" || it.startsWith("restoreState") }

    private fun failed(uri: String?, policy: Boolean = false, cancelled: Boolean = false) =
        WpeEvent.LoadFailed(if (policy) 102 else 1, cancelled = cancelled, tls = false, policy = policy, cannotShow = false, uri = uri, message = "failed")

    /** [offline] loaded, and failed before anything of it committed, as an app started offline sees it. */
    private fun EngineRig.failedFirstLoad() {
        engine.load(offline)
        engine.onEvent(WpeEvent.Policy(20, offline.url, NavigationKind.Other, false, false, false))
        engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, offline.url))
        engine.onEvent(failed(offline.url))
    }

    @Test fun aRetryOfContentThatNeverCommittedLoadsItAgain() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.client.navigation = NavigationDecision.Block
        rig.failedFirstLoad()
        assertEquals(listOf<WebError>(WebError.Network(offline.url, "failed")), rig.events.filterIsInstance<EngineEvent.Error>().map { it.error })
        rig.view.calls.clear()
        rig.engine.reload()
        assertEquals(listOf("loadUri ${offline.url}"), rig.view.starts(), "WebKit's reload would reload the floor, the committed document")
        rig.engine.onEvent(WpeEvent.Policy(21, offline.url, NavigationKind.Other, false, false, false))
        assertEquals(WpeNative.USE, rig.view.decisions[21], "the engine's own load")
        assertTrue(rig.client.asked.isEmpty(), "${rig.client.asked}")
        rig.close()
    }

    @Test fun aRetryOfAPageThatCommittedIsWebKitsReload() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(offline, offline.url)
        rig.view.calls.clear()
        rig.engine.reload()
        assertEquals(listOf("navigate ${WpeNative.GO_RELOAD}"), rig.view.starts())
        rig.close()
    }

    @Test fun contentThatNeverCommittedSavesNoState() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.failedFirstLoad()
        rig.view.savedState = "the floor's history".encodeToByteArray()
        assertNull(rig.engine.saveState(), "WebKit's state is the floor's: restored, it would show nothing of the content")
        rig.close()
    }

    @Test fun aPageThatCommittedSavesItsStateUnderItsKey() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(offline, offline.url)
        rig.view.savedState = "its history".encodeToByteArray()
        val saved = assertNotNull(rig.engine.saveState())
        assertEquals("its history", SavedWebKitState.decode(saved, offline.key)?.decodeToString())
        rig.close()
    }

    /** A content another load replaced before it committed saves nothing either: WebKit still holds the page before. */
    @Test fun contentStillLoadingSavesTheStateOfNoOtherPage() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(offline, offline.url)
        rig.engine.load(WebContent.Url("https://next.example/"))
        rig.view.savedState = "the last page's history".encodeToByteArray()
        assertNull(rig.engine.saveState())
        rig.close()
    }

    /** A retry of a restore that never committed restores it again; one WebKit refuses then loads the content. */
    @Test fun aRetryOfARestoreThatNeverCommittedRestoresItAgain() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val state = SavedWebKitState.encode(offline.key, "state".encodeToByteArray())
        assertTrue(rig.engine.restoreState(state, offline))
        rig.engine.onEvent(WpeEvent.Policy(30, offline.url, NavigationKind.BackForward, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, offline.url))
        rig.engine.onEvent(failed(offline.url))
        rig.view.calls.clear()
        rig.engine.reload()
        assertEquals(listOf("restoreState state"), rig.view.starts())
        rig.engine.onEvent(failed(offline.url))
        rig.view.restoreAccepts = false
        rig.view.calls.clear()
        rig.engine.reload()
        assertEquals(listOf("restoreState state", "loadUri ${offline.url}"), rig.view.starts())
        rig.close()
    }

    /**
     * The engine's own navigation refused by the rules (WebKit's reload of the floor, say) is an error, never silence:
     * once the main frame's load says it ended by policy, which no other frame's refusal makes it say.
     */
    @Test fun anOwnNavigationTheRulesRefuseIsAnError() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(offline, offline.url)
        rig.engine.reload()
        rig.engine.onEvent(WpeEvent.Policy(30, WebKitUrls.FLOOR_URL, NavigationKind.Reload, false, false, false))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[30])
        rig.engine.onEvent(failed(WebKitUrls.FLOOR_URL, policy = true))
        assertEquals(listOf<WebError>(WebError.BlockedByPolicy(offline.url)), rig.events.filterIsInstance<EngineEvent.Error>().map { it.error })
        // A retry loads the content again: WebKit's committed document is the floor's.
        rig.view.calls.clear()
        rig.engine.reload()
        assertEquals(listOf("loadUri ${offline.url}"), rig.view.starts())
        rig.close()
    }

    /**
     * A refusal WPE can't tie to the main frame at the action, an old document's iframe sent to the floor while the
     * engine's own load is under way, ends no load: no main-frame load fails for it, and the load stays the engine's.
     */
    @Test fun anotherFramesRefusedNavigationToTheFloorEndsNoOwnLoad() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        rig.show(WebContent.Url("https://a.example/"), "https://a.example/")
        rig.client.navigation = NavigationDecision.Block
        rig.engine.load(offline)
        rig.engine.onEvent(WpeEvent.Policy(40, WebKitUrls.FLOOR_URL, NavigationKind.Other, false, false, false))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[40])
        rig.engine.onEvent(WpeEvent.Policy(41, offline.url, NavigationKind.Other, false, false, false))
        assertEquals(WpeNative.USE, rig.view.decisions[41], "still the engine's own load")
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, offline.url))
        rig.engine.onEvent(WpeEvent.Response(42, offline.url, mainFrame = true, canShow = true, attachment = false, status = 200, mime = "text/html", filename = null))
        assertEquals(WpeNative.USE, rig.view.decisions[42])
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_COMMITTED, offline.url))
        assertTrue(rig.events.none { it is EngineEvent.Error }, "${rig.events}")
        assertContains(rig.events, EngineEvent.UrlChanged(offline.url))
        rig.close()
    }

    /** A state whose page is the floor restores nothing: the content loads instead, as on iOS, with no error to show. */
    @Test fun aRestoreWhosePageIsTheFloorLoadsTheContentInstead() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val state = SavedWebKitState.encode(offline.key, "the floor's history".encodeToByteArray())
        assertTrue(rig.engine.restoreState(state, offline), "WPE's restore is answered before its navigation is decided")
        rig.view.calls.clear()
        rig.engine.onEvent(WpeEvent.Policy(50, WebKitUrls.FLOOR_URL, NavigationKind.BackForward, false, false, false))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[50])
        rig.engine.onEvent(failed(WebKitUrls.FLOOR_URL, policy = true))
        assertEquals(listOf("loadUri ${offline.url}"), rig.view.starts())
        assertTrue(rig.events.none { it is EngineEvent.Error }, "${rig.events}")
        rig.close()
    }

    /** The engine's own load turned into a download is refused by policy too, and is no refusal by the rules. */
    @Test fun aDownloadOfTheEnginesOwnLoadIsNoError() = runTest {
        val rig = EngineRig(this)
        rig.warm()
        val file = WebContent.Url("https://a.example/f.zip")
        rig.engine.load(file)
        rig.engine.onEvent(WpeEvent.Policy(60, file.url, NavigationKind.Other, false, false, false))
        rig.engine.onEvent(WpeEvent.Load(WpeNative.LOAD_STARTED, file.url))
        rig.engine.onEvent(WpeEvent.Response(61, file.url, mainFrame = true, canShow = false, attachment = true, status = 200, mime = "application/zip", filename = "f.zip"))
        assertEquals(WpeNative.IGNORE, rig.view.decisions[61])
        rig.engine.onEvent(failed(file.url, policy = true))
        assertTrue(rig.events.none { it is EngineEvent.Error }, "${rig.events}")
        assertContains(rig.client.asked, DownloadRequest(file.url, "f.zip", "application/zip", null))
        rig.close()
    }
}
