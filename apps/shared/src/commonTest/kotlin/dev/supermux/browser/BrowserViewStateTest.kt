package dev.supermux.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserViewStateTest {
    @Test fun theStateIsAPageADocOrNothingYet() {
        assertEquals(JsonObject(mapOf("url" to JsonPrimitive("https://vitejs.dev/"))), BrowserViewState.page("https://vitejs.dev/")!!.toJson())
        assertEquals(JsonObject(mapOf("doc" to JsonPrimitive("h_7f3a"))), BrowserViewState.doc("h_7f3a")!!.toJson())
        assertEquals(JsonObject(emptyMap()), BrowserViewState.Empty.toJson())
    }

    @Test fun itRefusesWhatTheBrokerRefuses() {
        assertNull(BrowserViewState.page("javascript:alert(1)"))
        assertNull(BrowserViewState.page("file:///etc/passwd"))
        assertNull(BrowserViewState.page("https://x.test/${"a".repeat(4100)}"))
        assertNull(BrowserViewState.doc("../../etc"))
    }

    @Test fun itReadsBackWhatTheBrokerStored() {
        assertEquals<BrowserViewState?>(BrowserViewState.page("https://vitejs.dev/"), BrowserViewState.from(JsonObject(mapOf("url" to JsonPrimitive("https://vitejs.dev/")))))
        assertEquals(BrowserViewState.Empty, BrowserViewState.from(JsonObject(emptyMap())))
        assertEquals(BrowserViewState.Empty, BrowserViewState.from(JsonObject(mapOf("url" to JsonPrimitive("javascript:x")))), "a bad stored page opens empty")
    }
}
