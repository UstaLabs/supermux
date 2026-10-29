package dev.supermux.webview.runtime

import dev.supermux.webview.engine.WebEngineFactory

/** This platform's engines. Until its engine plan lands, a platform reports `Unavailable(NotImplemented)`. */
internal expect fun platformEngineFactory(): WebEngineFactory

/** This platform's pool and cache sizes (spec §4). */
internal expect fun platformRuntimeConfig(): RuntimeConfig
