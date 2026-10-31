package dev.supermux.webview.engine

/**
 * Marks the engine SPI: what an engine implements and what the runtime drives it with. That is [WebEngine],
 * [EngineEvent], [EngineConfig], [PageSetup], [EngineClient], [EmbeddingMode], [WebEngineResult],
 * [WebEngineFactory.create] and the `WebEngineFactory { … }` builder, the page side of the bridge
 * ([dev.supermux.webview.bridge.BridgeShim], [dev.supermux.webview.bridge.WebBridge.READY_PREFIX] and
 * [dev.supermux.webview.bridge.WebBridge.READY_MAX_CHARS]) and the overlay cut-outs
 * ([dev.supermux.webview.runtime.OverlayCutouts], [dev.supermux.webview.runtime.OverlayShape],
 * [dev.supermux.webview.runtime.ScrimShape]).
 *
 * It is for engine authors and for tests that drive engines, not for apps, and it isn't stable: while the six engines
 * were built it changed shape seven times (async creation with a checked reset and a per-lease setup; `needsCutouts`;
 * parking and [WebEngine.replayState]; [WebEngine.overlaysAtSnapshot]; [EngineEvent.PaintLost];
 * [WebEngineFactory.namedProfilesPersist]; [WebEngine.onUnusable]), and it will change again, with no deprecation
 * cycle. Apps pass factories around ([dev.supermux.webview.runtime.platformWebEngineFactory],
 * [dev.supermux.webview.runtime.WebViewRuntime.factory]) without opting in.
 *
 * Opt in where you implement or drive an engine, `@OptIn(WebEngineSpi::class)`, or for a module of engines with the
 * compiler option `-opt-in=dev.supermux.webview.engine.WebEngineSpi` (Gradle: `languageSettings.optIn(…)` on each source
 * set). The library's own engines do the latter.
 */
@RequiresOptIn(
    message = "The engine SPI is for engine authors and changes without notice. Opt in with @OptIn(WebEngineSpi::class).",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class WebEngineSpi
