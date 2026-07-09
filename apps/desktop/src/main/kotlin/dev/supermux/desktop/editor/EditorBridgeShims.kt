// M3 editor bridge — the PURE half of the KCEF↔CodeMirror wiring, split out from [DesktopEditorEngine]
// so every decision it makes (JS quoting, the injected shim JS, the JS→Kotlin payload parse, and the
// push/echo-skip/reveal-queue ordering) is unit-testable WITHOUT booting Chromium (KCEF can't run in
// `kotlin.test`). The engine is a thin adapter: it forwards the JS strings these helpers return to
// `browser.executeJavaScript` and feeds `onQuery` requests through [parseBridgeEvent].
//
// WHY the bundle runs UNMODIFIED: the committed cm6 bundle (apps/android/.../assets/editor/) calls a
// host bridge via `window.AndroidEditor.{onChange,onSave,onReady,onFontSize}` and posts LSP via
// `window.webkit.messageHandlers.lsp.postMessage` — both shaped for the mobile WebViews. On desktop
// there is no WebView bridge; instead [bridgeShimJs] DEFINES those globals in the page, each routing
// through CEF's message-router query function (see [DesktopEditorEngine] for why the query function
// is renamed off the default `cefQuery`). So the exact same cm6.js the phones ship boots here too.
package dev.supermux.desktop.editor

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** Font-size clamp — mirrors the bundle's FONT_MIN/MAX/DEFAULT (cm6-entry.mjs:82). */
internal const val EDITOR_FONT_MIN = 10
internal const val EDITOR_FONT_MAX = 24
internal const val EDITOR_FONT_DEFAULT = 13

/**
 * Quote [s] as a JS string literal via kotlinx-serialization (NOT hand-rolled escaping). A JSON
 * string is a valid JS string, so `JsonPrimitive(s).toString()` yields a `"…"` literal with quotes,
 * backslashes, newlines, tabs, and control chars all escaped correctly. `</script>` and backticks
 * pass through literally — safe here because we inject via `executeJavaScript`, never into an HTML
 * `<script>` context, and the literal is double-quoted (a backtick is an ordinary char inside it).
 */
internal fun jsQuote(s: String): String = JsonPrimitive(s).toString()

/**
 * The shim JS injected into the page right before `cmInit`. Defines the bridge globals the committed
 * bundle expects, each marshalling `{fn, arg}` back to Kotlin through CEF's message-router query
 * function [queryFn]. The `if (window[queryFn])` guard + try/catch is the "queue-or-drop gracefully"
 * contract: if the query function isn't installed yet the call is dropped rather than throwing —
 * though in practice the router is registered BEFORE the browser is created, so `queryFn` exists by
 * the time any page script runs. Every arg is stringified (the payload's `arg` is always a string;
 * `onFontSize` gets the number as a decimal string, re-parsed in [parseBridgeEvent]).
 */
internal fun bridgeShimJs(queryFn: String): String = """
    (function () {
      function post(fn, arg) {
        try { if (window.$queryFn) window.$queryFn({ request: JSON.stringify({ fn: fn, arg: arg }) }); } catch (e) {}
      }
      window.AndroidEditor = {
        onChange: function (s) { post("onChange", String(s)); },
        onSave: function () { post("onSave", ""); },
        onReady: function () { post("onReady", ""); },
        onFontSize: function (px) { post("onFontSize", String(px)); },
      };
      window.webkit = window.webkit || {};
      window.webkit.messageHandlers = window.webkit.messageHandlers || {};
      window.webkit.messageHandlers.lsp = { postMessage: function (s) { post("lspOut", String(s)); } };
    })();
""".trimIndent()

/**
 * The single script executed on `onLoadEnd`: the bridge shim FIRST, then `cmInit`. Concatenating
 * both into ONE `executeJavaScript` eval is the timing guarantee — the shim's `window.AndroidEditor`
 * is defined and in scope before `cmInit` runs and synchronously calls `bridge().onReady()`
 * (cm6-entry.mjs:193). Splitting into two evals would rely on CEF preserving call order across evals;
 * one eval removes the question entirely.
 */
internal fun initScript(
    queryFn: String,
    content: String,
    filename: String,
    lineWrap: Boolean,
    fontSize: Int,
): String = bridgeShimJs(queryFn) + "\n" +
    "cmInit(${jsQuote(content)}, ${jsQuote(filename)}, $lineWrap, $fontSize);"

// ── JS→Kotlin payload ────────────────────────────────────────────────────────

/** A parsed `window.$queryFn({request})` payload from the bundle. Unknown `fn` → parse returns null. */
internal sealed interface BridgeEvent {
    /** cm6 doc changed — [content] is the full document. Drives the echo-skip (see [EditorPushPlanner]). */
    data class Change(val content: String) : BridgeEvent

    /** Mod-S in the editor. */
    data object Save : BridgeEvent

    /** cmInit finished (fired once from cm6-entry.mjs:193). */
    data object Ready : BridgeEvent

    /** User zoom (keyboard/pinch) already applied in-page; [px] is the new size to persist. */
    data class FontSize(val px: Int) : BridgeEvent

    /** Outbound LSP JSON-RPC — TODO(M4). The engine logs-and-drops these for now. */
    data class LspOut(val payload: String) : BridgeEvent
}

