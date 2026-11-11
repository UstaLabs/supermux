package dev.supermux.browser

/** The shelves a device preset sits on in the device menu. */
enum class DeviceGroup(val label: String) { Phones("Phones"), Tablets("Tablets"), Desktops("Desktops") }

/** One device Chrome's DevTools offers: its CSS size in dp and its pixel ratio. */
data class DevicePreset(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpr: Double,
    val group: DeviceGroup,
)

/** Chrome's device list, trimmed to what people actually check. */
object DevicePresets {
    val all: List<DevicePreset> = listOf(
        DevicePreset("iphone-se", "iPhone SE", 375, 667, 2.0, DeviceGroup.Phones),
        DevicePreset("iphone-16", "iPhone 16", 393, 852, 3.0, DeviceGroup.Phones),
        DevicePreset("iphone-16-pro-max", "iPhone 16 Pro Max", 440, 956, 3.0, DeviceGroup.Phones),
        DevicePreset("pixel-9", "Pixel 9", 412, 924, 2.625, DeviceGroup.Phones),
        DevicePreset("galaxy-a55", "Galaxy A55", 360, 800, 2.25, DeviceGroup.Phones),
        DevicePreset("galaxy-z-fold-6", "Galaxy Z Fold 6", 412, 968, 2.625, DeviceGroup.Phones),
        DevicePreset("ipad-mini", "iPad Mini", 768, 1024, 2.0, DeviceGroup.Tablets),
        DevicePreset("ipad-pro-13", "iPad Pro 13\"", 1032, 1376, 2.0, DeviceGroup.Tablets),
        DevicePreset("surface-pro-10", "Surface Pro 10", 960, 1440, 2.0, DeviceGroup.Tablets),
        DevicePreset("laptop", "Laptop", 1280, 800, 1.0, DeviceGroup.Desktops),
        DevicePreset("laptop-hidpi", "Laptop HiDPI", 1440, 900, 2.0, DeviceGroup.Desktops),
        DevicePreset("desktop", "Desktop", 1920, 1080, 1.0, DeviceGroup.Desktops),
    )

    /** The widths Responsive offers as one-tap chips, as Chrome's ruler does. */
    val responsiveWidths: List<Int> = listOf(320, 375, 425, 768, 1024, 1440, 2560)

    fun byId(id: String?): DevicePreset? = all.firstOrNull { it.id == id }
}

/**
 * What a Browser tab in device mode shows: a preset ([presetId]) or a typed size (null, "Responsive"),
 * [width] × [height] in dp as the page sees it, already turned when [rotated].
 */
data class DeviceMode(val presetId: String?, val width: Int, val height: Int, val rotated: Boolean) {
    val preset: DevicePreset? get() = DevicePresets.byId(presetId)

    fun rotated(): DeviceMode = copy(width = height, height = width, rotated = !rotated)

    /** [preset], keeping this mode's rotation. */
    fun withPreset(preset: DevicePreset): DeviceMode =
        if (rotated) DeviceMode(preset.id, preset.height, preset.width, true)
        else DeviceMode(preset.id, preset.width, preset.height, false)

    /** A typed size: no longer a preset, clamped to what a page can sensibly be. */
    fun resized(width: Int = this.width, height: Int = this.height): DeviceMode =
        copy(presetId = null, width = width.coerceIn(MIN_SIDE, MAX_SIDE), height = height.coerceIn(MIN_SIDE, MAX_SIDE))

    companion object {
        const val MIN_SIDE = 50
        const val MAX_SIDE = 10_000
        val Default = DeviceMode("iphone-16", 393, 852, rotated = false)
    }
}
