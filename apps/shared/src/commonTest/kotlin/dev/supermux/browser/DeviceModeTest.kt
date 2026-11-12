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

    /** #122 2a2: a phone or tablet is Mobile (its own platform's browser agent), a desktop Desktop (the engine's own). */
    @Test fun aPresetIsMobileWithItsPlatformsAgentOrDesktop() {
        val iphone = DeviceMode.Default
        assertTrue(iphone.mobile)
        assertTrue("iPhone" in iphone.userAgent()!!)
        val pixel = iphone.withPreset(DevicePresets.byId("pixel-9")!!)
        assertTrue("Android" in pixel.userAgent()!! && "Mobile" in pixel.userAgent()!!)
        assertTrue("iPad" in iphone.withPreset(DevicePresets.byId("ipad-mini")!!).userAgent()!!)
        val laptop = iphone.withPreset(DevicePresets.byId("laptop")!!)
        assertEquals(false, laptop.mobile)
        assertNull(laptop.userAgent(), "Desktop: the engine's own agent")
    }

    @Test fun mobileOrDesktopIsYoursUntilAnotherPreset() {
        val desktop = DeviceMode.Default.withMobile(false)
        assertNull(desktop.userAgent())
        assertEquals(true, desktop.withPreset(DevicePresets.byId("pixel-9")!!).mobile, "a preset brings its own")
        val responsive = DeviceMode.Default.resized(width = 500)
        assertEquals(true, responsive.mobile, "a typed size keeps what it was")
        assertTrue("Android" in responsive.userAgent()!!, "Responsive and Mobile: a generic phone's agent")
    }

    @Test fun theAppearanceIsTheSystemsUntilChosen() {
        assertEquals(PageAppearance.System, DeviceMode.Default.appearance)
        assertEquals(PageAppearance.Dark, DeviceMode.Default.copy(appearance = PageAppearance.Dark).withPreset(DevicePresets.byId("laptop")!!).appearance)
    }

    /** #122 2b: Auto fits the device in the pane, never above 100 %; a chosen fit is that, whatever the pane. */
    @Test fun autoFitsThePaneAndNeverEnlarges() {
        val ipad = DeviceMode.Default.withPreset(DevicePresets.byId("ipad-mini")!!) // 768 × 1024
        assertNull(ipad.fit, "Auto by default")
        assertEquals(0.5f, ipad.scaleIn(paneWidth = 2000f, paneHeight = 512f), 0.001f)
        assertEquals(1f, DeviceMode.Default.scaleIn(paneWidth = 2000f, paneHeight = 2000f), "a small device stays at 100 %")
        assertEquals(0.75f, ipad.copy(fit = 75).scaleIn(paneWidth = 100f, paneHeight = 100f))
        assertEquals(1.5f, ipad.copy(fit = 150).scaleIn(paneWidth = 100f, paneHeight = 100f))
        assertTrue(DeviceMode.FITS.containsAll(listOf(50, 75, 100, 125, 150)))
    }
}
