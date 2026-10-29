package dev.supermux.webview.engine.ios

import platform.Foundation.NSNull
import platform.Foundation.NSNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Script results as Kotlin/Native hands WebKit's over: strings, Doubles, Booleans, NSNull, lists and maps. */
class JsonValuesTest {
    private fun json(value: Any?) = jsonElementOf(value)?.toString()

    @Test fun scalarsBecomeJson() {
        assertEquals("\"https://c1.invalid/a\"", json("https://c1.invalid/a"))
        assertEquals("true", json(true))
        assertEquals("1", json(1.0))
        assertEquals("1.5", json(1.5))
        assertEquals("null", json(NSNull()))
        assertEquals("null", json(null))
    }

    @Test fun anNSNumberBooleanStaysABoolean() {
        assertEquals("true", json(NSNumber(bool = true)))
        assertEquals("7", json(NSNumber(int = 7)))
    }

    @Test fun collectionsBecomeJson() {
        assertEquals("[1,\"a\",false]", json(listOf(1.0, "a", false)))
        assertEquals("{\"a\":[null]}", json(mapOf("a" to listOf(NSNull()))))
    }

    @Test fun whatJsonCantHoldIsNull() {
        assertNull(json(Double.NaN))
        assertNull(json(mapOf(1 to "a")))
        assertNull(json(Any()))
    }
}
