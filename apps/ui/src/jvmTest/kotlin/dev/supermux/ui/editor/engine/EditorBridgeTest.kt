package dev.supermux.ui.editor.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the JCEF bridge: JS quoting, shim/init script shape, payload parse, push plan. */
class EditorBridgeTest {

    // ── jsQuote ──────────────────────────────────────────────────────────────

    @Test
    fun js_quote_wraps_a_plain_string_in_double_quotes() {
        assertEquals("\"hello\"", jsQuote("hello"))
    }

    @Test
    fun js_quote_escapes_quotes_newlines_backslashes_tabs() {
        assertEquals("\"a\\\"b\"", jsQuote("a\"b"))
        assertEquals("\"a\\nb\"", jsQuote("a\nb"))
        assertEquals("\"a\\\\b\"", jsQuote("a\\b"))
        assertEquals("\"a\\tb\"", jsQuote("a\tb"))
    }

    @Test
    fun js_quote_survives_a_nasty_content_string() {
        // Quotes, newline, backtick, </script>, backslash — all must round-trip into a single valid
        // JS string literal (no premature terminator, no unescaped control char).
        val nasty = "line1 \"q\" `tick`\n</script>\t\\end"
        val quoted = jsQuote(nasty)
        assertTrue(quoted.startsWith("\"") && quoted.endsWith("\""), "not a quoted literal: $quoted")
        // No raw newline/tab leaked (would break the single-line executeJavaScript statement).
        assertTrue(!quoted.contains('\n') && !quoted.contains('\t'), "raw control char leaked: $quoted")
        // Interior double-quotes are all escaped: every " except the two delimiters is preceded by \.
        val interior = quoted.substring(1, quoted.length - 1)
        var i = 0
        while (i < interior.length) {
            if (interior[i] == '\\') { i += 2; continue }
            assertTrue(interior[i] != '"', "unescaped interior quote at $i in $quoted")
            i++
        }
        // Backtick and the </script> slash pass through literally (fine outside an HTML script ctx).
        assertTrue(interior.contains("`tick`"))
        assertTrue(interior.contains("</script>"))
    }

    // ── shim + init script ───────────────────────────────────────────────────

    @Test
    fun bridge_shim_defines_the_bundle_globals_and_routes_through_the_query_fn() {
        val shim = bridgeShimJs("smxEditorQuery")
        assertTrue(shim.contains("window.AndroidEditor"), "AndroidEditor bridge not defined")
        assertTrue(shim.contains("window.webkit"), "webkit.lsp shim not defined")
        assertTrue(shim.contains("messageHandlers"), "lsp messageHandlers not defined")
        // Every post routes through the named query function, guarded (queue-or-drop).
        assertTrue(shim.contains("window.smxEditorQuery"), "does not call the query function")
        assertTrue(shim.contains("if (window.smxEditorQuery)"), "missing the not-ready guard")
        // The editor, LSP, and walkthrough-diff callbacks are present.
        for (fn in listOf("onChange", "onSave", "onReady", "onFontSize", "onDiffLineClick", "onDiffExpand", "onDiffPage", "lspOut")) {
            assertTrue(shim.contains("\"$fn\"") || shim.contains(fn), "shim missing $fn")
        }
    }

    @Test
    fun init_script_puts_the_shim_before_cm_init() {
        val script = initScript("smxEditorQuery", "hi", "a.kt", true, 15)
        val shimIdx = script.indexOf("window.AndroidEditor")
        val cmInitIdx = script.indexOf("cmInit(")
        assertTrue(shimIdx in 0 until cmInitIdx, "cmInit must come AFTER the shim (shim=$shimIdx cmInit=$cmInitIdx)")
        // cmInit carries the quoted content + filename + wrap + size.
        assertTrue(script.contains("cmInit(\"hi\", \"a.kt\", true, 15);"), "cmInit args wrong: $script")
    }

    // ── parseBridgeEvent ─────────────────────────────────────────────────────

    @Test
    fun parse_change_carries_the_content() {
        val e = parseBridgeEvent("""{"fn":"onChange","arg":"new text"}""")
        assertEquals(BridgeEvent.Change("new text"), e)
    }

    @Test
    fun parse_save_and_ready_are_singletons() {
        assertEquals(BridgeEvent.Save, parseBridgeEvent("""{"fn":"onSave","arg":""}"""))
        assertEquals(BridgeEvent.Ready, parseBridgeEvent("""{"fn":"onReady"}"""))
    }

    @Test
    fun parse_font_size_reads_the_numeric_arg() {
        assertEquals(BridgeEvent.FontSize(18), parseBridgeEvent("""{"fn":"onFontSize","arg":"18"}"""))
    }

    @Test
    fun parse_font_size_with_non_numeric_arg_is_null() {
        assertNull(parseBridgeEvent("""{"fn":"onFontSize","arg":"big"}"""))
    }

    @Test
    fun parse_lsp_out_carries_the_payload() {
        assertEquals(BridgeEvent.LspOut("""{"serverId":"x"}"""), parseBridgeEvent("""{"fn":"lspOut","arg":"{\"serverId\":\"x\"}"}"""))
    }

