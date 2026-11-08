package dev.supermux.webview.runtime

import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.web.IframeEngineFactory
import dev.supermux.webview.engine.web.WebEngineDecisions

/** The web's engine: an iframe in a layer above the canvas (spec §7). */
internal actual fun platformEngineFactory(): WebEngineFactory = IframeEngineFactory

/**
 * The web: as many live iframes as the feasibility test's memory measurement allowed ([WebEngineDecisions.MAX_LIVE],
 * 12 by the spec), and 3 spares, which cost nothing here: a spare holds no iframe, since every document starts in a
 * fresh one. 64 MB of snapshots, which stays empty: cross-origin pixels can't be read. Within that, the engines keep
 * pages of another site to a budget of their own ([WebEngineDecisions.MAX_LIVE_URL]), since each gets a process.
 */
internal actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(maxLive = WebEngineDecisions.MAX_LIVE, maxIdle = 3, snapshotCacheBytes = 64L shl 20)
