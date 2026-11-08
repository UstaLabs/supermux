package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals

class LinkRoutingTest {
    private val tabs = listOf(OpenBrowserTab("t1", "https://vitejs.dev/guide/"), OpenBrowserTab("t2", "http://localhost:5173/login"))

    @Test fun aLinkOpensInANewBrowserTabUnlessATabShowsThatSameLink() {
        assertEquals(LinkRoute.NewTab("https://github.com/UstaLabs/supermux/pull/412"), routeLink("https://github.com/UstaLabs/supermux/pull/412", tabs))
        assertEquals(LinkRoute.Focus("t1"), routeLink("https://vitejs.dev/guide/", tabs))
        assertEquals(LinkRoute.Focus("t1"), routeLink("https://VITEJS.dev/guide/", tabs), "the host's case doesn't make another link")
        assertEquals(LinkRoute.NewTab("https://vitejs.dev/guide/#config"), routeLink("https://vitejs.dev/guide/#config", tabs), "another place on the page is another link")
        assertEquals(LinkRoute.Focus("t2"), routeLink("http://localhost:5173/login", tabs))
    }

    @Test fun aModifiedClickMailAndPhoneGoToTheSystem() {
        assertEquals(LinkRoute.System("https://vitejs.dev/guide/"), routeLink("https://vitejs.dev/guide/", tabs, modified = true))
        assertEquals(LinkRoute.System("mailto:a@b.test"), routeLink("mailto:a@b.test", tabs))
        assertEquals(LinkRoute.System("tel:+905550000000"), routeLink("tel:+905550000000", tabs))
    }

    @Test fun aLinkThatMustNeverOpenIsRefused() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "data:text/html,x", "supermux://pair", "#top", "/relative"))
            assertEquals(LinkRoute.Refused, routeLink(url, tabs), "for $url")
    }
}
