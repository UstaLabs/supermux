package dev.supermux.webview.wintest

import java.awt.event.InputEvent
import kotlinx.coroutines.delay

private const val VK_MENU = 0x12
private const val VK_F10 = 0x79

/**
 * A diagnosis for PU1/PU2, beyond the plan: do WebView2's popups appear at all in visual hosting, or does the click path
 * close them? Each popup is opened three ways: a real click (Robot, so AWT's click-to-focus and our MoveFocus run too),
 * SendMouseInput straight from the test (no AWT click, no MoveFocus), and the keyboard (Alt+Down on the focused select,
 * Shift+F10 for the menu). Every new top-level window, from any process, is listed at 100, 300 and 900 ms.
 */
suspend fun Harness.popups() {
    requireFrames()
    web.call("animate", "off")
    val dpr = web.call("viewport").list()[2].double()
    suspend fun localCentre(id: String): Pair<Int, Int> {
        val r = web.call("rect", id).list().map { it.double() }
        return ((r[0] + r[2] / 2) * dpr).toInt() to ((r[1] + r[3] / 2) * dpr).toInt()
    }

    suspend fun watch(label: String, open: suspend () -> Unit) {
        val before = topWindows(0).map { it.hwnd }.toSet()
        val since = pageClock()
        open()
        val seen = LinkedHashMap<String, TopWindow>()
        val timeline = StringBuilder()
        var waited = 0L
        for (at in listOf(100L, 300L, 900L)) {
            delay(at - waited)
            waited = at
            val now = topWindows(0).filter { it.hwnd !in before }
            now.forEach { seen[it.hwnd] = it }
            timeline.append("${at}ms=${now.size} ")
            if (at == 300L) screenshot("popup-$label")
        }
        val events = pageEvents(since).filter { it.type != "wheel" }
        report.info(
            "POP-$label",
            "windows" to seen.values.joinToString(";") {
                "${it.className}@pid${it.pid}@${it.rect.x},${it.rect.y},${it.rect.width}x${it.rect.height}"
            },
            "timeline" to timeline.toString().trim(),
            "page_events" to events.joinToString(",") { "${it.type}:${it.id}" },
            "page_focus" to web.call("focusId").str(),
            "value" to web.call("value", "sel1").str(),
            "os_focus" to WinNative.focusInfo(),
            "browser_pid" to WinNative.browserPid(),
        )
        key(Vk.ESCAPE)
        delay(500)
        web.call("setValue", "sel1", "alpha")
    }

    // The select.
    watch("select-robot") { click(pageRectOnScreen("sel1")) }
    watch("select-direct") {
        val (x, y) = localCentre("sel1")
        WinNative.mouse(WinNative.MOUSE_MOVE, 0, 0, x, y)
        delay(50)
        WinNative.mouse(WinNative.LEFT_DOWN, WinNative.MK_LBUTTON, 0, x, y)
        delay(40)
        WinNative.mouse(WinNative.LEFT_UP, 0, 0, x, y)
    }
    watch("select-keyboard") {
        web.eval("document.getElementById('sel1').focus()")
        WinNative.moveFocus(WinNative.FOCUS_PROGRAMMATIC)
        delay(300)
        key(Vk.DOWN, VK_MENU)
    }

    // The context menu.
    watch("menu-robot") {
        val hit = pageRectOnScreen("hit")
        click(hit.x + hit.width / 3, hit.y + hit.height / 2, InputEvent.BUTTON3_DOWN_MASK)
    }
    watch("menu-direct") {
        val (x, y) = localCentre("hit")
        WinNative.mouse(WinNative.MOUSE_MOVE, 0, 0, x, y)
        delay(50)
        WinNative.mouse(WinNative.RIGHT_DOWN, WinNative.MK_RBUTTON, 0, x, y)
        delay(40)
        WinNative.mouse(WinNative.RIGHT_UP, 0, 0, x, y)
    }
    watch("menu-keyboard") {
        web.eval("document.getElementById('in1').focus()")
        WinNative.moveFocus(WinNative.FOCUS_PROGRAMMATIC)
        delay(300)
        key(VK_F10, Vk.SHIFT)
    }

    // The input scenario's PU1/PU2 run right after its synthetic touch and pen taps (down, update, up; no leave). A
    // click on the select after each kind of tap, with and without a final WM_POINTERLEAVE. The taps without one run
    // last: if a tap without one blocks later mouse input, what follows it is blocked too.
    suspend fun tap(kind: Int, leave: Boolean) {
        val (x, y) = localCentre("btn1")
        WinNative.pointer(kind, WinNative.POINTER_DOWN, 7, x, y, 512)
        delay(40)
        WinNative.pointer(kind, WinNative.POINTER_UPDATE, 7, x + 1, y, 512)
        delay(40)
        WinNative.pointer(kind, WinNative.POINTER_UP, 7, x + 1, y, 0)
        if (leave) {
            delay(20)
            WinNative.pointer(kind, WinNative.POINTER_LEAVE, 7, x + 1, y, 0)
        }
        delay(400)
    }
    // Does the page get mouse presses back with time? One touch tap (with its leave), then clicks after 1, 5 and 15 s.
    tap(WinNative.PT_TOUCH, leave = true)
    var waited = 0L
    for (after in listOf(1_000L, 5_000L, 15_000L)) {
        delay(after - waited)
        waited = after
        watch("select-${after / 1000}s-after-touch") { click(pageRectOnScreen("sel1")) }
        waited += 1_400  // watch() takes about 1.4 s
    }
}
