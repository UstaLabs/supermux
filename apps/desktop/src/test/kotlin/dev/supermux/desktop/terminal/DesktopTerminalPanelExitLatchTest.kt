package dev.supermux.desktop.terminal

import dev.supermux.net.TerminalStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec for the agent-exit latch decision ([shouldFireExit]) extracted from [DesktopTerminalPanel]
 * (Android TerminalPanel:111-122 parity). The latch (`hadConnected`) subsumes any "previous
 * status" input: pre-connect DISCONNECTEDs — including a CONNECTING→DISCONNECTED retry loop —
 * never fire because the latch is still false.
 *
 * The COMPOSABLE wiring around it (status collection → latch update → onExit invocation, plus the
 * client/connector/widget lifecycle) needs a live websocket + a real Swing embed, so it is
 * exercised end-to-end in Task 8's live verification pass (and this task's standalone probe run)
 * rather than in a headless unit test — TerminalClient is final, and wrapping it in an interface
 * just for this would add a production seam with no other consumer.
 */
class DesktopTerminalPanelExitLatchTest {

    @Test
    fun fires_on_disconnect_after_having_connected() {
        assertTrue(shouldFireExit(TerminalStatus.DISCONNECTED, hadConnected = true))
    }

    @Test
    fun does_not_fire_on_pre_connect_disconnect() {
        assertFalse(shouldFireExit(TerminalStatus.DISCONNECTED, hadConnected = false))
    }

    @Test
    fun does_not_fire_while_connecting_even_after_having_connected() {
        // A reconnect attempt (CONNECTED → run-loop retry → CONNECTING) must not exit the tab.
        assertFalse(shouldFireExit(TerminalStatus.CONNECTING, hadConnected = true))
    }

    @Test
    fun does_not_fire_while_connected() {
        assertFalse(shouldFireExit(TerminalStatus.CONNECTED, hadConnected = true))
        assertFalse(shouldFireExit(TerminalStatus.CONNECTED, hadConnected = false))
    }

    @Test
    fun does_not_fire_while_connecting_before_first_connect() {
        assertFalse(shouldFireExit(TerminalStatus.CONNECTING, hadConnected = false))
    }
}
