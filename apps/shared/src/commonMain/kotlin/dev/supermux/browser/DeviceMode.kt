package dev.supermux.browser

/** The shelves a device preset sits on in the device menu. */
enum class DeviceGroup(val label: String) { Phones("Phones"), Tablets("Tablets"), Desktops("Desktops") }

/** Whose browser a device's pages meet, as Mobile: an iPhone's Safari, an iPad's, Chrome on Android; or a desktop's. */
enum class DeviceAgent { Iphone, Ipad, Android, Desktop }

/** One device Chrome's DevTools offers: its CSS size in dp, its pixel ratio and whose browser it has. */
data class DevicePreset(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpr: Double,
    val group: DeviceGroup,
    val agent: DeviceAgent,
)

/** How a page is told to look: as the system is, or light or dark whatever it is (`prefers-color-scheme`). */
enum class PageAppearance(val label: String) { System("System"), Light("Light"), Dark("Dark") }

/**
 * The user agents device mode gives a page as Mobile, as Chrome's device list writes them (its reduced Chrome agent on
 * Android). Desktop gives none: the engine's own.
 */
object DeviceAgents {
    const val IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    const val IPAD = "Mozilla/5.0 (iPad; CPU OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    const val ANDROID = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
}

/** Chrome's device list, trimmed to what people actually check. */
object DevicePresets {
    val all: List<DevicePreset> = listOf(
        DevicePreset("iphone-se", "iPhone SE", 375, 667, 2.0, DeviceGroup.Phones, DeviceAgent.Iphone),
        DevicePreset("iphone-16", "iPhone 16", 393, 852, 3.0, DeviceGroup.Phones, DeviceAgent.Iphone),
        DevicePreset("iphone-16-pro-max", "iPhone 16 Pro Max", 440, 956, 3.0, DeviceGroup.Phones, DeviceAgent.Iphone),
        DevicePreset("pixel-9", "Pixel 9", 412, 924, 2.625, DeviceGroup.Phones, DeviceAgent.Android),
        DevicePreset("galaxy-a55", "Galaxy A55", 360, 800, 2.25, DeviceGroup.Phones, DeviceAgent.Android),
        DevicePreset("galaxy-z-fold-6", "Galaxy Z Fold 6", 412, 968, 2.625, DeviceGroup.Phones, DeviceAgent.Android),
        DevicePreset("ipad-mini", "iPad Mini", 768, 1024, 2.0, DeviceGroup.Tablets, DeviceAgent.Ipad),
        DevicePreset("ipad-pro-13", "iPad Pro 13\"", 1032, 1376, 2.0, DeviceGroup.Tablets, DeviceAgent.Ipad),
        DevicePreset("surface-pro-10", "Surface Pro 10", 960, 1440, 2.0, DeviceGroup.Tablets, DeviceAgent.Desktop),
        DevicePreset("laptop", "Laptop", 1280, 800, 1.0, DeviceGroup.Desktops, DeviceAgent.Desktop),
        DevicePreset("laptop-hidpi", "Laptop HiDPI", 1440, 900, 2.0, DeviceGroup.Desktops, DeviceAgent.Desktop),
        DevicePreset("desktop", "Desktop", 1920, 1080, 1.0, DeviceGroup.Desktops, DeviceAgent.Desktop),
    )

    /** The widths Responsive offers as one-tap chips, as Chrome's ruler does. */
    val responsiveWidths: List<Int> = listOf(320, 375, 425, 768, 1024, 1440, 2560)

    fun byId(id: String?): DevicePreset? = all.firstOrNull { it.id == id }
}

/**
 * What a Browser tab in device mode shows: a preset ([presetId]) or a typed size (null, "Responsive"),
 * [width] × [height] in dp as the page sees it, already turned when [rotated].
 */
data class DeviceMode(
    val presetId: String?,
    val width: Int,
    val height: Int,
    val rotated: Boolean,
    /** Mobile (the device's browser's agent) or Desktop (the engine's own). A preset brings its own; then it's yours. */
    val mobile: Boolean = true,
    val appearance: PageAppearance = PageAppearance.System,
    /** The picture's scale in percent, or null for Auto: fit the pane, never above 100 %. The page's CSS size stays. */
    val fit: Int? = null,
    /** The page's devicePixelRatio, or null for the preset's (1 for Responsive). Only where the engine can set it. */
    val dpr: Double? = null,
    /** Touch (touch events, a coarse pointer), or null to follow [mobile]. Only where the engine can emulate it. */
    val touch: Boolean? = null,
) {
    fun deviceScaleFactor(): Double = dpr ?: preset?.dpr ?: 1.0

    fun hasTouch(): Boolean = touch ?: mobile

    /** The scale the device is drawn at in a pane of this size (the stage's room, in dp). */
    fun scaleIn(paneWidth: Float, paneHeight: Float): Float =
        fit?.let { it / 100f } ?: minOf(1f, paneWidth / width, paneHeight / height).coerceAtLeast(0.1f)

    val preset: DevicePreset? get() = DevicePresets.byId(presetId)

    fun withMobile(mobile: Boolean): DeviceMode = copy(mobile = mobile)

    /** The agent to give the page: its preset's browser as Mobile (a generic Android phone's without one), none as Desktop. */
    fun userAgent(): String? = if (!mobile) null else when (preset?.agent) {
        DeviceAgent.Iphone -> DeviceAgents.IPHONE
        DeviceAgent.Ipad -> DeviceAgents.IPAD
        else -> DeviceAgents.ANDROID
    }

    fun rotated(): DeviceMode = copy(width = height, height = width, rotated = !rotated)

    /** [preset], keeping this mode's rotation. */
    fun withPreset(preset: DevicePreset): DeviceMode = copy(
        presetId = preset.id,
        width = if (rotated) preset.height else preset.width,
        height = if (rotated) preset.width else preset.height,
        mobile = preset.agent != DeviceAgent.Desktop,
    )

    /** A typed size: no longer a preset, clamped to what a page can sensibly be. */
    fun resized(width: Int = this.width, height: Int = this.height): DeviceMode =
        copy(presetId = null, width = width.coerceIn(MIN_SIDE, MAX_SIDE), height = height.coerceIn(MIN_SIDE, MAX_SIDE))

    companion object {
        const val MIN_SIDE = 50
        const val MAX_SIDE = 10_000
        val Default = DeviceMode("iphone-16", 393, 852, rotated = false)

        /** The scales one can choose besides Auto, as Chrome's zoom menu. */
        val FITS = listOf(50, 75, 100, 125, 150)

        /** The pixel ratios one can choose besides the preset's, as Chrome's DPR menu. */
        val DPRS = listOf(1.0, 2.0, 3.0)
    }
}
