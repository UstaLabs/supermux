package dev.supermux.android.platform

import dev.supermux.ui.platform.Caps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
