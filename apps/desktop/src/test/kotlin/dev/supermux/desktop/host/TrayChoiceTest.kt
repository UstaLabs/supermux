package dev.supermux.desktop.host

import dev.supermux.desktop.host.linux.SniStatus
import dev.supermux.desktop.host.linux.SniStatus.FAILED
import dev.supermux.desktop.host.linux.SniStatus.REGISTERED
import dev.supermux.desktop.host.linux.SniStatus.STARTING
import dev.supermux.desktop.host.linux.SniStatus.UNSUPPORTED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayChoiceTest {
    /** Feeds [statuses] in order, carrying the latch like Main does; the choice at each step. */
    private fun run(statuses: List<SniStatus>, awtSupported: Boolean): List<TrayChoice> {
        var latch: Boolean? = null
        return statuses.map { s -> trayChoice(s, latch, awtSupported).also { latch = it.latch } }
    }

    @Test fun theMatrix() {
        // (status, latch in, awt supported) -> (useAwt, trayAvailable, latch out)
        val cases = listOf(
            Triple(STARTING, null, true) to TrayChoice(false, false, null),
            Triple(STARTING, null, false) to TrayChoice(false, false, null),
            Triple(STARTING, true, true) to TrayChoice(true, true, true),
            Triple(STARTING, false, true) to TrayChoice(false, false, false),
            Triple(REGISTERED, null, true) to TrayChoice(false, true, false),
            Triple(REGISTERED, true, true) to TrayChoice(false, true, false),
            Triple(REGISTERED, false, false) to TrayChoice(false, true, false),
            Triple(UNSUPPORTED, null, true) to TrayChoice(true, true, true),
            Triple(UNSUPPORTED, null, false) to TrayChoice(false, false, false),
            Triple(UNSUPPORTED, false, true) to TrayChoice(false, false, false),
            Triple(UNSUPPORTED, true, true) to TrayChoice(true, true, true),
            Triple(UNSUPPORTED, true, false) to TrayChoice(false, false, true),
            Triple(FAILED, null, true) to TrayChoice(true, true, true),
            Triple(FAILED, null, false) to TrayChoice(false, false, false),
            Triple(FAILED, false, true) to TrayChoice(false, false, false),
        )
        for ((input, want) in cases) {
            val (s, latch, awt) = input
            assertEquals(want, trayChoice(s, latch, awt), "trayChoice($s, $latch, $awt)")
        }
    }

    @Test fun unsupportedFirstThenRegisteredIsOneSniTray() {
        val steps = run(listOf(STARTING, UNSUPPORTED, REGISTERED, REGISTERED), awtSupported = true)
        assertEquals(listOf(false, true, false, false), steps.map { it.useAwt })
        assertEquals(TrayChoice(useAwt = false, trayAvailable = true, latch = false), steps.last())
    }

    @Test fun aWatcherThatLeavesAfterRegisteringNeverBringsAwtBack() {
        val steps = run(listOf(STARTING, REGISTERED, UNSUPPORTED, REGISTERED, UNSUPPORTED), awtSupported = true)
        assertTrue(steps.none { it.useAwt })
        assertEquals(listOf(false, true, false, true, false), steps.map { it.trayAvailable })
    }

    @Test fun neverTwoTrays() {
        for (awt in listOf(true, false)) {
            for (seq in listOf(
                listOf(STARTING, UNSUPPORTED, REGISTERED, UNSUPPORTED),
                listOf(FAILED),
                listOf(STARTING, REGISTERED),
                listOf(UNSUPPORTED, UNSUPPORTED, REGISTERED),
            )) {
                var latch: Boolean? = null
                for (s in seq) {
                    val c = trayChoice(s, latch, awt)
                    latch = c.latch
                    // SNI shows exactly when REGISTERED: AWT must not then.
                    assertFalse(c.useAwt && s == REGISTERED, "$seq awt=$awt")
                }
            }
        }
    }

    @Test fun noSniTrayIsTheOldAwtBehaviour() {
        // macOS / Windows pass FAILED: AWT exactly when it is supported, from the first frame.
        assertEquals(TrayChoice(true, true, true), trayChoice(FAILED, null, true))
        assertEquals(TrayChoice(false, false, false), trayChoice(FAILED, null, false))
    }
}