    @Test
    fun parse_diff_line_click_and_expand() {
        assertEquals(BridgeEvent.DiffLineClick(42), parseBridgeEvent("""{"fn":"onDiffLineClick","arg":"42"}"""))
        assertEquals(BridgeEvent.DiffExpand("up"), parseBridgeEvent("""{"fn":"onDiffExpand","arg":"up"}"""))
        assertNull(parseBridgeEvent("""{"fn":"onDiffExpand","arg":"sideways"}"""))
        assertEquals(BridgeEvent.DiffPage("next"), parseBridgeEvent("""{"fn":"onDiffPage","arg":"next"}"""))
    }

    @Test
    fun show_diff_region_js_passes_quoted_payload_to_bundle() {
        val js = showDiffRegionJs(
            path = "src/A\".kt",
            content = "one\ntwo\nthree",
            ranges = listOf(DiffRegionRange(2, 3, "change")),
            language = "kotlin",
        )
        assertTrue(js.startsWith("window.cmShowDiffRegion && window.cmShowDiffRegion("))
        assertTrue(js.contains("src/A\\\".kt"))
        assertTrue(js.contains("\"startLine\":2"))
        assertTrue(js.contains("\"language\":\"kotlin\""))
        val restored = showDiffRegionJs("a.kt", "one", emptyList(), "kotlin", restoreScrollTop = 73)
        assertTrue(restored.endsWith("window.cmSetScrollTop(73)})"))
    }

    // ── In-editor comment threads (block widgets) ───────────────────────────

    @Test
    fun show_diff_region_js_carries_threads_and_composer() {
        val js = showDiffRegionJs(
            path = "src/A.kt",
            content = "one\ntwo\nthree",
            ranges = listOf(DiffRegionRange(2, 2, "add")),
            language = "kotlin",
            threads = listOf(
                DiffRegionThread(
                    id = "c1", line = 2, status = "open",
                    comments = listOf(
                        DiffRegionComment("c1", "user", "why?", "2026-01-01"),
                        DiffRegionComment("c2", "agent", "because", ""),
                    ),
                ),
            ),
            composer = DiffRegionComposer(line = 3, draft = "half a thought"),
        )
        assertTrue(js.contains("\"threads\":["), js)
        assertTrue(js.contains("\"id\":\"c1\""), js)
        assertTrue(js.contains("\"line\":2"), js)
        assertTrue(js.contains("\"status\":\"open\""), js)
        assertTrue(js.contains("\"author\":\"agent\""), js)
        assertTrue(js.contains("\"createdAt\":\"2026-01-01\""), js)
        assertTrue(js.contains("\"composer\":{\"line\":3,\"draft\":\"half a thought\"}"), js)
    }

    @Test
    fun show_diff_region_js_omits_a_closed_composer_and_defaults_threads_empty() {
        val js = showDiffRegionJs("a.kt", "one", emptyList(), "kotlin")
        assertTrue(js.contains("\"threads\":[]"), js)
        assertTrue(js.contains("\"composer\":null"), js)
    }

    @Test
    fun bridge_shim_defines_the_comment_callbacks() {
        val shim = bridgeShimJs("q")
        for (fn in listOf("onCommentSubmit", "onReplySubmit", "onResolveThread", "onComposerState")) {
            assertTrue(shim.contains("$fn: function"), "missing $fn in shim")
        }
    }

    @Test
    fun parse_comment_submit_reply_resolve_and_composer_state() {
        assertEquals(
            BridgeEvent.CommentSubmit(12, "looks wrong"),
            parseBridgeEvent("""{"fn":"onCommentSubmit","arg":"{\"line\":12,\"text\":\"looks wrong\"}"}"""),
        )
        assertEquals(
            BridgeEvent.ReplySubmit("c1", "fixed"),
            parseBridgeEvent("""{"fn":"onReplySubmit","arg":"{\"threadId\":\"c1\",\"text\":\"fixed\"}"}"""),
        )
        assertEquals(
            BridgeEvent.ResolveThread("c1"),
            parseBridgeEvent("""{"fn":"onResolveThread","arg":"c1"}"""),
        )
        assertEquals(
            BridgeEvent.ComposerState(7, "draft"),
            parseBridgeEvent("""{"fn":"onComposerState","arg":"{\"line\":7,\"text\":\"draft\"}"}"""),
        )
        // line 0 = the composer closed (Esc / Cancel / submit) — still a valid event.
        assertEquals(
            BridgeEvent.ComposerState(0, ""),
            parseBridgeEvent("""{"fn":"onComposerState","arg":"{\"line\":0,\"text\":\"\"}"}"""),
        )
    }

    @Test
    fun parse_rejects_empty_or_malformed_comment_payloads() {
        assertNull(parseBridgeEvent("""{"fn":"onCommentSubmit","arg":"{\"line\":0,\"text\":\"x\"}"}"""))
        assertNull(parseBridgeEvent("""{"fn":"onCommentSubmit","arg":"{\"line\":4,\"text\":\"   \"}"}"""))
        assertNull(parseBridgeEvent("""{"fn":"onReplySubmit","arg":"{\"threadId\":\"\",\"text\":\"x\"}"}"""))
        assertNull(parseBridgeEvent("""{"fn":"onResolveThread","arg":"  "}"""))
        assertNull(parseBridgeEvent("""{"fn":"onCommentSubmit","arg":"not json"}"""))
    }

