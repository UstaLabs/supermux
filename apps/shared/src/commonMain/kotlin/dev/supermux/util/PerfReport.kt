package dev.supermux.util

import kotlin.math.roundToInt

/**
 * Card #54: the load-time trace as lines a person reads. The Load Timings panel, its Copy button
 * and the console summaries all print these, so the three always agree.
 */
object PerfReport {
    private val launchSteps = linkedMapOf(
        "process.start" to "process start",
        "host.init" to "store ready",
        "ws.connecting" to "connecting",
        "ws.open" to "socket open",
        "ws.subscribe.sent" to "subscribe sent",
        "ws.snapshot.arrived" to "snapshot in",
        "ws.snapshot.decoded" to "decoded",
        "state.snapshot.reduced" to "reduced",
        "ui.sessions.drawn" to "sessions drawn",
        "ui.workspaces.drawn" to "workspaces drawn",
        "ui.agents.drawn" to "Agents drawn",
        "avatars.loaded" to "Agent pictures",
    )

    fun size(chars: Long): String = when {
        chars >= 1024 * 1024 -> "${((chars * 100) / (1024 * 1024)) / 100.0} MB"
        chars >= 100 * 1024 -> "${chars / 1024} KB"
        else -> "${((chars * 10) / 1024) / 10.0} KB"
    }

    private fun secs(ms: Long): String = "${(ms / 100) / 10.0} s"
    private fun num(v: Any?): Long? = (v as? Number)?.toLong()
    private fun dbl(v: Any?): Double? = (v as? Number)?.toDouble()
    private fun ms(v: Any?): String = dbl(v)?.let { if (it < 10) "${(it * 10).roundToInt() / 10.0} ms" else "${it.roundToInt()} ms" } ?: "?"

    /** A request path with its ids folded to `:id` (mirrors the broker's `perfPath`). */
    fun route(path: String): String = path.split('/').joinToString("/") { seg ->
        val idLike = seg.isNotEmpty() && (seg.all { it.isDigit() } ||
            (seg.length >= 12 && seg.any { it.isDigit() } && seg.all { it.isLetterOrDigit() || it == '-' || it == '_' }))
        if (idLike) ":id" else seg
    }

    private fun httpText(e: PerfTrace.Event, body: PerfTrace.Event?): String {
        val f = e.fields
        val parts = mutableListOf("${f["method"]} ${route(f["path"] as? String ?: "?")} ${f["status"]}")
        (num(f["bytes"]) ?: num(body?.fields?.get("chars")))?.let { parts += size(it) }
        var timing = "${ms(f["ms"])} to headers"
        dbl(f["serverMs"])?.let { timing += " (broker ${ms(it)})" }
        parts += timing
        body?.let { parts += "decode ${ms(it.fields["decodeMs"])}" }
        return parts.joinToString(" · ")
    }

    private fun stepDetail(e: PerfTrace.Event): String {
        val f = e.fields
        return when (e.name) {
            "process.start" -> num(f["uptimeMs"])?.let { "the JVM ran $it ms before main" } ?: ""
            "ws.connecting" -> "attempt ${f["attempt"]}"
            "ws.open" -> "handshake ${ms(f["handshakeMs"])}"
            "ws.snapshot.arrived" -> "${size(num(f["chars"]) ?: 0)}, ${ms(f["sinceSubscribeMs"])} after subscribe"
            "ws.snapshot.decoded" -> ms(f["decodeMs"])
            "state.snapshot.reduced" -> "${ms(f["ms"])} · ${f["sessions"]} sessions · ${f["workspaces"]} workspaces · ${f["archivedWorkspaces"]} archived"
            "ui.sessions.drawn" -> "${f["sessions"]} sessions${if (num(f["n"]) != 1L) " (redraw ${f["n"]})" else ""}"
            "ui.workspaces.drawn" -> "${f["workspaces"]} in ${f["groups"]} groups"
            "ui.agents.drawn" -> "${f["agents"]} Agents, ${f["pictures"]} pictures yet"
            "avatars.loaded" -> "${f["fetched"]} fetched one at a time in ${ms(f["ms"])}"
            else -> f.entries.filter { it.key != "req" && it.key != "host" }.joinToString(" ") { "${it.key}=${it.value}" }
        }
    }

    /** The app launch: milestones in order, the requests it made, one summary line. */
    fun launch(): List<String> {
        val all = PerfTrace.events(PerfTrace.launchId)
        // The first connection only: a later reconnect is its own story.
        val firstReq = all.firstOrNull { it.name == "ws.connecting" }?.fields?.get("req")
        val steps = all.filter { e ->
            e.name in launchSteps && (e.fields["req"] == null || e.fields["req"] == firstReq) &&
                (e.name != "ui.sessions.drawn" || num(e.fields["n"]) == 1L)
        }.distinctBy { it.name }
        val out = mutableListOf("Launch ${PerfTrace.launchId}")
        for (e in steps) out += "  ${"+${e.t}".padStart(7)}  ${launchSteps.getValue(e.name).padEnd(17)} ${stepDetail(e)}"
        val bodies = all.filter { it.name == "http.body" }.associateBy { it.fields["req"] }
        val settledAt = steps.lastOrNull()?.t ?: 0
        val requests = all.filter { it.name == "http" && it.t <= settledAt + 2_000 }
        if (requests.isNotEmpty()) {
            out += "  requests until it settled:"
            for (r in requests) out += "  ${"+${r.t}".padStart(7)}  ${httpText(r, bodies[r.fields["req"]])}"
        }
        val at = { name: String -> steps.firstOrNull { it.name == name }?.t }
        val summary = mutableListOf<String>()
        at("ws.open")?.let { summary += "connected ${secs(it)}" }
        steps.firstOrNull { it.name == "ws.snapshot.arrived" }?.let { s ->
            val decode = steps.firstOrNull { it.name == "ws.snapshot.decoded" }?.fields?.get("decodeMs")
            val reduce = steps.firstOrNull { it.name == "state.snapshot.reduced" }?.fields?.get("ms")
            summary += "snapshot ${secs(s.t)} (${size(num(s.fields["chars"]) ?: 0)}: waited ${ms(s.fields["sinceSubscribeMs"])}, decode ${ms(decode)}, reduce ${ms(reduce)})"
        }
        at("ui.workspaces.drawn")?.let { summary += "workspaces ${secs(it)}" }
        at("ui.agents.drawn")?.let { a -> summary += "Agents ${secs(a)}" + (at("avatars.loaded")?.let { ", pictures ${secs(it)}" } ?: "") }
        if (summary.isNotEmpty()) out += "  = ${summary.joinToString(" · ")}"
        return out
    }

