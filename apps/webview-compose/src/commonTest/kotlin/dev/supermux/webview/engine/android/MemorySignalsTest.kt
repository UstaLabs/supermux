package dev.supermux.webview.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals

/** What Android's memory signals ask of a runtime (final review, item 5): pressure, a light trim, or recovery. */
class MemorySignalsTest {
    private val asked = mutableListOf<String>()
    private val signals = MemorySignals(pressure = { asked += "pressure" }, trim = { asked += "trim" }, recovered = { asked += "recovered" })

    /** An app switch on Android 14+: its UI hidden, then its process cached. Nothing of the views it shows goes. */
    @Test fun anAppSwitchIsALightTrimNotPressure() {
        signals.onTrimMemory(20)
        signals.onTrimMemory(40)
        assertEquals(listOf("trim"), asked)
    }

    @Test fun theRunningLevelsAndLowMemoryArePressure() {
        signals.onTrimMemory(5)
        signals.onTrimMemory(10)
        signals.onTrimMemory(15)
        signals.onLowMemory()
        assertEquals(listOf("pressure", "pressure", "pressure"), asked)
    }

    /** A pressure window ends when the user comes back: its half budget would otherwise greet them for 30 s. */
    @Test fun aWindowEndsWhenTheAppComesBackToTheForeground() {
        signals.onTrimMemory(15)
        signals.onActivityStarted()
        assertEquals(listOf("pressure"), asked, "an Activity starting while the app is in front ends nothing")
        signals.onTrimMemory(20)
        signals.onActivityStarted()
        assertEquals(listOf("pressure", "recovered"), asked)
        signals.onActivityStarted()
        assertEquals(listOf("pressure", "recovered"), asked, "once per return")
    }

    @Test fun aReturnFromTheCachedStateEndsAWindowToo() {
        signals.onTrimMemory(10)
        signals.onTrimMemory(40)
        signals.onActivityStarted()
        assertEquals(listOf("pressure", "trim", "recovered"), asked)
    }
}
