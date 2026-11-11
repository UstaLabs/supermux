package dev.supermux.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #122 phase 2: a Browser tab's device mode — Chrome's presets, a typed size, rotation. */
class DeviceModeTest {
    @Test fun thePresetsAreChromesSizes() {
        val iphone = DevicePresets.byId("iphone-16")!!
        assertEquals(393 to 852, iphone.width to iphone.height)
        assertEquals(3.0, iphone.dpr)
        assertEquals(375 to 667, DevicePresets.byId("iphone-se")!!.let { it.width to it.height })
        assertEquals(768 to 1024, DevicePresets.byId("ipad-mini")!!.let { it.width to it.height })
        assertEquals(1280 to 800, DevicePresets.byId("laptop")!!.let { it.width to it.height })
        assertTrue(DevicePresets.all.map { it.group }.containsAll(DeviceGroup.entries))
    }

    @Test fun aNewDeviceModeIsAnIphone16() {
        assertEquals(DeviceMode("iphone-16", 393, 852, rotated = false), DeviceMode.Default)
    }

    @Test fun rotatingSwapsTheSizeAndKeepsThePreset() {
        val turned = DeviceMode.Default.rotated()
        assertEquals(852 to 393, turned.width to turned.height)
        assertEquals("iphone-16", turned.presetId)
        assertEquals(DeviceMode.Default, turned.rotated())
    }

    @Test fun anotherPresetKeepsTheRotation() {
        val pixel = DevicePresets.byId("pixel-9")!!
        assertEquals(924 to 412, DeviceMode.Default.rotated().withPreset(pixel).let { it.width to it.height })
    }

    @Test fun aTypedSizeIsResponsiveAndStaysInRange() {
        val typed = DeviceMode.Default.resized(width = 500, height = 99_999)
        assertNull(typed.presetId)
        assertEquals(500 to DeviceMode.MAX_SIDE, typed.width to typed.height)
        assertEquals(DeviceMode.MIN_SIDE, DeviceMode.Default.resized(width = 10, height = 700).width)
    }
}
