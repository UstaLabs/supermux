package dev.supermux.ui.platform

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.theme.HapticKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlatformTest {

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a composable reaches the provided platform through LocalPlatform`() = runComposeUiTest {
        val fake = FakePlatform()
        setContent {
            CompositionLocalProvider(LocalPlatform provides fake) {
                val platform = LocalPlatform.current
                platform.openUrl("https://x")
                platform.copyToClipboard("hello")
            }
        }
        waitForIdle()
        assertEquals(listOf("https://x"), fake.openedUrls)
        assertEquals(listOf("hello"), fake.copied)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `LocalPlatform without a provider fails loudly`() = runComposeUiTest {
        val error = assertFailsWith<IllegalStateException> {
            setContent { LocalPlatform.current.openUrl("https://x") }
            waitForIdle()
        }
        assertContains(error.message ?: "", "Platform")
    }

    @Test
    fun `caps is a plain value type - equality and copy`() {
        val caps = Caps(
            push = true,
            camera = true,
            tray = false,
            externalDisplay = true,
            hardwareVideoDecode = true,
            localBroker = false,
            multiWindow = false,
            fileSystem = false,
        )
        assertEquals(caps, caps.copy())
        assertTrue(caps.copy(tray = true).tray)
    }

    @Test
    fun `pickFiles returns files that can be uploaded straight from their ChunkSource`() {
        val fake = FakePlatform()
        val picked = kotlinx.coroutines.runBlocking { fake.pickFiles(PickKind.Images) }
        assertEquals(PickKind.Images, fake.pickedKind)
        assertEquals(DEFAULT_REQUESTER, fake.pickedRequester) // screens opt in to a name
        assertEquals("a.txt", picked.single().name)
        assertEquals("text/plain", picked.single().mime)
        assertEquals(2L, picked.single().source.size)
    }

    @Test
    fun `every pick kind is representable`() {
        assertEquals(listOf(PickKind.Any, PickKind.Images, PickKind.Media), PickKind.entries.toList())
    }

    @Test
    fun `scanQr returns the decoded value, or null when the user cancels`() {
        val fake = FakePlatform(qrResult = "https://host/pair?t=abc")
        assertEquals("https://host/pair?t=abc", kotlinx.coroutines.runBlocking { fake.scanQr() })
        fake.qrResult = null
        assertEquals(null, kotlinx.coroutines.runBlocking { fake.scanQr() })
        assertEquals(2, fake.scans)
    }

    @Test
    fun `the default haptics is the shared no-op`() {
        FakePlatform().haptics.perform(HapticKind.Tick)
    }
}
