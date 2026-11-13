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

    // #122 (page-first keys): a key the page may use goes to it first; the app gets it only if the page leaves it unused.
    private val timers = mutableListOf<Runnable>()
    private fun pageFirstRouter() = KeyRouter(
        target = { canvas },
        onEdt = { it.run() },
        dispatch = { _, e ->
            seen += e
            if (e.keyCode == KeyEvent.VK_K && e.isControlDown) e.consume()
        },
        pageFirst = { _, _ -> true },
        later = { _, run -> timers += run },
    )

    @Test fun aPageFirstKeyGoesToThePageAndWaits() {
        assertFalse(pageFirstRouter().offer('K'.code, 0x25, flags = 1))
        assertTrue(seen.isEmpty(), "nothing offered to the app yet")
    }

    @Test fun aKeyThePageLeftUnusedReachesTheApp() {
        val router = pageFirstRouter()
        router.offer('K'.code, 0x25, flags = 1)
        router.verdict(used = false, vk = 'K'.code)
        assertEquals(listOf(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED), seen.map { it.id })
        assertTrue(seen.first().isControlDown)
    }

    @Test fun aKeyThePageUsedStaysThePages() {
        val router = pageFirstRouter()
        router.offer('K'.code, 0x25, flags = 1)
        router.verdict(used = true, vk = 'K'.code)
        assertTrue(seen.isEmpty())
    }

    @Test fun aPageThatNeverAnswersDoesNotKeepTheKey() {
        val router = pageFirstRouter()
        router.offer('K'.code, 0x25, flags = 1)
        timers.single().run()
        assertEquals(KeyEvent.KEY_PRESSED, seen.first().id)
        router.verdict(used = false, vk = 'K'.code) // late: already the app's
        assertEquals(2, seen.size)
    }

    /**
     * The VM (2026-11-12): the page's keydowns for Ctrl and Shift answered before the K's did, and their "unused" gave
     * the app a Ctrl+Shift+K the page then took. Only the answer for the pending key counts.
     */
    @Test fun anotherKeysAnswerLeavesThePendingKeyAlone() {
        val router = pageFirstRouter()
        router.offer('K'.code, 0x25, flags = 1 or 2)
        router.verdict(used = false, vk = 0x11) // Ctrl's own keydown
        router.verdict(used = false, vk = 0x10) // Shift's
        assertTrue(seen.isEmpty(), "still waiting for the K")
        router.verdict(used = true, vk = 'K'.code)
        timers.single().run()
        assertTrue(seen.isEmpty(), "the page kept it")
    }
}
