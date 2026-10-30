package dev.supermux.webview.engine.linux

import dev.supermux.webview.engine.webkit.NavigationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WpeEventTest {
    @Test fun aPolicyCarriesItsKindAndBits() {
        val b = (WpeNative.NAV_BACK_FORWARD.toLong() shl 4) or WpeNative.POLICY_GESTURE
        assertEquals(
            WpeEvent.Policy(7, "https://a.example/", NavigationKind.BackForward, newWindow = false, gesture = true, redirect = false),
            WpeEvent.decode(WpeNative.POLICY, 7, b, "https://a.example/", null),
        )
        val window = WpeEvent.decode(WpeNative.POLICY, 8, WpeNative.POLICY_NEW_WINDOW or (WpeNative.NAV_LINK.toLong() shl 4), "u", "_blank")
        assertEquals(WpeEvent.Policy(8, "u", NavigationKind.Link, newWindow = true, gesture = false, redirect = false), window)
    }

    @Test fun aResponseCarriesFrameStatusAndType() {
        val b = WpeNative.RESPONSE_MAIN or WpeNative.RESPONSE_ATTACHMENT or (404L shl 8)
        assertEquals(
            WpeEvent.Response(3, "https://a.example/f", mainFrame = true, canShow = false, attachment = true, status = 404, mime = "text/plain", filename = "f.txt"),
            WpeEvent.decode(WpeNative.RESPONSE, 3, b, "https://a.example/f", "text/plain\nf.txt"),
        )
        assertEquals(null, (WpeEvent.decode(WpeNative.RESPONSE, 3, 0, "u", "\n") as WpeEvent.Response).mime)
    }

    @Test fun historyListsSplitOnNewlines() {
        assertEquals(
            WpeEvent.History(canGoBack = true, canGoForward = false, back = listOf("webview-floor://reset/", "https://a/"), forward = emptyList()),
            WpeEvent.decode(WpeNative.HISTORY, 1, 0, "webview-floor://reset/\nhttps://a/", ""),
        )
    }

    @Test fun failuresAndTheRest() {
        assertEquals(
            WpeEvent.LoadFailed(302, cancelled = true, tls = false, policy = false, cannotShow = false, uri = "u", message = "m"),
            WpeEvent.decode(WpeNative.LOAD_FAILED, 302, WpeNative.FAIL_CANCELLED, "u", "m"),
        )
        assertEquals(WpeEvent.ImeCursor(10, 20, 3, 18), WpeEvent.decode(WpeNative.IME_CURSOR, (10L shl 32) or 20L, (3L shl 32) or 18L, null, null))
        assertEquals(WpeEvent.Progress(0.25f), WpeEvent.decode(WpeNative.PROGRESS, 250, 0, null, null))
        assertEquals(WpeEvent.Eval(5, ok = false, json = null), WpeEvent.decode(WpeNative.EVAL, 5, 0, null, "boom"))
        assertNull(WpeEvent.decode(999, 0, 0, null, null))
    }

    @Test fun thePagesRequests() {
        assertEquals(
            WpeEvent.Dialog(3, WpeNative.DIALOG_PROMPT, "Name?", "x"),
            WpeEvent.decode(WpeNative.DIALOG, 3, WpeNative.DIALOG_PROMPT.toLong(), "Name?", "x"),
        )
        assertEquals(WpeEvent.Permission(4, WpeNative.PERM_CAMERA, "u"), WpeEvent.decode(WpeNative.PERMISSION, 4, WpeNative.PERM_CAMERA, "u", null))
        assertEquals(
            WpeEvent.FileChooser(5, multiple = true, mimeTypes = listOf("image/png", "image/jpeg")),
            WpeEvent.decode(WpeNative.FILE_CHOOSER, 5, 1, "image/png\nimage/jpeg", null),
        )
        assertEquals(WpeEvent.NewWindow("https://b.example/", gesture = true), WpeEvent.decode(WpeNative.NEW_WINDOW, 1, 0, "https://b.example/", null))
    }
}
