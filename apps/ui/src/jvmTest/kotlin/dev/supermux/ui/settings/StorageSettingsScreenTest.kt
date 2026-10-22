package dev.supermux.ui.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.media.MediaCache
import dev.supermux.media.MediaKey
import dev.supermux.media.MediaStore
import dev.supermux.ui.chat.setPlatformContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class StorageSettingsScreenTest {
    /** Just enough of a store for the screen: a size, and a Clear that empties it. */
    private class SizedStore(var size: Long, override val persistent: Boolean = true) : MediaStore {
        var clears = 0
        override val hasFiles: Boolean get() = true

        override suspend fun read(key: MediaKey): ByteArray? = null

        override suspend fun exists(key: MediaKey): Boolean = false

        override suspend fun uri(key: MediaKey): String? = null

        override suspend fun write(key: MediaKey, bytes: ByteArray): Boolean = false

        override suspend fun writeStream(
            key: MediaKey,
            fill: suspend (append: suspend (ByteArray, Int) -> Unit) -> Boolean,
        ): Boolean = false

        override suspend fun delete(key: MediaKey) = Unit

        override suspend fun removeHost(host: String) = Unit

        override suspend fun clear() {
            clears++
            size = 0
        }

        override suspend fun sizeBytes(): Long = size
    }

    @Test fun showsTheSizeAndClearEmptiesIt() = runComposeUiTest {
        val store = SizedStore(size = 2_500_000)
        val cache = MediaCache(store, CoroutineScope(Dispatchers.Default))
        setPlatformContent { StorageSettingsScreen(cache = cache) }
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("storage_size").assertTextEquals("2 MB") }.isSuccess }
        onNodeWithTag("storage_clear").performClick()
        waitUntil(timeoutMillis = 5_000L) { runCatching { onNodeWithTag("storage_size").assertTextEquals("Empty") }.isSuccess }
        assertEquals(1, store.clears)
        onNodeWithTag("storage_clear").assertIsNotEnabled()
    }

    @Test fun aMemoryOnlyCacheSaysSo() = runComposeUiTest {
        val cache = MediaCache(SizedStore(size = 0, persistent = false), CoroutineScope(Dispatchers.Default))
        setPlatformContent { StorageSettingsScreen(cache = cache) }
        onNodeWithText("Kept only until this page closes", substring = true).assertExists()
    }
}
