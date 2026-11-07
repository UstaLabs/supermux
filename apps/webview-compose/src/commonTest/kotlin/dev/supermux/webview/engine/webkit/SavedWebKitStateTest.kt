package dev.supermux.webview.engine.webkit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class SavedWebKitStateTest {
    private val webKit = byteArrayOf(1, 2, 3, 0, -1)

    @Test fun aStateComesBackForItsOwnContent() {
        val saved = SavedWebKitState.encode("bundle:plugin-1/index.html", webKit)
        assertContentEquals(webKit, SavedWebKitState.decode(saved, "bundle:plugin-1/index.html"))
    }

    @Test fun anotherContentsStateIsRefused() {
        val saved = SavedWebKitState.encode("bundle:plugin-1/index.html", webKit)
        assertNull(SavedWebKitState.decode(saved, "bundle:plugin-2/index.html"))
    }

    @Test fun anythingElseIsRefused() {
        assertNull(SavedWebKitState.decode(ByteArray(0), "k"))
        assertNull(SavedWebKitState.decode("fake:k".encodeToByteArray(), "k"))
        assertNull(SavedWebKitState.decode(byteArrayOf(87, 75, 83, 49, 127, 0, 0, 0, 1), "k"))
        assertNull(SavedWebKitState.decode(SavedWebKitState.encode("k", ByteArray(0)), "k"))
    }
}
