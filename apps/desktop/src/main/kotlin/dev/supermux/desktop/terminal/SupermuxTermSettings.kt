package dev.supermux.desktop.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.model.TerminalTypeAheadSettings
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Font
import java.io.InputStream

/**
 * JediTerm [DefaultSettingsProvider] themed for supermux.
 *
 * Colors are CONSTRUCTOR PARAMS (ARGB ints straight off the shared `SupermuxColors` pane tones —
 * `terminal` / `terminalForeground`), resolved by the caller from the composable theme
 * (`LocalPanes.current`) so the terminal follows the app's light/dark palette. The defaults are
 * the dark tones (terminal 0xFF050605 / fg 0xFFD8DED3) purely as a headless/test convenience.
 *
 * Font: Geist Mono from the bundled `/fonts/geist_mono_regular.ttf` resource (the same file
 * `theme/Type.kt` uses for Compose text), loaded ONCE via [Font.createFont] and cached in the
 * companion; falls back to the logical [Font.MONOSPACED] (logged) if the resource is missing or
 * unparseable. Size 13f.
 *
 * ### Typeahead is DISABLED — which member controls it
 * The `SettingsProvider` member feeding `JediTermWidget`'s `TerminalTypeAheadManager` is
 * **`getTypeAheadSettings(): TerminalTypeAheadSettings`** (declared on `UserSettingsProvider`,
 * implemented in `DefaultSettingsProvider` as `return TerminalTypeAheadSettings.DEFAULT`).
 * Verified against the 3.73 bytecode via javap: `TerminalTypeAheadSettings.DEFAULT`'s static
 * initializer is `new TerminalTypeAheadSettings(true, MILLISECONDS.toNanos(100), ...)` — i.e.
 * JediTerm's own typeahead is ENABLED by default. We override it to `isEnabled = false` because
 * the shared supermux `PredictionEngine` (Task 5) is the ONLY prediction system; two independent
 * predictors would double-draw echoes.
 */
class SupermuxTermSettings(
    private val background: Int = DARK_TERMINAL_BG,
    private val foreground: Int = DARK_TERMINAL_FG,
) : DefaultSettingsProvider() {

    /** Default cell style = theme fg on theme bg. `getDefaultForeground`/`getDefaultBackground`
     *  (what `TerminalPanel` paints the window with) both derive from this single member. */
    override fun getDefaultStyle(): TextStyle =
        TextStyle(terminalColor(foreground), terminalColor(background))

    override fun getTerminalFont(): Font = baseMonoFont.deriveFont(terminalFontSize)

    override fun getTerminalFontSize(): Float = 13f

    /** JediTerm's built-in typeahead OFF — see class KDoc for the member provenance. Threshold and
     *  style are carried over from DEFAULT; with `enabled = false` they are inert. */
    override fun getTypeAheadSettings(): TerminalTypeAheadSettings = TerminalTypeAheadSettings(
        /* enabled = */ false,
        TerminalTypeAheadSettings.DEFAULT.latencyThreshold,
        TerminalTypeAheadSettings.DEFAULT.typeAheadStyle,
    )

    override fun audibleBell(): Boolean = false

    /**
     * JediTerm's own SGR mouse-report forwarding OFF — [WheelAccumulator] via
     * [DesktopTerminalPanel]'s wheel listener is the sole path for wheel→tmux bytes.
     *
     * `DefaultSettingsProvider.enableMouseReporting()` is `true` by default, and 3.73's
     * `TerminalPanel` has its OWN built-in remote-mouse listener (`JediTerminal`, registered via
     * `TerminalPanel.addTerminalMouseListener` inside `JediTermWidget`'s constructor) that, when
     * this flag is on AND the emulator has parsed a mouse-tracking DECSET from the pty (exactly
     * what tmux's `mouse on` sends on attach — `JediEmulator` DOES implement `?1000`/`?1002`/
     * `?1003`/`?1006` parsing), sends its OWN SGR wheel-button (64/65) bytes to the `TtyConnector`
     * automatically. Confirmed empirically (headless probe: construct a `JediTermWidget`, force
     * both the model's and display's negotiated `MouseMode`/`MouseFormat` the way the real DECSET
     * parse would, dispatch a synthetic `MouseWheelEvent` at `TerminalPanel`, and observe
     * `sendInput`): with this flag left at its `true` default, JediTerm's own listener sends one
     * `"[<65;...M"` sequence *before* any listener added afterwards (ours, added once the
     * widget already exists) even runs — `MouseWheelEvent.consume()` does NOT stop sibling
     * `MouseWheelListener`s registered on the same component (`AWTEventMulticaster` invokes all of
     * them unconditionally), so a second, our own, forward on top would double every wheel notch.
     * Flipping this to `false` disables ONLY that remote byte-send path (confirmed via the same
     * probe: zero `sendInput` calls once this returns `false`); it does not resurrect JediTerm's
     * LOCAL scrollback scroll, which is independently gated on the terminal's negotiated
     * `MouseMode` (see `TerminalPanel.isLocalMouseAction`), not on this setting, and is a no-op
     * under our tmux alt-screen sessions regardless (same "inert" story as SwiftTerm-mac/termlib).
     * Click/drag mouse-report forwarding is not part of this milestone on ANY supermux client
     * (Android/iOS are touch-only and don't attempt it either), so losing JediTerm's built-in
     * version of it here is scope-consistent, not a regression.
     */
    override fun enableMouseReporting(): Boolean = false

    companion object {
        /** Dark pane tones (shared Theme.kt `supermuxDark`) — defaults only; production passes
         *  the resolved theme colors. */
        val DARK_TERMINAL_BG: Int = 0xFF050605.toInt()
        val DARK_TERMINAL_FG: Int = 0xFFD8DED3.toInt()

        private const val FONT_RESOURCE = "/fonts/geist_mono_regular.ttf"

        /** Geist Mono, loaded once for every settings instance (Font.createFont is not cheap).
         *  createFont works headlessly, so this is safe in tests/CI. */
        internal val baseMonoFont: Font by lazy {
            loadMonoFont { SupermuxTermSettings::class.java.getResourceAsStream(FONT_RESOURCE) }
        }

        /** Load the bundled mono TTF from [open]; on ANY failure (missing resource, corrupt font)
         *  log and fall back to the logical monospaced font so the terminal still renders.
         *  Seam-shaped (stream supplier in, Font out) so the fallback path is unit-testable. */
        internal fun loadMonoFont(open: () -> InputStream?): Font = runCatching {
            val stream = open() ?: error("font resource $FONT_RESOURCE not found")
            stream.use { Font.createFont(Font.TRUETYPE_FONT, it) }
        }.getOrElse { e ->
            println("[SupermuxTermSettings] Geist Mono load failed — falling back to logical monospaced: $e")
            Font(Font.MONOSPACED, Font.PLAIN, 13)
        }

        /** ARGB int (Compose/Android color convention) → JediTerm [TerminalColor] (rgb, no alpha —
         *  the pane tones are fully opaque). */
        internal fun terminalColor(argb: Int): TerminalColor = TerminalColor(
            (argb ushr 16) and 0xFF,
            (argb ushr 8) and 0xFF,
            argb and 0xFF,
        )
    }
}
