package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `/transcribe/stream` wire as the client reads and writes it. */
class DictationStreamWireTest {

    @Test fun the_url_uses_the_ws_scheme_and_escapes_its_parameters() {
        assertEquals(
            "wss://host:9898/transcribe/stream?dictationId=abc-1",
            dictationStreamUrl("https://host:9898", "abc-1", null),
        )
        assertEquals(
            "ws://h/transcribe/stream?dictationId=d&session=s%20%C3%A7%26x",
            dictationStreamUrl("http://h/", "d", "s ç&x"),
        )
        assertEquals("ws://h/transcribe/stream?dictationId=d", dictationStreamUrl("ws://h", "d", ""))
    }

    @Test fun broker_frames_decode() {
        assertEquals(DictationStreamEvent.Text("hi there", 3), decodeDictationStreamEvent("""{"type":"text","text":"hi there","seq":3}"""))
        assertEquals(DictationStreamEvent.Text("hi"), decodeDictationStreamEvent("""{"type":"text","text":"hi"}"""))
        assertEquals(DictationStreamEvent.Final("done.", pendingEdit = true), decodeDictationStreamEvent("""{"type":"final","text":"done.","pendingEdit":true}"""))
        assertEquals(DictationStreamEvent.Final("", pendingEdit = false), decodeDictationStreamEvent("""{"type":"final","text":""}"""))
        assertEquals(DictationStreamEvent.Done, decodeDictationStreamEvent("""{"type":"done"}"""))
        assertEquals(DictationStreamEvent.Error("models down", fatal = true), decodeDictationStreamEvent("""{"type":"error","message":"models down","fatal":true}"""))
        assertNull(decodeDictationStreamEvent("""{"type":"speech","active":true}"""), "unknown frames are skipped")
        assertNull(decodeDictationStreamEvent("""{"type":"text"}"""), "a text frame without text is not one")
        assertNull(decodeDictationStreamEvent("not json"))
    }

    @Test fun app_frames_encode() {
        assertEquals("""{"type":"edit","text":"mine \"quoted\""}""", encodeDictationEdit("mine \"quoted\"", null))
        assertEquals("""{"type":"edit","text":"x","seq":7}""", encodeDictationEdit("x", 7))
        assertEquals("""{"type":"stop"}""", encodeDictationControl("stop"))
        assertEquals("""{"type":"cancel"}""", encodeDictationControl("cancel"))
    }
}