    @Test
    fun parse_eval_result_reads_the_nested_request_id_and_value() {
        assertEquals(
            BridgeEvent.EvalResult(17, "hello\nworld"),
            parseBridgeEvent("""{"fn":"evalResult","arg":"{\"id\":17,\"value\":\"hello\\nworld\"}"}"""),
        )
    }

    @Test
    fun parse_eval_result_rejects_a_missing_or_negative_request_id() {
        assertNull(parseBridgeEvent("""{"fn":"evalResult","arg":"{\"value\":\"x\"}"}"""))
        assertNull(parseBridgeEvent("""{"fn":"evalResult","arg":"{\"id\":-1,\"value\":\"x\"}"}"""))
    }

    @Test
    fun parse_unknown_fn_is_null() {
        assertNull(parseBridgeEvent("""{"fn":"onWat","arg":"x"}"""))
    }

    @Test
    fun parse_malformed_json_is_null() {
        assertNull(parseBridgeEvent("not json"))
        assertNull(parseBridgeEvent(""))
        assertNull(parseBridgeEvent("""{"fn":}"""))
    }

    @Test
    fun parse_content_with_escaped_quotes_and_newlines_round_trips() {
        // The shim does JSON.stringify({fn,arg}); a content arg with quotes+newlines arrives escaped.
        val content = "a \"quoted\" line\nand another"
        // `JsonObject.toString()` IS its JSON encoding — used directly here because the reified
        // `Json.encodeToString(value)` overload does not resolve against a `JsonElement` on :ui's
        // serialization classpath.
        val request = kotlinx.serialization.json.JsonObject(
            mapOf(
                "fn" to kotlinx.serialization.json.JsonPrimitive("onChange"),
                "arg" to kotlinx.serialization.json.JsonPrimitive(content),
            ),
        ).toString()
        assertEquals(BridgeEvent.Change(content), parseBridgeEvent(request))
    }

    @Test
    fun eval_result_script_routes_a_stringified_value_through_the_named_query_function() {
        val script = evalResultJs("smxEditorQuery", 42, "cmGetContent()")
        assertTrue(script.contains("window.smxEditorQuery"))
        assertTrue(script.contains("fn: \"evalResult\""))
        assertTrue(script.contains("id: 42"))
        assertTrue(script.contains("send((cmGetContent()))"))
        assertTrue(script.contains("catch (e) { send(\"\"); }"))
    }

    // ── parseLspOut (M4g-3) ──────────────────────────────────────────────────

    @Test
    fun parse_lsp_out_extracts_server_id_and_message() {
        val payload = """{"serverId":"ts","message":"{\"jsonrpc\":\"2.0\",\"id\":1}"}"""
        val (serverId, message) = parseLspOut(payload) ?: error("expected a parsed pair")
        assertEquals("ts", serverId)
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", message)
    }

    @Test
    fun parse_lsp_out_returns_null_for_malformed_json() {
        assertNull(parseLspOut("not json"))
        assertNull(parseLspOut("{"))
    }

    @Test
    fun parse_lsp_out_returns_null_when_server_id_is_missing_or_blank() {
        assertNull(parseLspOut("""{"message":"hi"}"""))
        assertNull(parseLspOut("""{"serverId":"","message":"hi"}"""))
    }

    @Test
    fun parse_lsp_out_defaults_a_missing_message_to_empty_string() {
        val (serverId, message) = parseLspOut("""{"serverId":"ts"}""") ?: error("expected a parsed pair")
        assertEquals("ts", serverId)
        assertEquals("", message)
    }

    // ── LSP JS-statement builders (M4g-3; pure — mirrors EditorPushPlanner's cmSet* builders) ──

    @Test
    fun lsp_connect_js_quotes_all_four_arguments() {
        val js = lspConnectJs("ts", "file:///root/", "file:///root/a.ts", "typescript")
        assertEquals(
            "window.cmLspConnect(\"ts\",\"file:///root/\",\"file:///root/a.ts\",\"typescript\")",
            js,
        )
    }

    @Test
    fun lsp_connect_js_escapes_a_uri_containing_quotes_or_spaces() {
        val js = lspConnectJs("ts", "file:///my project/", "file:///my \"weird\" file.ts", "typescript")
        assertTrue(js.contains("\\\"weird\\\""), "interior quote not escaped: $js")
        assertTrue(js.contains("my project"))
    }

    @Test
    fun lsp_message_js_quotes_both_arguments() {
        val js = lspMessageJs("ts", "{\"id\":1}")
        assertEquals("window.cmLspMessage(\"ts\",\"{\\\"id\\\":1}\")", js)
    }

    @Test
    fun lsp_disconnect_js_is_a_guarded_call() {
        assertEquals("window.cmLspDisconnect && window.cmLspDisconnect()", lspDisconnectJs())
    }
}
