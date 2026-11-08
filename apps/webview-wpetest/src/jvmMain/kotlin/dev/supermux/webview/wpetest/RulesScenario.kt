package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.DIFF
import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.PASS
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * W1–W9: whether the iOS engine's WebKit rules hold on WPE as they are. PASS: unchanged. DIFF: the mechanism works but
 * differs, and Part B adapts (the line says how). FAIL: the mechanism a rule needs isn't there. W10 is Task 9's diff.
 */
suspend fun rules(lab: Lab) {
    val s = "rules"
    val r = lab.report
    val local = "https://aaaaaaaaaaaaaaaaaaaaaaaaaa.invalid"
    val view = lab.host.create(1280, 720, 1.0)
    view.configure(setOf(LabView.LAB_ORIGIN, local, "webview-bundle://demo.invalid"))
    view.loadLab()
    lab.show(view)
    lab.waitUntil(5_000) { view.document != null }

    // W1: the relay's handler lives in its world only.
    withTimeoutOrNull(2_000) { view.pageMessages.receive() } // the document's $ready
    view.deliver("""{"t":"probe","id":1}""", LabView.LAB_ORIGIN)
    val probe = withTimeoutOrNull(2_000) { view.pageMessages.receive() }
    r.line(s, "W1-world-isolation", if (probe?.contains("\"handlers\":false") == true) PASS else FAIL, "probe" to probe)

    // W2: document-start scripts of two worlds, in either order: SENDER's queue gets $ready through every time.
    var readies = 0
    repeat(20) {
        while (view.pageMessages.tryReceive().isSuccess) Unit
        view.loadLab()
        val m = withTimeoutOrNull(3_000) { view.pageMessages.receive() }
        if (m?.contains("\$ready") == true) readies++
    }
    r.line(s, "W2-start-order", if (readies == 20) PASS else FAIL, "readies" to readies, "loads" to 20)

    // W3: decide-policy's navigation types are NavigationRules' kinds.
    val rulesHtml = LabHost.resource("wpetest/rules.html")
    val own = ConcurrentHashMap.newKeySet<String>()
    val asked = ConcurrentLinkedQueue<LabEvent>()
    view.policy = { e ->
        when {
            e.kind != WpeNative.POLICY -> WpeNative.USE
            e.s1 != null && own.remove(e.s1) -> WpeNative.USE // the lab's own load, as an engine's own navigation
            else -> {
                asked += e
                WpeNative.IGNORE // the page's: recorded and refused, so nothing goes out
            }
        }
    }
    for (page in listOf("rules-a", "rules-b")) {
        val url = "${LabView.LAB_ORIGIN}/$page"
        own += url
        val t = System.nanoTime()
        view.loadHtml(rulesHtml, url)
        lab.waitUntil(5_000) { view.loadFinished(url, t) }
    }
    val expected = listOf(
        "click" to WpeNative.NAV_LINK, "submit" to WpeNative.NAV_FORM, "assign" to WpeNative.NAV_OTHER,
        "reload" to WpeNative.NAV_RELOAD, "back" to WpeNative.NAV_BACK_FORWARD,
    )
    val seen = ArrayList<String>()
    var allMatch = true
    for ((action, type) in expected) {
        asked.clear()
        view.eval("__rules.$action()")
        lab.waitUntil(2_000) { asked.isNotEmpty() }
        val kind = asked.firstOrNull()?.let { ((it.b shr 4) and 0xf).toInt() }
        seen += "$action=${kind ?: "none"}"
        if (kind != type) allMatch = false
    }
    r.line(s, "W3-navigation-types", if (allMatch) PASS else FAIL, "seen" to seen.joinToString(","))

    // W4: subframe navigations reach decide-policy like the main frame's, with no frame flag (F17); only the response
    // says which frame. DIFF: NavigationRules wants isMainFrame when the action is decided.
    val actions = ConcurrentLinkedQueue<LabEvent>()
    val responses = ConcurrentLinkedQueue<LabEvent>()
    view.policy = { e ->
        if (e.kind == WpeNative.POLICY) actions += e else responses += e
        WpeNative.USE
    }
    view.loadUri("${LabHost.BUNDLE}index.html")
    lab.waitUntil(5_000) { responses.any { it.s1?.endsWith("frame.html") == true } }
    val frameAction = actions.firstOrNull { it.s1?.endsWith("frame.html") == true }
    val mainResponse = responses.firstOrNull { it.s1?.endsWith("index.html") == true }
    val frameResponse = responses.firstOrNull { it.s1?.endsWith("frame.html") == true }
    val mainIsMain = mainResponse?.let { (it.b and WpeNative.RESPONSE_MAIN) != 0L }
    val frameIsMain = frameResponse?.let { (it.b and WpeNative.RESPONSE_MAIN) != 0L }
    r.line(s, "W4-main-frame", if (frameAction != null && mainIsMain == true && frameIsMain == false) DIFF else FAIL,
        "subframe_action_seen" to (frameAction != null), "subframe_action_frame_name" to frameAction?.s2,
        "main_response_main" to mainIsMain, "frame_response_main" to frameIsMain)

    // W7: a bundle on the engine's own scheme: a tuple origin, a secure context, its own files fetched.
    lab.waitUntil(3_000) { view.eval("window.__fetched || null")?.contains("ok") == true }
    val bundle = lab.parse(view.eval("[self.origin, isSecureContext, !!window.__appLoaded, window.__fetched || null]"))?.jsonArray
    val origin = bundle?.getOrNull(0)?.jsonPrimitive?.contentOrNull
    val secure = bundle?.getOrNull(1)?.jsonPrimitive?.boolean
    val app = bundle?.getOrNull(2)?.jsonPrimitive?.boolean
    val fetched = bundle?.getOrNull(3)?.jsonPrimitive?.contentOrNull
    r.line(s, "W7-bundle-scheme",
        if (origin == "webview-bundle://demo.invalid" && secure == true && app == true && fetched?.contains("ok") == true) PASS else FAIL,
        "origin" to origin, "secure" to secure, "script" to app, "fetched" to fetched)

    // W6: in-memory HTML on its https://<hash>.invalid origin, through load_html's base.
    view.policy = { WpeNative.USE }
    var t = System.nanoTime()
    view.loadLab("$local/")
    lab.waitUntil(5_000) { view.loadFinished("$local/", t) }
    val page = lab.parse(view.eval("__labPage.origin()"))?.jsonObject
    val pageOrigin = page?.get("origin")?.jsonPrimitive?.contentOrNull
    val pageSecure = page?.get("secure")?.jsonPrimitive?.boolean
    r.line(s, "W6-html-origin", if (pageOrigin == local && pageSecure == true) PASS else FAIL, "origin" to pageOrigin, "secure" to pageSecure)

    // W5: the floor reset (F19): restoring the floor's state replaces the list, leaving the floor alone; the back list
    // comes oldest first; a page's history.back() into the floor is a BackForward action the engine can refuse.
    val floor = "webview-floor://reset/"
    t = System.nanoTime()
    view.loadUri(floor)
    lab.waitUntil(5_000) { view.loadFinished(floor, t) }
    val floorState = WpeNative.saveState(view.id)
    for (name in listOf("a", "b")) {
        t = System.nanoTime()
        view.loadLab("${LabView.LAB_ORIGIN}/$name")
        lab.waitUntil(5_000) { view.loadFinished("${LabView.LAB_ORIGIN}/$name", t) }
    }
    delay(300)
    val backOnB = view.events(WpeNative.HISTORY).lastOrNull()?.s1
    val oldestFirst = backOnB == "$floor\n${LabView.LAB_ORIGIN}/a"
    t = System.nanoTime()
    val restored = floorState != null && WpeNative.restoreState(view.id, floorState)
    lab.waitUntil(5_000) { view.loadFinished(floor, t) }
    delay(300)
    val afterRestore = view.events(WpeNative.HISTORY, t).lastOrNull()
    val floorAlone = afterRestore != null && afterRestore.a == 0L && afterRestore.s1.isNullOrEmpty()
    val refused = ConcurrentLinkedQueue<LabEvent>()
    view.policy = { e ->
        if (e.kind == WpeNative.POLICY && e.s1 == floor) {
            refused += e
            WpeNative.IGNORE
        } else WpeNative.USE
    }
    t = System.nanoTime()
    view.loadLab("${LabView.LAB_ORIGIN}/c")
    lab.waitUntil(5_000) { view.loadFinished("${LabView.LAB_ORIGIN}/c", t) }
    view.eval("history.back()")
    lab.waitUntil(2_000) { refused.isNotEmpty() }
    delay(500)
    val stayed = view.eval("location.href")?.contains("/c") == true
    val backKind = refused.firstOrNull()?.let { ((it.b shr 4) and 0xf).toInt() }
    r.line(s, "W5-floor-reset", if (restored && floorAlone && oldestFirst && backKind == WpeNative.NAV_BACK_FORWARD && stayed) PASS else FAIL,
        "restored" to restored, "floor_alone" to floorAlone, "back_on_b" to backOnB?.replace("\n", "|"), "oldest_first" to oldestFirst,
        "back_kind" to backKind, "stayed" to stayed)

    // W8: WebKit tells title and same-document URL changes itself: the relay's hints aren't needed here.
    view.policy = { WpeNative.USE }
    t = System.nanoTime()
    view.loadLab("${LabView.LAB_ORIGIN}/t")
    lab.waitUntil(5_000) { view.loadFinished("${LabView.LAB_ORIGIN}/t", t) }
    val t8 = System.nanoTime()
    view.eval("document.title = 'w8-title'; history.pushState({}, '', '/w8-pushed'); true")
    val title = view.awaitEvent(WpeNative.TITLE, t8, 2_000) { it.s1 == "w8-title" }
    val uri = view.awaitEvent(WpeNative.URI, t8, 2_000) { it.s1 == "${LabView.LAB_ORIGIN}/w8-pushed" }
    r.line(s, "W8-native-title-url", if (title != null && uri != null) PASS else FAIL,
        "title_ms" to title?.let { (it.atNs - t8) / 1e6 }, "uri_ms" to uri?.let { (it.atNs - t8) / 1e6 })

    // W9: prefers-color-scheme follows the display's dark-mode setting, for every view at once (F18). DIFF: no lease
    // can be dark while another is light.
    val other = lab.host.create(640, 360, 1.0)
    WpeNative.frameMode(other.id, true, false, 1.0)
    other.configure()
    t = System.nanoTime()
    other.loadLab("${LabView.LAB_ORIGIN}/other")
    lab.waitUntil(5_000) { other.loadFinished("${LabView.LAB_ORIGIN}/other", t) }
    WpeNative.setDark(true)
    delay(500)
    val darkA = view.eval("__labPage.dark()")
    val darkB = other.eval("__labPage.dark()")
    WpeNative.setDark(false)
    delay(500)
    val lightA = view.eval("__labPage.dark()")
    r.line(s, "W9-dark-scope", if (darkA == "true" && darkB == "true" && lightA == "false") DIFF else FAIL,
        "dark_a" to darkA, "dark_b" to darkB, "after_light_a" to lightA)
    other.destroy()
    view.destroy()
}
