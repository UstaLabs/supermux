package dev.supermux.ui.whatsnew

import dev.supermux.ui.chat.CARD_THEME_VARIABLES
import dev.supermux.ui.chat.CardTheme
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The What's new page (#90): the bundled edition, told which platform it is on, what the app can do, and its theme. */
class WhatsNewPageTest {

    private val theme = CardTheme(dark = true, vars = CARD_THEME_VARIABLES.map { it to "#112233" })

    @Test fun the_page_is_told_its_platform_and_the_actions_the_app_takes() {
        val doc = whatsNewDocument(
            "<!doctype html><html lang=\"en\"><head><title>x</title></head><body></body></html>",
            theme, ReleasePlatform.Windows, setOf(WhatsNewAction.Done, WhatsNewAction.Settings),
        )
        assertTrue("data-platform=\"win\"" in doc, doc)
        assertTrue("data-actions=\"done settings\"" in doc, doc)
        assertTrue("--sm-fg:#112233" in doc, "the app's theme, as a card gets it")
        assertTrue(doc.startsWith("<!doctype html>"))
    }

    @Test fun an_unknown_platform_says_none() {
        val doc = whatsNewDocument("<html><head></head><body></body></html>", theme, null, emptySet())
        assertTrue("data-platform=\"\"" in doc, doc)
    }

    @Test fun the_page_asks_for_actions_by_name_and_anything_else_is_ignored() {
        assertEquals(WhatsNewAction.Done, whatsNewAction(buildJsonObject { put("action", JsonPrimitive("done")) }))
        assertEquals(WhatsNewAction.ResumeSetting, whatsNewAction(buildJsonObject { put("action", JsonPrimitive("resume-setting")) }))
        assertNull(whatsNewAction(buildJsonObject { put("action", JsonPrimitive("format-disk")) }))
        assertNull(whatsNewAction(JsonPrimitive("done")))
    }

    @Test fun every_shipped_edition_is_bundled_and_names_its_own_version() = runTest {
        for (version in EDITIONS) {
            val edition = parseEdition(loadEdition(version))
            assertEquals(version, edition?.version, "files/whatsnew/$version.html")
            assertTrue(edition!!.chapters.isNotEmpty())
        }
    }
}
