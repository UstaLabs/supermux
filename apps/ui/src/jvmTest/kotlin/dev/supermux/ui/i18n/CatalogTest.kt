package dev.supermux.ui.i18n

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #123: the catalogs — reading them, falling back to English, plural rules, placeholders. */
@OptIn(ExperimentalTestApi::class)
class CatalogTest {

    private val english = parseCatalog(
        """
        <resources>
            <!-- a comment <string name="ghost">no</string> -->
            <string name="settings_title">Settings</string>
            <string name="shared">Only in English</string>
            <string name="empty_kw"></string>
            <string name="hello">Hello %1${'$'}s, %2${'$'}d new</string>
            <plurals name="files">
                <item quantity="one">%1${'$'}d file</item>
                <item quantity="other">%1${'$'}d files</item>
            </plurals>
        </resources>
        """.trimIndent(),
    )
    private val turkish = parseCatalog(
        """
        <resources>
            <string name="settings_title">Ayarlar</string>
            <string name="hello">Merhaba %1${'$'}s, %2${'$'}d yeni</string>
            <string name="shared"></string>
            <plurals name="files">
                <item quantity="one">%1${'$'}d dosya</item>
                <item quantity="other">%1${'$'}d dosya</item>
            </plurals>
        </resources>
        """.trimIndent(),
    )

    @Test fun reads_strings_plurals_and_skips_comments() {
        assertEquals("Settings", english.strings["settings_title"])
        assertEquals(null, english.strings["ghost"])
        assertEquals(mapOf("one" to "%1\$d file", "other" to "%1\$d files"), english.plurals["files"])
    }

    @Test fun unescapes_entities_and_backslashes_as_compose_resources_did() {
        val c = parseCatalog("""<string name="a">GitHub &amp; GitLab &lt;3 \"x\" it\'s\nnext ç</string>""")
        assertEquals("GitHub & GitLab <3 \"x\" it's\nnext ç", c.strings["a"])
    }

    @Test fun the_chosen_language_wins_and_english_fills_what_it_lacks() {
        assertEquals("Ayarlar", lookup("settings_title", turkish, english))
        assertEquals("Only in English", lookup("shared", turkish, english), "an empty translation falls back")
        assertEquals("Only in English", lookup("shared", parseCatalog(""), english), "a missing key falls back")
        assertEquals("", lookup("empty_kw", turkish, english), "empty in English stays empty")
    }

    @Test fun a_missing_catalog_falls_back_to_english_and_nothing_ever_shows_a_raw_key() {
        assertEquals("Settings", lookup("settings_title", null, english))
        // Even with no catalog at all (a failed read), the text is words, never `settings_title`.
        val none = lookup("settings_title", null, null)
        assertTrue(none.isNotBlank() && '_' !in none, none)
        val plural = lookupPlural("files_count", "tr", 2, null, null)
        assertTrue(plural.isNotBlank() && '_' !in plural, plural)
    }

    @Test fun plural_rules_for_english_and_turkish() {
        assertEquals("one", pluralCategory("en", 1))
        assertEquals("other", pluralCategory("en", 0))
        assertEquals("other", pluralCategory("en", 2))
        assertEquals("one", pluralCategory("tr", 1))
        assertEquals("other", pluralCategory("tr", 5))
        assertEquals("1 file", formatCatalogText(lookupPlural("files", "en", 1, null, english), arrayOf(1)))
        assertEquals("3 files", formatCatalogText(lookupPlural("files", "en", 3, null, english), arrayOf(3)))
        assertEquals("3 dosya", formatCatalogText(lookupPlural("files", "tr", 3, turkish, english), arrayOf(3)))
        assertEquals("2 files", formatCatalogText(lookupPlural("files", "tr", 2, parseCatalog(""), english), arrayOf(2)))
    }

    @Test fun placeholders_fill_by_position_and_a_lone_percent_stays() {
        assertEquals("Merhaba Ada, 3 yeni", formatCatalogText(lookup("hello", turkish, english), arrayOf<Any>("Ada", 3)))
        assertEquals("%62", formatCatalogText("%%1\$d", arrayOf(62)))
        assertEquals("62%", formatCatalogText("%1\$d%", arrayOf(62)))
    }

    @Test fun the_shipped_catalogs_read_and_turkish_has_every_english_key() {
        val en = Catalogs.now("en")!!
        val tr = Catalogs.now("tr")!!
        assertTrue(en.strings.size > 1000, "English has ${en.strings.size} strings")
        assertEquals(emptySet(), en.strings.keys - tr.strings.keys)
        assertEquals(listOf("en", "tr"), Catalogs.languages)
        assertEquals("en", shippedLanguage("de"), "a language without a catalog shows English")
    }

    @Test fun localized_outside_any_provider_reads_english() = runComposeUiTest {
        setContent { Text(localized(Str.settings_title)) }
        onNodeWithText("Settings").assertExists()
    }

    @Test fun localized_with_a_turkish_catalog_missing_a_key_shows_the_english() = runComposeUiTest {
        val partial = Catalog(mapOf("settings_title" to "Ayarlar"), emptyMap())
        setContent {
            CompositionLocalProvider(LocalCatalogs provides LoadedLanguage("tr", partial, Catalogs.now("en"))) {
                Text(localized(Str.settings_title) + " / " + localized(Str.settings_back))
            }
        }
        onNodeWithText("Ayarlar / Back").assertExists()
    }

    @Test fun the_web_url_of_a_catalog_carries_its_version_and_nothing_else_changes() {
        assertTrue(versionedResourcePath("files/i18n/values-tr/shell.xml").startsWith("files/i18n/values-tr/shell.xml?v="))
        assertEquals("font/geist.ttf", versionedResourcePath("font/geist.ttf"))
    }
}
