package dev.supermux.ui

/**
 * Brand-first theme policy.
 *
 * supermux ships a hand-authored OKLCH brand palette (teal on a warm near-black ladder,
 * see [supermuxDark] / [supermuxLight]). Material You / dynamic color repaints every
 * surface from the device wallpaper, which erases that identity. So dynamic color is an
 * explicit **opt-in** (accent personalization for users who want it), never the first-run
 * experience — a fresh install shows the brand.
 *
 * Kept in the shared module beside the palette so the policy has a single source of truth
 * and is unit-testable without the Android framework.
 */
object ThemeDefaults {
    /**
     * Brand-first: dynamic color (Material You) is off until the user opts in.
     *
     * Deliberately a plain `val`, NOT `const`: a `const` is inlined into every call site, and a
     * Kotlin incremental compile can leave a stale inlined copy in consumers when this flips — so a
     * normal `assembleDebug` could ship the old default while the source reads the new one. A `val`
     * is always read live, so the default can never silently fail to propagate.
     */
    val DYNAMIC_COLOR_ENABLED = false

    /**
     * Effective dynamic-color setting.
     *
     * @param stored the persisted preference, or `null` when the user has never set it
     *   (fresh install) — in which case the brand-first default applies.
     */
    fun dynamicColorEnabled(stored: Boolean?): Boolean = stored ?: DYNAMIC_COLOR_ENABLED
}
