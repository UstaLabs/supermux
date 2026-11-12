package dev.supermux.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #122 pick and comment: what the picker's scripts answer, read back; the scripts themselves run in the Mac check. */
class PagePickTest {
    @Test fun anElementIsReadWithItsSelectorTextLabelAndBox() {
        val json = """{"selector":"aside.summary > button.buy","tag":"button","text":"Pay €42","label":"button.buy · 312×44",""" +
            """"box":{"x":0.1,"y":0.5,"w":0.25,"h":0.05}}"""
        assertEquals(
            PageElement("aside.summary > button.buy", "button", "Pay €42", "button.buy · 312×44", PageBox(0.1f, 0.5f, 0.25f, 0.05f)),
            PickScripts.parseElement(json),
        )
        assertNull(PickScripts.parseElement("null"))
        assertNull(PickScripts.parseElement(null))
        assertNull(PickScripts.parseElement("""{"selector":"a"}"""), "no box, no element")
    }

    @Test fun boxesKeepTheirPlacesAndAGoneElementIsNull() {
        assertEquals(
            listOf(PageBox(0f, 0f, 1f, 0.5f), null),
            PickScripts.parseBoxes("""[{"x":0,"y":0,"w":1,"h":0.5},null]""", 2),
        )
        assertEquals(listOf(null, null), PickScripts.parseBoxes(null, 2), "no answer: none found")
    }

    /** A selector goes into the script as a JSON string: a quote in it can't end the script's string. */
    @Test fun selectorsAreQuotedIntoTheScript() {
        val script = PickScripts.boxesOf(listOf("""a[title="x'y"]"""))
        assertTrue("""["a[title=\"x'y\"]"]""" in script, script)
    }

    @Test fun thePointIsAFractionOfTheViewport() {
        val script = PickScripts.elementAt(0.25f, 0.5f, up = 1)
        assertTrue("innerWidth*0.25" in script && "innerHeight*0.5" in script && "i<1" in script, script)
    }
}
