package dev.supermux.webview.wintest

import kotlin.math.abs
import kotlinx.coroutines.delay

/** Scan codes that type "şöçğüıi" on the Turkish Q layout (the keys a US keyboard labels ; , . [ ] i '). */
val TURKISH_Q_SCANS = intArrayOf(0x27, 0x33, 0x34, 0x1A, 0x1B, 0x17, 0x28)
const val TURKISH_EXPECTED = "şöçğüıi"
private val NIHON = intArrayOf(0x31, 0x17, 0x23, 0x18, 0x31)

private fun hasJapanese(text: String) = text.any { it in '぀'..'ヿ' || it in '一'..'鿿' }

/** Layout-dependent typing (Turkish Q) and IME composition (Microsoft IME, Japanese) on both sides of the hand-off. */
suspend fun Harness.ime() {
    requireFrames()
    web.call("animate", "off")
    val in1 = pageRectOnScreen("in1")
    val a = windowRectOnScreen(app.fieldAInWindow)

    // F6: Turkish Q in the page, then in Compose A.
    click(in1)
    delay(300)
    web.call("setValue", "in1", "")
    val turkish = WinNative.setInputLanguage("0000041F")
    delay(500)
    for (code in TURKISH_Q_SCANS) scan(code)
    delay(300)
    val page = web.call("value", "in1").str()
    click(a)
    delay(300)
    onUi { app.fieldA = "" }
    for (code in TURKISH_Q_SCANS) scan(code)
    delay(300)
    report.check(
        "F6-turkish-q", page == TURKISH_EXPECTED && app.fieldA == TURKISH_EXPECTED,
        "page" to page, "a" to app.fieldA, "layout" to turkish,
    )
    WinNative.setInputLanguage("00000409")
    delay(400)

    // F7a/F7b: Japanese in the page. IME on, "nihon", convert, open the candidate list, commit.
    click(in1)
    delay(300)
    web.call("setValue", "in1", "")
    val since = pageClock()
    val japanese = WinNative.setInputLanguage("00000411")
    delay(800)
    val toggle = imeOnAndType { web.call("value", "in1").str() }
    key(Vk.SPACE)
    delay(500)
    val before = topWindows(0).map { it.hwnd }.toSet()
    key(Vk.SPACE)
    delay(800)
    val candidates = topWindows(0).filter { it.hwnd !in before }
    val shot = screenshot("ime-candidates")
    val input = pageRectOnScreen("in1")
    val near = candidates.any { abs(it.rect.x - input.x) <= 400 && it.rect.y in (input.y - 40)..(input.y + input.height + 150) }
    key(Vk.RETURN)
    delay(500)
    val value = web.call("value", "in1").str()
    val events = pageEvents(since)
    val composed = hasJapanese(value) && events.any { it.type == "compositionstart" } && events.any { it.type == "compositionend" }
    report.line(
        "F7a-japanese-ime-in-page",
        if (toggle.startsWith("none")) Verdict.INCONCLUSIVE else verdict(composed),
        listOf("value" to value, "ime_toggle" to toggle, "layout" to japanese),
    )
    report.line(
        "F7b-candidates-near-the-input",
        if (toggle.startsWith("none")) Verdict.INCONCLUSIVE else verdict(near),
        listOf(
            "new_windows" to candidates.joinToString(";") { "${it.className}@${it.rect.x},${it.rect.y}" },
            "input" to "${input.x},${input.y},${input.width}x${input.height}", "shot" to shot.name,
        ),
    )

    // F7c: the same in Compose A, the AWT side of the hand-off.
    click(a)
    delay(300)
    onUi { app.fieldA = "" }
    // Layouts are per thread: F7a switched the page's; AWT's thread still has F6's Turkish Q until it is switched too.
    val japaneseA = WinNative.setInputLanguage("00000411")
    delay(800)
    val toggleA = imeOnAndType { app.fieldA }
    key(Vk.SPACE)
    delay(500)
    key(Vk.RETURN)
    delay(500)
    report.line(
        "F7c-japanese-ime-in-compose",
        if (toggleA.startsWith("none")) Verdict.INCONCLUSIVE else verdict(hasJapanese(app.fieldA)),
        listOf("a" to app.fieldA, "ime_toggle" to toggleA, "layout" to japaneseA),
    )

    WinNative.sendVk(Vk.IME_OFF, true)
    WinNative.sendVk(Vk.IME_OFF, false)
    WinNative.imeControl(0, -1)
    WinNative.setInputLanguage("00000409")
    delay(400)
}

/**
 * Turns the Microsoft IME on and types "nihon". Returns the toggle that worked: VK_IME_ON, then VK_KANJI (Alt+` on a US
 * keyboard), then (beyond the plan) WM_IME_CONTROL to the focused window's default IME window: open, hiragana mode.
 * "none" means the text stayed ASCII every time, and "nihon" was typed plainly.
 */
private suspend fun Harness.imeOnAndType(value: suspend () -> String): String {
    suspend fun clear() {
        key(Vk.ESCAPE)
        key(Vk.A, Vk.CONTROL)
        key(Vk.BACK)
        delay(300)
    }
    for ((name, vk) in listOf("VK_IME_ON" to Vk.IME_ON, "VK_KANJI" to Vk.KANJI)) {
        key(vk)
        delay(400)
        for (code in NIHON) scan(code)
        delay(400)
        if (!value().contains("nihon")) return name
        clear()
    }
    val status = WinNative.imeControl(1, IME_HIRAGANA)
    delay(400)
    for (code in NIHON) scan(code)
    delay(400)
    if (!value().contains("nihon")) return "IMC_SETOPENSTATUS($status)"
    clear()
    for (code in NIHON) scan(code)
    delay(300)
    return "none($status)"
}

/** IME_CMODE_NATIVE | IME_CMODE_FULLSHAPE: hiragana. */
private const val IME_HIRAGANA = 0x09
