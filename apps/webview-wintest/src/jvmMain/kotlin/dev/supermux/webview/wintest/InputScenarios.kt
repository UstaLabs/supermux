package dev.supermux.webview.wintest

import java.awt.Point
import java.awt.event.InputEvent
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Mouse through AWT → Compose → SendMouseInput (C6), touch and pen through SendPointerInput (C6), and WebView2's own
 * popups, which are browser-process windows placed from the parent window and the bounds (C10).
 */
suspend fun Harness.input() {
    requireFrames()
    web.call("animate", "off")
    val hit = pageRectOnScreen("hit")

    // M1: hover in and out. The LEAVE sent on Compose's Exit is what clears the page's hover state.
    var since = pageClock()
    moveTo(hit.x + hit.width / 2, hit.y + hit.height / 2)
    delay(300)
    val gap = composeGapPoint()
    moveTo(gap.x, gap.y)
    delay(300)
    var events = pageEvents(since)
    report.check(
        "M1-hover-enter-leave", events.any { it.type == "enter" } && events.any { it.type == "leave" },
        "events" to events.joinToString(",") { it.type },
    )

    // M2: clicks land where they were aimed: 9 points across #hit, compared in CSS pixels.
    val css = web.call("rect", "hit").list().map { it.double() }
    var worst = 0.0
    var clicks = 0
    for (fy in listOf(0.2, 0.5, 0.8)) {
        for (fx in listOf(0.1, 0.5, 0.9)) {
            val cx = css[0] + css[2] * fx
            val cy = css[1] + css[3] * fy
            val target = cssToScreen(cx, cy, 0.0, 0.0)
            since = pageClock()
            click(target.x, target.y)
            delay(650)  // past the double-click interval
            val c = pageEvents(since).firstOrNull { it.type == "click" && it.id == "hit" } ?: continue
            clicks++
            worst = maxOf(worst, abs(c.x - cx), abs(c.y - cy))
        }
    }
    report.check("M2-click-position", clicks == 9 && worst <= 1.5, "clicks" to clicks, "worst_css_px" to worst)

    // M3: a double click selects a word (the second press goes as WM_LBUTTONDBLCLK).
    val word = web.call("wordRect", "words", "oneword").list().map { it.double() }
    val wordOnScreen = cssToScreen(word[0], word[1], word[2], word[3])
    since = pageClock()
    click(wordOnScreen.x + wordOnScreen.width / 2, wordOnScreen.y + wordOnScreen.height / 2, count = 2)
    delay(400)
    val selected = web.call("selection").str()
    report.check(
        "M3-double-click-word", selected.trim() == "oneword" && pageEvents(since).any { it.type == "dblclick" },
        "selection" to selected,
    )
    web.call("clearSelection")

    // M4: the right button.
    since = pageClock()
    click(hit.x + hit.width / 2, hit.y + hit.height / 2, InputEvent.BUTTON3_DOWN_MASK)
    delay(500)
    events = pageEvents(since)
    report.check(
        "M4-right-click", events.any { it.type == "contextmenu" && it.button == 2 },
        "events" to events.joinToString(",") { it.type },
    )
    key(Vk.ESCAPE)
    delay(300)

    // M5: the wheel scrolls the scroller under the pointer, both ways.
    web.call("setScrollTop", "scroller", 0)
    val scroller = pageRectOnScreen("scroller")
    moveTo(scroller.x + scroller.width / 2, scroller.y + scroller.height / 2)
    robot.mouseWheel(3)
    delay(600)
    val down = web.call("scrollTop", "scroller").double()
    robot.mouseWheel(-3)
    delay(600)
    val back = web.call("scrollTop", "scroller").double()
    report.check("M5-wheel", down > 50.0 && back < down, "after_3_down" to down, "after_3_up" to back)

    // M6: a drag selects text across the sentence.
    val text = web.call("rect", "words").list().map { it.double() }
    val from = cssToScreen(text[0] + 4, text[1] + text[3] / 2, 0.0, 0.0)
    val to = cssToScreen(text[0] + text[2] - 4, text[1] + text[3] / 2, 0.0, 0.0)
    moveTo(from.x, from.y)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    delay(80)
    for (step in 1..20) {
        robot.mouseMove(from.x + (to.x - from.x) * step / 20, from.y)
        delay(15)
    }
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    delay(300)
    val dragged = web.call("selection").str()
    report.check("M6-drag-select", dragged.length >= 20, "selected_chars" to dragged.length)
    web.call("clearSelection")

    // P: touch and pen through SendPointerInput, straight to WebView2 (the VM has no touch screen and no pen).
    for ((kind, name) in listOf(WinNative.PT_TOUCH to "touch", WinNative.PT_PEN to "pen")) {
        val button = web.call("rect", "btn1").list().map { it.double() }
        val dpr = web.call("viewport").list()[2].double()
        val x = ((button[0] + button[2] / 2) * dpr).toInt()
        val y = ((button[1] + button[3] / 2) * dpr).toInt()
        since = pageClock()
        WinNative.pointer(kind, WinNative.POINTER_DOWN, 7, x, y, 512)
        delay(40)
        WinNative.pointer(kind, WinNative.POINTER_UPDATE, 7, x + 1, y, 512)
        delay(40)
        WinNative.pointer(kind, WinNative.POINTER_UP, 7, x + 1, y, 0)
        // The pointer goes away, as real touch and pen do. Without a leave WebView2 keeps the pointer and swallows every
        // later mouse press (seen on 156.0.4314.56: PU1 and PU2's clicks never reached the page).
        delay(20)
        WinNative.pointer(kind, WinNative.POINTER_LEAVE, 7, x + 1, y, 0)
        delay(400)
        events = pageEvents(since)
        val pressed = events.firstOrNull { it.type == "pointerdown" }
        val near = pressed != null && abs(pressed.x * dpr - x) <= 2.0 && abs(pressed.y * dpr - y) <= 2.0
        report.check(
            "P-$name", pressed?.pointerType == name && near && events.any { it.type == "click" && it.id == "btn1" },
            "pointer_type" to pressed?.pointerType, "at" to pressed?.let { "${it.x},${it.y}" },
            "events" to events.joinToString(",") { it.type },
        )
    }

    // Chromium drops mouse presses that come within about a second of a touch or pen release (it takes them for the
    // OS's compatibility mouse events), so the mouse checks below wait that out.
    delay(1_500)

    // PU1: the select's popup opens as its own window, right under (or over) the select.
    val select = pageRectOnScreen("sel1")
    val pid = WinNative.browserPid()
    val beforeSelect = topWindows(pid).map { it.hwnd }.toSet()
    click(select)
    delay(700)
    val popups = topWindows(pid).filter { it.hwnd !in beforeSelect }
    val selectShot = screenshot("select-popup")
    val placed = popups.any {
        abs(it.rect.x - select.x) <= 8 &&
            (abs(it.rect.y - (select.y + select.height)) <= 8 || abs(it.rect.y + it.rect.height - select.y) <= 8)
    }
    key(Vk.DOWN)
    key(Vk.DOWN)
    key(Vk.RETURN)
    delay(400)
    val chosen = web.call("value", "sel1").str()
    report.check(
        "PU1-select-popup", placed && chosen == "gamma",
        "popups" to popups.joinToString(";") { "${it.className}@${it.rect.x},${it.rect.y},${it.rect.width}x${it.rect.height}" },
        "select" to "${select.x},${select.y},${select.width}x${select.height}", "value" to chosen, "shot" to selectShot.name,
    )

    // PU2: the context menu opens at the pointer and closes with Escape.
    val point = Point(hit.x + hit.width / 3, hit.y + hit.height / 2)
    val beforeMenu = topWindows(pid).map { it.hwnd }.toSet()
    click(point.x, point.y, InputEvent.BUTTON3_DOWN_MASK)
    delay(700)
    val menus = topWindows(pid).filter { it.hwnd !in beforeMenu }
    val menuShot = screenshot("context-menu")
    val atPointer = menus.any { abs(it.rect.x - point.x) <= 30 && abs(it.rect.y - point.y) <= 30 }
    key(Vk.ESCAPE)
    delay(400)
    val menuHandles = menus.map { it.hwnd }.toSet()
    val closed = topWindows(pid).none { it.hwnd in menuHandles }
    report.check(
        "PU2-context-menu", atPointer && closed,
        "menus" to menus.joinToString(";") { "${it.className}@${it.rect.x},${it.rect.y}" },
        "pointer" to "${point.x},${point.y}", "closed_by_escape" to closed, "shot" to menuShot.name,
    )
}
