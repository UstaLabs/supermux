package dev.supermux.android.platform

import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the Android capability table. Screens branch on these booleans, so a silent flip would
 * change what UI is offered on a phone (e.g. a tray affordance, or a real path picker).
 */
class AndroidPlatformTest {

    @Test
    fun `android capabilities match the platform table`() {
        assertEquals(
            Caps(
                push = true,
                camera = true,
                tray = false,
                externalDisplay = true,
                hardwareVideoDecode = true,
                localBroker = false,
                multiWindow = false,
                fileSystem = false,
            ),
            ANDROID_CAPS,
        )
    }

    @Test
    fun `android has no browsable file system - only SAF-granted URIs`() {
        assertFalse(ANDROID_CAPS.fileSystem)
        assertTrue(ANDROID_CAPS.push)
    }

    // ── PickerHost: the three behaviours that bite. Driven with String results so the logic is
    // testable with no Activity and no android.net.Uri (production uses PickerHost<Uri>).

    private fun host(): Pair<PickerHost<String>, MutableList<PickKind>> {
        val host = PickerHost<String>()
        val launched = mutableListOf<PickKind>()
        host.onLaunch = { kind -> launched.add(kind) }
        return host to launched
    }

    @Test
    fun `a pick resumes with the delivered result`() = runTest {
        val (host, launched) = host()
        val pick = async { host.pick(PickKind.Media) }
        yield()
        assertEquals(listOf(PickKind.Media), launched)
        host.deliver("content://photo")
        assertEquals("content://photo", pick.await())
    }

    @Test
    fun `cancelling the picker resumes with null - the caller sees an empty list`() = runTest {
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Any) }
        yield()
        host.deliver(null)
        assertNull(pick.await())
    }

    @Test
    fun `with no launcher registered a pick behaves like a cancel`() = runTest {
        val host = PickerHost<String>()
        assertNull(host.pick(PickKind.Any))
        assertNull(host.inFlightId)
    }

    @Test
    fun `a pick that outlives activity recreation is claimed, not lost`() = runTest {
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Images) }
        yield()
        val savedMarker = host.inFlightId // what rememberSaveable persists across the restart
        assertNotNull(savedMarker)
        pick.cancel() // the awaiting coroutine dies with the old composition

        // The re-created activity: a fresh host, restored marker, fresh launcher registration.
        val recreated = PickerHost<String>()
        recreated.onLaunch = { }
        recreated.inFlightId = savedMarker
        recreated.deliver("content://photo") // the OS finally answers the pre-restart pick

        assertEquals("content://photo", recreated.drainUnclaimed())
        assertNull(recreated.drainUnclaimed()) // claimed once, then gone
    }

    @Test
    fun `a superseded pick answering late never resumes the new caller`() = runTest {
        val (host, _) = host()
        val first = async { host.pick(PickKind.Any) }
        yield()
        val second = async { host.pick(PickKind.Media) }
        yield()
        assertNull(first.await()) // starting a second pick cancels the first

        host.deliver("stale-from-the-first-picker") // arrives with the FIRST launch's id
        yield()
        assertTrue(second.isActive) // ignored: not the current request

        host.deliver("fresh")
        assertEquals("fresh", second.await())
    }
}
