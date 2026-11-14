package dev.supermux.ui.i18n

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #123: Android starts reading the catalogs at Application.onCreate, off the main thread, so the first string on
 * screen never parses a catalog on the UI thread (measured: ~+20 ms of cold start on the Fold before this).
 */
@OptIn(ExperimentalTestApi::class)
class CatalogsWarmTest {

    @BeforeTest fun fresh() = Catalogs.forgetForTest()

    @AfterTest fun again() = Catalogs.forgetForTest()

    @Test fun warming_reads_every_shipped_catalog_once_and_now_reuses_it() {
        Catalogs.warm()
        val en = runBlocking { Catalogs.awaitWarmForTest("en") }
        val tr = runBlocking { Catalogs.awaitWarmForTest("tr") }
        assertSame(en, Catalogs.now("en"), "now() hands back the warmed catalog, not a second parse")
        assertSame(tr, Catalogs.now("tr"))
    }

    @Test fun now_while_warming_waits_for_that_read_instead_of_starting_another() {
        Catalogs.warm()
        val en = Catalogs.now("en")
        assertEquals("Settings", en?.text(Str.settings_title))
        assertSame(en, runBlocking { Catalogs.awaitWarmForTest("en") }, "the very read warm() started, not a second")
    }

    @Test fun the_first_frame_after_warming_shows_english() = runComposeUiTest {
        Catalogs.warm()
        setContent { Text(localized(Str.settings_title)) }
        onNodeWithText("Settings").assertExists()
    }

    @Test fun without_warming_the_first_string_still_reads_english() = runComposeUiTest {
        setContent { Text(localized(Str.settings_title)) }
        onNodeWithText("Settings").assertExists()
    }
}
