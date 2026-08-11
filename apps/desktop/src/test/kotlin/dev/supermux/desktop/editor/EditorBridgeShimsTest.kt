package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the JCEF bridge: JS quoting, shim/init script shape, payload parse, push plan. */
class EditorBridgeShimsTest {

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
        // The four bundle callbacks + lspOut are present.
        for (fn in listOf("onChange", "onSave", "onReady", "onFontSize", "lspOut")) {
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
        val request = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "fn" to kotlinx.serialization.json.JsonPrimitive("onChange"),
                    "arg" to kotlinx.serialization.json.JsonPrimitive(content),
                ),
            ),
        )
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
