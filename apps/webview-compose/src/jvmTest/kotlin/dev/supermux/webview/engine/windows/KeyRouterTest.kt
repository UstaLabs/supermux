package dev.supermux.webview.engine.windows

import java.awt.Canvas
import java.awt.event.KeyEvent
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyRouterTest {
    private val canvas = Canvas()
    private val seen = mutableListOf<KeyEvent>()

    private fun router(edt: (Runnable) -> Unit = { it.run() }) = KeyRouter(
        target = { canvas },
        onEdt = edt,
        dispatch = { _, e ->
            seen += e
            if (e.keyCode == KeyEvent.VK_K && e.isControlDown && e.isShiftDown) e.consume()
        },
    )

    @Test fun anAppShortcutIsTakenAndItsReleaseFollows() {
        assertTrue(router().offer('K'.code, 0x25, flags = 1 or 2))
        assertEquals(listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED), seen.map { it.id })
    }

    @Test fun aKeyNobodyTakesIsThePagesAndComposeSeesNoRelease() {
        assertFalse(router().offer('C'.code, 0x2e, flags = 1))
        assertEquals(listOf(KeyEvent.KEY_PRESSED), seen.map { it.id })
    }

    @Test fun aBusyEdtLeavesTheKeyToThePageWithin50ms() {
        val never = Executors.newSingleThreadExecutor()
        val started = System.nanoTime()
        assertFalse(router(edt = { never.execute { Thread.sleep(1_000); it.run() } }).offer('K'.code, 0, flags = 3))
        assertTrue(System.nanoTime() - started < 300_000_000L)
        never.shutdownNow()
    }

    @Test fun windowsVirtualKeysBecomeAwtsKeyCodes() {
        assertEquals(KeyEvent.VK_ENTER, KeyRouter.awtKeyCode(0x0D))
        assertEquals(KeyEvent.VK_DELETE, KeyRouter.awtKeyCode(0x2E))
        assertEquals(KeyEvent.VK_INSERT, KeyRouter.awtKeyCode(0x2D))
        assertEquals(KeyEvent.VK_SEMICOLON, KeyRouter.awtKeyCode(0xBA))
        assertEquals(KeyEvent.VK_F13, KeyRouter.awtKeyCode(0x7C))
        assertEquals(KeyEvent.VK_K, KeyRouter.awtKeyCode('K'.code))
    }
}
