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

    private val title = StringKey(0)
    private val shared = StringKey(1)
    private val emptyKw = StringKey(2)
    private val hello = StringKey(3)
    private val files = PluralKey(0)

    private val english = parseCatalog(
        "s\t0\tSettings\ns\t1\tOnly in English\ns\t2\t\ns\t3\tHello %1\$s, %2\$d new\n" +
            "p\t0\tone\t%1\$d file\np\t0\tother\t%1\$d files\n",
        strings = 4,
        plurals = 1,
    )
    private val turkish = parseCatalog(
        "s\t0\tAyarlar\ns\t1\t\ns\t3\tMerhaba %1\$s, %2\$d yeni\np\t0\tone\t%1\$d dosya\np\t0\tother\t%1\$d dosya\n",
        strings = 4,
        plurals = 1,
    )
    private val none = parseCatalog("", strings = 4, plurals = 1)

    @Test fun reads_strings_and_plurals_by_number() {
        assertEquals("Settings", english.text(title))
        assertEquals(mapOf("one" to "%1\$d file", "other" to "%1\$d files"), english.plurals[0])
        assertEquals(null, none.text(title))
    }

    @Test fun unescapes_the_compact_format() {
        val c = parseCatalog("s\t0\tline one\\nline two\\ttab \\\\ back\n", strings = 1, plurals = 0)
        assertEquals("line one\nline two\ttab \\ back", c.text(StringKey(0)))
    }

    @Test fun the_chosen_language_wins_and_english_fills_what_it_lacks() {
        assertEquals("Ayarlar", lookup(title, turkish, english))
        assertEquals("Only in English", lookup(shared, turkish, english), "an empty translation falls back")
        assertEquals("Only in English", lookup(shared, none, english), "a missing key falls back")
        assertEquals("", lookup(emptyKw, turkish, english), "empty in English stays empty")
    }

    @Test fun a_missing_catalog_falls_back_to_english_and_nothing_is_ever_blank() {
        assertEquals("Settings", lookup(title, null, english))
        assertEquals(UNREADABLE, lookup(title, null, null))
        assertTrue(UNREADABLE.isNotBlank())
        assertEquals(UNREADABLE, lookupPlural(files, "tr", 2, null, null))
    }

    @Test fun plural_rules_for_english_and_turkish() {
        assertEquals("one", pluralCategory("en", 1))
        assertEquals("other", pluralCategory("en", 0))
        assertEquals("other", pluralCategory("en", 2))
        assertEquals("one", pluralCategory("tr", 1))
        assertEquals("other", pluralCategory("tr", 5))
        assertEquals("1 file", formatCatalogText(lookupPlural(files, "en", 1, null, english), arrayOf(1)))
        assertEquals("3 files", formatCatalogText(lookupPlural(files, "en", 3, null, english), arrayOf(3)))
        assertEquals("3 dosya", formatCatalogText(lookupPlural(files, "tr", 3, turkish, english), arrayOf(3)))
        assertEquals("2 files", formatCatalogText(lookupPlural(files, "tr", 2, none, english), arrayOf(2)))
    }

    @Test fun placeholders_fill_by_position_and_a_lone_percent_stays() {
        assertEquals("Merhaba Ada, 3 yeni", formatCatalogText(lookup(hello, turkish, english), arrayOf<Any>("Ada", 3)))
        assertEquals("%62", formatCatalogText("%%1\$d", arrayOf(62)))
        assertEquals("62%", formatCatalogText("%1\$d%", arrayOf(62)))
    }

    @Test fun the_shipped_catalogs_read_and_turkish_has_every_english_key() {
        val en = Catalogs.now("en")!!
        val tr = Catalogs.now("tr")!!
        assertTrue(en.strings.count { it != null } > 1000, "English has ${en.strings.count { it != null }} strings")
        assertEquals(emptyList(), en.strings.indices.filter { en.strings[it] != null && tr.strings[it] == null })
        assertEquals("Settings", en.text(Str.settings_title))
        assertEquals("Ayarlar", tr.text(Str.settings_title))
        assertEquals(listOf("en", "tr"), Catalogs.languages)
        assertEquals("en", shippedLanguage("de"), "a language without a catalog shows English")
    }

    @Test fun localized_outside_any_provider_reads_english() = runComposeUiTest {
        setContent { Text(localized(Str.settings_title)) }
        onNodeWithText("Settings").assertExists()
    }

    @Test fun localized_with_a_turkish_catalog_missing_a_key_shows_the_english() = runComposeUiTest {
        val partial = parseCatalog("s\t${Str.settings_title.id}\tAyarlar\n")
        setContent {
            CompositionLocalProvider(LocalCatalogs provides LoadedLanguage("tr", partial, Catalogs.now("en"))) {
                Text(localized(Str.settings_title) + " / " + localized(Str.settings_back))
            }
        }
        onNodeWithText("Ayarlar / Back").assertExists()
    }

    @Test fun search_labels_are_found_by_name_and_nothing_else_is() {
        assertTrue(I18nFiles.searchKey("search_general_theme") >= 0)
        assertEquals(-1, I18nFiles.searchKey("settings_title"))
    }

    @Test fun the_web_url_of_a_catalog_carries_its_version_and_nothing_else_changes() {
        assertTrue(versionedResourcePath("files/i18n/tr.txt").startsWith("files/i18n/tr.txt?v="))
        assertEquals("font/geist.ttf", versionedResourcePath("font/geist.ttf"))
    }
}
