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

    /** Outbound LSP JSON-RPC (`{serverId,message}`); parsed by [parseLspOut] and forwarded to the
     *  DesktopLspBridge via the engine's `onLspOut` callback (M4g-3). */
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

/**
 * Parse cm6's outbound `{serverId,message}` JSON payload (posted via the shim's `lspOut` hook — see
 * [bridgeShimJs]) → (serverId, message). Uses kotlinx.serialization (NOT `org.json`, unlike Android's
 * `parseLspOut` in `EditorScreen.kt:586-591` — desktop convention throughout this module). Returns
 * null for malformed JSON or a missing/blank `serverId`; a missing `message` defaults to "".
 */
internal fun parseLspOut(payload: String): Pair<String, String>? {
    val parsed = try {
        bridgeJson.decodeFromString<LspOutPayload>(payload)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return if (parsed.serverId.isEmpty()) null else parsed.serverId to parsed.message
}

@kotlinx.serialization.Serializable
private data class LspOutPayload(val serverId: String = "", val message: String = "")

// ── LSP JS-statement builders (pure — mirrors EditorPushPlanner's cmSet* builders; the engine
//    forwards these strings verbatim to `browser.executeJavaScript`) ──────────────────────────

/** JS to connect cm6's LSP client for the active file (port of Android EditorEngine.kt:247-252). */
internal fun lspConnectJs(serverId: String, rootUri: String, fileUri: String, languageId: String): String =
    "window.cmLspConnect(${jsQuote(serverId)},${jsQuote(rootUri)},${jsQuote(fileUri)},${jsQuote(languageId)})"

/** JS to deliver an inbound JSON-RPC message string to the cm6 LSP client for [serverId]. */
internal fun lspMessageJs(serverId: String, message: String): String =
    "window.cmLspMessage(${jsQuote(serverId)},${jsQuote(message)})"

/** JS to tear down all cm6 LSP connections and revert to a plain editor. Guarded (`&&`) exactly
 *  like Android's `EditorEngine.lspDisconnect` — `window.cmLspDisconnect` may not exist if cm6
 *  never finished booting the LSP client machinery. */
internal fun lspDisconnectJs(): String = "window.cmLspDisconnect && window.cmLspDisconnect()"

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

    /**
     * The renderer died or the page failed to load: stop claiming ready so every push queues again
     * until a fresh page fires [onReady]. State (content/filename/wrap/font) is KEPT so a reload can
     * restore the document. KNOWN LIMITATION: the restore pushes [lastScrollTop] — the last
     * PROGRAMMATIC scroll, not wherever the user had scrolled to — and a pendingReveal consumed
     * before the crash is not re-fired. Good enough for a crash path; a future retry affordance that
     * wants exact restoration must snapshot cmGetScrollTop live before the renderer dies.
     */
    fun onRendererLost() {
        ready = false
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
