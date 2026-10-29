package dev.supermux.webview.engine.web

/** How a page keeps Compose overlays visible over it: by cutting them out, or by hiding while one touches it. */
internal enum class CutoutMode {
    /** `clip-path: path(evenodd, …)` on the page: holes show, and take the clicks (CSS Masking: clips hit-test too). */
    ClipPath,

    /** Fallback: a page that an overlay intersects hides (the runtime's placeholder or canvas shows) until it closes. */
    HidePage,
}

/**
 * What the web engine's feasibility test decided (docs/superpowers/plans/2026-10-29-webview-web-engine.md, "Feasibility
 * results", 2026-10-29). Each value names the measurement it follows; change one only with a new measurement.
 */
internal object WebEngineDecisions {
    /**
     * G2: hit testing follows `clip-path` on the page's wrapper, rounded corners included, with trusted clicks too. Only
     * while the clipped div is the frame's only hit target: the outer div needs `pointer-events: none`, and the clipped
     * div an explicit `auto`, as the property is inherited. Otherwise the outer div, transparent but sized to the page,
     * fills every hole and takes the clicks under an inert page too (G2's first runs).
     */
    val cutoutMode: CutoutMode = CutoutMode.ClipPath

    /**
     * G6: an engine-started document adds no tab history, and once a page's iframe is removed, a Back never navigates a
     * new, uniquely named iframe. So `reset()` can promise that nothing from before it is reachable.
     */
    const val RESET_ISOLATES_HISTORY: Boolean = true

    /** G4: live iframes at once. 12 while a small demo costs Chrome at most 20 MB (spec §4 asks 12 on the web). */
    const val MAX_LIVE: Int = 12
}
