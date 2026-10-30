package dev.supermux.webview.layouttest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageMessagesTest {
    @Test fun painted() = assertEquals(PageEvent.Painted, PageMessages.parse("""{"t":"painted"}"""))

    @Test fun aPointerDownKeepsItsPositionAndTarget() =
        assertEquals(PageEvent.Down(12.5f, 40f, "ta"), PageMessages.parse("""{"t":"down","x":12.5,"y":40,"target":"ta"}"""))

    @Test fun scrollAndInput() {
        assertEquals(PageEvent.Scrolled(1200f), PageMessages.parse("""{"t":"scroll","y":1200}"""))
        assertEquals(PageEvent.Input("çşğ 日本"), PageMessages.parse("""{"t":"input","value":"çşğ 日本"}"""))
    }

    @Test fun frameIntervals() =
        assertEquals(PageEvent.Frames(listOf(16.6, 16.8)), PageMessages.parse("""{"t":"frames","i":[16.6,16.8]}"""))

    @Test fun theTextBoxLayout() = assertEquals(
        PageEvent.Layout(listOf(500f, 40f, 260f, 80f), 784f, 480f),
        PageMessages.parse("""{"t":"layout","ta":[500,40,260,80],"vw":784,"vh":480}"""),
    )

    @Test fun anAccessibilityProbe() =
        assertEquals(PageEvent.Ax(true, 42, false), PageMessages.parse("""{"t":"ax","reaches":true,"nodes":42,"bridge":false}"""))

    @Test fun anythingElseIsUnknownAndNeverThrows() {
        for (raw in listOf("", "not json", "[1,2]", """{"t":{"x":1}}""", """{"t":"down","x":"a"}""", """{"t":"frames","i":[{}]}""", """{"t":"nope"}""")) {
            assertEquals(PageEvent.Unknown(raw), PageMessages.parse(raw), raw)
        }
    }

    @Test fun windowInfoReadsTheGluesJson() {
        val info = WindowInfo.parse(
            """{"windowNumber":4711,"backingScale":2,"frame":[100,200,1200,888],"content":[1200,860],"titlebar":28,""" +
                """"maxFps":60,"screen":"Color LCD","isKey":true,"isActive":1,"visible":true,""" +
                """"inputSource":"com.apple.keylayout.Turkish","canPostEvents":false,"macos":"Version 26.6.2"}""",
        )
        assertEquals(4711L, info.windowNumber)
        assertEquals(2.0, info.backingScale)
        assertEquals(1200.0, info.contentWidth)
        assertEquals(28.0, info.titlebar)
        assertTrue(info.isKey)
        assertTrue(info.isActive, "a boolean the glue wrote as 1 still reads as true")
        assertFalse(info.inputMethodActive)
        assertEquals(1000.0 / 60, info.nominalFrameMillis)
    }

    @Test fun anInputMethodIsRecognised() {
        val info = WindowInfo.parse(
            """{"windowNumber":1,"backingScale":2,"frame":[0,0,1,1],"content":[1,1],"titlebar":28,"maxFps":120,"screen":"",""" +
                """"isKey":false,"isActive":false,"visible":false,"inputSource":"com.apple.inputmethod.Kotoeri.RomajiTyping.Japanese",""" +
                """"canPostEvents":false,"macos":""}""",
        )
        assertTrue(info.inputMethodActive)
    }
}
