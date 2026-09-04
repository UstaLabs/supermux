package dev.supermux.ui.platform

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.NoHaptics
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A recording [Platform] — the pattern every screen test uses to assert a platform call. */
private class FakePlatform(
    override val caps: Caps = Caps(
        push = false,
        camera = false,
        tray = false,
        externalDisplay = false,
        hardwareVideoDecode = false,
        localBroker = false,
        multiWindow = false,
        fileSystem = false,
    ),
    override val haptics: Haptics = NoHaptics,
) : Platform {
    val openedUrls = mutableListOf<String>()
    val copied = mutableListOf<String>()
    var pickedKind: PickKind? = null
    var pickedRequester: String? = null

    override fun openUrl(url: String) { openedUrls.add(url) }
    override fun copyToClipboard(text: String) { copied.add(text) }
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> {
        pickedKind = kind
        pickedRequester = requester
        return listOf(PickedFile("a.txt", "text/plain", ByteArrayChunkSource(byteArrayOf(1, 2))))
    }
}

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
    fun `the default haptics is the shared no-op`() {
        FakePlatform().haptics.perform(HapticKind.Tick)
    }
}
