package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure half of the KCEF bridge: JS quoting, shim/init script shape, payload parse, push plan. */
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
}
