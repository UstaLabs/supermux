package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserTabSyncTest {
    private fun page(url: String) = BrowserViewState.page(url)!!

    @Test fun aCommittedPageIsWrittenOnceAndAgainWhenItsTitleArrives() {
        val sync = BrowserTabSync()
        assertEquals(BrowserPatch(page("https://vitejs.dev/").toJson(), null), sync.committed("https://vitejs.dev/", null))
        assertNull(sync.committed("https://vitejs.dev/", null), "nothing new")
        assertEquals("Vite", sync.committed("https://vitejs.dev/", "  Vite ")?.title)
        assertNull(sync.committed("https://vitejs.dev/", "Vite"))
    }

    @Test fun aPageTheBrokerWouldRefuseIsNeverWritten() {
        val sync = BrowserTabSync()
        assertNull(sync.committed(null, "x"))
        assertNull(sync.committed("about:blank", "x"))
        assertNull(sync.committed("javascript:alert(1)", "x"))
    }

    @Test fun aLongTitleIsCut() {
        assertEquals(200, BrowserTabSync().committed("https://a.test/", "t".repeat(500))?.title?.length)
    }

    @Test fun aNewTabOpensTheStoredPage() {
        assertEquals("https://vitejs.dev/", BrowserTabSync().toFollow(page("https://vitejs.dev/"), showing = null))
        assertNull(BrowserTabSync().toFollow(BrowserViewState.Empty, showing = null))
    }

    @Test fun theTabsOwnEchoIsNeverFollowedEvenAfterItMovedOn() {
        val sync = BrowserTabSync()
        sync.committed("https://a.test/", null)
        sync.committed("https://b.test/", null)
        // The broker's echo of the first write arrives while the tab already shows the second page.
        assertNull(sync.toFollow(page("https://a.test/"), showing = "https://b.test/"))
    }

    @Test fun anotherClientsPageIsFollowed() {
        val sync = BrowserTabSync()
        sync.committed("https://a.test/", null)
        assertEquals("https://c.test/", sync.toFollow(page("https://c.test/"), showing = "https://a.test/"))
        assertNull(sync.toFollow(page("https://a.test/"), showing = "https://a.test/"), "already there")
        assertNull(sync.toFollow(BrowserViewState.Empty, showing = "https://a.test/"), "a cleared state never unloads a page")
    }
}
