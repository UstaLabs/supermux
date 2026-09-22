package dev.supermux.web

import dev.supermux.state.LocalStorageSettingsStore
import kotlinx.browser.localStorage
import kotlinx.browser.window
import org.w3c.dom.StorageEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStorageSettingsStoreTest {
    @BeforeTest fun clear() = localStorage.clear()

    @Test fun missingKeyIsNull() = runTest {
        assertNull(LocalStorageSettingsStore().string("nope").first())
    }

    @Test fun putThenRead() = runTest {
        val s = LocalStorageSettingsStore()
        s.putString("appearance:mode", "DARK")
        assertEquals("DARK", s.string("appearance:mode").first())
        assertEquals("DARK", s.stringNow("appearance:mode"))
        assertEquals("DARK", localStorage.getItem("supermux:appearance:mode"))
    }

    @Test fun putNullRemoves() = runTest {
        val s = LocalStorageSettingsStore()
        s.putString("k", "v"); s.putString("k", null)
        assertNull(s.string("k").first()); assertNull(localStorage.getItem("supermux:k"))
    }

    /**
     * The flow is a live view, not a one-shot read. Each write is followed by a [yield] so the
     * collector is actually resumed between them: the revision behind the flow is a StateFlow and
     * would otherwise conflate two same-tick writes into the last value, which would prove nothing.
     */
    @Test fun flowObservesLaterWrites() = runTest {
        val s = LocalStorageSettingsStore()
        val seen = mutableListOf<String?>()
        val job = launch { s.string("k").take(3).toList(seen) }
        yield()
        s.putString("k", "1"); yield()
        s.putString("k", "2"); yield()
        job.join()
        assertEquals(listOf(null, "1", "2"), seen)
    }

    /**
     * The backing store is process-global, so the change signal must be too: a store built by the
     * shell has to see a write made through the store the fleet holds.
     */
    @Test fun instancesObserveEachOther() = runTest {
        val reader = LocalStorageSettingsStore()
        val writer = LocalStorageSettingsStore()
        val seen = mutableListOf<String?>()
        val job = launch { reader.string("shared").take(2).toList(seen) }
        yield()
        writer.putString("shared", "from-the-other-instance"); yield()
        job.join()
        assertEquals(listOf(null, "from-the-other-instance"), seen)
    }

    /** Another tab's write raises `storage` here and nothing else does; the flow must follow it. */
    @Test fun crossTabStorageEventReEmits() = runTest {
        val s = LocalStorageSettingsStore()
        val seen = mutableListOf<String?>()
        val job = launch { s.string("k").take(2).toList(seen) }
        yield()
        localStorage.setItem("supermux:k", "written-by-another-tab")
        window.dispatchEvent(StorageEvent("storage"))
        yield()
        job.join()
        assertEquals(listOf(null, "written-by-another-tab"), seen)
    }
}
