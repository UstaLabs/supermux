package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.INCONCLUSIVE
import dev.supermux.webview.wpetest.Verdict.INFO
import dev.supermux.webview.wpetest.Verdict.PASS
import kotlinx.coroutines.delay

/**
 * C1–C3: from nothing to the first frame with the page's content: cold (WebKit started by this process), a new view
 * (WebKit running), a warm spare (a blank view loads). M1–M3: memory as views are added, each its own site.
 */
suspend fun startup(lab: Lab) {
    val s = "startup"
    val r = lab.report

    // C1: cold. WebKit started in LabHost.start; the window opened in between, so that gap is left out.
    val t1 = System.nanoTime()
    val first = lab.host.create(1280, 720, 1.0)
    WpeNative.frameMode(first.id, true, false, 1.0)
    first.configure()
    first.loadLab()
    val c1 = first.firstContentFrame(t1, 15_000)
    val coldMs = c1?.let { lab.host.initMs + (it.arrivalNs - t1) / 1e6 }
    r.line(s, "C1-cold-first-paint", bar(coldMs, 1_500.0, 3_000.0), "ms" to coldMs, "init_ms" to lab.host.initMs,
        "create_to_paint_ms" to c1?.let { (it.arrivalNs - t1) / 1e6 })

    // C2: a new view while WebKit runs: create, configure, load, first frame with content.
    val t2 = System.nanoTime()
    val second = lab.host.create(1280, 720, 1.0)
    WpeNative.frameMode(second.id, true, false, 1.0)
    second.configure(setOf("https://second.invalid"))
    second.loadLab("https://second.invalid/")
    val c2 = second.firstContentFrame(t2, 10_000)
    val newMs = c2?.let { (it.arrivalNs - t2) / 1e6 }
    r.line(s, "C2-new-view-first-paint", bar(newMs, 500.0, 1_000.0), "ms" to newMs)

    // C3: a warm spare (created, blank) loads a small page: §10's "≤ 100 ms desktop".
    val spare = lab.host.create(1280, 720, 1.0)
    WpeNative.frameMode(spare.id, true, false, 1.0)
    spare.configure(setOf("https://spare.invalid"))
    val tb = System.nanoTime()
    spare.loadUri("about:blank")
    lab.waitUntil(5_000) { spare.loadFinished("about:blank", tb) }
    delay(500)
    val t3 = System.nanoTime()
    spare.loadLab("https://spare.invalid/")
    val c3 = spare.firstContentFrame(t3, 10_000)
    val warmMs = c3?.let { (it.arrivalNs - t3) / 1e6 }
    r.line(s, "C3-warm-spare-first-paint", bar(warmMs, 100.0, 250.0), "ms" to warmMs)

    // M1–M3: eight more views, each its own site, idle once painted.
    val totals = ArrayList<Long>()
    val views = ArrayList<LabView>()
    for (i in 1..8) {
        val t = System.nanoTime()
        val v = lab.host.create(1280, 720, 1.0)
        WpeNative.frameMode(v.id, true, false, 1.0)
        v.configure(setOf("https://m$i.invalid"))
        v.loadLab("https://m$i.invalid/")
        v.firstContentFrame(t, 10_000)
        delay(1_500)
        views += v
        val procs = webkitProcesses()
        val web = procs.filter { it.name == "WPEWebProcess" }
        val network = procs.filter { it.name == "WPENetworkProcess" }
        val jvm = selfPssKb()
        val total = procs.sumOf { it.pssKb } + jvm
        totals += total
        r.line(s, "M-step", INFO, "views" to 3 + i, "total_mb" to total / 1024.0, "web_processes" to web.size,
            "web_mb" to web.sumOf { it.pssKb } / 1024.0, "network_mb" to network.sumOf { it.pssKb } / 1024.0, "jvm_mb" to jvm / 1024.0)
    }
    val perView = (totals.last() - totals.first()) / 7.0 / 1024.0
    r.line(s, "M1-per-view", if (perView <= 150.0) PASS else FAIL, "mb_per_view" to perView,
        "total_first_mb" to totals.first() / 1024.0, "total_last_mb" to totals.last() / 1024.0)
    val processes = webkitProcesses()
    r.line(s, "M2-web-processes", INFO, "views" to 11, "web_processes" to processes.count { it.name == "WPEWebProcess" })
    r.line(s, "M3-network-process", INFO, "network_mb" to processes.filter { it.name == "WPENetworkProcess" }.sumOf { it.pssKb } / 1024.0)
    (views + listOf(first, second, spare)).forEach { it.destroy() }
}

/** PASS within [pass] ms; INCONCLUSIVE within [soft] (software rendering, a shared box); FAIL beyond, or with no frame. */
private fun bar(ms: Double?, pass: Double, soft: Double): Verdict = when {
    ms == null -> FAIL
    ms <= pass -> PASS
    ms <= soft -> INCONCLUSIVE
    else -> FAIL
}
