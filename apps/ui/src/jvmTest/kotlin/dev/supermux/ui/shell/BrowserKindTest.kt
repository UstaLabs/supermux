package dev.supermux.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import dev.supermux.proto.ViewDto
import dev.supermux.workspace.NewViewKind
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserKindTest {
    @Test fun browserIsTheSixthRowOnBothPages() {
        assertEquals(NewViewKind.BROWSER, NewViewKind.entries[5])
        assertEquals("Browser", NewViewKind.BROWSER.label)
        assertTrue(NewViewKind.BROWSER in phoneAddKinds())
    }

    @Test fun aNewBrowserTabStartsWithNoPage() {
        // `{}`: the broker's "new tab" state (browserStateError accepts it).
        assertEquals(JsonObject(emptyMap()), addViewState(NewViewKind.BROWSER))
    }

    @Test fun closingABrowserTabAsksNothing() {
        // Its page is a link: closing it stops nothing.
        assertFalse(closeNeedsConfirmation("browser"))
        assertTrue(closeNeedsConfirmation("terminal"))
    }

    @Test fun aBrowserTabHasTheGlobeOnTheSwitcher() {
        assertEquals(Icons.Filled.Language, ViewDto(id = "b", workspaceId = "w", kind = "browser").switcherIcon())
    }
}
