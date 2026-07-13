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

    // ── Mouse reporting: DELIBERATELY LEFT AT THE DEFAULT (`enableMouseReporting()` == true) ──
    //
    // Do NOT add a custom wheel→tmux bridge to DesktopTerminalPanel, and do NOT override
    // `enableMouseReporting()` — JediTerm 3.73 already does the whole job natively, unlike the
    // touch-only terminal libs (SwiftTerm-iOS, ConnectBot termlib) that forced Android/iOS to
    // build the shared `dev.supermux.net.TerminalScroll` bridge. Task 4 established this
    // empirically:
    //
    // - `TerminalPanel` registers the `JediTerminal` itself as a built-in `TerminalMouseListener`
    //   (via `TerminalPanel.addTerminalMouseListener`, called from `JediTermWidget`'s
    //   constructor). Gated on `SettingsProvider.enableMouseReporting()` (default `true`) AND the
    //   terminal having negotiated a mouse-tracking mode from the pty — which tmux's `mouse on`
    //   always sends on attach (`JediEmulator` parses the `?1000`/`?1002`/`?1003`/`?1006`
    //   DECSETs, `?1006` → SGR format).
    // - Under that gate it forwards WHEEL as SGR wheel-button (64/65) reports — bytecode refs:
    //   `TerminalPanel.lambda$addTerminalMouseListener$4` → `JediTerminal.mouseWheelMoved` →
    //   `mousePressed` → `mouseReport`/`sendBytes` — AND click/drag/release the same way
    //   (`TerminalPanel$7.mousePressed` etc., gated on the same flag). This matches the web
    //   client (the parity reference), where xterm.js forwards clicks and wheel natively under
    //   tmux mouse-on; Android/iOS being wheel-only is a touch-platform constraint, not a design
    //   choice.
    // - Verified with a headless probe (since deleted): a real `JediTermWidget` with negotiated
    //   SGR mouse mode dispatched a synthetic `MouseWheelEvent`, and JediTerm's own listener sent
    //   exactly one correct `ESC [<65;...M` to the `TtyConnector`. With `enableMouseReporting()`
    //   overridden to `false`, zero bytes were sent — and click reporting would have died with it,
    //   which is why Task 4's first cut (custom wheel bridge + `false` override) was reverted: it
    //   silently killed click/drag/copy-mode/TUI-mouse to replace a path JediTerm already had.
    //
    // For anyone tempted to re-add a bridge anyway: an extra `MouseWheelListener` on
    // `TerminalPanel` DOUBLE-FORWARDS every notch, because `MouseWheelEvent.consume()` does not
    // stop sibling listeners already registered on the same component (`AWTEventMulticaster`
    // invokes all of them unconditionally). If trackpad feel ever demands the shared
    // `TerminalScroll` accumulator on desktop (sub-notch smoothing), the surgical path is to
    // remove ONLY JediTerm's own wheel listener and reinstate a pure accumulator (see this file's
    // git history at Task 4 for the reverted `WheelAccumulator`), keeping `enableMouseReporting()`
    // true so clicks keep working. Premature now.

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
