# webview-compose: the platform's own web engine, inside Compose

`dev.supermux.webview:webview-compose` is a Compose Multiplatform web view for apps that show **hundreds of web
views**: interactive demos posted into a chat, or a plugin system built on web pages. Each `WebView` is a cheap
handle. A small pool of live engines is shared among them, and a view without an engine shows a snapshot of its page,
or a placeholder.

It is built for each platform's own engine: Android `WebView`, `WKWebView` on iOS and macOS, WebView2 on Windows, WPE
WebKit on Linux, and an `<iframe>` on the web. **None of these engines exists yet**, and until a platform's does, the
platform reports `Unavailable(NotImplemented)`. What exists today is everything around the engines: the pool,
activation, snapshots, page memory, overlays, the page bridge and the engine contract, tested against the library's
fake engine.

It depends on Compose and kotlinx (coroutines, serialization) only, nothing else of supermux. It targets Android, iOS
(arm64 and simulator arm64), the desktop JVM and wasmJs. It isn't published yet; depend on the project, as
[`webview-sample`](../webview-sample) does: `implementation(project(":webview-compose"))`.

The design, including the goals set for the engines, is in the [design document][design]. This README describes what is
built.

[design]: ../../docs/superpowers/specs/2026-10-28-webview-compose-design.md

## Status: what has actually been run, and where

| platform | engine | what has run | what has not |
|---|---|---|---|
| any | fake engine | `:webview-compose:jvmTest`: **542 tests**: 409 common ones (the pool, activation, overlay geometry, the bridge, the caches and page memory, `WebViewState`, content, URLs and the navigation policy, the fake engine) and 133 JVM-only ones (the `WebView` composable, its host and the overlay modifiers over the fake engine, in Compose UI tests). `:webview-sample:jvmTest`: **9 tests**, among them a 200-message chat read and scrolled with every frame checked against the budgets, with no engine leaked | any real engine |
| browser (wasmJs) | not built | `:webview-compose:wasmJsBrowserTest`: **449 tests** in headless Chrome 156: the 409 common ones, and 40 more, among them the page-side bridge shim executed by the browser | the iframe engine |
| Android, iOS, macOS, Windows, Linux | not built | the module compiles for Android and iOS (`compileDebugKotlinAndroid`, `compileKotlinIosSimulatorArm64`) | everything else: every platform reports `Unavailable(NotImplemented)` until its engine is built |

