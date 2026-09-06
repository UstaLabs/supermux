package dev.supermux.android.platform

import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
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
                clipboardImages = true,
                saveAs = true,
                walkthrough = true,
                appearanceControls = true,
                dynamicColor = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                appUpdate = true,
            ),
            ANDROID_CAPS,
        )
    }

    @Test
    fun `android has no browsable file system - only SAF-granted URIs`() {
        assertFalse(ANDROID_CAPS.fileSystem)
        assertTrue(ANDROID_CAPS.push)
    }

    // ── PickerHost: the behaviours that bite. Driven with String results so the logic is
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
        val pick = async { host.pick(PickKind.Media, "chat") }
        yield()
        assertEquals(listOf(PickKind.Media), launched)
        host.deliver("content://photo")
        assertEquals("content://photo", pick.await())
        assertNull(host.inFlightId)
    }

    @Test
    fun `cancelling the picker resumes with null - the caller sees an empty list`() = runTest {
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Any, "chat") }
        yield()
        host.deliver(null)
        assertNull(pick.await())
        assertNull(host.unclaimed.value) // a cancel is not a stash
    }

    @Test
    fun `with no launcher registered a pick behaves like a cancel`() = runTest {
        val host = PickerHost<String>()
        assertNull(host.pick(PickKind.Any, "chat"))
        assertNull(host.inFlightId)
    }

    @Test
    fun `a double-tapped attach button never opens a second picker`() = runTest {
        val (host, launched) = host()
        val first = async { host.pick(PickKind.Any, "chat") }
        yield()
        // Same frame, second tap: rejected outright — one pick in flight at a time.
        assertNull(host.pick(PickKind.Any, "chat"))
        assertEquals(listOf(PickKind.Any), launched)

        host.deliver("content://one")
        assertEquals("content://one", first.await())
    }

    @Test
    fun `a delivery with nothing in flight is dropped`() = runTest {
        val (host, _) = host()
        host.deliver("content://ghost")
        assertNull(host.unclaimed.value)

        // ...and it did not poison the next pick.
        val pick = async { host.pick(PickKind.Any, "chat") }
        yield()
        host.deliver("content://real")
        assertEquals("content://real", pick.await())
    }

    @Test
    fun `a pick that outlives activity recreation reaches the requesting screen`() = runTest {
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Images, "chat") }
        yield()
        val id = assertNotNull(host.inFlightId) // what rememberSaveable persists
        val kind = assertNotNull(host.inFlightKind)
        val requester = assertNotNull(host.inFlightRequester)
        pick.cancel() // the awaiting coroutine dies with the old composition
        yield() // let the cancellation unwind so the host stops seeing a waiter

        // The re-created activity: a fresh host, restored marker, launcher re-registered.
        val recreated = PickerHost<String>()
        val relaunched = mutableListOf<PickKind>()
        recreated.onLaunch = { relaunched.add(it) }
        recreated.restoreInFlight(id, kind, requester)

        // A collector attached BEFORE the OS answers — the target case (rotation while the picker
        // is still in the foreground), which a one-shot drain would miss.
        val seen = mutableListOf<UnclaimedPick<String>>()
        val collector = launch {
            recreated.unclaimed.filterNotNull().collect { seen.add(it); recreated.claim(it) }
        }
        yield()
        recreated.deliver("content://photo")
        yield()
        collector.cancel()

        assertEquals(listOf(UnclaimedPick("content://photo", PickKind.Images, "chat")), seen)
        assertNull(recreated.unclaimed.value) // claimed

        // And the screen can pick again: nothing is left in flight.
        val next = async { recreated.pick(PickKind.Any, "chat") }
        yield()
        assertEquals(listOf(PickKind.Any), relaunched)
        recreated.deliver(null)
        next.await()
    }

    @Test
    fun `an unclaimed pick is tagged with the screen that asked for it`() = runTest {
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Media, "session-launcher") }
        yield()
        pick.cancel()
        yield()
        host.deliver("content://video")
        val stash = assertNotNull(host.unclaimed.value)
        assertEquals("session-launcher", stash.requester)
        assertEquals(PickKind.Media, stash.kind)
    }
    @Test
    fun `a marker nothing can complete is cleared on resume`() = runTest {
        // The activity died mid-pick and the OS result died with it: the marker is restored from
        // rememberSaveable but no callback will ever arrive.
        val host = PickerHost<String>()
        val launched = mutableListOf<PickKind>()
        host.onLaunch = { launched.add(it) }
        host.restoreInFlight(7L, PickKind.Images, "chat")

        assertTrue(host.clearStuckInFlight(), "a waiter-less marker must be cleared on resume")
        assertNull(host.inFlightId)
        assertNull(host.inFlightKind)
        assertNull(host.inFlightRequester)

        // Rule 1 is un-wedged: the screen can pick again.
        val next = async { host.pick(PickKind.Any, "chat") }
        yield()
        assertEquals(listOf(PickKind.Any), launched)
        host.deliver(null)
        next.await()
    }

    @Test
    fun `a live pick is not cleared on resume`() = runTest {
        // Resumed with the picker still up (the window right after launch): the waiter is alive and
        // the marker must survive, or its eventual result would be dropped by rule 3.
        val (host, _) = host()
        val pick = async { host.pick(PickKind.Images, "chat") }
        yield()
        assertFalse(host.clearStuckInFlight(), "a live pick must not be cleared")
        assertNotNull(host.inFlightId)
        host.deliver("content://kept")
        assertEquals("content://kept", pick.await())
    }
}
