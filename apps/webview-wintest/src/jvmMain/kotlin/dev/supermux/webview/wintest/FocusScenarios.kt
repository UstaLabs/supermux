package dev.supermux.webview.wintest

import java.awt.Point
import javax.swing.JFrame
import kotlinx.coroutines.delay

/**
 * The main risk (spec §7): keyboard focus moving between AWT/Compose and WebView2's own window, by click, by Tab, across
 * window activation, and with shortcuts. Run once per configuration of the matrix (wv2.parent, wv2.focusDefer,
 * wv2.returnFocus).
 */
suspend fun Harness.focus() {
    requireFrames()
    web.call("animate", "off")
    onUi {
        app.fieldA = ""
        app.fieldB = ""
    }
    web.call("setValue", "in1", "")
    val in1 = pageRectOnScreen("in1")
    val a = windowRectOnScreen(app.fieldAInWindow)
    val b = windowRectOnScreen(app.fieldBInWindow)

    // F1: click into the page and type.
    click(in1)
    delay(400)
    val osFocus1 = WinNative.focusInfo()
    val awtFocused1 = onUi { app.window.isFocused }
    typeAscii("abc")
    delay(300)
    val page1 = web.call("value", "in1").str()
    report.check(
        "F1-click-page-then-type", page1 == "abc" && app.fieldA.isEmpty() && app.fieldB.isEmpty(),
        "page" to page1, "a" to app.fieldA, "page_focus_event" to web.webFocused, "os_focus" to osFocus1,
        "awt_window_focused" to awtFocused1,
    )

    // F2: click back into Compose and type.
    click(a)
    delay(400)
    val osFocus2 = WinNative.focusInfo()
    typeAscii("xyz")
    delay(300)
    val page2 = web.call("value", "in1").str()
    report.check(
        "F2-click-compose-then-type", app.fieldA == "xyz" && page2 == "abc" && !web.webFocused,
        "a" to app.fieldA, "page" to page2, "page_focus_event" to web.webFocused, "os_focus" to osFocus2,
    )

    // F3: twenty round trips, one key each way.
    repeat(20) {
        click(in1)
        delay(250)
        typeAscii("q")
        delay(120)
        click(a)
        delay(250)
        typeAscii("w")
        delay(120)
    }
    delay(300)
    val page3 = web.call("value", "in1").str()
    report.check(
        "F3-twenty-round-trips", page3 == "abc" + "q".repeat(20) && app.fieldA == "xyz" + "w".repeat(20),
        "page_q" to page3.count { it == 'q' }, "page_w" to page3.count { it == 'w' },
        "a_w" to app.fieldA.count { it == 'w' }, "a_q" to app.fieldA.count { it == 'q' },
    )

    // F4: Tab into the page, through it and out to Compose B; then Shift+Tab back in and out to Compose A.
    click(a)
    delay(300)
    key(Vk.TAB)
    delay(500)
    val entered = web.webFocused
    val firstId = web.call("focusId").str()
    typeAscii("t")
    delay(200)
    report.check(
        "F4a-tab-into-page", entered && web.call("value", "in1").str().endsWith("t"),
        "page_focus_event" to entered, "page_element" to firstId,
    )
    var tabs = 0
    while (tabs < 15 && web.webFocused) {
        key(Vk.TAB)
        delay(250)
        tabs++
    }
    delay(300)
    typeAscii("u")
    delay(200)
    report.check("F4b-tab-out-to-compose-b", !web.webFocused && app.fieldB.endsWith("u"), "tabs" to tabs, "b" to app.fieldB)
    key(Vk.TAB, Vk.SHIFT)
    delay(500)
    val lastId = web.call("focusId").str()
    report.check("F4c-shift-tab-into-page", web.webFocused, "page_element" to lastId)
    var backTabs = 0
    while (backTabs < 15 && web.webFocused) {
        key(Vk.TAB, Vk.SHIFT)
        delay(250)
        backTabs++
    }
    delay(300)
    typeAscii("v")
    delay(200)
    // The 'v' lands at A's caret, which is wherever F3's last click left it, not necessarily at the end.
    report.check(
        "F4d-shift-tab-out-to-compose-a", !web.webFocused && app.fieldA.count { it == 'v' } == 1,
        "tabs" to backTabs, "a" to app.fieldA,
    )

    // F5: another window takes activation; ours comes back by its title bar; the page still has the keys.
    click(in1)
    delay(300)
    typeAscii("d")
    delay(200)
    val decoy = onUi {
        JFrame("wv2 decoy").apply {
            setBounds(app.window.x + app.window.width - 300, app.window.y + 60, 260, 160)
            isVisible = true
        }
    }
    delay(400)
    val decoyPoint = onUi { decoy.locationOnScreen.let { Point(it.x + 130, it.y + 100) } }
    click(decoyPoint.x, decoyPoint.y)
    delay(500)
    activateWindow()
    delay(400)
    typeAscii("e")
    delay(300)
    val page5 = web.call("value", "in1").str()
    report.check(
        "F5-reactivation-keeps-page-focus", page5.endsWith("de"),
        "page" to page5, "a" to app.fieldA, "page_focus_event" to web.webFocused,
    )
    onUi { decoy.dispose() }
    delay(300)

    // F8a: copy in the page, paste into Compose B.
    click(in1)
    delay(300)
    web.call("setValue", "in1", "copyme")
    key(Vk.A, Vk.CONTROL)
    key(Vk.C, Vk.CONTROL)
    delay(300)
    click(b)
    delay(300)
    onUi { app.fieldB = "" }
    key(Vk.V, Vk.CONTROL)
    delay(400)
    report.check("F8a-copy-in-page-paste-in-compose", app.fieldB == "copyme", "b" to app.fieldB)

    // F8b: an app shortcut reaches the app while the page has focus (AcceleratorKeyPressed), and the page never sees it.
    click(in1)
    delay(300)
    val sinceNs = System.nanoTime()
    val since = pageClock()
    key(Vk.K, Vk.CONTROL, Vk.SHIFT)
    delay(400)
    val accelerator = web.events().any {
        it.atNs > sinceNs && it.event.kind == WinNative.EV_ACCELERATOR && it.event.a == Vk.K.toLong() && (it.event.b and 0x800L) != 0L
    }
    val pageSawK = pageEvents(since).any { it.type == "keydown" && it.key.equals("k", ignoreCase = true) }
    report.check("F8b-app-shortcut-while-page-focused", accelerator && !pageSawK, "accelerator" to accelerator, "page_saw_k" to pageSawK)

    // F9: what AWT made of all this (informational); the full log goes next to the report.
    report.note("focus-log", app.focusLog.joinToString("\n"))
    report.info(
        "F9-awt-focus-log",
        "entries" to app.focusLog.size,
        "awt_window_lost_focus" to app.focusLog.count { it.endsWith("awt window lost focus") },
        "force_focus_used" to app.focusLog.count { "forceFocus" in it },
    )
}
