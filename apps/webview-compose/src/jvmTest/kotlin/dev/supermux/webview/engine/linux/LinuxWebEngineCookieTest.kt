package dev.supermux.webview.engine.linux

import dev.supermux.webview.WebCookie
import dev.supermux.webview.WebProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

// #155: a Browser tab's preview key goes into the WPE engine's network session (the Named profile's, shared by its engines).
class LinuxWebEngineCookieTest {
    private val key = WebCookie("cmux_preview", "pv", "https://relay.test/p/", path = "/p/", maxAgeSeconds = 86400)

    @Test fun aCookieGoesToWebKitAsASetCookieForItsUrlAndIsDoneWhenWebKitSaysSo() = runTest {
        val rig = EngineRig(this, WebProfile.Named("supermux-browser"))
        rig.warm()
        var result: Boolean? = null
        rig.scope.launch { result = rig.engine.setCookie(key) }
        val call = rig.view.calls.last { it.startsWith("setCookie") }
        assertEquals("https://relay.test/p/ cmux_preview=pv; Path=/p/; Secure; HttpOnly; SameSite=Lax; Max-Age=86400", call.substringAfter(' ').substringAfter(' '))
        rig.engine.onEvent(WpeEvent.CookieSet(call.split(' ')[1].toLong(), true))
        assertEquals(true, result)
        rig.close()
    }

    @Test fun webKitRefusingTheCookieIsAFalse() = runTest {
        val rig = EngineRig(this, WebProfile.Named("supermux-browser"))
        rig.warm()
        var result: Boolean? = null
        rig.scope.launch { result = rig.engine.setCookie(key) }
        val call = rig.view.calls.last { it.startsWith("setCookie") }
        rig.engine.onEvent(WpeEvent.CookieSet(call.split(' ')[1].toLong(), false))
        assertEquals(false, result)
        rig.close()
    }

    @Test fun aCookieThatCouldAddAnAttributeNeverReachesWebKit() = runTest {
        val rig = EngineRig(this, WebProfile.Named("supermux-browser"))
        rig.warm()
        assertEquals(false, rig.engine.setCookie(WebCookie("a", "b; Domain=evil", "https://relay.test/")))
        assertTrue(rig.view.calls.none { it.startsWith("setCookie") })
        rig.close()
    }
}