All of it ran on 2026-10-29 on an Apple M1 Mac (macOS 26.6), JDK 17. The perf lab's numbers, for the fake engine, are
[below](#the-perf-lab).

## Quick start

```kotlin
@Composable
fun AgentDemo(html: String) {
    // Html hashes its text once: remember the content rather than rebuilding it at every composition.
    val content = remember(html) { WebContent.Html(html) }
    val state = rememberWebViewState(content)
    // Live once it is on screen and has stopped moving: WebView's default activation, made for lists.
    WebView(state, sizing = WebSizing.WrapContentHeight(max = 400.dp))
}
```

Today this shows the placeholder: once the view tries to go live, `state.phase` is `Unavailable(NotImplemented)`.

- **`WebContent`** is what to show: `Url(url, headers)` for http(s) pages, `Html(html)` for in-memory HTML on an origin
  of its own (agent demos), and `Bundle(origin, resources, entry)` for a set of files served on an origin (plugins).
- **`WebViewState`** is the logical page: `content`, `url`, `title`, `loading`, `canGoBack`, `canGoForward`, `phase`
  (`Cold`, `Activating`, `Live`, `Crashed`, `Unavailable`), `error`, `contentSize`, `console`, `bridge`, and a
  `navigator` (`load`, `back`, `forward`, `reload`, `stop`). `state.load(content)` navigates, even to the content
  already shown. Show a state in one `WebView` at a time. Main thread only. The navigator reaches only the engine of the
  view that shows the state; an engine that throws puts the view in `Crashed`, and nothing reaches your code.
- **`rememberWebViewState(content)`** keys the state by `content.key`, and never undoes what you load into it: it loads
  `content` again only when the argument itself changes. It isn't saveable on purpose: HTML bodies would overflow
  Android's saved-state bundle. Page memory brings the page back instead ([below](#snapshots-and-page-memory)).
- **`WebView(state, modifier, settings, client, activation, sizing, placeholder)`**:
  - `settings`: `javaScriptEnabled`, `inspectable`, `darkMode` (`Auto`, `Light`, `Dark`), `transparentBackground`,
    `userAgentSuffix`, `profile` (`Default`, `Named(id)` or `Ephemeral`; unset, `Html` gets `Ephemeral` and the rest
    `Default`), `mediaPlaybackRequiresGesture` and `bridgeOrigins`.
  - `activation`: when the view goes live; `Activation.WhenVisible()` unless you say otherwise ([Activation](#activation)).
  - `client`: answers the page's requests: navigations, new windows, dialogs, file choosers, permissions and
    downloads. Every default is the safe answer ([Security defaults](#security-defaults)).
  - `placeholder`: covers the view while there's no snapshot. The default is an opaque card in the resolved theme,
    with the page's title once known. A custom one should be opaque too.

## The pool and memory

All web views share one `WebViewRuntime`: the engine pool, the snapshot cache, page memory and the overlay registry.
`WebViewRuntime.Default` serves unless `ProvideWebViewRuntime(runtime) { … }` provides another.

- **Budget.** At most `maxLive` engines are leased at once: 4 on phones, 6 on tablets, 8 on desktop and 12 iframes on
  the web (`RuntimeConfig.platformDefault()`). Over budget, a view evicts the lowest-priority view below its own;
  with nobody to evict, it keeps its cover and waits for capacity. Priority: the focused view (touched last, or
  holding the keyboard focus), then visible `Eager` views, then other visible views, then hidden ones. Ties go to the
  least recently used, where use means a touch or coming into view.
- **Spares.** Up to `maxIdle` blank engines are kept for reuse: 3, or 2 on iOS, where each idle `WKWebView` may keep
  its own WebContent process. An engine given back is reset and kept as a spare if there's room, or destroyed, so
  there are never more than `maxLive + maxIdle` engines. The exception is an engine whose eviction or reset overran
  its time bound: it is released once that call returns. A spare is created ahead of need only when the pool has none
  of that kind and got none back in the last 10 s, as at cold start; otherwise recycling supplies them.
- **Never lost, never shared.** Every engine the pool creates ends up leased, a spare or released, whatever throws or
  is cancelled; one whose call hangs past its bound is never reused, and is released once that call returns. An engine
  serves one view at a time, and a state reaches only the engine of the view that shows it. An engine changes hands
  only after the view that had it lets go of its surface, waiting up to 500 ms for the composition to do so. Everything
  is bounded in time: a creation 20 s, a view saving its page before an eviction 5 s, a reset 10 s, a snapshot or a save
  2 s. An engine that overruns is destroyed, never reused.
- **Failures.** A failed creation is retried after 2 s, 10 s, then every 60 s. Other `Unavailable` reasons stay until
  `navigator.reload()`. An engine that throws, or whose renderer dies, puts its view in `Crashed`, covered by its last
  snapshot or its placeholder; `navigator.reload()` brings it back. `runtime.onError` sees what the guards catch.
- **Memory pressure.** Call `runtime.onMemoryPressure()` when the OS says memory is short (Android's `onTrimMemory`, an
  iOS memory warning, the macOS memory-pressure source); the library doesn't listen for these itself yet. It releases
  the spares, evicts live views down to half the budget (never the focused one) and gives back half of what the
  snapshot cache holds. Until `runtime.onMemoryRecovered()`, or 30 s after the latest call, the budget stays at half,
  no spares are kept, and the snapshot cache keeps to that half, even as the evicted views snapshot their pages on the
  way out.

## Activation

```kotlin
Activation.WhenVisible(settleMillis = 150)  // the default, for lists: live once visible and still for 150 ms
Activation.Eager                            // panes: live whenever visible, even while moving
Activation.OnInteraction                    // very long lists: a snapshot until tapped
```

- `WebView` uses `WhenVisible()` unless told otherwise, because lists are the main use. Give a pane `Eager`.
- A cold view never activates while it moves (a scroll, an animation) unless it is `Eager`, so `Eager` is for panes,
  not list items. A live view stays live while it moves.
- A view is visible while at least 1×1 px of it is in the window, after the clipping of its scroll containers.
- A view that leaves the screen but stays composed keeps its engine for `graceMillis` (2 s), and a hidden `Eager` pane
  for `hiddenReleaseMillis` (10 min), so scrolling a `Column` back and forth doesn't rebuild pages.
- A view that leaves the composition gives its engine back as soon as it has saved its page. In a `LazyColumn` that is
  every item that scrolls out of the composed window, so the grace doesn't apply there: an item scrolled back is a new
  view, which shows its page's snapshot at its height at once ([page memory](#snapshots-and-page-memory)) and goes
  live as any other. Expect a page start per item you scroll back to, not a kept engine.
- A new view of a page whose save is still under way (an item that has just scrolled away, a state moved to another
  view) waits up to 1 s for that save before its first document, so it starts from what it keeps; then it decides
  again whether to go live, in case it left the screen meanwhile.

## Sizing

- **`WebSizing.Fill`**, the default: the view takes the size its modifier gives it.
- **`WebSizing.WrapContentHeight(max, initial = 160.dp)`**: full width, and the page's content height, capped at
  `max` and followed with a spring. A taller page scrolls inside the view. The height comes from the bridge shim's
  `ResizeObserver` (so only pages that get the shim, see [The bridge](#the-bridge)), or from an engine that measures
  natively.
  - **Choose `initial` close to your typical content.** Until pages report, every view in a list is `initial` tall,
    and that first layout decides which views are on screen and go live; real heights then push some of them off
    screen again.
  - Page memory keeps each page's last height, so a view recreated by a lazy list shows at its real height at once.
  - Reported sizes are the page's to choose, so they're checked: non-finite, zero and negative sizes are ignored,
    both sides are clamped to 32,000 CSS px, and the view is never laid out taller than 262,142 px, the most Compose's
    `Constraints` can hold. A page that grows with its own viewport (`100vh` plus a margin, up to `400vh`) twice in a
    row is caught, and stops growing the view.

## Snapshots and page memory

- **Covers.** A view without a painted page shows its last snapshot, or its placeholder when it has none. The cover
  stays until the engine paints the document the view started, so, with engines that keep their contract, a page
  never shows a white or blank frame. Navigations the page makes itself, reloads and history navigations keep the
  previous page on screen until the next one paints: engines hold paint, by contract.
- **When.** Snapshots are taken only while the page is composed and laid out: 500 ms after its first paint, 500 ms
  after the user's last interaction, and when a deactivation or an eviction takes the engine, before the view lets go
  of it. A view that leaves the composition takes none, and keeps its last good snapshot.
- **Where.** In an LRU cache bounded by pixel bytes (`snapshotCacheBytes`: 32 MB on phones and tablets, 128 MB on
  desktop, 64 MB on the web), keyed by profile, content, pixel size, theme and density. A view never shows a snapshot of
  other content, of another profile or of the other theme. Only views on screen hold their cover's pixels, so covers
  take about a screenful, and no placeholder is composed under a cover.
- **Page memory.** A lazy list disposes the items that scroll away, and the item that scrolls back is a new view with a
  new state. So when an engine goes, the runtime remembers, by profile and content key, the page's snapshot, height,
  engine state (history and scroll, where the engine can save them) and page state. A new view of the same content in
  the same profile shows its snapshot at its real height from its first frame, and restores the rest when it goes
  live. It keeps 512 pages and 16 MB at most, least recently used out first.
- **Profiles are a boundary.** A profile is a user's cookies and storage, so nothing of a page shown in one profile
  reaches another: a view that shows the same content in another profile (a settings change, or a state moved to a view
  with other settings) starts fresh, with no restore and no cover.
- **Logout.** Users who share a profile, by logging in with cookies in `WebProfile.Default`, share the keys too, and a
  plugin's `Bundle` has the same key for every user. Call `runtime.clear()` at logout: it forgets every page and
  snapshot, no view shows a snapshot from before it, states you still hold drop what they remembered from before it
  when next shown, and pages live across it save nothing more (reload or remove them). Engines keep their profile's
  cookies and storage: give each user a `WebProfile.Named` of their own, or clear that data through the platform.
  `runtime.forget(content)` forgets one content in every profile, for content that is gone.
- **Page state.** A page keeps what it needs across engines through the shim. The page has 300 ms to answer when its
  engine goes. What it saved is pinned to the origin of the document that saved it, and given back only to a document
  of that origin:

  ```js
  hostBridge.onSaveState(() => ({ draft: input.value }));
  hostBridge.onRestoreState(saved => { input.value = saved.draft; });
  ```

## The bridge

The page side is `window.hostBridge`, the same on every engine; the app side is `state.bridge`. Messages are JSON.

```kotlin
// Register once on the state: handlers and listeners outlive engines, and stay registered across state.load().
state.bridge.handle("sum") { params -> JsonPrimitive(params.jsonArray.sumOf { it.jsonPrimitive.int }) }
state.bridge.on("clicked") { data -> println("clicked: $data") }

// While the view is live:
val title = state.bridge.call("title")              // suspends; BridgeTimeoutException after 10 s
val sent = state.bridge.post("theme", JsonPrimitive("dark"))
```

```js
hostBridge.handle('title', () => document.title);
hostBridge.on('theme', theme => { document.body.dataset.theme = theme; });
const sum = await hostBridge.call('sum', [1, 2, 3]);
hostBridge.post('clicked', { at: Date.now() });
```

- **Who gets the shim.** With JavaScript on, documents of the content's own origin (`Html`, `Bundle`) and of the
  origins in `WebSettings.bridgeOrigins`. A `Url` page gets it only if its origin is listed there, for example
  `WebSettings(bridgeOrigins = setOf("https://app.example.com"))`. There is no wildcard. Each is an origin as browsers
  write it, `scheme://host[:port]` with no path, no trailing slash and no default port; anything else is refused.
- **While the view has no engine,** `call` throws `BridgeException` and `post` returns false. Until the page's shim
  has announced itself, calls wait within their timeout, and posts are queued, the newest 64.
- **After `state.load(…)`** the bridge leaves the old document at once: what you send waits for the new content's
  document, even while the view moves to another engine for it, and never reaches the page being replaced. What was
  waiting for the old content fails, or is dropped.
- **Handlers and listeners stay** across `load()`. When one state shows one party's content after another's (plugins),
  remove what you registered for the last one (`on` and `handle` return the function that does), or give each party a
  state of its own.
- **The page is untrusted.**
  - Each document announces itself with a random nonce, and every message is addressed to, and signed by, one
    document. Nothing meant for one document reaches the next, even on the same origin, and a new document fails the
    old one's pending calls.
  - Messages from other origins are dropped. Messages are size-capped (64 KiB, replies 256 KiB), depth-checked, strict
    JSON, and rate-limited (200 a second, bursts of 400). At most 16 page calls run at once, 10 s each.
  - A handler's exception reaches the page only as "handler failed", unless it is a `BridgeException` meant for the
    page; `bridge.onError` sees the real one.
  - Names starting with `$` are the library's. `bridge.droppedMessages` counts what was dropped.
- A page's own `call` times out after 30 s by default.

## Overlays and scrims

With the engines as designed, Compose draws over the page on Android, iOS and the frame-mode desktops (Windows,
Linux), so menus, dialogs and tooltips simply cover it. A page that sits *above* the Compose canvas can't be covered:
the web's `<iframe>`, and macOS in one of the layouts its engine may use. Such a page cuts the overlays out of itself,
and dims under the scrims, so mark them:

```kotlin
val shape = MenuDefaults.shape
DropdownMenu(expanded, onDismissRequest, modifier = Modifier.webViewOverlay(shape), shape = shape) { … }
```

- **`Modifier.webViewOverlay(shape)`** goes on a menu, popup, tooltip or dialog surface. Give it the shape the surface
  is drawn with; each corner keeps its own radius. Put it after the modifiers that move, size or transform the
  surface.
- **`Modifier.webViewScrim(color)`** goes on a modal scrim, with the colour the scrim is drawn with. Stacked scrims
  compound in the order they appeared.
- **Several windows.** A multi-window app wraps each window's content in `ProvideWebViewOverlays { … }`, so an
  overlay in one window never cuts a hole in another window's page.
- The geometry is common code, and unit-tested: each live page gets its visible area minus the overlays as an
  even-odd path, whose holes are disjoint and cover exactly the overlays. An isolated overlay keeps its per-corner
  radii, and so does one that contains the others away from its corners. Applying it, with the scrims, is the
  engines' part (`applyOverlays`): by design, CSS `clip-path` on the web and a layer mask on macOS. No engine applies
  it yet.

**Material dialogs.** Material's dialog draws its scrim in a layer of its own, which no modifier reaches, so a page
above the canvas would stay bright under it. Turn that scrim off, and draw a full-window one behind the dialog's
content:

```kotlin
val scrim = Color.Black.copy(alpha = 0.32f)
val shape = AlertDialogDefaults.shape
Dialog(
    onDismissRequest = onDismiss,
    // The content may fill the window, and the dialog's own scrim is off.
    properties = DialogProperties(usePlatformDefaultWidth = false, scrimColor = Color.Transparent),
) {
    Box(Modifier.fillMaxSize().background(scrim).webViewScrim(scrim), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.webViewOverlay(shape),
            shape = shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            // the title, the text and the buttons
        }
    }
}
```

- The scrim is now the dialog's own content, so a tap on it no longer counts as outside the dialog: give it a click
  handler if a tap outside should dismiss.
- `scrimColor` belongs to Compose Multiplatform's `DialogProperties` on desktop, iOS and the web. Android's has none,
  and Android needs no recipe: Compose draws over the page there.

## Security defaults

The library enforces these itself, and tests them:

- **Local content.** Unless given an origin, `Html` is served on `https://c<hash>.invalid`, where the hash is the
  first 130 bits of the HTML's SHA-256. Each demo is then an origin and a site of its own, which no other content can
  forge. `.invalid` never resolves, so a request an engine fails to intercept fails closed. `Html` runs in the
  `Ephemeral` profile unless the settings name another.
- **Navigation.** The default policy (`defaultNavigationDecision`, used by `WebClient.Default`) parses URLs the way
  browsers do.
  - Local content stays on its own origin, its own `blob:` URLs and `about:blank` or `srcdoc`. Network pages can't
    navigate into local origins.
  - `javascript:`, `data:`, `file:` and unknown schemes are blocked.
  - Leaving the app needs a user gesture, and only http(s), `mailto:`, `tel:` and `sms:` URLs ever reach the OS. The
    runtime checks the scheme again before opening one, whatever a custom client decided.
- **Page requests.** By default, dialogs are dismissed (`beforeunload` leaves the page, so a page can't trap the user),
  file choosers cancelled, permissions and downloads denied, and new windows blocked. `onNavigation` and `onNewWindow`
  may run off the main thread: keep them fast, pure and thread-safe.
- **The bridge,** as [above](#the-bridge): listed origins only, a nonce per document, and limits on everything.
- **`WebContent.Url`** takes only http(s) URLs with a host. Its `toString` shows no userinfo, query, fragment or header
  values, and its key carries a fingerprint of its headers, so the same URL sent with other credentials is another page.
- **Users.** Snapshots and page memory are kept per profile and content, so a page shown in another profile never gets
  another profile's snapshot or saved state. Users who share a profile are told apart only by `runtime.clear()` at
  logout ([Snapshots and page memory](#snapshots-and-page-memory)).
- **History.** A switch to or from local content (`Html`, `Bundle`) starts the new document on a fresh lease, an engine
  the pool has reset or a new one, so no history crosses into or out of local content. Network pages loaded one after
  another in a view keep their history, as in a browser tab.

The rest is the engines' part. Since no engine exists yet, these are goals, set by the design and the engine contract:
no `file://` access, mixed content blocked, pop-ups denied, certificate errors never bypassed, inspectors only when
`inspectable = true`, the shim run in an isolated world where the engine has one, and every message checked against
the receiving document's origin when it arrives.

## The runtime

```kotlin
// One per app, created once, for the life of the process.
val webViews = WebViewRuntime(config = RuntimeConfig.platformDefault().copy(maxLive = 6))

ProvideWebViewRuntime(webViews) { App() }
```

- `RuntimeConfig(maxLive, maxIdle = 3, snapshotCacheBytes, graceMillis = 2_000, hiddenReleaseMillis = 600_000,
  pageMemoryEntries = 512)`.
- `WebViewRuntime(factory, config, scope, clock)`: `factory` creates the engines, the platform's by default. `scope`
  runs the pool's and the views' work, on `Dispatchers.Main` by default; on desktop the library brings
  `kotlinx-coroutines-swing` for it.
- Control it through the runtime: `onMemoryPressure()`, `onMemoryRecovered()`, `onError`, `clear()` and
  `forget(content)`. The pool's own controls are internal, so the cache halving, the error forwarding and the budget
  (`RuntimeConfig.maxLive`) stay consistent.
- For monitoring, as the perf lab does: `pool.liveCount`, `idleCount`, `budget` and `maxLive`; `snapshots.sizeBytes`,
  `count` and `maxBytes`; `pages.count` and `sizeBytes`; `coverBytes` and `coverCount`.
- Main thread only: the runtime, the states and the views.

## For engine authors: the engine SPI

An engine implements `WebEngine`, and a `WebEngineFactory` creates it; give the factory to `WebViewRuntime`. The full
contract is the KDoc of [`engine/WebEngine.kt`](src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt). In
short:

- **Creation.** `create(config)` may suspend, and never throws: failures are `WebEngineResult.Unavailable(reason,
  detail)`. `EngineConfig` holds only what can't change after construction, the profile and the media playback
  policy, and engines are pooled by it. Everything else comes per lease through `configure(PageSetup)`, before every
  `load` or `restoreState`.
- **Threads and events.** The runtime calls every member on the main thread. Events may come from any thread; the
  engine buffers them, and never drops a lifecycle event or a page's `$ready`, which it recognizes as the bridge does
  (`WebBridge.READY_PREFIX`, `READY_MAX_CHARS`). Once `load`, `reload` or `restoreState` returns, no event caused by an
  earlier document is delivered, even one still buffered: engines filter by navigation.
- **No white frame.** After creation or a reset, the surface stays invisible until `FirstPaint`, or a main-frame
  `Error`. Between documents, the engine keeps showing the previous one until the next paints.
- **Reuse.** `reset()` returns true only if nothing of the last lease can come back: no history, no late event, no
  pending request, no per-lease script or grant, and an `Ephemeral` profile's data wiped. Otherwise it returns false,
  and the pool destroys the engine. `isUsable` turns false for good after a crash or a release, and after `release()`
  every member does nothing.
- **Snapshots and state.** `snapshot()` is null until the current document has painted, and unless `Surface` is
  composed and laid out at a non-zero size. `restoreState(state, content)` returns false when it can't restore that
  content's state, and the runtime loads the content instead.
- **The bridge.** Inject `BridgeShim.source(sendExpression)` at document start into the main frame of documents whose
  origin is in `PageSetup.bridgeOrigins`, with JavaScript on, and deliver host messages by calling
  `window.__hostBridgeReceive(json)`. `postMessage(json, targetOrigin)` delivers only if the main frame's document
  has that origin when the message arrives. Page messages come from the main frame only, with the origin the engine
  sees.
- **The view.** `Surface(modifier)` hosts the native view or the frames. When an engine changes hands, the runtime
  waits for the view that had it to let go of its surface before the next one composes it, for 500 ms at most.
  `applyOverlays(cutouts)` cuts overlays out of a page above the canvas; an engine that Compose draws over returns
  false from `needsCutouts`, and the runtime computes none for it. `setVisible` and `requestFocus` are optional.

## Testing with the fake engine

`dev.supermux.webview.testing.FakeWebEngine` follows the engine contract with no browser behind it, so an app can test
its web views wherever Compose UI tests run:

```kotlin
@Test fun theDemoGoesLive() = runComposeUiTest {
    val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 240f }))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val runtime = WebViewRuntime(
        factory = engines,
        config = RuntimeConfig(maxLive = 2, snapshotCacheBytes = 8L shl 20),
        scope = scope,
        clock = { mainClock.currentTime }, // grace periods and settle times on the test's clock
    )
    val state = WebViewState(WebContent.Url("https://example.com/"))
    setContent { ProvideWebViewRuntime(runtime) { WebView(state, sizing = WebSizing.WrapContentHeight(max = 400.dp)) } }
    waitUntil { state.phase == WebViewPhase.Live }
    assertEquals(240.dp, state.contentSize?.height)
    // The pool made a spare at cold start too: every engine was used as the contract says.
    engines.created.forEach { assertEquals(emptyList(), it.violations) }
    scope.cancel()
}
```

- **`FakeEngineBehavior`**: `paintsOnLoad`, `contentHeight` or `contentHeightInViewport` (a page sized by its
  viewport), `pageState` (what the page's save provider returns), `resetSucceeds`, `needsCutouts` and `color`. Unless
  `color` is set, each content paints a colour of its own, derived from its key (`colorOf(content)`), so a screenshot
  shows which snapshot covers which view.
- **The factory** can be held open (`createGate`), made to fail (`failCreate`), or report a platform without engines
  (`unavailable`); `created` keeps every engine it made.
- **An engine** can be driven (`emit` any event, `postFromPage` as the page's `hostBridge.post`, `resetGate`,
  `saveStateGate`) and inspected: `calls`, `cutouts`, `lastSetup`, `config`, `released`. Like a real one, it delivers
  no event of a document after the next navigation.
- **`violations`** lists every break of the contract by its caller: a load without `configure`, lease calls during or
  after a failed reset, anything after `release`, a surface composed twice. Assert it stays empty.

## The perf lab

[`webview-sample`](../webview-sample) is the sample app and the perf lab, on the fake engine for now, on desktop:

```
./gradlew :webview-sample:run       # the desktop window: the perf lab and the overlay demo
./gradlew :webview-sample:jvmTest   # the lab's scripted runs, every frame checked; screens to build/sample-screens/
```

The lab is a chat of 50 to 1,000 messages, 200 by default. Each message embeds a `WhenVisible`, `WrapContentHeight`
web view: an agent demo as `Html`, every fifth a `Url` and every tenth a plugin `Bundle`, whose button tells the app
over the bridge (`hostBridge.post('clicked', …)`), and the message shows it. The list is a `LazyColumn` or
a plain `Column`, or shows still images instead, as the baseline. Its scripted runs, from the top to the end and back
without stopping (Scroll test), a screen at a time (Reading test), or one fling, each print a `perf-lab` line with the
frames, the engine churn, the peaks and the memory held. Buttons send memory pressure and recovery.

These numbers are for the **fake engine**: its pages are solid colours. They come from `:webview-sample:jvmTest` on
2026-10-29 (Apple M1, JDK 17), in a 1,024 × 768 window at density 1, over 200 messages, and were the same in each of
three runs:

| run | budget: live + spares | engines created / destroyed | peak live | peak engines in existence | pages started | snapshots | peak covers | snapshot cache at the end | page memory at the end |
|---|---|---|---|---|---|---|---|---|---|
| Reading test, `LazyColumn` | 2 + 3 | 8 / 5 | 2 | 4 | 126 | 130 | 2.9 MB, 4 views | 31.4 of 32.0 MB | 126 pages, 35 KB |
| Reading test, `Column`, all 200 composed | 3 + 3 | 6 / 3 | 3 | 6 | 202 | 401 | 3.9 MB, 4 views | 30.9 of 32.0 MB | 199 pages, 56 KB |
| Scroll test, `LazyColumn` | 4 + 3 | 5 / 2 | 3 | 5 | 3 | 2 | 1.8 MB, 2 views | 1.8 of 32.0 MB | 3 pages, 855 B |

- In these runs, checked at every frame, no page started while the list moved, live engines never exceeded the
  budget, engines in existence never exceeded `maxLive + maxIdle`, and covers stayed within a screenful.
- **Memory pressure** (2 live + 3 spares, 32 MB of snapshots): live engines 2 → 1, engines in existence 2 → 1, the
  snapshot cache 1.8 MB (2 snapshots) → 798 KB (1), and still 798 KB 5 s later. After recovery: 2 live, 1.8 MB.
- **Frames.** The tests step a virtual clock one 16 ms frame at a time, so the frame intervals in the `perf-lab`
  lines (all 16.0 ms, none dropped) say nothing about smoothness. What the harness measures is the wall-clock cost of
  each frame the list moves: the scroll, composition, layout and drawing, and the runtime's queued work. In the last
  run's Scroll test that was p50 0.9 ms and p95 2.5 ms with web views, against p50 3.3 ms and p95 7.2 ms with still
  images. Over three runs, p50 ranged from 0.9 to 2.7 ms against 3.2 to 5.9 ms, and p95 from 2.5 to 9.1 ms against
  4.5 to 12.7 ms. With solid-colour pages, that is the list's cost and the runtime's, not an engine's.

Two of the design's targets hold on the fake engine: over a 200-demo list, live engines stay within the budget and the
snapshot cache within its bound. The others are goals for the engines, to be measured on each platform once its engine
exists: no more dropped frames with web views than with images, first paint of a settled view within 100 ms on
desktop and 200 ms on mobile from a warm spare, a bridge round trip within 2 ms on desktop and 5 ms on mobile (p50),
60 fps for frame-mode engines, and cut-outs that follow a menu's open animation in the same frame.

## Building and testing

```
./gradlew :webview-compose:jvmTest              # common and JVM tests; Compose UI tests on the fake engine
./gradlew :webview-compose:wasmJsBrowserTest    # common tests and the bridge shim, in headless Chrome
./gradlew :webview-compose:compileDebugKotlinAndroid :webview-compose:compileKotlinIosSimulatorArm64
./gradlew :webview-sample:jvmTest               # the perf lab's scripted runs
```

The Apple targets build on a Mac only; elsewhere their tasks are skipped. A library test task that runs past 10 minutes,
or a sample one past 15, fails instead of hanging.

## Not built yet

- The engines: one per platform, each with its platform's memory-pressure hook. Until then, `Unavailable`.
- A disk cache for snapshots.
- Batching bridge messages per frame, if real engines' numbers show it is needed.
- Publishing: the module isn't published to a repository yet.
