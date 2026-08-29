package dev.supermux.android.session

import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneLayerBackTest {
    @Test fun wideNeverConsumes() {
        assertEquals(
            PhoneLayerBackAction.None,
            phoneLayerBackAction(wide = true, editorConsumesBack = false, imeVisible = false),
        )
        assertEquals(
            PhoneLayerBackAction.None,
            phoneLayerBackAction(wide = true, editorConsumesBack = false, imeVisible = true),
        )
    }

    @Test fun editorOwnsBackEvenWhenImeVisible() {
        assertEquals(
            PhoneLayerBackAction.None,
            phoneLayerBackAction(wide = false, editorConsumesBack = true, imeVisible = true),
        )
        assertEquals(
            PhoneLayerBackAction.None,
            phoneLayerBackAction(wide = false, editorConsumesBack = true, imeVisible = false),
        )
    }

    @Test fun imeFirstThenClearSelection() {
        assertEquals(
            PhoneLayerBackAction.HideIme,
            phoneLayerBackAction(wide = false, editorConsumesBack = false, imeVisible = true),
        )
        assertEquals(
            PhoneLayerBackAction.ClearSelection,
            phoneLayerBackAction(wide = false, editorConsumesBack = false, imeVisible = false),
        )
    }
}