private val bridgeJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parse a message-router request string into a [BridgeEvent]. Returns null for malformed JSON, a
 * missing/unknown `fn`, or an `onFontSize` whose `arg` isn't numeric — the engine logs-and-ignores
 * a null (never throws on a CEF thread). Pure + total: safe to unit-test with any input.
 */
internal fun parseBridgeEvent(request: String): BridgeEvent? {
    val payload = try {
        bridgeJson.decodeFromString<BridgePayload>(request)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return when (payload.fn) {
        "onChange" -> BridgeEvent.Change(payload.arg)
        "onSave" -> BridgeEvent.Save
        "onReady" -> BridgeEvent.Ready
        "onFontSize" -> payload.arg.trim().toIntOrNull()?.let { BridgeEvent.FontSize(it) }
        "lspOut" -> BridgeEvent.LspOut(payload.arg)
        else -> null
    }
}

@kotlinx.serialization.Serializable
private data class BridgePayload(val fn: String = "", val arg: String = "")

// ── Push ordering / echo-skip / reveal-queue (pure state machine) ─────────────

/**
 * Models WHAT `cm*` JS the engine should emit for each Kotlin→JS call, given the ready state and the
 * last-pushed document — the desktop analog of Android EditorEngine's pushToView/setDocument/
 * reveal-queue logic (EditorEngine.kt:101-130,233-241). Emits a `List<String>` of JS statements (in
 * order) that the engine forwards to `browser.executeJavaScript`; returns an empty list when a call
 * must be deferred until [onReady] (e.g. a reveal before cm6 first-paints). Pure — no KCEF — so the
 * ordering, the echo-skip, and the queue-until-ready are all unit-tested directly.
 */
internal class EditorPushPlanner(
    lineWrap: Boolean,
    fontSize: Int,
) {
    var ready: Boolean = false
        private set
    var lineWrap: Boolean = lineWrap
        private set
    var fontSize: Int = fontSize.coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX)
        private set

    private var lastContent = ""
    private var lastFilename = ""
    private var lastScrollTop = 0
    private var pendingReveal: Pair<Int, Int?>? = null

    /** cmInit args snapshot the engine reads at onLoadEnd (whatever was set before the page loaded). */
    fun initContent(): String = lastContent
    fun initFilename(): String = lastFilename

    /**
     * A new document (tab switch / disk reload / echo of our own edit). On a path change, push the
     * whole document + language + wrap + font + scroll (parity EditorEngine.kt:104-109). On a
     * same-file content change, push ONLY the text — re-pushing scroll/lang/font every keystroke
     * yanks the caret back (cmSetScrollTop is not a no-op). Records lastContent BEFORE emitting so a
     * later echo of the same text is a no-op.
     */
    fun setDocument(content: String, filename: String, scrollTop: Int = 0): List<String> {
        val pathChanged = filename != lastFilename
        lastFilename = filename
        return if (pathChanged) {
            lastContent = content
            lastScrollTop = scrollTop
            if (ready) pushToView(content, filename, scrollTop) else emptyList()
        } else if (content != lastContent) {
            lastContent = content
            if (ready) listOf("cmSetContent(${jsQuote(content)})") else emptyList()
        } else {
            emptyList()
        }
    }

    /** Scroll to a 1-indexed [line] (optional [endLine]). Deferred until [onReady] if not ready. */
    fun revealLine(line: Int, endLine: Int?): List<String> {
        pendingReveal = line to endLine
        return if (ready) flushReveal() else emptyList()
    }

    fun setFontSize(px: Int): List<String> {
        fontSize = px.coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX)
        return if (ready) listOf("cmSetFontSize($fontSize)") else emptyList()
    }

    fun setLineWrap(on: Boolean): List<String> {
        lineWrap = on
        return if (ready) listOf("cmSetLineWrap($on)") else emptyList()
    }

    fun setScrollTop(px: Int): List<String> {
        lastScrollTop = px
        return if (ready) listOf("cmSetScrollTop($px)") else emptyList()
    }

    /** cm6 first-painted: mark ready and flush the queued document + pending reveal in one shot. */
    fun onReady(): List<String> {
        ready = true
        return pushToView(lastContent, lastFilename, lastScrollTop)
    }

    /** Echo-skip: record an inbound onChange as last-known BEFORE it round-trips back through Compose
     *  state, so the resulting [setDocument] sees `content == lastContent` and skips the re-push
     *  (parity EditorEngine.kt:175). Without this, fast typing can shove a stale snapshot back. */
    fun recordEcho(content: String) {
        lastContent = content
    }

    /** A user zoom already applied in-page: keep our copy in sync, emit nothing (no loop-back). */
    fun recordUserFontSize(px: Int): Int {
        fontSize = px.coerceIn(EDITOR_FONT_MIN, EDITOR_FONT_MAX)
        return fontSize
    }

    private fun pushToView(content: String, filename: String, scrollTop: Int): List<String> = buildList {
        add("cmSetContent(${jsQuote(content)})")
        add("cmSetLanguage(${jsQuote(filename)})")
        add("cmSetLineWrap($lineWrap)")
        add("cmSetFontSize($fontSize)")
        add("cmSetScrollTop($scrollTop)")
        addAll(flushReveal())
    }

    private fun flushReveal(): List<String> {
        val r = pendingReveal ?: return emptyList()
        pendingReveal = null
        return listOf("cmRevealLine(${r.first}, ${r.second ?: -1})")
    }
}