    /** One chat open, timed from the click (selection) to fully shown. */
    fun chat(trace: String): List<String> {
        val all = PerfTrace.events(trace)
        val open = all.firstOrNull { it.name == "chat.open" } ?: return emptyList()
        val f = open.fields
        val out = mutableListOf(
            "Chat \"${f["name"] ?: f["session"]}\" ($trace) — ${if (f["loaded"] == true) "already in memory" else "not loaded, ${f["messages"]} message(s) in memory"}",
        )
        val bodies = all.filter { it.name == "http.body" }.associateBy { it.fields["req"] }
        var fetched = 0L
        for (e in all) {
            val t = "+${e.t - open.t}".padStart(7)
            when (e.name) {
                "chat.open", "http.body" -> Unit
                "chat.fetch" -> out += "  $t  fetch ${listOfNotNull("history".takeIf { e.fields["logs"] == true }, "extras".takeIf { e.fields["extras"] == true }).joinToString(" + ")}"
                "http" -> {
                    fetched += num(e.fields["bytes"]) ?: num(bodies[e.fields["req"]]?.fields?.get("chars")) ?: 0
                    out += "  $t  ${httpText(e, bodies[e.fields["req"]])}"
                }
                "chat.loaded" -> out += "  $t  loaded ${e.fields["messages"]} messages, ${e.fields["activity"]} activity rows (${ms(e.fields["ms"])})"
                "chat.firstFrame" -> out += "  $t  first frame (${e.fields["items"]} rows)"
                "chat.complete" -> out += "  $t  fully shown (${e.fields["items"]} rows)"
                else -> out += "  $t  ${e.name} ${e.fields.entries.joinToString(" ") { "${it.key}=${it.value}" }}"
            }
        }
        val first = all.firstOrNull { it.name == "chat.firstFrame" }?.t
        val complete = all.firstOrNull { it.name == "chat.complete" }?.t
        val summary = mutableListOf<String>()
        first?.let { summary += "on screen ${it - open.t} ms" }
        complete?.let { summary += "fully shown ${it - open.t} ms" }
        if (fetched > 0) summary += "fetched ${size(fetched)}"
        if (summary.isNotEmpty()) out += "  = ${summary.joinToString(" · ")}"
        return out
    }

    /** WebSocket traffic since launch per frame type, and how often the UI recomposed. */
    fun websocket(): List<String> {
        val totals = PerfTrace.totals()
        val upMs = PerfTrace.elapsedMs()
        val frames = totals.frames.entries.sortedByDescending { num(it.value["chars"]) ?: 0 }
        val n = frames.sumOf { (num(it.value["n"]) ?: 0) }
        val chars = frames.sumOf { (num(it.value["chars"]) ?: 0) }
        val out = mutableListOf("WebSocket since launch (${secs(upMs)}): $n frames, ${size(chars)}")
        for ((type, f) in frames) {
            val reduce = totals.reduce[type]?.get("ms")
            out += "  ${type.padEnd(22)} ${"${f["n"]}".padStart(6)}  ${size(num(f["chars"]) ?: 0).padStart(9)}  decode ${ms(f["decodeMs"])} (worst ${ms(f["maxDecodeMs"])})  reduce ${ms(reduce)}"
        }
        if (totals.recompose.isNotEmpty()) out += "  recompositions: ${totals.recompose.entries.joinToString(" · ") { "${it.key} ${it.value}" }}"
        return out
    }

    /** Every HTTP request since launch, grouped by route. */
    fun http(): List<String> {
        val reqs = PerfTrace.since(0).filter { it.name == "http" }
        if (reqs.isEmpty()) return listOf("HTTP since launch: none")
        val byRoute = reqs.groupBy { "${it.fields["method"]} ${route(it.fields["path"] as? String ?: "?")}" }
            .entries.sortedByDescending { (_, list) -> list.sumOf { num(it.fields["bytes"]) ?: 0 } }
        val out = mutableListOf("HTTP since launch: ${reqs.size} requests, ${size(reqs.sumOf { num(it.fields["bytes"]) ?: 0 })}")
        for ((route, list) in byRoute) {
            val times = list.mapNotNull { dbl(it.fields["ms"]) }
            val avg = if (times.isEmpty()) null else times.average()
            out += "  ${route.padEnd(36)} ×${list.size.toString().padEnd(4)} ${size(list.sumOf { num(it.fields["bytes"]) ?: 0 }).padStart(9)}  avg ${ms(avg)}  worst ${ms(times.maxOrNull())}"
        }
        return out
    }

    /** Everything, as one text (the panel's Copy). */
    fun all(chats: Int = 5): String = buildList {
        addAll(launch()); add("")
        for (t in PerfTrace.chatTraces(chats)) { addAll(chat(t)); add("") }
        addAll(websocket()); add("")
        addAll(http())
    }.joinToString("\n")
}
