# webview-compose: the platform's own web engine, inside Compose

`dev.supermux.webview:webview-compose` is a Compose Multiplatform web view for apps that show **hundreds of web
views**: interactive demos posted into a chat, or a plugin system built on web pages. Each `WebView` is a cheap
handle. A small pool of live engines is shared among them, and a view without an engine shows a snapshot of its page,
or a placeholder.

It is built for each platform's own engine: Android `WebView`, `WKWebView` on iOS and macOS, WebView2 on Windows, WPE
WebKit on Linux, and an `<iframe>` on the web. **Every one of them exists** ([The web engine](#the-web-engine),
[Android](#android), [iOS](#ios), [macOS](#macos), [Linux](#linux), [Windows](#windows)). Everything around the engines
is built and tested against the library's fake engine on every platform: the pool, activation, snapshots, page memory,
overlays, the page bridge and the engine contract.

It depends on Compose and kotlinx (coroutines, serialization) only, nothing else of supermux. It targets Android, iOS
(arm64 and simulator arm64), the desktop JVM and wasmJs. It isn't published yet; depend on the project, as
[`webview-sample`](../webview-sample) does: `implementation(project(":webview-compose"))`, and for tests on its testing
module, `implementation(project(":webview-compose-testing"))` ([Testing](#testing-with-the-fake-engine)).

Its public API is checked ([The API](#the-api)): every public declaration says so (explicit API mode), the binary API
of every target is dumped in [`api/`](api) and `check` fails on any change the dump doesn't show, the engine SPI is
behind an opt-in, and nothing apps can reach writes the runtime's caches or pool.

The design, including the goals set for the engines, is in the [design document][design]. This README describes what is
built.

[design]: ../../docs/superpowers/specs/2026-10-28-webview-compose-design.md

## Status: what has actually been run, and where

| platform | engine | what has run | what has not |
|---|---|---|---|
| any | fake engine | `:webview-compose:jvmTest`: **1308 tests**: 617 common ones (the pool, activation, overlay geometry, the bridge, the caches and page memory and their statistics, `WebViewState`, content, URLs and the navigation policy, the engines' event queue, the Android engine's document gate, local content, bridge scripts, saved state and pending requests, the WebKit engines' URLs, navigation rules, relay and saved state, the fake engine), 222 JVM-only ones (the `WebView` composable, its host and the overlay modifiers over the fake engine, in Compose UI tests), the macOS engine's 115, the Linux engine's 186, the Windows engine's 155 and its frame store's 13 (in their rows). `:webview-compose:testDebugUnitTest`, the common ones on Android's host JVM: **617 tests**, the 7 that need Android's real Bitmap skipped. `:webview-compose-testing:jvmTest`: **1 test** (the macOS test driver's window copies). `:webview-sample:jvmTest`: **21 tests**, among them a 200-message chat read and scrolled with every frame checked against the budgets, with no engine leaked | any real engine |
| browser (wasmJs) | iframe (`IframeWebEngine`) | `:webview-compose:wasmJsBrowserTest`: **787 tests** in headless Chrome 156: the 617 common ones and 170 more, covering the bridge shim, the WebKit relay, the web engine (its shim and port, documents and commits, first paint, frames, cut-outs and scrims, links, the keyboard, scroll chaining, the budget for pages of other sites, reset) and `WebView` on it end to end. Also its feasibility test (Karma and the DevTools protocol), and the sample's browser build driven with trusted clicks, keys and wheels, and recorded frame by frame, by `web-check/run.mjs`: see [The web engine](#the-web-engine) | Safari and Firefox (planned, by hand), mobile browsers, touch input from a real touch screen, an installed PWA. A demo that takes the keyboard every 150 ms still gets a few keys typed in the app ([The web engine](#the-web-engine)) |
| iOS | `WKWebView` | `:webview-compose:iosSimulatorArm64Test` on the iOS 26.5 simulator, **708 tests**: the 617 common ones, the snapshot codec's among them, and 91 iOS ones (the engine driven through its WebKit callbacks: navigations and what counts as the user's gesture, server redirects, failed loads, resets mid-load and a reset's wait for its floor, a release and what it leaves the collector, questions pending when a view goes; its replay to an adopting view, its handoff from the cover, the scheme handler and its byte ranges, named profiles' stores, also on 16.4, snapshot pixels at the screen's scale, script results' JSON, memory warnings, the view that holds the page, and the tests' own wait for the main queue, which a loaded Mac can't cut short), none of them passing by the collector's luck. The engine's **50 conformance checks**, in the sample app with real WebKit pages (`webview-sample/device-checks/ios-engine-checks.sh`): the 31 shared with macOS and 19 of the iOS engine's own, each a probe from its review (failed loads, resets mid-load, a release, adoption racing a handoff, a page widened, profiles, the relay's trusted events and cap, a dialog pending at park, a bundled video, the back-forward cache, and what its mutants left unchecked): 50 of 50 on the iOS 26.5 simulator and 50 of 50 on 16.4 (an iPhone 14; there, under loads of 100–250, a reset of a web view out of any window timed out: [iOS](#ios)), among them a parked page adopted live, unreloaded, with its value, scroll position, URL, history, title and bridge as they were, and its first paint only once on screen; a cover, stored and decoded, against the screen (mean difference 0.96 of 255, p99 9); a first paint that waits for the page's content, and not for good; and the handoff from the cover, against the page at each display tick its copies leave ([below](#snapshots-and-page-memory)). The sample's UI flows with Maestro (`ios-sim.sh`): live embeds, a drag on an embed scrolls the chat, a plugin's button reaches the app, menus and dialogs over a live page, typing, and the user-gesture probe: drags over a page that tries to leave by itself leave all its tries its own, and a tap on its link is the user's. On the final tree (2026-10-31), 8 of 8 steps on 26.5 and 8 of 8 on 16.4 (6 flows, then the app still running and no uncaught Kotlin exception). The Overlays tab sits at the screen's right edge, and in two runs on a heavily loaded Mac Maestro's tap there never switched screens; it never happened again (the app took 11 of 11 taps, 5 of them 1 pt from the edge), so the flows now swipe the tab row to its end and tap the whole tab, then wait for the Overlays screen's own Menu button. Simulator numbers (`MEASURE=1`, 26.5, the Mac shared with other sessions): first paint from a warm engine p50 467 ms (to its commit 408 ms, from it 55 ms), the bridge round trip p50 0.3 ms, a snapshot at the screen's 3× (960 × 1440) p50 37 ms; a lease's first page 451 ms to its commit, a page of another site in the same engine 359 ms; in a run whose pages took 240–360 ms to parse instead of 4–30, under a load of up to 162: 976 (872, 116), 0.4, 34, 859 and 447 ms ([iOS](#ios)) | a physical iPhone or iPad; iOS 15; VoiceOver; IME composition (kana to kanji); a WebContent process killed by the OS; real memory warnings; media heard while hidden (the check reads WebKit's playback state of a muted video: suspended while hidden, playing when shown; this simulator played no generated audio, hidden or not); five rounds of the handoff in a row since the review's fixes (one round in each run); the 200 ms first-paint target |
| macOS | `WKWebView` under the Compose canvas, over JNI | `:webview-compose:jvmTest`, now **1308** tests, among them the macOS engine's 115: driven through a fake of its glue (epochs, the handoff, the motion cover and its true size, an embed growing live, the slot at the backing scale, reset, release, a crash, server redirects, the bundle's scheme), its surface in Compose UI tests, and, with the real glue and real WebKit in the test process (no window), its ABI, its JSON, its hit test's region, the threads it attaches, its own logic (epoch stamps, questions answered once, scheme tasks) and server redirects against a loopback server. The engine's **47 checks** in the desktop sample's window on a MacBook Air M1 (macOS 26.6.2, 2×, 60 Hz): the 31 shared with iOS, 7 of the Mac's own (presses around marked overlays, a menu closed by the page, ⌘A and ⌘Z, focus, VoiceOver's reach, memory pressure, wheel routing), 5 for the review's findings (the window's height changed and zoomed, an embed growing, a press over a clickable, a page half out of its scroller over another, an app's UI zoom), 3 for motion and 1 for the chat's dark previews scrolled by trackpad gestures with their momentum, each judging where the page's content is in the window: `CHECKS DONE passed=47 failed=0` on 2026-11-01, with the backdrop under moving pages (load 5.5–9): the trackpad check's copies with a light band at a preview's edge 413 of 2155 without the backdrop → 0 of 2278 with it. Before it, 46 of 46 on the final tree, under a load average of 445–469; in the run before it (23–139), 43 of 46, and the 3 that failed there (two handoffs where Compose presented its frame late, ⌘ keys while another app was in front) 3 of 3 right after. Motion, the page's marker out of place or light strips in the slot, without the cover → with it: pane 3 and 6 of 95 copies → 0 of 122, window 0 and 12 of 97 → 0 of 97 (the run before: 15 and 24 of 100 → 0 of 181, 0 and 28 of 172 → 0 of 174); a list scroll, which the cover leaves live, 0 of 181 in both; the window's backing in none. A growing embed: its marker in place and its presses taken in every frame (154, and 255 the run before), never covered. A release build shrunk by ProGuard with the library's rules, and the app Compose packages: 5 of 5 checks each ([macOS](#macos)). Before the review's fixes: the handoff, five rounds in a row: 420 frames judged, 0 differ from the page; first paint from a warm engine p50 90.1 ms (after the commit 39.9 ms), the bridge round trip p50 0.2 ms, a snapshot at 2× (640 × 960) p50 3.0 ms; the perf lab over 200 messages, web views against still images: Reading 1–3 vs 1–5 dropped, Scroll 1–5 vs 1–3 dropped, peak live 4 of 8; load average 3–18 at the runs | the manual checklist ([macOS](#macos)): trackpad momentum and pinch, Turkish and Japanese IME, ⌘C/⌘V, VoiceOver reading the page, hover and the cursor under overlays, full screen and a screen of another scale, a real live resize, menus and dialogs by hand; a ProMotion display; an Intel Mac (the x86_64 slice is built, never run); Compose's own release tasks, whose ProGuard runner fails on this Gradle |
| Linux | WPE WebKit 2.54.2 (`LinuxWebEngine`), its frames drawn by Compose | `:webview-compose:jvmTest`: **186 Linux tests**: the engine on a fake native view (leases, documents, the bridge, local content, the surface's frames and input, the handoff from the cover, reset, parked pages, the page's requests, navigation asked at the response, hung pages, a recycled engine's frames, `<select>` menus, the clipboard), the runtime's checks, profiles and archive extractor. The glue's own **7 C tests** (`native/linux/test.sh`). The engine's **55 conformance checks**, in the sample app with real WebKit pages, on the runtime this module builds (190.6 MB, 57.9 MB zstd), in Ubuntu 24.04 with no WPE of its own, **with WebKit's page sandbox on** (`SANDBOX=on`): 55 of 55, among them pages in bubblewrap's PID namespace, an iframe answered before the main document it raced, a hung page ended, the last lease's frame never shown, Ctrl shortcuts and the clipboard both ways, a `<select>` by mouse and keys, a login across a restart, and a second process of the app refused its profile. With the sandbox on outside any container, on Ubuntu 26.04 with NVIDIA's GTX 1660 (`linux-sandbox-check.sh`): the page paints from bubblewrap's sandbox (before, the first view aborted the JVM). The draw lab, six 1600×1200 spinners in software: 49.1 → 42.9 ms of UI-thread CPU per frame. The sample on WPE engines, 200 web views in a LazyColumn: the scroll test 1891 frames, p50 15.8 ms, p99 16.1 ms, 1 dropped; the reading test 2426 frames, p50 15.8 ms, p99 16.1 ms, 2 dropped. The feasibility test's numbers: 1080p copy p95 3.48 ms, bridge round trip p50 0.22 ms, 141.7 MB per view. GPU gate, WebKit and the upload on an AMD GPU (Mesa), in a container, **accepted as measured** (not a ship blocker): a full-frame 1080p animation 20.5 fps, copy + upload p95 10.4 ms (the bar is 4), a warm spare's first paint 127 ms (the bar is 100) ([Linux](#linux)) | Compose drawing on a GPU (a real desktop session); the GPU gate on a desktop's GPU; IME composition; a colour picker; Tab leaving the page; arm64; Wayland without XWayland; the sandbox outside a container with a D-Bus session bus (`desktop` has no `xdg-dbus-proxy`: its check hid the session) |
| Android | system WebView (`AndroidWebEngine`), a native view under the Compose canvas | The engine's pure rules, among `jvmTest`'s common tests. **90 instrumented tests** on the user's Galaxy Z Fold7 (Android 16, WebView 156), over adb (`webview-compose/device-checks/android-instrumented.sh`), the whole suite in one run at `2df7ae549` (`OK (90 tests)`): WebView's startup and features, the Activity's life, local content and the network, the document gate (nothing of an earlier document heard, the callbacks WebView makes at once heard from the commit), the bridge, the page's requests and their safe defaults, reset and ephemeral wipes, saved state, dark pages, console without the shim, snapshots (the page's own draw, never the app's UI over it, a page half off screen, blank draws, their cost), covers against the screen, parking and replay, the handoff from the cover (every frame around its lift), a dead and a hung renderer, nested scrolling, focus, the keyboard's inset, back, a restore into a recycled engine (no WebView built: 10.1 ms on main), no snapshot during a fling (the main thread around it p50 11.6 ms, max 14.2 ms). The sample on the phone (`webview-sample/device-checks/android-sample.sh`): the perf lab's Reading and Scroll tests over 200 web views, the Scroll test on this tree 3795 frames, p99 8.3 ms, 3 dropped ([Android](#android)) | the manual checklist, with the user and not answered yet: dialogs, the file picker, Turkish and Japanese typing, menus and dialogs over a page, back, links out, `<select>`, dark mode, folding and rotating, DeX or XREAL; TalkBack; an emulator, another phone, a tablet; Android below 16, a WebView older than 156 (the keyboard's older path), or one lacking a feature the engine needs; a renderer killed by the OS, and the OS's own `onTrimMemory`; first paint and the bridge's round trip, timed; a release build |
| Windows | WebView2 156.0.4314.58 in visual hosting (`WindowsWebEngine`), its root visual captured off screen with Windows.Graphics.Capture and drawn by Compose, over JNI | `:webview-compose:jvmTest`, now **1308** tests, among them **155 Windows tests** (the engine on a fake native page: leases, epochs, documents, the bridge with its secret and held messages, local content on `https://*.invalid`, the policy answered on the glue's thread, the page's requests, reset with history and grants, hung pages, a crash closing the page at once with nothing reaching it after, parking and `replayState`, save and restore, the surface's frames, placement, pointer, cursor, focus and accelerators, the handoff and what a frame shows) and the frame store's (no frame written into a block a recording still draws, nor into one without room for it). The glue's **13 C++ tests** (83 checks, `glue_test.exe`). The engine's **82 conformance checks** in the sample on a Windows 11 26H2 x64 VM with **no GPU** (WARP), WebView2 156.0.4314.58: 81 of 81 three runs in a row, then 82 of 82 on the final tree (one check added for the review's autoplay finding); the 43 shared with Linux and 39 of Windows' own: the focus hand-off both ways and by Tab, an app shortcut before the page, a demo that can't take the keyboard, `<select>` and date popups placed right after a window's height change, an app's UI zoom, cursors, the context menu's editing items, a patched `postMessage`, the Japanese IME composing in the page and in Compose with its candidates under the input, autoplay as the engine's config says, and kept when another engine of its profile goes, a hung page's renderer ended, an engine made right after the last one's release in under 3 s, a second process of the app sharing its profiles and never an ephemeral store. A release build shrunk by ProGuard with the library's rules, and the packaged app (jpackage): 7 of 7 checks each; without the keep rule the engine is refused, the rule named. The DLL's cache: hash-named, replaced when tampered with, two instances at once. The ARM64 DLL cross-compiled (32 natives, as x64's). On the VM: a warm first paint p50 71 ms, the bridge p50 0.4–0.5 ms, 23 MB a view (the budget stays 8 + 3), a press to the page's `pointerdown` p50 26 ms (the engine's part 5 ms) and 94 ms to the screen; the perf lab over 200 messages, web views against images: Scroll 20–22 % of frames dropped against 27 %, Reading 33 % against 28 % (software rendering drops a quarter with images too). On a GTX 1660 SUPER, on the engine's own path (2026-10-31): 1080p copy + upload p95 4.29–4.72 ms, a warm first paint p50 88 ms (cold 200–215 ms), the bridge 0.4–0.6 ms, 24 MB private a view plus 119 MB fixed (the GPU process about 45 MB of it), a press to the screen p50 66.7 ms; the perf lab, web views against images: Reading 1.7 % of frames dropped against 1.1 % (one unexplained run at 8.3 %), Scroll 1.0–1.4 % against 1.3 %. The GPU gate before it, on the three-copy chain: frames delivered 88–97 %, a 1080p animation drawn at 44–52 fps ([Windows](#windows)) | §10's 4 ms at 1080p full frame (p95 4.29–4.72 ms on the GTX 1660 SUPER) and 60 fps (the GPU gate's 44–52 fps): known limits on that card, every WebView2 frame arriving whole; a HiDPI screen (the VMs run at 100 %); a machine whose display runs on another GPU than skiko's (built for, not run); Windows on ARM64 (the DLL is built, never run); touch and pen beyond mouse emulation; drag and drop into pages (off); a screen reader; `ComposePanel` in a Swing window; zero-copy (tried and dropped: [Windows](#windows)) |

All of it ran on 2026-10-29 and 30 on an Apple M1 Mac (macOS 26.6), JDK 17, Xcode 26.5, and the Android row's device
runs on the phone. The test counts are from one run of every suite on 2026-10-31, with the API tightened on top of the
Android device fixes (`2df7ae549`) and the Windows engine's crash fix after it, read from the suites' result files
(`:webview-compose:check` and `:webview-compose-testing:check`, which run the library's JVM, iOS simulator, browser and
Android host-JVM suites, and `:webview-sample:jvmTest`): none failed. `:webview-compose:jvmTest` ran again on 2026-11-01
with the macOS engine's backdrop and its 3 tests (1308, none failed), as did `:webview-sample:jvmTest` (21). The perf
lab's numbers, for the fake engine, are [below](#the-perf-lab).

## Quick start

```kotlin
// The app's model: a message's content is built once, where the message is (Html hashes its text as it is built, so
// build a big one off the main thread), never in the list item, which is composed anew each time it scrolls in.
class Message(val id: String, html: String) {
    val demo = WebContent.Html(html)
}

@Composable
fun AgentDemo(message: Message) {
    val state = rememberWebViewState(message.demo)
    // Live once it is on screen and has stopped moving: WebView's default activation, made for lists.
    WebView(state, sizing = WebSizing.WrapContentHeight(max = 400.dp))
}
```

On Android, iOS and macOS (14 and later), on Linux with its WPE runtime ([Linux](#linux)), on Windows with the WebView2
Runtime ([Windows](#windows)), and in a browser, this shows the demo, live once it is on screen and has stopped moving.

- **`WebContent`** is what to show: `Url(url, headers)` for http(s) pages, `Html(html)` for in-memory HTML on an origin
  of its own (agent demos), and `Bundle(origin, resources, entry)` for a set of files served on an origin (plugins).
  `Html` hashes its text (SHA-256: about 2 ms for 200,000 characters on a desktop, more on a phone), so keep it in your
  model; building it again for a text hashed lately (the latest 64, 2,000,000 characters at most) finds the hash kept.
- **`WebViewState`** is the logical page: `content`, `url`, `title`, `loading`, `canGoBack`, `canGoForward`, `phase`
  (`Cold`, `Activating`, `Live`, `Crashed`, `Unavailable`), `error`, `contentSize`, `console`, `bridge`, and a
  `navigator` (`load`, `back`, `forward`, `reload`, `stop`). `state.load(content)` navigates, even to the content
  already shown. Show a state in one `WebView` at a time. Main thread only. The navigator reaches only the engine of the
  view that shows the state; an engine that throws puts the view in `Crashed`, and nothing reaches your code. On
  Windows, history a page makes without the user's activation is skipped by Back: `canGoBack` doesn't count it
  ([Windows](#windows)). `phase` and `error` are open to new kinds: a `when` over them needs an `else`.
- **`rememberWebViewState(content)`** keys the state by `content.key`, and never undoes what you load into it: it loads
  `content` again only when the argument itself changes. It isn't saveable on purpose: HTML bodies would overflow
  Android's saved-state bundle. Page memory brings the page back instead ([below](#snapshots-and-page-memory)).
- **`WebView(state, modifier, settings, client, activation, sizing, placeholder)`**:
  - `settings`: `WebSettings(…)` with named arguments, or `WebSettings.Default.copy(…)`: `javaScriptEnabled`,
    `inspectable`, `darkMode` (`Auto`, `Light`, `Dark`), `transparentBackground`, `userAgentSuffix`, `profile`
    (`Default`, `Named(id)` or `Ephemeral`; unset, `Html` gets `Ephemeral` and the rest `Default`),
    `mediaPlaybackRequiresGesture` and `bridgeOrigins`. Each profile and media setting in use splits the pool
    ([below](#the-pool-and-memory)). On Windows, `mediaPlaybackRequiresGesture = false` is the profile's autoplay
    setting for each origin the engine shows, shared by the engines of that profile on that origin ([Windows](#windows)).
  - `activation`: when the view goes live; `Activation.WhenVisible()` unless you say otherwise ([Activation](#activation)).
  - `client`: answers the page's requests: navigations, new windows, dialogs, file choosers, permissions and
    downloads. Every default is the safe answer ([Security defaults](#security-defaults)).
  - `placeholder`: covers the view while there's no snapshot. The default is an opaque card in the resolved theme,
    with the page's title once known. A custom one should be opaque too.

## The pool and memory

All web views share one `WebViewRuntime`: the engine pool, the snapshot cache, page memory and the overlay registry.
`WebViewRuntime.Default` serves unless `ProvideWebViewRuntime(runtime) { … }` provides another.

- **Budget.** At most `maxLive` engines are leased or parked at once: 4 on phones, 6 on tablets, 8 on desktop (6 on
  Linux, with 2 spares instead of 3: every WPE engine is a web process of its own, about 142 MB) and 12 iframes on the
  web (`RuntimeConfig.platformDefault()`). Over budget, a view takes the slot of a parked page first,
  the one parked longest, then evicts the lowest-priority view below its own; with nobody to evict, it keeps its cover
  and waits for capacity. Priority: the focused view (touched last, or holding the keyboard focus), then visible
  `Eager` views, then other visible views, then hidden ones, then parked pages. Ties go to the least recently used,
  where use means a touch or coming into view.
- **Parked pages.** A view that leaves the composition with its page live (an item a `LazyColumn` disposes as it
  scrolls away) saves the page as before, then parks it in the pool instead of giving its engine back: unreset, hidden
  (`setVisible(false)`), with no holder. The next view of the same page, in the same profile and engine config, adopts
  it as it is: no load, no reset, its in-page state intact, and Live as soon as the engine replays its page. Only a
  view that can show the page as it is adopts it (the same scripts on or off, user agent and transparency, and bridge
  origins that still include the page's: a document keeps those from its start), and only one; another gets an engine
  of its own. A parked page holds its slot until a view needs it, or for `hiddenReleaseMillis` (10 min, as a hidden
  view) if no view adopts it: then it saves what it has (its page state and engine state, bounded as an eviction is)
  and its engine is reset, for that view or as a spare. A page whose engine dies while parked (its renderer crashed) is
  released at once, and a view of it starts fresh. What a page says while parked (its posts, its calls, its console)
  reaches no one, since nobody listened ([the bridge](#the-bridge)): the engine drops it as it comes. A parked page
  holds at most one event of each kind for its next view (its state, its latest `$ready`), however long it is parked
  and however much it says, so a chatty demo (a post twice a second, a log line every 100 ms, for its whole 10
  minutes) is adopted live like a quiet one.
- **Spares.** Up to `maxIdle` blank engines are kept for reuse: 3, or 2 on iOS, where each idle `WKWebView` may keep
  its own WebContent process. An engine given back is reset and kept as a spare if there's room, or destroyed, so
  there are never more than `maxLive + maxIdle` engines. The exception is an engine whose eviction or reset overran
  its time bound: it is released once that call returns. A spare is created ahead of need only when the pool has none
  of that kind and got none back in the last 10 s, as at cold start; otherwise recycling supplies them.
- **Profiles and media settings split the pool.** An engine is made for one profile and one
  `mediaPlaybackRequiresGesture` (neither can change once it is made), and serves only views of that pair, as a parked
  page does. The budget is one for all, but the spares are not: a spare serves only its own pair, and an engine given
  back to a full spare room replaces another pair's spare only once that one has sat unused for 10 s. So every distinct
  `WebProfile.Named`, or media setting, an app shows at once splits the pool and its 3 spares, and its views build
  engines where they would have reused one. `Html` content (`Ephemeral` unless the settings say otherwise) and the rest
  (`Default`) already make two pairs: keep the others in use at once few.
- **Never lost, never shared.** Every engine the pool creates ends up leased, a spare, parked or released, whatever
  throws or is cancelled; one whose call hangs past its bound is never reused, and is released once that call returns. An engine
  serves one view at a time, and a state reaches only the engine of the view that shows it. An engine changes hands
  only after the view that had it lets go of its surface, waiting up to 500 ms for the composition to do so. Everything
  is bounded in time: a creation 20 s, a view saving its page before an eviction 5 s, a reset 10 s, a snapshot or a save
  2 s. An engine that overruns is destroyed, never reused.
- **Failures.** A failed creation is retried after 2 s, 10 s, then every 60 s. Other `Unavailable` reasons stay until
  `navigator.reload()`. An engine that throws, or whose renderer dies, puts its view in `Crashed`, covered by its last
  snapshot or its placeholder. A crashed view on screen goes live again by itself a second later, at most once a minute
  (on Android every WebView shares one renderer, so one page's death crashes every live view): one that crashes again
  within the minute stays `Crashed`, and one off screen waits until it is shown. `navigator.reload()` still brings a
  crashed view back at any time. `runtime.onError` sees what the guards catch.
- **Memory pressure.** Call `runtime.onMemoryPressure()` when the OS says memory is short (Android's `onTrimMemory` at
  its running levels, an iOS memory warning, the macOS memory-pressure source); on Android, iOS and macOS the library
  listens for these itself while any of the runtime's web views is shown, elsewhere it doesn't yet. It releases the
  spares and every parked page (each saves itself first), evicts live views down to half the budget, hidden ones first
  and never the focused one, and the snapshot cache lets go of every decoded snapshot and half of what it stores. Until
  `runtime.onMemoryRecovered()`, or 30 s after the latest call, the budget stays at half, no spares are kept, nothing is
  parked, and the snapshot cache keeps nothing decoded and stores within that half, even as the evicted views snapshot
  their pages on the way out. Views on screen still get their covers decoded, and hold them themselves.
- **Light trims.** `runtime.onBackgroundTrim()` is for an OS that says the app's process may be cached without memory
  being short (Android's background levels, at every app switch; the library calls it there itself). It lets go of
  what is quickest to make again: the decoded snapshots, the spares and the parked pages (each saves itself first).
  Live views keep their pages, the cache what it stores, and the pool its whole budget: no window follows.

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
- A view that leaves the screen but stays composed (a plain `Column`, a tab not shown) keeps its engine, whatever its
  activation, told it is hidden and ranked lowest: scrolled back, its page is as it was, with nothing reloaded. A
  visible view that needs the slot takes it at once (the hidden page saves itself first), memory pressure takes hidden
  pages first, and a view hidden for `hiddenReleaseMillis` (10 min) gives its engine back by itself.
- A view that leaves the composition saves its page and parks it ([parked pages](#the-pool-and-memory)). In a
  `LazyColumn` that is every item that scrolls out of the composed window: an item scrolled back is a new view, which
  shows its page's snapshot at its height at once ([page memory](#snapshots-and-page-memory)) and adopts the parked
  page, live again without a load. Once the pool has reclaimed the page for other views, the item starts it again
  under its snapshot, restoring what was saved. So within the budget, scrolling away and back keeps pages alive in a
  `LazyColumn` as in a `Column`.
- Compose keeps items that scrolled out of a `LazyColumn` composed for a while without placing them: those it measured
  on its way to the new first item stay so until it measures without them, which a list at rest may not do for a long
  time, and those kept for reuse until they are deactivated, which disposes them. An item composed but no longer
  placed counts as hidden, as in a `Column`: told so and ranked lowest, so a visible view that needs a slot takes its
  engine first. Scrolled back first, it comes back as it was.
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
- **Embeds and panes.** A view in a scrolling list is an embed: on iOS a drag that starts on its page scrolls the list
  (taps still reach the page), and on macOS, Linux and Windows the wheel and the trackpad scroll the list. Any other
  view is a pane, which gives its page every gesture. A view counts as an embed when it sizes to its page
  (`WrapContentHeight`) or a scrolling parent measures it (a lazy list's item, a scrolling `Column`). A parent of fixed
  size in between hides the list from it (`item { Box(Modifier.height(240.dp)) { WebView(…) } }`): say so around the
  view, `CompositionLocalProvider(LocalWebSurfaceHints provides WebSurfaceHints(inScrollingList = true)) { WebView(…) }`
  (both in `dev.supermux.webview`), or `false` for a pane a scrolling parent measures. What the app provides wins. It is
  a composition local rather than a `WebView` parameter: it is about the place views sit in, which the code around them
  knows, and it reaches every view there at once. On Android and the web the hint changes nothing: they chain scrolling
  to the list their own way ([Android](#android), [the web](#the-web-engine)).

## Snapshots and page memory

- **Covers.** A view without a painted page shows its last snapshot, or its placeholder when it has none. The cover
  stays until the engine has drawn the document the view started in its place, with its content, for a frame
  (`FirstPaint`): the page draws under the cover, unseen, so the first frame without the cover is one the page drew,
  and with engines that keep their contract, a page never shows a white, blank or bare-background frame. Navigations
  the page makes itself, reloads and history navigations keep the previous page on screen until the next one paints:
  engines hold paint, by contract.
- **At their true size.** A cover is the page's picture from the view's top-left, at the size the page had (by the
  pixels a dp it was kept at): where the view has since grown or changed shape, the page's own background (the colour
  along the picture's left, right and bottom edges) fills the rest, and what no longer fits is cut. A view of the
  picture's own shape is filled exactly. A page that reflows keeps its text's size when its viewport changes, so a
  picture stretched or magnified to its view was never what the page would show.
- **The handoff, measured.** On the phone (Galaxy Z Fold7, WebView 156) every frame around the cover's lift is
  copied and compared with the page (`HandoffFlashTest`), for a cold activation from the cover, a parked page adopted
  and a page hidden then shown, light and dark. Before, a cold activation showed one frame of the page's bare
  background, without its text: the cover lifted on the visual state of the document's commit. Android now waits for
  the document's first contentful paint (or its load, or 3 s), then a visual state callback and two frames drawn in
  place, about 30 ms in all after the commit; five runs in a row showed no frame that differs from the page. On the
  web the covered iframe draws in its place at `opacity: 0` (a frame in a hidden box draws nothing at all), and is
  shown a frame after its load and commit. On the iOS simulators (26.5, and 16.4 on an iPhone 14) the sample app runs
  the same check (`HandoffChecks`): the window copied around the lift at each display tick its copies leave it, after
  making sure its copies show the Compose canvas over a page. The ticks a copy overruns don't come: after the iOS
  review's fixes, with copies of p50 16–18 ms on 26.5, 62 display frames went by unseen between 202 copies, and with
  8–10 ms on 16.4, 8 between 238 (one round of the six cases on each; no frame differed from the page). The recorder
  prints these counts (`MEASURE frames-seen`). On 26.5, before any change, one frame in 30 runs differed: all grey, an
  adopted view's place before its first frame showed, a copy a frame behind the canvas (which presents late while no
  native view is in it), so a view now counts as in place after three ticks there. On 16.4 a cold activation showed
  its cover as the page shrunk into a quarter of its place, on white (light: mean 19.4, p99 219; dark: mean 200.7, p99
  237), in every round: a new `WKWebView` had no size when its first page committed, WebKit zoomed that page out to
  its least scale, 0.25, and iOS 16 kept that scale once the view had its size, so its snapshot did too. A web view
  made at the screen's size laid the page out at the screen's width instead, in 7 of 10 cold activations: the view's
  size, changed while the page loaded, didn't reach the page. So a new `WKWebView` starts at the screen's size, and a
  page that started loading at another size is told the view's size again when it commits. iOS waits as Android does,
  too: a document's first paint is its first contentful paint (or its load, or 3 s), two animation frames, then two
  display ticks with the web view in its window, never hidden under the cover; an adopted page waits two display ticks
  once it has drawn again. After: five rounds in a row on each simulator, all six cases, and no frame differs from the
  page.
- **When.** Snapshots are taken only while the page is composed and laid out, and only of a document that has painted
  (an engine racing its own navigation could otherwise give the document before's picture): 500 ms after its first
  paint, 500 ms after the user's last interaction, and when a deactivation or an eviction takes the engine, before the
  view lets go of it. One that falls due while the view moves (its list scrolls or flings) waits until it has been
  still for 500 ms: an engine's snapshot may take the main thread for longer than a frame. A view that leaves the
  composition takes none, and keeps its last good snapshot.
- **Fresh.** A deactivation or an eviction takes a new snapshot unless the one kept still shows the page: nothing the
  runtime knows of changed the page since (a commit, a new size, a touch, another theme, density or view size), the
  cache still holds it, and the page wasn't on screen for more than 500 ms after it. A page can change by itself (an
  animation, a clock, live data) and tell no one, so one shown longer than that is snapshotted again on its way out,
  and one that left the screen within 500 ms of its snapshot keeps it. A lazy list's item that scrolls away takes none
  (above): the page it parks keeps its picture, and a view that adopts the page shows that picture until the page has
  painted in its new place, a frame or two. A snapshot is kept with the view's size and density as they were when it
  was taken, so a view laid out again meanwhile still gets its cover at its true size.
- **Sharp.** A cover looks like the page: the runtime keeps every engine's snapshot at the screen's own density, up to
  3 pixels a dp (with 4,000,000 pixels as a safety cap only), with its aspect ratio, and in full colour, never 16-bit.
  This happens in one place, after `engine.snapshot()`, for every engine. On the phone (Galaxy Z Fold7, cover screen
  1080 × 2520 at density 2.625, WebView 156), a 480 dp embed's cover, stored and decoded as below, against the live
  page as the screen showed it: an agent demo differed by 0.63 on average per colour channel (of 255), and by 7 at the
  99th percentile; a text-heavy article by 0.48, and 7. The page's own draw alone differed from the screen by 0.01. On
  the iOS simulator (iOS 26.5, an iPhone at 3×), a 320 × 480 pt article embed: the page's own snapshot was the screen
  pixel for pixel, and its cover, stored and decoded, differed by 0.96 on average and 9 at the 99th percentile.
- **The page, never the app.** On Android a snapshot is the WebView drawn by itself into a bitmap, on the main thread:
  nothing the app draws over the page (its header, a menu, a dialog, the runtime's cover) can end up in it, and a page
  half off screen still gets one. One that comes out all one colour (video and WebGL, which that path can't draw) is
  discarded. With the view wholly on screen and nothing the app marked over it (the runtime tells the engine before
  each snapshot, `overlaysAtSnapshot`), a copy of the window (`PixelCopy`) comes first instead: the render thread makes
  it, and the main thread draws nothing. On iOS a
  snapshot is `WKWebView.takeSnapshot`, which WebKit renders from the page itself, so nothing over it can end up in it.
  The web takes no snapshots (cross-origin pixels can't be read).
- **Its cost.** On the phone, at the screen's density, a 480 dp embed draws itself in 13 to 21 ms (p50), and a demo
  filling the screen (1080 × 2520) in 14 to 28 ms (p50 over three runs of 20 draws, 39 ms at most). A page's first
  draw after it loads is cold, 2 to 4 times slower (34 ms for the full-screen demo). The draws should fit 32 ms. One
  that takes longer is kept, since the main thread has waited for it already, and sets how many pixels fit (with a
  20 % margin; a page's first draw counts at a third of its time), so a view as big is drawn at the largest of 3, 2.5,
  2 and 1.5 pixels a dp that fits from then on, and smaller views keep the screen's density. A later draw within the
  bound raises the budget again, at most doubling it each time. A WebGL page's draw came out blank, and was
  discarded. On iOS a snapshot needs no such budget: WebKit renders it off the main thread, and the engine copies its
  pixels into an image off it too. On the simulator (a Mac shared and loaded), a 320 × 480 pt embed at 3×, 960 × 1440,
  took 27 to 37 ms from asking to the image (p50 33 ms over 20), against 16 to 34 ms at 2× (p50 17 ms).
- **Stored encoded.** The cache stores each snapshot encoded, off the main thread: a JPEG at quality 92, or a PNG for a
  page with a transparent background, which keeps its alpha. On the phone the demo embed's cover is 90 KB stored and
  the article's 169 KB, against 5.4 MB of pixels; encoding took 5 ms (p50, 22 ms at most) and decoding 3 ms (8 ms at
  most). On the iOS simulator the article embed's cover is 179 KB against 5.5 MB of pixels; Skia encoded it in 6.4 ms
  (p50, 12 ms at most) and decoded it in 4.3 ms (7 ms at most). Least recently used out first, within
  `snapshotCacheBytes` of stored bytes: 32 MB on phones and tablets, so 150 to 300 sharp pages; 128 MB on desktop,
  64 MB on the web. Keyed by profile, content, pixel size, theme and density: a view never shows a snapshot of other
  content, of another profile or of the other theme.
- **Decoded on demand.** Beside them the cache keeps decoded snapshots, ready to draw, least recently used out first,
  within `decodedSnapshotBytes` of pixels (24 MB, 64 MB on desktop): the ones just taken, and every decode. A view
  asks for its cover's decode as it is composed: a lazy list composes an item just before it scrolls in, so its cover
  is usually ready by the frame it shows. A decode runs off the main thread, once however many views ask; while it
  runs, the view shows its placeholder, and its cover from the frame after. Only views on screen hold their cover's
  pixels, so covers take about a screenful, and no placeholder is composed under a cover.
- **Page memory.** A lazy list disposes the items that scroll away, and the item that scrolls back is a new view with a
  new state. So when an engine goes, or a page is parked, the runtime remembers, by profile and content key, the page's
  snapshot, height, engine state (history and scroll, where the engine can save them) and page state; a parked page
  that is reclaimed saves its engine and page state again. A new view of the same content in the same profile shows
  its snapshot at its real height from its first frame. Then it adopts the page if it is still parked, and restores
  nothing into it, since the live page is newer; or it restores the rest when it goes live on another engine. It keeps
  512 pages and 16 MB at most, least recently used out first.
- **Profiles are a boundary.** A profile is a user's cookies and storage, so nothing of a page shown in one profile
  reaches another: a view that shows the same content in another profile (a settings change, or a state moved to a view
  with other settings) starts fresh, with no restore and no cover, and its state shows nothing of what the page showed
  in the other profile (its URL, title, error and history), in the default placeholder or anywhere you read it.
- **Logout.** Users who share a profile, by logging in with cookies in `WebProfile.Default`, share the keys too, and a
  plugin's `Bundle` has the same key for every user. Call `runtime.clear()` at logout: it forgets every page and
  snapshot, and no view shows a snapshot from before it. A state a view shows without a live page drops the last
  user's page at once, what the page showed (its URL, title, error and history: the default placeholder shows the
  title) as well as what was remembered of it. A state you hold that no view shows drops it when a view is made for it,
  before that view's first frame; until then it still holds it, so don't show its title elsewhere across a logout, or
  drop the state. Parked pages are given back unsaved so no view adopts the last user's page, and pages live in a
  view, on screen or in a hidden tab, are given back unsaved too and load afresh. Engines keep their profile's cookies
  and storage: give each user a `WebProfile.Named` of their own, or clear that data through the platform.
  `runtime.forget(content)` forgets one content in every profile, for content that is gone: its pages parked or live in
  a view are given back unsaved (a view loads it afresh), and a save of it under way keeps nothing.
- **Page state.** A page keeps what it needs across engines through the shim. The page has 300 ms to answer when its
  engine goes. What it saved is pinned to the origin of the document that saved it, and given back only to a document
  of that origin. Register the provider when the page starts: one registered while the page is parked, where nobody
  hears it, is heard again when a view adopts the page (the shim repeats it in its answer to the bridge's `$hello`),
  and asked from then on; a page the pool reclaims before any view adopts it isn't asked:

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
- **A parked page** ([the pool](#the-pool-and-memory)) has no view: what it posts, calls or logs meanwhile reaches no
  listener, and its calls time out in the page. The view that adopts it hears it from the adoption on, even what it
  says before it has announced itself to that view: the bridge keeps it, the newest 64 messages up to 256 KiB, until
  the page's `$ready`. A page that must tell the app what happened while it was away tells it again later, or the app
  asks it (`call`) once the view is `Live` again.
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

With the engines as designed, Compose draws over the page on Android, iOS, macOS and the frame-mode desktops (Windows,
Linux), so menus, dialogs and tooltips simply cover it. A page that sits *above* the Compose canvas can't be covered:
the web's `<iframe>`. Such a page cuts the overlays out of itself, and dims under the scrims, so mark them:

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
- **On the web, mark everything you draw over a page**, buttons and labels as well as menus and dialogs. Whatever is
  unmarked stays under the page's iframe.
- The geometry is common code, and unit-tested: each live page gets its visible area minus the overlays as an even-odd
  path, whose holes are disjoint and cover exactly the overlays. An isolated overlay keeps its per-corner radii, and so
  does one that contains the others away from its corners. Applying it, with the scrims, is the engines' part
  (`applyOverlays`). The web engine cuts the holes with CSS `clip-path`, which clips clicks too, and dims under each
  scrim, where the page takes no clicks. On macOS Compose draws over the page, but a press goes to whatever is under the
  pointer natively: the page takes no press inside a marked overlay or under a marked scrim, so mark the overlays that
  take clicks there too.

**Material dialogs.** Material's dialog draws its scrim in a layer of its own, which no modifier reaches, so a page
above the canvas would stay bright under it. On macOS the page dims under it, but still takes the clicks there. Turn
that scrim off, and draw a full-window one behind the dialog's content:

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
    runtime checks the scheme again before opening one, whatever a custom client decided, and opens at most one URL a
    gesture: what a view asks to open within a second of its last open is taken as the same click's burst (a page
    that answers one press with several navigations or new windows), and dropped.
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

The rest is the engines' part, set by the design and the engine contract:
- no `file://` access;
- mixed content blocked;
- pop-ups denied;
- certificate errors never bypassed;
- inspectors only when `inspectable = true`;
- the shim's channel in an isolated world where the engine has one;
- every message checked against the receiving document's origin when it arrives.

The web engine's share is described [below](#the-web-engine). The iOS engine does all of it, and its conformance checks
test the bridge's parts with real pages ([iOS](#ios)). The Android engine does all of it but the isolated world: its
shim's channel is in the page's world, and each document's reply proxy reaches that document only ([Android](#android));
its instrumented tests check the bridge's parts on the phone. The Windows engine does all of it but the isolated world,
which WebView2 doesn't give a document's scripts: its shim's channel is taken before any page script runs, every
message carries its document's secret and token, checked in the glue before the app hears it, and the receiver checks
the origin it captured when the document was created; its conformance checks test the bridge's parts with real pages,
a page that patches `postMessage` among them ([Windows](#windows)).

## The runtime

```kotlin
// One per app, created once, for the life of the process.
val webViews = WebViewRuntime(RuntimeConfig.platformDefault().copy(maxLive = 6))

ProvideWebViewRuntime(webViews) { App() }
```

- `WebViewRuntime(config)` takes one `RuntimeConfig`, so an option added later changes no constructor. Without one it
  takes `RuntimeConfig.platformDefault()`.
- `RuntimeConfig(maxLive, maxIdle = 3, snapshotCacheBytes, decodedSnapshotBytes = 24L shl 20, hiddenReleaseMillis = 600_000, pageMemoryEntries = 512, factory = null, scope = null, clock = null)`,
  changed with `copy(…)`. `snapshotCacheBytes` bounds the snapshots as stored, encoded, and `decodedSnapshotBytes` the
  decoded ones ready to draw, in pixel bytes. `hiddenReleaseMillis` is how long a view off screen keeps its engine when
  no visible view needs it, and how long a parked page keeps its own when no view adopts it. `factory` creates the
  engines, the platform's when null (`platformWebEngineFactory()`); `scope` runs the pool's and the views' work, a scope
  of the runtime's own on `Dispatchers.Main` when null (on desktop the library brings `kotlinx-coroutines-swing` for
  it); `clock` is the platform's monotonic clock when null. The runtime shows what it resolved as `factory`, `scope` and
  `clock`.
- Control it through the runtime: `onMemoryPressure()`, `onMemoryRecovered()`, `onBackgroundTrim()`, `onError`,
  `clear()` and `forget(content)`. The pool's, the snapshot cache's and page memory's own controls are internal, so the
  cache halving, the error forwarding and the budget (`RuntimeConfig.maxLive`) stay consistent, and the only logout is
  `runtime.clear()`: clearing the caches alone would leave the pages parked or live in views, and their covers.
- For monitoring, read-only, as the perf lab does: `pool.liveCount`, `parkedCount`, `idleCount`, `budget` and
  `maxLive`; `snapshots.sizeBytes`, `count` and `maxBytes` (stored), `decodedBytes`, `decodedCount` and
  `decodedMaxBytes`, `hits` (a request answered decoded at once), `misses` (one that had to start a decode) and
  `evictions` (stored snapshots let go to stay within the budget); `pages.count`, `sizeBytes`, `maxBytes`, `maxEntries`,
  `hits` and `misses` (a new view's lookup of its page); `coverBytes` and `coverCount`.
- Main thread only: the runtime, the states and the views.

## The API

- **Checked.** `webview-compose` builds in explicit API mode: every public declaration says `public`, with its type.
  Kotlin's ABI validation dumps the public binary API of every target into [`api/`](api) (`jvm/`, `android/`, and one
  klib dump for iOS and the web), and `check` runs `checkKotlinAbi`, which fails on a change the dump doesn't show.
  After a deliberate change, `updateKotlinAbi` writes it again (on a Mac: the iOS targets build there only), and its
  diff is reviewed with the code's. The testing module's API is checked the same way. The dumps don't record which
  declarations need an opt-in: the compiler enforces that at every use.
- **Built to grow.** The types apps build, or get, that will grow are plain classes, not data classes: `WebSettings`,
  `RuntimeConfig`, the requests (`NavigationRequest`, `NewWindowRequest`, `FileChooserRequest`, `PermissionRequest`,
  `DownloadRequest`, `WebDialog`'s kinds), `ConsoleMessage`, `WebError`'s kinds and `WebSurfaceHints`. They compare with
  `==` and have no `componentN`; a new property comes as a new constructor parameter at the end, with a default, and the
  signature before it stays, hidden, so code built against an earlier version still runs.
- **Open enums.** `UnavailableReason`, `WebError`, `WebViewPhase` and `PermissionKind` may gain entries: a `when` over
  them needs an `else`.
- **The engine SPI** is behind `@WebEngineSpi` ([below](#for-engine-authors-the-engine-spi)), an opt-in at error level:
  apps don't need it, and it changes without notice.

## For engine authors: the engine SPI

An engine implements `WebEngine`, and a `WebEngineFactory` creates it; give the factory to the runtime
(`RuntimeConfig(factory = …)`). The full contract is the KDoc of
[`engine/WebEngine.kt`](src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt).

The SPI is behind an opt-in, `@WebEngineSpi` (`RequiresOptIn` at error level): `WebEngine`, `EngineEvent`,
`EngineConfig`, `PageSetup`, `EngineClient`, `EmbeddingMode`, `WebEngineResult`, `EngineEventQueue`,
`WebEngineFactory.create` and the `WebEngineFactory { … }` builder, `BridgeShim`, `WebBridge.READY_PREFIX` and
`READY_MAX_CHARS`, and the cut-out types (`OverlayCutouts`, `OverlayShape`, `ScrimShape`). It isn't stable: while the
six engines were built it changed shape seven times (async creation with a checked reset and a per-lease setup;
`needsCutouts`; parking and `replayState`; `overlaysAtSnapshot`; `PaintLost`; `namedProfilesPersist`; `onUnusable`),
and it will change again, with no deprecation cycle. Opt in where you implement or drive an engine,
`@OptIn(WebEngineSpi::class)`, or for a module of engines with `languageSettings.optIn("dev.supermux.webview.engine.WebEngineSpi")`
on each source set, as the library and the sample do. Apps hold and pass factories (`platformWebEngineFactory()`, a
test's) without it. In short:

- **Creation.** `create(config)` may suspend, and never throws: failures are `WebEngineResult.Unavailable(reason,
  detail)`. `EngineConfig` holds only what can't change after construction, the profile and the media playback
  policy, and engines are pooled by it. Everything else comes per lease through `configure(PageSetup)`, before every
  `load` or `restoreState`.
- **Threads and events.** The runtime calls every member on the main thread. Events may come from any thread; the
  engine buffers them in an `EngineEventQueue`, as every engine of the library does, and never drops a lifecycle event
  or a page's `$ready`, which it recognizes as the bridge does
  (`WebBridge.READY_PREFIX`, `READY_MAX_CHARS`). Once `load`, `reload` or `restoreState` returns, no event caused by an
  earlier document is delivered, even one still buffered: engines filter by navigation.
- **No white frame.** After creation or a reset, the surface stays invisible until `FirstPaint`, or a main-frame
  `Error`. `FirstPaint` means the document is drawn in its place with its content, under the runtime's cover, for at
  least a frame: not its commit, and not its bare background. Between documents, the engine keeps showing the previous
  one until the next paints.
- **Reuse.** `reset()` returns true only if nothing of the last lease can come back: no history, no late event, no
  pending request, no per-lease script or grant, and an `Ephemeral` profile's data wiped. Otherwise it returns false,
  and the pool destroys the engine. `isUsable` turns false for good after a crash or a release, and after `release()`
  every member does nothing.
- **Snapshots and state.** `snapshot()` is null until the current document has painted, and unless `Surface` is
  composed and laid out at a non-zero size. It should be the page as the engine draws it, not a copy of the screen,
  which would take in the app's UI over the page; an engine that can only copy the screen does so only when
  `overlaysAtSnapshot(cutouts)`, called just before each snapshot for engines Compose draws over, says nothing is over
  the page (`cutouts.isNone`). `restoreState(state, content)` returns false when it can't restore that
  content's state, and the runtime loads the content instead.
- **Parking and replay.** A page whose view left the composition may be kept live, unbound, with no client, no
  subscriber and no `Surface`, told it is hidden: the engine keeps its document as it is. A view that adopts it
  subscribes anew and calls `replayState()`, with no `configure` unless the setup changed and no `load`: the engine
  tells it what the page is now, as events (`UrlChanged`, `HistoryChanged`, `TitleChanged`, the latest
  `ContentSize`, where it knows them) and `FirstPaint` once the current document is on screen again. Pages answer
  the bridge's `$hello` with their `$ready`, and re-report their size. Meanwhile, with no subscriber, the engine's
  `EngineEventQueue` keeps at most one event of each kind: the latest of each state event and the page's latest
  `$ready`, no `FirstPaint`, and none of the page's other messages or console output, which it drops as they come.
  The runtime drops what an adopting view hears of those from before the replay all the same: they were said while
  nobody listened. While a page is parked the pool sets the engine's `onUnusable`: an engine that dies by itself (its
  renderer crashed, the platform retired it) calls it, and the pool releases the engine at once.
- **The bridge.** Inject `BridgeShim.source(sendExpression)` at document start into the main frame of documents whose
  origin is in `PageSetup.bridgeOrigins`, with JavaScript on, and deliver host messages by calling
  `window.__hostBridgeReceive(json)`. `postMessage(json, targetOrigin)` delivers only if the main frame's document
  has that origin when the message arrives. Page messages come from the main frame only, with the origin the engine
  sees.
- **The view.** `Surface(modifier)` hosts the native view or the frames. When an engine changes hands, the runtime
  waits for the view that had it to let go of its surface before the next one composes it, for 500 ms at most.
  `applyOverlays(cutouts)` cuts overlays out of a page above the canvas; an engine that Compose draws over returns
  false from `needsCutouts`, and the runtime computes none for it. `requestFocus` is optional.
- **Hidden pages.** `setVisible(false)` comes for a view off screen and for a parked page; engines may throttle the page
  but must keep its document. What each one does:
  - Android: `WebView.onPause()`, which stops the page's animations, geolocation and media and tells it it is hidden.
    Its JavaScript keeps running (`pauseTimers()` would pause every WebView in the process). A parked page's WebView is
    also out of any window; adopted, it stays invisible until it has drawn the page in its new place.
  - The web: the frame stays laid out in its layer, where it last was, with `visibility: hidden`. The iframe never
    leaves the page, which would reload it, and keeps its viewport size, so the page doesn't reflow; it takes no
    clicks, and the browser throttles a hidden cross-origin frame. A page of another site over the web's budget for
    such pages may give up its slot off screen, and loads afresh when shown.
  - iOS: the page's media is suspended (`setAllMediaPlaybackSuspended`): what plays stops, the page can't start it
    again, and it resumes when shown. Its JavaScript keeps running while its view is in a window. A parked page's
    `WKWebView` is out of any window, where WebKit throttles it by itself (its timers, and its animation frames, which
    stop); adopted, its first paint waits until it has drawn the page in its new place, and the view's cover hides it
    until then.
  - The fake records it.

## Testing with the fake engine

The fake engine and the test drivers live in their own module, [`webview-compose-testing`](../webview-compose-testing),
so the library's own artifact ships no test hooks. Depend on it from tests: `testImplementation(project(":webview-compose-testing"))`,
or in a KMP module `commonTest.dependencies { implementation(project(":webview-compose-testing")) }`. It targets what
the library does, and its API is checked as the library's is ([The API](#the-api)).

`dev.supermux.webview.testing.FakeWebEngine` follows the engine contract with no browser behind it, so an app can test
its web views wherever Compose UI tests run:

```kotlin
@Test fun theDemoGoesLive() = runComposeUiTest {
    val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 240f }))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val runtime = WebViewRuntime(
        RuntimeConfig(
            maxLive = 2,
            snapshotCacheBytes = 8L shl 20,
            factory = engines,
            scope = scope,
            clock = { mainClock.currentTime }, // hidden and settle times on the test's clock
        ),
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
  viewport), `pageState` (what the page's save provider returns), `resetSucceeds`, `needsCutouts`,
  `replayPaintsAtOnce` (false holds an adopted page's first paint back until the test emits one, as iOS and the web do)
  and `color`. Unless `color` is set, each content paints a colour of its own, derived from its key
  (`colorOf(content)`), so a screenshot shows which snapshot covers which view.
- **The factory** can be held open (`createGate`), made to fail (`failCreate`), or report a platform without engines
  (`unavailable`); `created` keeps every engine it made. None of this, nor the example above, needs the engine SPI's
  opt-in: its everyday constructor takes the behavior only, and the one that also takes an `EmbeddingMode` is the SPI's.
- **An engine** can be driven (`emit` any event, `postFromPage` as the page's `hostBridge.post`, `resetGate`,
  `saveStateGate`) and inspected: `calls`, `cutouts`, `lastSetup`, `config`, `released`, and `documentsStarted`, which
  tells a page kept alive from one started again. The members that take or give the SPI's types (`emit`, `lastSetup`,
  `config`, `cutouts`, `mode`, `events`, `client`) need `@OptIn(WebEngineSpi::class)`; `calls`, `violations`,
  `postFromPage`, the gates, `released` and `documentsStarted` don't. Like a real one, it delivers no event of a
  document after the next navigation, and `replayState()` repeats the current document's last commit, history, title
  and size, and its first paint once it has painted. Its pages speak the bridge's wire format as the shim does; the
  library's own tests check it against the library's codec.
- **`violations`** lists every break of the contract by its caller: a load without `configure`, lease calls during or
  after a failed reset, anything after `release`, a surface composed twice. Assert it stays empty.
- **`MacEngineTesting`** (desktop JVM) drives the real macOS engine in a real window, for the sample's checks: input
  posted into the app's own event queue, the window as the window server composited it, AppKit's state, the motion
  cover's switch. It reaches the engine's internals, so its module's JVM compilation has the library's classes as
  friends, as a test compilation would; IDEs don't know that, and flag its calls.

## The perf lab

[`webview-sample`](../webview-sample) is the sample app and the perf lab: on the fake engine on desktop, on the iframe
engine in a browser, and on the system WebView on Android ([Android](#android)):

```
./gradlew :webview-sample:run       # the desktop window: the perf lab and the overlay demo
./gradlew :webview-sample:jvmTest   # the lab's scripted runs, every frame checked; screens to build/sample-screens/
./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the same in a browser, served on localhost only
```

The lab is a chat of 50 to 1,000 messages, 200 by default. Each message embeds a `WhenVisible`, `WrapContentHeight`
web view: an agent demo as `Html`, every fifth a `Url` and every tenth a plugin `Bundle`, whose button tells the app
over the bridge (`hostBridge.post('clicked', …)`), and the message shows it. The list is a `LazyColumn` or
a plain `Column`, or shows still images instead, as the baseline. Its scripted runs, from the top to the end and back
without stopping (Scroll test), a screen at a time (Reading test), or one fling, each print a `perf-lab` line with the
frames, the engine churn, the peaks (live and parked pages among them) and the memory held. The numbers panel shows
live, parked and spare engines as they change. Buttons send memory pressure and recovery.

These numbers are for the **fake engine**: its pages are solid colours. They come from `:webview-sample:jvmTest` on
2026-10-29 (Apple M1, JDK 17), in a 1,024 × 768 window at density 1, over 200 messages, with hidden views keeping
their engines, lazy items parking their pages, and sharp snapshots stored encoded:

| run | budget: live + spares | engines created / destroyed | peak live | peak parked | peak engines in existence | pages started | snapshots | peak covers | snapshot cache at the end (stored, kept, decoded) | page memory at the end |
|---|---|---|---|---|---|---|---|---|---|---|
| Reading test, `LazyColumn` | 2 + 3 | 4 / 0 | 2 | 2 | 4 | 132 | 131 | 3.6 MB, 4 views | 280 KB of 32.0 MB, 131 kept, 23.9 MB decoded (29) | 132 pages, 37 KB |
| Reading test, `Column`, all 200 composed | 3 + 3 | 6 / 0 | 3 | 0 | 6 | 200 | 200 | 3.9 MB, 4 views | 415 KB of 32.0 MB, 199 kept, 23.5 MB decoded (28) | 196 pages, 55 KB |
| Scroll test, `LazyColumn` | 4 + 3 | 5 / 0 | 3 | 3 | 5 | 3 | 2 | 1.8 MB, 2 views | 4 KB of 32.0 MB, 2 kept, 1.8 MB decoded (2) | 3 pages, 855 B |

Before parking, the same runs created and destroyed 8 / 5, 6 / 3 and 5 / 2 engines. Every page the runs saw keeps its
picture: the reading run's 131 of 131 (pictures kept as pixels, even 16-bit at one pixel a dp, left 72 in 32 MB), and
a 60-message run's 34 of 34. The tests read it from the cache's statistics now (`snapshots.count` and `evictions`, the
pictures let go for room): in the run of 2026-10-31, the reading run kept 173 pictures and the 60-message run 53, and
neither let one go. The fake's solid-colour pages are about 2 KB each as JPEGs; real ones are tens to a couple of
hundred KB (below).

**On the phone, on Android's WebView.** The Reading test on the Galaxy Z Fold7's cover screen (1080 × 2520 at 2.625,
120 Hz), WebView 156, on 2026-10-29: `Reading test, LazyColumn · 200 web views: 4783 frames, p50 8.3 · p95 8.3 · p99
8.5 · max 58.5 ms, 31 dropped; engines created 1, destroyed 0, peak live 4/4, peak parked 3, peak in existence 6/7;
pages started 199, snapshots 197; covers up to 6.8 MB (4 views); cache 4.9 MB of 32.0 MB, 200 snapshots kept, 22.1 MB
decoded (14); page memory 200 pages, 242 KB`. Every message keeps a sharp picture, in 4.9 MB; with full-size pictures
kept as pixels, an earlier run's cache was full at 31.6 MB, and most pages had lost theirs. The Scroll test ran before
pages were kept alive, on 2026-10-29: `Scroll test, LazyColumn · 200 web views: 3825 frames, p50 8.3 · p95 8.3 · p99
8.3 · max 24.9 ms, 6 dropped; engines created 0, destroyed 0, peak live 2/4, peak in existence 3/7; pages started 0,
snapshots 0; covers up to 1.6 MB (2 views); cache 1.6 MB of 32.0 MB; page memory 2 pages, 2 KB`, and on the fake
engine, for comparison, 3836 frames, max 16.6 ms, 8 dropped. On the tree with pages kept alive and the phone's fixes
(`2df7ae549`, 2026-10-31) it drew 3795 frames, p99 8.3 ms, 3 dropped.

- In these runs, checked at every frame, no page started while the list moved, live engines never exceeded the
  budget, engines in existence never exceeded `maxLive + maxIdle`, and covers stayed within a screenful.
- **Memory pressure** (2 live + 3 spares, 32 MB of snapshots): live engines 2 → 1, engines in existence 2 → 1, the
  snapshot cache 4 KB stored (2 snapshots) → 2 KB (1), and still 2 KB 5 s later. After recovery: 2 live, 4 KB (2).
- **Frames.** The tests step a virtual clock one 16 ms frame at a time, so the frame intervals in the `perf-lab`
  lines (all 16.0 ms, none dropped) say nothing about smoothness. What the harness measures is the wall-clock cost of
  each frame the list moves: the scroll, composition, layout and drawing, and the runtime's queued work. In the last
  run's Scroll test that was p50 0.5 ms and p95 1.5 ms with web views, against p50 2.3 ms and p95 3.1 ms with still
  images. Over earlier runs, p50 ranged from 0.9 to 2.7 ms against 3.2 to 5.9 ms, and p95 from 2.5 to 9.1 ms against
  4.5 to 12.7 ms. With solid-colour pages, that is the list's cost and the runtime's, not an engine's.

Two of the design's targets hold on the fake engine: over a 200-demo list, live engines stay within the budget and the
snapshot cache within its bound. The others are goals for the engines, to be measured on each platform once its engine
exists: no more dropped frames with web views than with images, first paint of a settled view within 100 ms on
desktop and 200 ms on mobile from a warm spare, a bridge round trip within 2 ms on desktop and 5 ms on mobile (p50),
60 fps for frame-mode engines, and cut-outs that follow a menu's open animation in the same frame.

**In a browser, on the iframe engine.** These numbers come from `web-check/run.mjs lab` in headless Chrome 156 with the
GPU, on the final tree on 2026-10-31 (Apple M1, its load 7.7 to 10 on 8 cores):
- Reading test: `Reading test, LazyColumn · 200 web views: 2314 frames, p50 16.7 · p95 16.7 · p99 16.8 · max 33.4 ms,
  3 dropped; engines created 9, destroyed 0, peak live 5/12, peak parked 11, peak in existence 14/15; pages started
  197, snapshots 0; covers up to 0 B (0 views); cache 0 B of 64.0 MB, 0 snapshots kept, 0 B decoded (0); page memory
  200 pages, 45 KB`. At rest: `engines live 0 of 12 · parked 11 · spares 3 · in existence 14 · created 14 · destroyed 0
  · pages started 200 · snapshots 0`, and Chrome's process tree at 1671 MB of RSS, which counts the framework's shared
  pages once in every process. Pages scrolled away stay live and parked, so the run creates 9 engines and destroys
  none (before keep-alive, 2026-10-29: 38 created and 40 destroyed, peak 6 of 15 in existence).
- Scroll test: `Scroll test, LazyColumn · 200 web views: 1766 frames, p50 16.7 · p95 16.7 · p99 16.8 · max 16.8 ms,
  0 dropped; engines created 0, destroyed 0, peak live 3/12, peak parked 3, peak in existence 5/15; pages started 0,
  snapshots 0; covers up to 0 B (0 views); cache 0 B of 64.0 MB, 0 snapshots kept, 0 B decoded (0); page memory 3
  pages, 688 B`. At rest: `engines live 3 of 12 · parked 0 · spares 2 · in existence 5 · created 5 · destroyed 0 ·
  pages started 3 · snapshots 0`, and 1519 MB.
- Chrome's own counters in the Reading test: 2439 layouts in 432 ms, 2561 style recalculations in 241 ms, 5459 ms of
  script; in the Scroll test 42 layouts in 19 ms and 43 style recalculations in 8 ms.
- From showing a view to its page's first paint, cold: 200.8 ms, with no spare yet and the first web view in the page
  (spec §10 asks ≤ 100 ms on desktop from a warm spare, which this doesn't measure).
- The bridge's round trip through the whole library: p50 0.1 ms, p95 0.4 ms, and 0.3 / 0.7 ms in another run (spec
  §10 asks ≤ 2 ms on desktop).

## The web engine

On the web (wasmJs), each live page is an `<iframe>` (`IframeWebEngine`). It was built after a feasibility test that
measured the risky parts first. The numbers are below.

- **Where it sits.** Compose for Web draws on a canvas, and an iframe can't be drawn into one, so the page sits above
  the canvas.
  - It sits in a layer of the library's own, next to Compose's interop container.
  - A page is placed in the same layout pass that moves its view, and clipped to what its scroll container shows.
  - It is never moved to another parent, because an iframe that moves reloads. Compose's `HtmlElementView`
    re-inserts its element when Compose stops placing it, and that reloaded pages in the test.
- **Overlays.** Compose can't draw over the page, so the page cuts out what is drawn over it.
  - Every `Modifier.webViewOverlay` becomes a hole. It uses `clip-path`, which clips clicks too.
  - A `Modifier.webViewScrim` dims the page, and the page takes no clicks under it.
  - **Mark everything you draw over a page**, buttons and labels included. Anything unmarked is under the iframe.
- **Documents.** Every document the app starts gets a fresh iframe. So the engine adds nothing to the browser's history,
  and a page from an earlier view can't come back.
  - `Html` content is a `srcdoc` in `sandbox="allow-scripts"`: an origin of its own, with no storage, no popups and no
    top-level navigation.
  - `Url` pages keep their own origin (`allow-scripts allow-same-origin allow-forms`). Use them only for pages you trust
    to frame, and only once the app's origin refuses to be framed (**`Url` pages and the app's origin**, below).
  - A page shows once it has drawn its content in its place, under the view's cover. A demo shows at its first
    contentful paint, or its DOM and two frames if nothing in it is contentful, so a slow image or a request that hangs
    doesn't hold the cover. A `Url` page, which can't be seen before its load, shows at its load. Nothing waits more
    than 3 s.
  - A demo that reloads or navigates itself keeps its bridge: its shim says when its document leaves, and the next one
    connects afresh.
- **The bridge** runs over a `MessagePort`.
  - Each document creates its own port, and hands it to the app with its first message, together with a secret the
    engine gave it. One port a document: more handshakes are closed, and queue nothing.
  - A demo's document without the secret gets no port. A demo can navigate its frame to a document the engine wrote no
    shim into, such as a `data:` URL: its handshakes are closed and queue nothing, and it commits once, at its load.
  - The shim keeps every built-in it calls on its port, and the input getters scroll chaining reads, from before any
    script of the page runs. A page that patches `MessagePort.prototype.postMessage` or an event's getters gets neither
    the port nor a forged gesture.
  - Presses, the keyboard, scrolling, flings, links, a first paint and a document leaving count only when a shim with
    the secret tells of them. A `Url` page runs no shim: a port that one of its documents hands over, one a document,
    carries bridge messages only.
  - Messages for a document go only to its port, and only if it has the origin they are for.
- **Links.** A link the user clicks in a demo goes through `WebClient.onNavigation`, so by default it opens in a new
  tab.
  - Only while the app's window has the user's activation, which the click gives it, and at most one each 500 ms.
  - A link to a place in the demo itself (`#here`) moves the demo there, and asks the app nothing.
  - SVG links and links in shadow roots go through the app too.
- **The keyboard.** A demo takes the keyboard only if the user gave it: a press in the demo, more recent than any in
  the app; Tab from the app; or the app's `requestFocus`.
  - A demo that takes it otherwise gives it back at once to what had it: a Compose text field, another demo, or the
    app. The view hears nothing.
  - A page without the shim (`Url`) may take it while the app's window has the user's activation. A page without
    scripts can't take it by itself.
  - **Not solved.** The browser moves the keyboard into a frame before the app hears of it, so the engine can only give
    it back. A demo taking it every 150 ms still got 3 to 5 of 43 keys typed into the app's field in three runs
    (`web-check focus`, on a busy Mac), and keys typed into another demo while it steals don't reach that demo. One
    taking it once a second got none.
- **Scrolling.** A wheel or a touch drag over a live page goes to the page alone: Compose never sees it. So the page's
  shim sends on what the page couldn't scroll itself, and the view hands it to Compose's nested scroll.
  - A wheel over a demo scrolls the demo while it can, then the list around it. Scrollers in shadow roots count.
  - A drag that ends while it is chaining flings the list.
  - A drag over an element whose `touch-action` keeps the browser from panning an axis, such as a canvas the demo
    rotates or draws on, stays the demo's on that axis.
  - What may scroll is worked out once a gesture, not on every event.
  - Only input the browser made counts, at most 400 CSS px a frame, and only finite amounts.
- **Budget.** 12 live iframes (`RuntimeConfig.platformDefault()`), from the memory measured below.
  - The measurement was of demos, which share a renderer process. A page of another site (`Url`) runs in a process of
    its own, so only 4 of them are live at once.
  - Past that, a view on screen takes the slot of a page whose view is off screen: one parked for a later view first,
    then one only hidden, never a view on screen. That page goes, its view is covered again (`EngineEvent.PaintLost`),
    and it loads afresh once its view is back and a slot is free. With no slot to take, a page waits behind its
    placeholder until one frees.
  - A spare, or a view not shown yet, takes no slot from anyone.
- **Shown again.** A view hidden while still bound to its page (a list item kept composed, a pager's tab) shows, when
  it comes back, the page as it was for at most a frame or two, then as it is, and never a blank frame
  (`web-check reshow`). Its frame keeps the place it was last shown in while it's hidden, and takes the new one as it
  shows again: Chrome shows an iframe hidden while its box is outside the viewport blank for a frame when it comes back,
  which happened in 16 of 16 runs before this rule. A covered reveal, as an adopted page gets, would show the placeholder instead, since the web
  has no snapshots.

| Measured on 2026-10-29 (Apple M1, Chrome 156) | Result |
|---|---|
| Reloads of live pages while a list scrolls, gets items inserted above, reorders, or doesn't place a page for 5 frames | None, in all four, in two runs. Compose's own `HtmlElementView`, for comparison: 1 reload, when the list didn't place it for 5 frames |
| Clicks through a cut-out reach Compose, and elsewhere reach the page: 6 points, rounded corners, synthetic and trusted clicks | All 6 points, and the page under a scrim takes none, in Karma and with trusted clicks. This needs the frame's outer element to take no clicks itself: as first written, it filled every hole |
| Placing about 12 live pages each frame while their list scrolls (JS time) | With the runtime and 12 live pages: p50 0.0 ms, p95 0.3–0.4 ms, mean 0.09–0.10 ms (the page's clock steps in 0.1 ms). The first test created 18 iframes as they scrolled in and measured p95 0.5–1.3 ms, mostly that creation, which `WhenVisible` doesn't do mid-scroll. A moving menu's hole was late in 0 of 32 of its frames |
| Chrome's memory per live demo page, and after 200 pages came and went | Too little to tell from Chrome's own drift on a busy Mac. Physical footprint over 48 pages gave −1.07 to −0.74 MB a page, and a per-process breakdown about 2.4 MB. Summed RSS counts the framework's shared pages in every process and can't measure it at all. After 200 cycles: +15 and −16 MB, within about ±40 MB of drift |
| Bridge round trip, p50 / p95 | Raw port: 0.0–0.1 / 0.1–0.2 ms. `WebBridge` over the port: 0.1–0.2 / 0.2–0.3 ms. Through `WebView`: 0.1–0.3 / 0.4–0.7 ms |
| Browser history | The engine's documents add no entries, and Back never navigated a new page's iframe. Chrome keeps a removed iframe's own entries (those its page made), and Back over them does nothing |
| The browser sample, with trusted input (`web-check/run.mjs overlay` and `wheel`) | A menu and a dialog over a live page take real clicks, and the page under them doesn't. A link the user clicks in the page opens outside the app. A wheel of 300 px over a live demo moves the list 300 px |

| Measured on 2026-10-30 (Apple M1, Chrome 156 headless with the GPU, the Mac shared with other builds) | Result |
|---|---|
| A menu and a dialog over a live page, with trusted clicks (`web-check overlay`) | 7 of 7 checks, at load 265 |
| A trusted wheel of 300 px over a live demo (`web-check wheel`) | The list moved 300 px |
| The handoff from the cover, in a CDP screencast (`web-check handoff`) | From the first frame that shows the view: the cover in 3, then the page in 2, and none that shows neither |
| A view still bound to its page, hidden while the page changes colour, then shown (`web-check reshow`) | No blank frame in 8 runs on 2026-10-30 (loads 5–30). Before the hidden frame kept its last shown place, a blank frame in 16 of 16 runs; two earlier runs (loads 60 and 208) had happened not to show it |
| Real typing in the app's Compose text field, then a click into a demo and typing there (`web-check focus`) | No demo taking the keyboard: 43 of 43 keys in the field, 5 of 5 in the demo. A demo taking it every 150 ms: 5 of 43 keys lost to it (3 to 5 in three runs), and none of 5 reach the demo clicked. The app's window has the user's activation all the while the user types, so activation can't tell the user's focus from a page's |
| A real click on a demo's link to `#here` (`web-check anchor`) | The demo moves to `#here`, and no tab opens |
| The perf lab's Reading test (`web-check lab`), before the review's performance items and after all its fixes | 2299 → 2314 frames, p50 16.7 → 16.7 / p95 16.7 → 16.8 / p99 16.8 → 16.8 ms, max 283.2 → 33.4 ms, 2 → 3 dropped (load 174, then 23 to 49). Chrome's counters after: 2452 layouts in 424 ms, 2577 style recalculations in 226 ms, 5264 ms of script |
| The perf lab's Scroll test, the same | 1766 → 1766 frames, p50 16.7 / p95 16.7 / p99 16.8 ms both times, max 16.8 ms, 0 dropped both times. After: 43 layouts in 21 ms, 44 style recalculations in 9 ms |
| Pages placed with `transform` instead of `left` and `top` (the review's suggestion) | Not adopted. All the layout of the Reading test, placement included, came to 0.18 ms a frame (424 ms over 2314 frames), so a transform could save at most that, in frames already at the display's interval; and `left` and `top` keep the page on whole device pixels |

**`Url` pages and the app's origin.** A `Url` page runs with `allow-same-origin`, so a document of the app's own origin
in its frame, after a redirect or a navigation of its own, is the app's, and can script it.
- The engine refuses a `Url` on the app's origin, by the browser's own URL parser, and a page whose load reaches the
  app's origin anyway loses its frame and reports `BlockedByPolicy`. That is defence in depth only: by its load, its
  scripts have run.
- So an app that shows `Url` pages must, as a hard requirement:
  - send `Content-Security-Policy: frame-ancestors 'none'` with **every** response on its origin, not only its page;
  - never serve content an agent controls on its origin, under any path. Serve such previews on an origin of their own
    (a subdomain).
- **supermux doesn't meet it yet, and that blocks `Url` views in supermux:**
  - its server sends `frame-ancestors 'self'`, which lets the app's own pages into a frame inside the app, and sends it
    only with its static files (`src/channels/web/static-serve.ts:83`); its other responses send no policy;
  - with no proxy base domain, it serves agents' previews under `/p/<slug>/` on the app's own origin
    (`src/channels/web/index.ts:840-851`).

**What the web can't do.** The engine says so instead of pretending.
- **No snapshots.** A cross-origin page's pixels can't be read, so a cold view shows its placeholder.
- **No `evaluate`, no back or forward, no stop.** A page's own navigations still add entries to the browser's
  history, as any iframe's do: links it follows itself, `pushState`, and fragment links. Chrome keeps those entries
  after the page's view is gone. Back then steps over them, one press each, and they navigate nothing.
- **A `Url` page's own navigations don't go through `WebClient`.** The links it follows, the forms it sends and its
  redirects stay in its frame, unseen. Only a demo's link clicks reach the app.
- **Paint isn't held across a demo's own navigation to another origin.** The paint holding the engine contract
  describes is Chrome's own here, and Chrome holds paint within an origin only: a demo that navigates itself to
  another origin shows a blank frame between the two documents, which the engine, hearing of the new document only at
  its load, can't cover.
- **Demos inherit the app's Content-Security-Policy.** A `srcdoc` document takes its parent's, so a `script-src` on the
  app's page that doesn't allow inline scripts stops every demo's scripts, and the engine's shim with them.
- **Dialogs, file choosers, permissions and downloads** are denied by the sandbox, and `WebClient` never hears of them.
- **`userAgentSuffix` and `inspectable` do nothing.** The browser's DevTools always work.
- **Some content is refused.**
  - `WebContent.Bundle`, `Url` with headers, and `Url` in an `Ephemeral` profile report `UnsupportedOnPlatform`.
  - A `Url` on the app's own origin, or `http:` in an `https:` app, reports `BlockedByPolicy`.
  - Named profiles share the browser's one storage.
- **Most sites refuse to be framed.** 36 of 42 top sites refuse, and logins inside third-party iframes fail, so `Url`
  is for pages you serve or proxy yourself, on an origin of their own (**`Url` pages and the app's origin**, above).
- **Scrolling is chained from demos only.** A `Url` page runs no shim, so a wheel or a drag over one scrolls that page
  and never the list around it.

To rerun the measurements, run these from `apps/` on a Mac with Chrome (elsewhere, set `CHROME_BIN`):

    ./gradlew :webview-compose:wasmJsBrowserTest -Pwebview.spike=true --tests 'dev.supermux.webview.engine.web.spike.*'
    node webview-sample/web-check/run.mjs memory webview-sample/web-check/probe
    node webview-sample/web-check/run.mjs input webview-sample/web-check/probe
    ./gradlew :webview-sample:wasmJsBrowserDistribution
    node webview-sample/web-check/run.mjs overlay webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs wheel webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs lab webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs handoff webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs reshow webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs focus webview-sample/build/dist/wasmJs/productionExecutable
    node webview-sample/web-check/run.mjs anchor webview-sample/build/dist/wasmJs/productionExecutable

## iOS

`WKWebView`, in a `UIKitView` under the Compose canvas (spec §7). Compose draws over the page, so menus and dialogs need
no cut-outs.

- **Touches.** In an embed in a list ([embeds and panes](#sizing)) Compose gets a drag that starts on the page first
  (`UIKitInteropInteractionMode.Cooperative`), so the list scrolls, and taps still reach the page. In a pane the page
  gets every touch at once (`NonCooperative`), and scrolls itself.
- **The user's gestures.** WebKit has no public user-gesture flag on navigations, so the relay reports what the page,
  or any of its frames, saw from the user in trusted events, which no page script can make: a tap, a click, or a key
  but Escape. A request of that document's to leave (a navigation the client is asked about, or a new window) within a
  second is then the user's, once. A drag the list keeps to scroll itself is not (Compose hit-tests the page's view for
  it all the same), nor anything a page script does: a page that tries to leave while the user scrolls the chat over it
  stays in the app. A scroll of the user's own in the page counts as using it (it takes focus), never as a gesture.
- **Local content.** `Html` loads on `https://c<hash>.invalid`, a secure origin of its own. WebKit can't serve `https`
  from the app, so a `Bundle` is served from `webview-bundle://<host>.invalid/…`, also a secure context: the page sees
  that as its origin (`self.origin`, `location.origin`), while the bridge, the navigation policy and `WebViewState.url`
  use `https://<host>.invalid`. Use relative URLs in a bundle: an absolute `https://<host>.invalid/…` fails, as
  `.invalid` never resolves. A bundle's files are served in byte ranges too, as a server would, so a bundled `<video>`
  or `<audio>` plays.
- **The bridge** spans two worlds: `window.hostBridge` lives in the page's own, and its channel to the app in an
  isolated `WKContentWorld` that page scripts can't reach. Messages from the app are checked against the page's origin
  there, when they arrive. The relay there acts on trusted events only (a page's own focus, blur or back-forward
  `pageshow` count for nothing), and caps what a page sends at 500 messages at once, then 500 a second, before they
  reach the app's main thread. A document's `$ready` passes apart from that, once for the document and once for each
  `$hello` the app sends, so a page asking itself to announce in a loop mints none. (The relay is the macOS and Linux
  engines' too; only iOS asks it for the user's input, and for title and URL hints, which Kotlin/Native can't key-value
  observe.)
- **The handoff from the cover.** The `WKWebView` is never hidden: composed, it is in its window under the Compose
  canvas and draws there, under the cover. A document says `FirstPaint` once it has content, two animation frames after
  its first contentful paint or its load (the isolated world sees both, with the page's JavaScript off too), or after
  3 s with neither, and then once the web view has been shown in its window for two display ticks (`CADisplayLink`). A
  page hidden meanwhile (`setVisible(false)`, as when its view parks it) completes no handoff until it is shown again,
  so a view that adopts it never hears a first paint from before. A new `WKWebView` starts at the screen's size, never
  none, and a page that started loading at another size is told the view's size again at its commit: iOS 16 kept the
  size a page started loading at. The scroll view is WebKit's to paint, with the page's own background: a view that
  widens shows that, never a light band, where WebKit hasn't drawn the page yet.
- **Reuse.** `WKWebView` can't clear its history, so a reset puts back a one-page history that no navigation can reach:
  a page's `history.back()` never reaches the previous lease's pages, and `canGoBack` starts false. A reset succeeds
  while the page still loads, and before it commits, and goes on the moment its floor has loaded (0.6 s for one on the
  iOS 26.5 simulator, most of it the floor's own WebContent process starting). WebKit runs that navigation at background
  priority for a web view out of any window, as a recycled engine's is: on the 16.4 simulator, under a Mac loaded to
  100–250, its processes stalled for 3 to 8 s, the floor's 4 s ran out, and the reset returned false (four runs of
  four; at 45–80 it passed), so the pool destroys such an engine and makes another. A released engine unloads its page
  at once (its scripts, timers and loads stop), pauses its media and closes picture-in-picture and full screen; nothing
  WebKit keeps (a completion block, a delegate) keeps the engine.
- **Failed loads.** A page whose load failed saves no engine state (WebKit still holds the floor, or the page before),
  `reload()` loads it again, and its error names the page, never the floor. A saved state whose page is the floor
  restores nothing, so the runtime loads the content instead: a view of a page that failed (an app started offline)
  shows its error again, never its cover for good.
- **What iOS has no public API for:** a file chooser hook (WebKit shows its own picker; `onFileChooser` isn't called),
  `beforeunload` dialogs (pages simply leave), and geolocation's prompt: `onPermission` hears camera, microphone and
  motion, while a page asking for its position gets WebKit's own prompt. A download goes to `onDownload` and is never
  saved: as on Android, the app gets the URL. A question the page asked (a dialog, a permission) still open when its
  view goes gets the safe answer then, so the page a next view adopts isn't left waiting on it.
- **Profiles.** `Named` profiles get stores of their own from iOS 17 (`WKWebsiteDataStore(forIdentifier:)`), which
  persist. On iOS 15–16 WebKit names no stores: each named profile then gets a store of its own, which all its engines
  share and which lasts until the app quits, never the default store that every other profile would share, and
  `runtime.factory.namedProfilesPersist` is false. `Ephemeral` is a store per engine, wiped when the engine is given
  back.
- **Page state.** `Html` keeps no engine state (WebKit can't restore an in-memory document): it reloads, and gets its
  page state back through `hostBridge.onSaveState`. `Url` and `Bundle` pages get their history and scroll back.
- **Parked pages** ([above](#the-pool-and-memory)) keep their `WKWebView`, document and scroll position out of any
  window, with their media suspended (a playing video is suspended while hidden and plays on when shown). A view that
  adopts one hears its URL, history and title at once, and its first paint once the page has drawn in the new view
  (two animation frames there) and been shown there for two display ticks, which lifts the cover: nothing reloads, the
  page's own state is as it was, and its shim announces itself to the new view's bridge. A page WebKit brings back
  from its back-forward cache announces itself anew, and the bridge reaches it.
- **A WebContent process the OS kills** (it does, to apps in the background) puts its views in `Crashed`, as the
  contract says: one on screen goes live again by itself a second later, at most once a minute, and
  `navigator.reload()` brings any of them back. A parked page whose process was killed is released, and a view of it
  starts afresh.
- **Memory warnings** reach `runtime.onMemoryPressure()` by themselves, while any of the runtime's web views is shown.
- **A lease's first page** took 451 ms to its commit on the iOS 26.5 simulator, and a page of another site in the same
  engine, with no floor between, 359 ms (p50 of 6): most of it is the new site's own WebContent process, which each
  `Html` origin gets as a site of its own (spec §9). The floor and the spare add 60–90 ms. Warming the engine's data
  store before the lease changed nothing; an `https` floor (the review's suggestion) could save at most those
  60–90 ms, and a reset would then restore an `https` page without the network: not tried. Measure on a device before
  deciding.
- **Running it,** on a Mac, from `apps/`: `webview-sample/device-checks/ios-engine-checks.sh` (the conformance checks:
  the 31 shared with macOS and 19 of the iOS engine's own; `DEVICE="iPhone 14"` for iOS 16.4),
  `webview-sample/device-checks/ios-sim.sh` (the UI flows, and the user-gesture probe: drags over a page that tries to
  leave by itself, then a tap on its link), and `MEASURE=1 webview-sample/device-checks/ios-engine-checks.sh` (the
  numbers). Simulators only, no signing. Both scripts shut down a simulator they booted.

## macOS

`WKWebView`, driven from the JVM through a small Objective-C library over JNI (`native/macos`), in the view AWT gives
the window, **under** skiko's Metal layer: Compose clears a hole where the page shows. A layout test in a real window
chose this over a page above the canvas (its results are below). macOS 14 or later; elsewhere on the desktop the engine
reports `Unavailable(NotImplemented)`.

- **Overlays.** Compose draws over the page, so menus, dialogs and tooltips show, shadows and all, as anywhere else.
  But a press goes to whatever is under the pointer natively: mark every overlay that takes clicks with
  `Modifier.webViewOverlay`, and every modal scrim with `Modifier.webViewScrim`, or the page takes their presses. A
  press on the page closes a Compose menu that is open, as a press outside it would anywhere: while an overlay is
  marked, the page's press is handed to Compose too, and the page's own place in the layout takes it, so no clickable
  under the page sees it. Mouse moves over a marked overlay, a scrim, or a page that takes no input (under its cover)
  go to Compose alone: hover and the cursor there are Compose's, and the page hears none of them (what it hovered last
  stays hovered until the pointer moves on the page again, or leaves its frame).
- **Placement.** The page keeps the slot Compose lays it out in, measured from the window's top-left, whatever happens
  to the window's height (a resize, the zoom button; full screen is on the manual checklist). Only the part of the slot
  that shows is drawn and takes presses: a page half scrolled out of a clipped list covers nothing beside it. The slot
  reaches WebKit at the window's backing scale, not at the app's `LocalDensity`, so an app's UI zoom puts the page
  exactly in its hole. Under a scaled ancestor the page is laid out at the size Compose draws it, not scaled.
- **Moving pages.** A native view can't move in the same frame as Compose: skiko presents Compose's frame from a Metal
  handler of its own once the GPU has drawn it, and AppKit moves views on its main thread, at its next commit. The layout
  test saw a page trail its slot by a frame or two, with the window's own background in the gap. This engine places the
  view from Compose's layout, so on an idle GPU it keeps up with its slot; while WebGL pages keep the GPU busy it runs two
  or three frames ahead of its hole, and the band of the hole it has left would show what is under the canvas: AWT's own
  view, Swing's light grey (`#EEEEEE`), a white band at the trailing edge of every live preview while the chat scrolls
  (a user's report, 2026-11-01). So a backdrop lies under every page view where the page showed in the last quarter
  second, in the page's own background (the colour along its last snapshot's edges, the theme's before it has one; none
  for a transparent page), and that band shows the page's colour. It never covers a page, being under all of them, and
  a page that leaves its list or its window leaves it behind for that quarter second, while Compose's frame still has
  its hole. What trails inside the view is the page's content while the view is resized (strips of WKWebView's own
  white, up to 16 px, whatever the page's colours), until WebKit has drawn the page at its new size. So the cover shows
  only while a page reflows: while its pane is dragged or the
  window is resized, the page's last snapshot covers the slot at its true size (as every engine's cover is drawn,
  [Snapshots](#snapshots-and-page-memory)), and the page takes no input until its slot is still and it has been drawn
  there. A page that only moves, in a list that scrolls, stays live and keeps its input. So does an embed
  (`WrapContentHeight`) that only grows or shrinks: it shows more or less of itself, and nothing in it moves. What it
  grew by shows the page's background (the colour along its snapshot's edges) until WebKit has drawn it there, a frame
  or two later: WebKit is asked at once, and again while it keeps growing. A page that changed since its last snapshot
  jumps to its live state when the resize ends.
- **Input.** Typing and input methods are WKWebView's own. ⌘C, ⌘X, ⌘V, ⌘A, ⌘Z and ⇧⌘Z reach the page while it has
  focus (AWT would keep them); other ⌘ shortcuts are the app's. An embed in a list ([embeds and panes](#sizing))
  leaves the wheel and trackpad gestures to its list, so a chat scrolls over its demos; a pane scrolls and zooms
  itself. Trackpad swipe navigation is off: a swipe would show WebKit's picture of the page before a lease's first,
  which no lease may reach. A press or a key in the page makes its next request to leave (a navigation the client is
  asked about, or a new window) within a second the user's, once.
- **VoiceOver** reaches the page through a bridge on AWT's view, which lists each shown page's web view: AWT itself
  exposes only Java's tree. The bridge subclasses AWT's private view class at run time, and a check pins it.
- **The handoff from the cover, local content, the bridge, reuse, profiles and pending questions** are iOS's (their
  WebKit rules are common code): a document's first paint once its content is drawn in place for two display ticks;
  `Html` on its own `https://<hash>.invalid` origin, `Bundle` on `webview-bundle://`; the bridge's channel in an
  isolated world; a reset back to a one-page history; a data store per named profile; the safe answer, when its view
  goes (it parks the page), to a question of the page's the client hasn't answered.
- **Failed loads** follow iOS's rules ([iOS](#ios)): a page whose load failed saves no engine state, `reload()` loads it
  again, and a saved state whose page is the floor restores nothing, so the runtime loads the content. Its error names
  the page, never the floor: WebKit names a refused connection's URL only as an `NSURL`, which the glue reads too.
- **Server redirects** of the app's own navigations (a load, a restore, a reload, back and forward) are followed to the
  web, http(s), without asking `WebClient.onNavigation`, until the page commits, as on Android; a redirect anywhere else
  (any scheme off the web) ends the load with `WebError.BlockedByPolicy`. iOS does the same (the rule is common code).
  WebKit's public API tells a redirect from a navigation the old page starts by script only by its request, which
  WebKit's network layer has sent already: its headers name `Sec-Fetch-*` or `Upgrade-Insecure-Requests`, which no
  unsent request has. A WebKit without them (iOS before 16.4) asks the client, as before.
- **File inputs** ask `WebClient.onFileChooser` (macOS has the hook iOS lacks); paths or `file:` URIs come back.
- **The native library** is in the library's jar, built on a Mac (universal, arm64 and x86_64; the x86_64 half has never
  run) and signed ad hoc. On first use it is extracted to `~/Library/Caches/dev.supermux.webview/<version>/<sha-256>/`
  (`-Ddev.supermux.webview.cacheDir` names another directory), checked against the jar's own bytes, so an app packaged
  by Compose's mac packaging, which re-signs the libraries inside jars, still loads it.
  `-Ddev.supermux.webview.macLibrary=<path>` loads another build of it. It loads in the background, started when the
  runtime is made: no composition waits for it.
- **Release builds.** Compose Desktop's ProGuard step (`packageRelease*`, `createReleaseDistributable`, `runRelease`)
  reads no rules from library jars, and shrinking removes `MacGlue.onEvent`, which only the glue calls, by name: every
  engine is then `Unavailable(NativeLibraryFailed)`, with a message that names the rule. Add the library's rules
  (`src/jvmMain/resources/META-INF/proguard/webview-compose-desktop.pro`) to the app's build, as `webview-sample` does:

  ```kotlin
  compose.desktop.application.buildTypes.release.proguard {
      configurationFiles.from(project(":webview-compose").file("src/jvmMain/resources/META-INF/proguard/webview-compose-desktop.pro"))
  }
  ```

  On this repository's Gradle 9.7, Compose 1.12.2's ProGuard runner fails before ProGuard runs
  (`getStandardOutput(...) must not be null`), after it has written its configuration, so
  `webview-sample/device-checks/mac-release-check.sh shrunk` runs ProGuard 7.8.0 on that configuration itself. The app
  Compose packages (`createDistributable`, unshrunk) runs as it is (`mac-release-check.sh packaged`).
- **Windows and backgrounds.** A page needs a Compose window or dialog (`ComposeWindow`, `ComposeDialog`); a
  `ComposePanel` inside a Swing frame isn't supported yet. A transparent page (`transparentBackground`) shows the window's
  backing under it, not the Compose content behind it.
- **Limits.** An ancestor drawn with alpha below 1, or into an offscreen layer, hides the page: the hole clears only
  that layer. Compose's own text field keeps showing its caret while the page has focus. The engine's test hooks
  (`MacEngineTesting`, the glue's `test*` functions) ship in the library. The VoiceOver bridge can drop out if
  key-value observation gives AWT's view its own class back; it is put back when a page next attaches to the window.
  While the GPU is busy (WebGL pages on screen, or a load average of 30 and more on the M1), AppKit and skiko present a
  frame or a few apart: a scrolled page runs a few frames ahead of its hole, so the hole's trailing band shows the
  page's background (the backdrop, under Moving pages) rather than its content, and its leading edge is under the
  canvas until the hole catches up; Compose more than a quarter second behind would show Swing's grey past the
  backdrop. Or, for a frame, what Compose draws behind a page that is shown again.
- **Running it,** on a Mac, from `apps/`: `webview-sample/device-checks/mac-engine-checks.sh [check,...]` (the checks,
  in a window), `MEASURE=1 webview-sample/device-checks/mac-engine-checks.sh` (the numbers), `LAB=1` with the same script
  (the perf lab's runs), `webview-sample/device-checks/mac-sample.sh manual` (the sample for a person),
  `webview-sample/device-checks/mac-release-check.sh shrunk|packaged` (a release build), and
  `webview-sample/device-checks/mac-cleanup.sh` (what the runs left). Each starts the app in the logged-in session as a
  transient LaunchAgent, since an SSH session has no window server. Each run has a directory of its own,
  `<copy>/runs/<run>/`, beside the copy's `apps/` (its logs, frames, and the glue it extracts), and a LaunchAgent label
  naming the copy and the run, so runs from other copies of the tree or other sessions are never touched;
  `mac-cleanup.sh` stops only what its own runs started, and deletes only their directories, never anything in
  `~/Library`.

| Measured 2026-10-30, MacBook Air M1, macOS 26.6.2, 2×, 60 Hz | Result |
|---|---|
| The engine's checks in the sample's window | 47 checks: `CHECKS DONE passed=47 failed=0` on 2026-11-01 with the backdrop under moving pages (load 5.5–9). Before it, 46 checks: `CHECKS DONE passed=46 failed=0` on the final tree, under a load average of 445–469. In the run before it (load 23–139), 43 of 46, and the 3 that failed there 3 of 3 right after: two handoffs, where a copy showed what Compose drew a frame before (its blue box not yet over the page; the grey behind a page shown again), and ⌘ keys while another app was in front. On the merged tree (2026-10-30, load 7–16): 45 of 46, then 46 of 46; the one miss was the list scroll, where in 2 of 181 copies the page trailed its place by about 37 px (the presentation skew under [Limits](#macos)), 3 of 3 right after. On the final tree (2026-10-31, load 6.5–9): 45 of 46, the miss a check whose copy of the window caught Mission Control opening mid-check (macOS's log shows the gesture), 1 of 1 alone right after. Before the review's fixes, 41 of 41 twice (load 6.8–9.5) |
| The review's findings, on the library before its fixes (`ff96c3e4a`) → after | the window 150 dp shorter: the page's marker left its slot → in its slot shorter, taller and zoomed, a press on each side of its top edge landing on that side. An embed grown by 8 × 60 CSS px under the runtime's spring: 147 of 270 frames magnified and refusing presses, the cover drawn 88 times → 0 of 255 (and of 154 in the final run), never covered, presses taken throughout, no light strip (12 of 249 frames showed WebKit's white where it grew, before that part showed the page's background). A press on the page: 1 click on the clickable under it → none. A page half out of its scroller: page B's area all page A's blue, 162,880 px → 0 of it. The app's density at 1.25 times the screen's: a 320 × 240 CSS px viewport in a 400 × 300 pt hole → 400 × 300. A window resized, with the cover: 84 of 119 copies with the page's marker magnified, out of place → 0 of 174 |
| A pane dragged, a window resized (3 s each), without the cover | the page's content trailing inside its view: its marker out of place in 3 of 95 copies dragging the pane, 0 of 97 resizing the window; WKWebView's light strips in the slot in 6 (up to 62 px) and 12 (up to 16 px) (the final run; the one before: 15 of 100 and 0 of 172, strips in 24, up to 14 px, and 28, up to 10 px). The runs before the fixes: strips in 52 of 179 and 43 of 180. The window's backing in none |
| The same with the cover, and a list scrolled as the engine runs it (no cover for a move) | pane 0 of 122 (the cover drawn 119 times), window 0 of 97 (49 times), list 0 of 181, the cover never drawn (the final run; the one before: 0 of 181, 172 times; 0 of 174, 87 times; 0 of 181): no marker out of place, no strip, no backing. One list run under load 31–53: 3 of 180 copies with the page a few frames ahead of its hole |
| The chat scrolled by the trackpad (a user's report, 2026-11-01: a white area in the previews; load 5.5–13) | Trackpad gestures with their momentum, posted as AppKit gets them, the window copied as fast as it can be. The sample's chat (49 previews, 9 gestures a pass): light rows in a dark preview in 258 of 4399 and 283 of 4405 copies without the backdrop, the band at the trailing edge of each live preview, `#EEEEEE` (Swing's), up to 128 pt in a 4000 pt/s flick: the view three layouts ahead of the hole in Compose's frame, WebKit's drawing the whole view; with the backdrop 0 of 4806 and 0 of 4851. The trackpad check (12 dark three.js and Canvas previews in rounded cards, a drag and three gestures with momentum): light bands across the column in 406, 371 and 413 of about 2100 copies without the backdrop (up to 72 pt), with it 11 of 2219 while a page that left the list took its backdrop along (the band at the list's edge), then 0 of 2180 and 0 of 2278 |
| The strips' colour, without the cover, on a deep blue page | light strips in 43 of 169 copies with WebKit's under-page colour forced white, 37 of 145 with WebKit's own, 49 of 175 with the page's own blue: they are WKWebView's own white, not the under-page area, so its colour is left as it was |
| A release build | shrunk by ProGuard 7.8.0 with the library's rules (54 jars, 31 MB): 5 of 5 checks (an engine made, a page committed on its origin, the bridge both ways, a first paint, a press); without the rule, 0 of 5, every engine `Unavailable` with a message naming it. The app Compose packages (`createDistributable`, signed ad hoc, its runtime bundled), run by its own launcher: 5 of 5 |
| The handoff from the cover, five rounds of three scenarios, light and dark | 420 frames judged (14 around each lift), 0 differ from the page; a copy p50 6.5–12.0 ms, max 19.6 ms (load 6–9) |
| First paint from a warm engine | p50 90.1 / p90 98.9 ms; to the commit p50 51.3 ms, after it p50 39.9 ms (the best of three runs; their medians 90.1–94.9 ms; load 8–13) |
| The bridge's round trip | p50 0.2 / p90 0.5 ms (three runs: p50 0.2–0.3 ms) |
| One snapshot at 2× (640 × 960) | p50 3.0 / max 6.0 ms (three runs: p50 3.0–3.8 ms) |
| The perf lab, 200 messages in a `LazyColumn`: the Reading and Scroll tests with web views, then with still images | three runs, load 3–18. Reading, web views: 2395–2399 frames, p50 16.7 / p95 17.6–17.7 ms, 1–3 dropped; Scroll, web views: 1765–1768 frames, p50 16.7 / p95 17.5–17.7 ms, 1–5 dropped; Reading, images: 3226–3236 frames, p50 16.6–16.7 / p95 18.2–18.4 ms, 1–5 dropped; Scroll, images: 2358–2359 frames, p50 16.7 / p95 18.5–18.6 ms, 1–3 dropped. Peak live 4 of 8 (Reading) and 3 of 8 (Scroll), at most 11 engines in existence of 11; the snapshot cache 4.2 MB of 128 MB at the end |

The last five rows are from before the review's fixes, and haven't been measured since.

The layout test (2026-10-30, the same Mac, under load 10–28): with the page under the canvas, 7 of 7 clicks went to the
right side, among them 3 px inside and 6 px outside a menu's edge; typing went to whichever side had focus; ⌘Z reached
the page only through the key monitor, and VoiceOver only through the bridge; the page trailed its moving slot by
10–17 px in a list, 25–33 px dragging a pane, and up to 358 px resizing the window. A page above the canvas, with
cut-outs, passed and failed the same rows and trailed as far (15–16 px, 18–29 px, up to 386 px), but hid the overlays'
shadows, so the tie went to the page under the canvas.

**By hand** (the manual checklist, not done yet), in the sample from `mac-sample.sh manual`:
- Hover and the cursor under a menu, a dialog, and a page's cover: none of the page's hover states, nor its I-beam or
  hand, shows under them; Compose's own hover does; the pointer moving off the page onto a menu gets the arrow. Events
  posted to the window can't drive the tracking areas these need, so no check can.
- Full screen (the green button), the zoom button, and the window moved to a screen of another scale: each page in its
  hole, a press on each side of its edge landing on that side.
- A window resized by its edge, and a pane dragged: the cover while it moves, no white, the page live once it stops.
- A dark page in an embed that grows as it streams: no light flash under its new lines.
- Trackpad momentum and pinch in a pane; the wheel and the trackpad over an embed scroll its list; the sample's chat
  flung hard: no light band at a preview's edges (the gestures the trackpad check posts are AppKit's, not the
  trackpad's own).
- Turkish and Japanese input methods in a page's text field; ⌘C and ⌘V between the page and another app.
- VoiceOver reading a page.
- Menus and dialogs over a page: they show, take their presses, and a press on the page closes an open menu without
  reaching anything under the page.

## Linux

WPE WebKit 2.54 in frame mode (spec §7, "Linux: frames"): WebKit renders off screen through WPEPlatform objects of the
engine's own, into shared-memory buffers, and Compose draws the frames. Compose draws over the page, so menus and
dialogs need no cut-outs.

- **The runtime.** WPE isn't in the jar. The app ships `wpe-runtime-2.54.2-linux-x64` (57.9 MB as `.tar.zst`,
  75.5 MB as `.tar.gz`, 190.6 MB unpacked), built by `runtime/linux/runtime.sh` on Ubuntu 24.04, so it runs on 24.04
  and newer; x64 only.
  - **Its libavif decodes only.** It is Ubuntu's (1.0.4, the same source and flags) with dav1d its only codec.
    Ubuntu's own also links three AV1 encoders and a second decoder, which WebKit never uses: with them the runtime
    was 63.7 MB.
  - **Installing it,** one of:
    - unpacked, in Compose Desktop's packaged resources (`wpe-runtime/`), which the app's installer puts in place;
    - as one archive, `wpe-runtime.tar.gz` in the packaged resources (or `-Ddev.supermux.webview.wpe.archive=<file>`,
      `$WEBVIEW_WPE_ARCHIVE`; a `.tar.zst` needs the system's `zstd`). At first launch the engine unpacks it once into
      `~/.local/share/dev.supermux.webview/wpe-runtime/<version>-<hash>`: a folder per runtime, written to a temporary
      folder and renamed, under a lock (apps launched at once unpack it once), every file's SHA-256 checked against the
      manifest. It refuses `..`, absolute paths, hard links, symlinks that leave the runtime and files the manifest
      doesn't list. An index keyed by the archive's path, size and time spares hashing it at the next launch;
    - for development, a folder: `-Ddev.supermux.webview.wpe.runtime=<dir>` or `$WEBVIEW_WPE_RUNTIME`.

    No folder shared by every app and version is looked in.
  - **What it checks** at each launch: the runtime's manifest (version, ABI, architecture, every file's size), the
    helper processes there and executable, and the glue's SHA-256.
  - **Its libraries win** over a user's `LD_LIBRARY_PATH` (RPATH, not RUNPATH). The system's GLib, GStreamer, GL and
    fonts are used: a system without `libgstgl` (a stock Ubuntu Server 26.04) gets `Unavailable(NativeLibraryFailed)`.
  - **Without one,** it reports `Unavailable(NoWpe)` with the reason, never a crash.
- **The sandbox.** WebKit runs pages in bubblewrap's sandbox, with the runtime's folder mounted in it.
  - WebKit aborts the process where it can't start a sandboxed page, so the engine checks first. Without a `bwrap` that
    makes namespaces here, or without `/usr/bin/xdg-dbus-proxy`, it is `Unavailable` with the reason, unless the app
    passes `-Ddev.supermux.webview.wpe.sandbox=off`. The proxy is needed even with `DBUS_SESSION_BUS_ADDRESS` unset:
    GLib finds the session bus at `$XDG_RUNTIME_DIR/bus`, and WebKit asks it for the accessibility bus.
  - With the sandbox off, the opt-out stays WebKit's: the app's child processes don't inherit it.
- **Frames.**
  - A frame WebKit renders is copied, its damaged areas only, into memory of the engine's at the start of the next
    Compose frame, and acknowledged then. WebKit's damage is in buffer pixels, at scale 2 too.
  - So a page draws no faster than the window shows it, and a hidden one not at all.
  - Compose draws a Skia image over that memory, not a copy of it (`drawImage` would copy a mutable bitmap in full at
    every draw), as WebKit drew it: from the top left, cut to the view, so while WebKit catches up with a new size the
    page doesn't stretch. The memory grows in steps of 256 px, so a resize step by step allocates nothing.
  - At a fractional scale WebKit is told enough logical pixels to cover the view.
  - The draw lab (`MODE=draw linux-engine-checks.sh`): six 1600×1200 views with a spinner each, in Xvfb, software: the
    UI thread's CPU time per frame 49.1 ms before these changes, 42.9 ms after (OpenGL on llvmpipe; 49.3 and 42.7 ms
    without GL), 20 then 22.5 fps. The rest is Compose drawing and presenting a 4800×2400 window in software, which a
    desktop's GPU does instead; nothing has been measured on one. A GPU texture per engine updated in its damaged
    areas isn't reachable: Compose Desktop doesn't expose its `DirectContext`.
  - 1080p, in software (the feasibility test): copy p95 3.48 ms, upload p95 1.46 ms.
  - A recycled engine draws nothing until the next page has a frame of its own: never the last lease's page, nor the
    floor's white, under a transparent page's cover.
- **On a GPU.** The GPU gate measured WebKit's painting and the upload on a GPU once, in a container on the dev box:
  a weak, loaded integrated GPU (Ryzen 5 3500U's Radeon Vega Mobile, Mesa's `radeonsi`), the container capped at
  2 CPUs and 3 GB, the box's load 15 to 18. Software, then the GPU, back to back. It is **accepted as measured**,
  not a ship blocker:
  - a full-frame 1080p animation: 20.5 fps, 4.6× software's 4.4 (the bar is 2×);
  - copy + upload p95, full frame: 10.4 ms (the bar is 4 ms): the copy is the loaded CPU's, and this GPU's upload
    (p95 4.3 ms) is slower than software GL's;
  - a warm spare's first paint: 127 ms (the bar is 100 ms; software 171 ms);
  - a view's memory: 70.5 MB, against 150 MB in software.

  **NVIDIA's proprietary driver:** a page loads and paints on Ubuntu 26.04 with it and a GTX 1660 (`desktop`), the
  sandbox on and off. Its EGL has no `EGL_MESA_platform_surfaceless`; where Mesa's EGL isn't installed beside it,
  WebKit's source says the web process aborts. CPU painting is the way out ([Not built yet](#not-built-yet)).
- **Input.**
  - Pointer, wheel and keys reach WebKit in its logical pixels, and a press focuses the page.
  - Characters come from AWT, so layouts, dead keys and AltGr work. With Control held (AWT reports Ctrl+C as the
    character 0x03 on X11) a key is its letter, so WebKit's shortcuts work: select all, copy, cut, paste, undo, and
    the page's own Ctrl+F.
  - **The clipboard is the system's**, text only: a page's copy goes to AWT's clipboard (a Compose text field pastes
    it), and what AWT's holds reaches WebKit when a page's surface takes the focus and before a paste shortcut.
  - **A `<select>`** opens its menu in a popup under it: chosen with the mouse, or with Up, Down and Enter.
  - In an embed in a list ([embeds and panes](#sizing)) the wheel scrolls the list; in a pane, the page.
  - Not yet: an input method's composition, a colour picker (WPE has no hook for one), and Tab leaving the page (WPE
    has no `takeFocus`: focus stays in it).
- **The bridge** spans two script worlds, as on iOS:
  - `window.hostBridge` lives in the page's own world;
  - its channel lives in a world named `webview`, which page scripts can't reach;
  - messages from the app are checked against the page's origin there, when they arrive.

  WPE's script messages carry no origin, so a page message's origin is its document's committed URL's. Between
  WebKit's thread and the engine a page's messages are bounded (1,024, and 1 Mi characters), as the queue bounds them.
- **The handoff from the cover.** A document says `FirstPaint` once it has content: two animation frames after its
  first contentful paint or its load, or after 3 s with neither. Then it waits for the screen: a surface has drawn
  WebKit's frame of that moment, or a newer one, at the surface's size, and two Compose frames have passed, still at
  that size. So the cover lifts onto the page as drawn, never onto a frame of the view's earlier size.
- **Navigation.** WPE says whether a navigation is the main frame's only at its response.
  - A page's http(s) navigations are asked about there: `isMainFrame` is right, but the request has gone out.
  - No main-frame response gets past the client unasked but the engine's own navigation's (a load, restore, back,
    forward or reload of the app's): a response nothing announced is asked about too. So the page's own
    `history.back()` and `location.reload()` are asked about at their response: WPE can't tell a subframe's from the
    main frame's at the action.
  - Other schemes are asked about at once, as the main frame's: a `mailto:` link in a third-party iframe is asked as
    the main frame's, and so may open outside.
  - **Server redirects** of the engine's own navigations (a load, restore, reload, back or forward) are followed to the
    web without asking the client, until the page commits, as on the other engines (WebKit's `redirect` flag); a
    redirect off the web ends the load with `WebError.BlockedByPolicy`, once the main frame's load has failed by
    policy. WPE doesn't say which frame a redirect is for either: one an old document's frame makes while the app's
    load is under way is let through at its action too, and its subframe response is asked about as before.
- **Failed loads** follow iOS's rules ([iOS](#ios)): a page whose load failed saves no engine state, and `reload()`
  loads it again. An engine's own navigation the rules refuse is an error, never a page under its cover for good; a
  restore whose page is the floor loads the content instead (WPE answers a restore before its navigation is
  decided, so `restoreState` can't say false for it).
- **A page that hangs.** On screen, a page is asked every 3 s whether it answers; silent for 5 s (WebKit's own check
  needs input it waits on, and gets none from a mouse), its web process is ended and the engine reports `Crashed`,
  so the view gets a new engine, as on Android. A release ends the page's process, and a reset ends a page that
  doesn't answer instead of keeping it.
- **Dark mode** is the process's: the last view to set its theme sets every page's `prefers-color-scheme`.
- **Local content** is served as on iOS:
  - `Html` on `https://c<hash>.invalid`, and `Bundle` on `webview-bundle://<host>.invalid`, with relative URLs;
  - a missing file is an HTTP 404, and a file keeps its provider's headers;
  - an `Html` document has no back/forward entry: WPE keeps `load_html`'s document out of session history, so back
    from a page it navigated to skips it. (`load_alternate_html` adds the entry, but going back to it refetches the
    origin, which doesn't resolve.)
- **The page's requests:** dialogs, permissions, file choosers and new windows reach the client. WPE doesn't say:
  - which frame raised a dialog, so its `url` is the page's;
  - which origin asks for a permission, so it is the page's.

  A question still open when its view goes (it parks the page) gets the safe answer then, as on iOS.
  File choosers take local paths (`file:` URIs). Downloads go to `onDownload` and are never saved. WebKit asks about
  notifications only from a user gesture, and only in a persistent profile: in an `Ephemeral` one (the default for
  `Html`) it denies them without asking.
- **Profiles** are the app's own: `~/.local/share/dev.supermux.webview/wpe/<app>/` (and its `.cache` twin), the app
  named without doing anything: its jpackage launcher's name, else its main class with a hash of where its code lives,
  unless it passes `-Ddev.supermux.webview.app=<name>`.
  - `Default` and each `Named` profile is one network session, which all its engines share, its cookies kept across
    restarts (`cookies.sqlite`): a login in one view is every view's of that profile. A `Named` profile's session
    goes with its last engine. `Ephemeral` is a session per engine, in memory, wiped when the engine is given back.
  - One process of an app at a time has a profile: another gets `Unavailable(CreationFailed)` for it, as two of
    WebKit's network processes can't share a store.
- **Parked pages** keep their document and their last frame.
  - Hidden from WebKit, which stops drawing them, and muted: WPE can't suspend a page's media as iOS does, so a video
    plays on silently.
  - A view that adopts one shows it as it was. Its cover lifts once the view has drawn the page.
- **The inspector:** `inspectable` turns WebKit's developer extras on; WPE serves its inspector only where the app
  runs with `WEBKIT_INSPECTOR_HTTP_SERVER=127.0.0.1:<port>`, then at that address in a browser.
- **Memory pressure:** no hook. WebKit's own monitor sheds its web processes' caches; the pool hears nothing.
- **Running it.**
  - From `apps/`, on the dev box: `webview-sample/device-checks/linux-engine-checks.sh` runs the conformance checks
    on `desktop`, in a plain Ubuntu 24.04 (`SANDBOX=on` with WebKit's sandbox, in a container that lets bubblewrap
    make namespaces; `RUNTIME_ARCHIVE=` with the runtime as an archive). `MODE=lab` runs the sample unattended, with
    screenshots; `MODE=draw` the draw lab.
  - `webview-sample/device-checks/linux-sandbox-check.sh`: the engine's natives with the sandbox on, outside any
    container, on `desktop`'s own Ubuntu 26.04.
  - `native/linux/test.sh <WPE prefix>`: the glue's own tests, in the build image.
  - On a Linux desktop with the runtime in place: `./gradlew :webview-sample:run -Dwebview.sample.engine=wpe`.

## Windows

The Windows engine is Microsoft Edge WebView2 in visual hosting: each page renders into a Composition visual that
nothing shows on screen, Windows.Graphics.Capture copies what changed into memory, and Compose draws it like any other
image. Overlays, clipping, scrims and the pool work as they do on Linux. Input goes to the page through WebView2
(`SendMouseInput`), the keyboard by moving Win32 focus into it (`MoveFocus`), and WebView2's own popups (a select's
list, a date picker, the context menu) are its own windows, placed by it.

**Requirements:** Windows 10 1809 or later with Windows.Graphics.Capture, and the WebView2 Runtime 120 or later
(Windows 11 has it; on Windows 10, the Evergreen installer: https://go.microsoft.com/fwlink/p/?LinkId=2124703).
Without either, the factory says `Unavailable` with the reason (`NotImplemented` or `NoWebView2Runtime`, with the
version found), and `WebView` falls back as on every platform. x64; ARM64 is built, not yet run.

**Nothing to install with the app:** the glue (`webview_windows.dll`, 527 KB, the WebView2 loader and the C runtime
linked in, system DLLs only besides) is in the library's jar, for x64 and ARM64; at first use it is copied to
`%LOCALAPPDATA%\dev.supermux.webview\cache\<version>\<sha256>\`, checked against its hash, and loaded. A copy there that
changed is replaced, not trusted, and apps started at once share one copy.

**A release build shrunk with ProGuard** needs the library's rules (`webview-compose-desktop.pro`, as for macOS):
without them the engine says `Unavailable(NativeLibraryFailed)` and names the rule.

**Profiles:** one user data folder per app, `%LOCALAPPDATA%\dev.supermux.webview\webview2\<app>` (the app is
`-Ddev.supermux.webview.app`, else the packaged launcher's name, else the main class with a hash of where its code
lives: Linux's rule). `Default` is WebView2's default profile, `Named(id)` a profile of its own, `Ephemeral` an
InPrivate profile per engine, wiped at each reset. Two processes of the same app share the folder and see the same
`Default` and `Named` data (WebView2 serves both from one browser process); their ephemeral engines never share a
store. An InPrivate profile leaves a folder of 1 to 3 MB (its scaffolding; its data stays in memory), one per
ephemeral slot, and the slots are few.

**Differences from the other engines:**
- `restoreState()` brings back the page and its scroll, not its back list (WebView2 has no session state to save).
- Dark mode is per engine (`prefers-color-scheme` emulated per page).
- Hidden pages are throttled by Chromium and asked to use less memory; memory pressure (Windows' low-memory
  notification) asks every page not on screen. Desktop Windows has no signal that a process may be cached, so nothing
  calls the pool's `onBackgroundTrim()`; an app may, for a long minimize.
- A page can't take the keyboard by itself: only a press, a Tab into it, or `requestFocus()` moves it there.
- Browser shortcuts are off (no F5, Ctrl+R, Ctrl+P, zoom keys); F12 and Ctrl+Shift+I open DevTools when `inspectable`.
  An app's own shortcuts (`onPreviewKeyEvent` around the `WebView`) reach the app first.
- The context menu keeps editing items only.
- Downloads are refused, and the client told; new windows are the client's to open.
- History a page makes without the user's activation (a `pushState` from a timer, or from the app's `evaluate()`; a
  link the app clicks by script) is skipped by Back, as in Chrome and Edge: `canGoBack` doesn't count it, and
  `goBack()` (`navigator.back()`) passes over it. The user's own clicks make history as everywhere.
- Autoplay is per origin, not per engine: `mediaPlaybackRequiresGesture = false` is the profile's autoplay site setting
  on WebView2 (it asks nothing for a `play()` with no gesture), allowed for each origin the engine shows and back to the
  default once the last engine of the process that allowed it is released. So:
  - engines of one profile on one origin share it, whatever their own setting, and so do the app's other processes:
    one process's last release sets it back while another's page loads, and that page is allowed again at its next
    navigation;
  - a recycled engine keeps the origins of its earlier leases allowed until its release;
  - an app that is killed leaves it allowed in a persistent profile (`Default`, `Named`) until an engine that allows
    that origin is next released.
- SmartScreen (Microsoft's URL reputation check) is off: WebView2 sent every page's URL to Microsoft at its navigation,
  the app's own `https://*.invalid` content too, and a check still on its way when a page closed held WebView2's browser
  process 6 s from exiting, so the next engine, which needs a new browser once the last is going, waited for it.

**Frames, and what they cost:** WebView2 hands over every frame whole, so a page that animates costs a whole copy per
frame whatever changed: on a GTX 1660 SUPER, on the engine's own path, p95 4.29–4.72 ms at 1080p (the GPU gate's
three-copy chain took 2.3–2.6 ms at window size and 5.6–6.6 ms at 1080p), mostly off the UI thread. A page that doesn't
change costs nothing. A 1080p animation draws at 44–52 fps there (the gate), under §10's 60. Zero-copy into skiko's
Direct3D (Task 34) was tried and dropped: skiko can't hand a foreign D3D12 resource back to D3D11 in the right state
without native code against its bundled Skia.

**Measured:** without a GPU on the VM (WARP, software rendering; WebView2 156.0.4314.58), and on a GTX 1660 SUPER on
the engine's own path (2026-10-31), with what only the GPU gate measured before it (156.0.4314.56) marked so:

| Measure | Spec §10 | No GPU (WARP) | GTX 1660 SUPER |
|---|---|---|---|
| a warm spare's first paint, p50 | ≤ 100 ms | 71 ms (p95 81–97 ms) | 88 ms |
| a new engine's first paint, p50 | — | 218–228 ms | 200–215 ms |
| the bridge's round trip, p50 | ≤ 2 ms | 0.4–0.5 ms | 0.4–0.6 ms |
| memory a view (private bytes; working set) | ≤ 150 MB keeps 8 + 3 | 23 MB (51–55 MB), and about 70 MB for the browser, GPU and utility processes: 8 live and 3 spares | 24 MB, and 119 MB fixed (the GPU process about 45 MB of it) |
| a press: to the page's `pointerdown`; on screen | — | 26 ms (the engine's part 5 ms); 94 ms | —; 66.7 ms (the GPU gate: about 23 ms; 50.5 ms) |
| 1080p copy + upload, p95 | 4 ms | — | 4.29–4.72 ms (the GPU gate's chain: 5.6–6.6 ms; 2.3–2.6 ms at window size) |
| frames delivered; a 1080p animation | 60 fps | — | 88–97 %; 44–52 fps (the GPU gate) |
| frames dropped over 200 messages, web views against images | no more | Scroll 20–22 % against 27 %; Reading 33 % against 28 % | Scroll 1.0–1.4 % against 1.3 %; Reading 1.7 % against 1.1 %, and one run at 8.3 %, unexplained |

Without a GPU a quarter of the frames miss with images too; the Reading test's 5 points more with web views are pages
starting, painting and being snapshotted on that machine's UI thread. A press there spends 19.6 ms getting from the OS
to AWT (the JVM's input path) and 68.8 ms from the page's `pointerdown` to the screen (Chromium's frame, DWM, the
capture and Compose's frame, all in software); the engine's own part, from Compose to the page, is 5.2 ms. On the
GPU, web views match images in the Scroll test, and drop 0.6 points more in the Reading test, where one run of
several dropped 8.3 % for a reason not found.

**Not run:** §10's 4 ms at 1080p full frame and 60 fps (the numbers above: known limits on that card, every WebView2
frame arriving whole); a HiDPI screen (the VMs run at 100 %); a machine whose display runs on another GPU than skiko's (built for,
not run); Windows on ARM64 (the DLL is built, never run); touch and pen beyond mouse emulation; drag and drop into
pages (off); a screen reader; `ComposePanel` in a Swing window; zero-copy (tried and dropped, above).

**Running it,** from `apps/` on the dev box (the VM is `supermux-windows`, on `desktop`):
- `webview-compose/native/windows/winvm.sh build` builds the glue on the VM, runs its own tests (`glue_test.exe`) and
  fetches the DLL (`build arm64`: ARM64's); `winvm.sh gradle …` runs Gradle in Docker on `desktop`, with the DLLs
  fetched.
- `webview-sample/device-checks/windows-engine-checks.sh`: the glue built, the sample staged on the VM, and the
  conformance checks on its desktop (`SKIP_BUILD=1` and `SKIP_STAGE=1` reuse the last ones).
- `webview-sample/device-checks/windows-release-check.sh shrunk|unkept|packaged|cache`: the release build.
- `winvm.sh lab`: the perf lab, unattended, with screenshots (`-Dwebview.sample.labImages=true` on the images);
  `winvm.sh lab "-Dwebview.sample.lab=memory,firstpaint,bridge,press"`: the measures above.
- On Windows itself, with the DLL in `webview-compose/build/native/windows-x64/`:
  `./gradlew :webview-sample:run -Dwebview.sample.engine=webview2`.

## Android

The system WebView, one per engine, in an `AndroidView` under the Compose canvas (spec §7). Compose draws over the
page, so menus and dialogs need no cut-outs. Android 8 (API 26) and later.

- **What it needs.** A WebView with `WEB_MESSAGE_LISTENER`, `DOCUMENT_START_SCRIPT`, `VISUAL_STATE_CALLBACK` and
  `NAVIGATION_LISTENER`, and `MULTI_PROFILE` for `Named` and `Ephemeral` profiles. Without them, or without a WebView,
  views are `Unavailable(NoWebViewProvider)`, and its detail lists what is missing. `Url` content needs the app's
  `INTERNET` permission: the library declares none.
- **Creation.** WebView starts up once per process, its slow part in the background (`startUpWebView`). An engine is
  built two frames after a view asks for one, so building it never stalls the frame that shows the view's cover.
- **Its Activity.** A WebView keeps the window and display of the context it was built with. So an engine is built for
  the Activity that shows web views, never the application context (whose density is the phone's, even on DeX or XREAL
  glasses), and moves only between live Activities. An engine whose Activity is destroyed, or that shows on a display of
  another density, serves no further lease (it reports `isUsable` false, so it is never parked or adopted: its page
  stays for the lease it has), and an idle one goes at once. The density is checked again at every configuration change,
  so an Activity that declares `configChanges` and moves to another display retires its engines too. An Activity that a
  rotation or a fold recreates gets new engines: declare `configChanges`, as the sample does, to keep them.
- **Documents.** Every load, reload, restore and reset starts an epoch. A document gate decides which of WebView's
  callbacks speak for a document the runtime may hear: none until a marker posted at the boundary has run and the
  engine's own navigation has committed, as `NavigationListener` reports it.
  - WebView posts most callbacks to the main thread, in order: the commit, titles, the page's messages, JS dialogs,
    downloads. Those are judged in that order.
  - It makes others at once, from the browser's own task: console output, permission prompts, file choosers, new
    windows and the page's own navigations. A second navigation listener, on a direct executor, hears the commit at
    that moment, and from then on those speak for the new document. Console lines a page writes before the posted
    commit wait for it (64 at most), so the runtime hears the commit first.
  - Which is which was measured on WebView 156, from each callback's stack. Before the second listener, a page's
    `onload` console line could be lost (the phone's test of it failed), and an early prompt could get its safe
    default unasked.
  - **Server redirects** of the engine's own navigations (load, reload, restore, back, forward) are followed without
    asking the client, which decides no navigation the app starts; to `http(s)` only, and any other scheme ends the
    load. WebView offers them to `shouldOverrideUrlLoading` before the commit, when the gate is still shut: until
    the review of 2026-10-30, any URL that redirects (`/docs` to `/docs/`, a short link, a login page) failed.
  - **A load that only changes the fragment** keeps its document: it paints again at once (`FirstPaint` lifts the
    cover), and the bridge keeps talking to it.
  - **When the engine's own load fails without a commit** (a 204, a download, a cancelled load), or is still under way,
    `reload()` loads the content again (WebView would reload the page before it, or a reset's blank), `saveState()`
    saves nothing (WebView's history is the page before's, which a restore would show in this content's place), and back
    or forward start an epoch, so the page that comes back has its URL, title and bridge. Load progress is heard by
    document: not what an earlier one still loads after ours failed.
- **The bridge** is `addWebMessageListener` and `addDocumentStartJavaScript`, for the lease's bridge origins only;
  never `@JavascriptInterface`.
  - The shim and its channel live in the page's world: the engine uses none of WebView's isolated worlds.
  - The shim signs every message with its epoch's random token, so an earlier document's messages are dropped. Page
    messages come from the main frame only.
  - Messages to the page go through the reply proxy of the last message its main frame sent. WebView makes one per
    document, so a message reaches that document only, and only if its origin is the one the message is for, and
    only while the gate hears that document's token.
  - There is one listener. A mid-lease change of the bridge's origins or of JavaScript that it covers keeps it, so
    the current page keeps its channel; the engine drops what origins taken off the list send. One it doesn't cover
    replaces it at the next load, as the document goes: WebView drops what the documents of a removed listener send,
    and a second listener added to a WebView with a document crashed WebView 156 natively.
- **Local content** is served from `https://<origin>.invalid/` through `shouldInterceptRequest`, with `no-store` and
  `nosniff`. Any other request to a `.invalid` host is a 404, and so is a bundle file its provider hasn't given in
  10 s.
- **Profiles.** `Default` is WebView's default profile, and `Named(id)` its profile `wvc_named_<id>`. Every `Ephemeral`
  engine has a profile of its own, so one engine's wipe never touches another's data. It is wiped whole when the
  engine is reset or released, and the reset waits for it: `WebStorageCompat.deleteBrowsingData` (cookies, every kind
  of storage, Cache Storage, service workers, caches), then the cookies again and the geolocation grants. A WebView
  without `DELETE_BROWSING_DATA` can't, so there an `Ephemeral` reset fails (the pool destroys the engine) and a
  released engine's profile isn't reused. WebView can't delete a profile its process has used, so wiped profiles are
  reused, and an earlier process's are deleted.
- **Reset** replaces the lease's WebView with a new one: no document, no history, no scripts or listeners. It gives
  every request still open its safe default (and the client's question about it is cancelled, as whenever a
  document goes), lets the page's new windows go, and wipes an `Ephemeral` profile (5 s at most). It starts an epoch
  before and after, so nothing of the last lease is heard, and the focus changes the lease left queued are dropped.
  A release, or the renderer's death, ends it at once. If any of it fails, it returns false, and the pool destroys
  the engine. It used to load `about:blank` and wait for its commit instead.
- **State.** `saveState` keeps up to 64 KB of WebView's own state (history, scroll, form data), for that content only.
  WebView appends restored history to a used WebView's, so a restore needs one that has never navigated: the reset
  built it, so a restore into a recycled engine, in the frame a view scrolls back, builds none.
- **Hidden and parked pages** ([above](#the-pool-and-memory)) keep their WebView and document, paused with `onPause`:
  their animations, geolocation and media stop, and the page is told it is hidden. Their JavaScript keeps running:
  `pauseTimers()` would pause every WebView in the process. A parked page's WebView is out of any window. A view that
  adopts it hears its URL, history and title at once, and its first paint once it has drawn the page in its new place.
- **The handoff from the cover.** A WebView is invisible after its creation and after a reset, until its first
  document's commit has drawn (a visual state callback); from then on it draws under the cover. A document says
  `FirstPaint` once it has content (its first contentful paint or its load event, or 3 s with neither) and has drawn
  it in place (a visual state callback, then two frames): about 30 ms after the commit in all. Between documents,
  WebView keeps the old page on screen.
- **Snapshots** are taken only once the view has been still for 500 ms, never while its list scrolls or flings.
  With nothing marked over the page and the view wholly on screen, a snapshot is a `PixelCopy` of the window, made on
  the render thread (it has WebGL and video too). Otherwise the page draws itself into a bitmap, on the main thread, at
  the screen's density, so the app's UI over the page is never in it ([Snapshots and page
  memory](#snapshots-and-page-memory)). Every draw counts toward the draw budget, a document's first at a third of
  its time, and good draws raise a lowered budget again. A draw all of one colour, every pixel read off the main
  thread, is discarded: a grid of samples took a sparse page for a blank one.
- **The page's requests.**
  - Navigations the page starts reach `onNavigation` synchronously (`shouldOverrideUrlLoading`). A new window is
    caught by a throwaway WebView, which hands its URL to `onNewWindow`, in the epoch that opened it only: a window
    that navigates after a load or a reset reaches no client. Every boundary, reset and release lets the catchers go,
    and one survives the renderer's death, as the engine does.
  - A request still open when its document goes, or when its view goes (it parks the page: the client changes), gets
    its safe default, and the client's coroutine asking about it is cancelled, so the app's dialog or prompt goes too.
  - Dialogs (`alert`, `confirm`, `prompt`, `beforeunload`), file choosers, and the camera, microphone and geolocation
    prompts reach the client. Geolocation grants aren't kept. A file chooser's answer is a list of URIs: the sample's
    are the system document picker's `content:` URIs.
  - Downloads go to `onDownload`, and WebView saves nothing.
  - Certificate errors are never bypassed, mixed content is never allowed, and `file:` and `content:` access is off.
- **Dark mode.** `prefers-color-scheme` follows the lease's theme through the WebView's context, mid-lease too.
  WebView's algorithmic darkening is off.
- **Input.**
  - The WebView is a `NestedScrollingChild3`: what the page can't scroll of a drag goes to the list around it, so a
    chat scrolls over its embeds, and so does the gesture's fling, if its drag reached the list. A flick the page took
    itself (an inner scroller, a carousel, a canvas) doesn't fling the list.
  - A page keeps every gesture that starts on it, and nested scrolling is vertical only: a horizontal pager or a
    swipe-to-dismiss around a web view can't take a horizontal drag that starts on the page.
  - The keyboard's inset reaches the WebView unconsumed, and WebView 139 and later shrink the page's visual viewport
    by it. An older WebView's view is padded instead.
  - System back goes back in the page while the page has focus and can go back; otherwise the app's back runs.
- **One renderer** serves every WebView of the app, so one page's death crashes every live view
  ([Failures](#the-pool-and-memory)), and those on screen come back by themselves. A page that blocks the renderer is
  terminated once WebView reports it unresponsive (5 s), and takes the other live views with it, and also every
  WebView of the app that isn't the library's (the app's own, an SDK's), which then get `onRenderProcessGone`: one
  whose client doesn't handle it takes the app down, as WebView does with any renderer death.
- **The inspector** switch is the process's: while any lease asks for `inspectable`, every page of the app is
  inspectable.
- **Memory warnings** reach the runtime by themselves, while any of the runtime's web views is shown. Only the running
  levels (`TRIM_MEMORY_RUNNING_LOW`, `_CRITICAL`) and `onLowMemory` are memory pressure (`runtime.onMemoryPressure()`).
  A cached process (`TRIM_MEMORY_BACKGROUND` and past it, which Android 14+ sends at every app switch) gets a light
  trim (`runtime.onBackgroundTrim()`): the decoded snapshots go, decoded again from what the cache stores, and so do
  the spares and the parked pages, each saved first; the views keep their pages, the cache its snapshots and the pool
  its whole budget. An Activity starting after the app's UI went away ends any pressure window.
- **Running it,** from `apps/` on the dev box, with the phone reachable over adb. Gradle runs on the Mac
  (`webview-mac-gradle`), and the APKs are copied back.
  - `webview-compose/device-checks/android-instrumented.sh [class]`: the instrumented tests. The test APK is
    installed, run with `am instrument`, and removed, even when the run is interrupted. A locked phone is BLOCKED
    (exit 2), before the build and again before the install. The test APK allows cleartext to 127.0.0.1 and
    localhost only, for its own `TestServer` (redirects, Cache Storage and service workers on a secure origin).
  - `webview-sample/device-checks/android-sample.sh [reading|scroll|fling] [fake]`: the sample, installed and
    started, on the fake engine with `fake`. With a run, it prints the run's `perf-lab` line. The app stays installed.

| Measured on the user's Galaxy Z Fold7 (Android 16, WebView 156.0.8078.47) | Result |
|---|---|
| The instrumented tests, the whole suite in one run | `OK (90 tests)` at `2df7ae549` (2026-10-31): the review's fixes, their follow-ups and the fixes the phone's runs led to. Earlier, `OK (58 tests)` at `bf4fd43b6`; a run on the review's fixes crashed natively in WebView 156 when the engine added a second message listener to a WebView showing a page, and the engine keeps one now |
| A restore into a recycled engine, in the frame a view scrolls back | no WebView built (the reset built it): 10.1 ms on main |
| Snapshots during a fling | none taken; the main thread around it p50 11.6 ms, max 14.2 ms |
| The callbacks WebView makes at once (`EngineEarlyCallbacksTest`), five fresh pages each | console lines from an inline script and from `onload`, an inline `alert` and `confirm`, an early camera prompt: each heard, five runs in a row. Before the second navigation listener, the console test failed |
| The handoff from the cover (`HandoffFlashTest`), every frame around the cover's lift | a cold activation, a parked page adopted and a page hidden then shown, light and dark: five runs in a row, and no frame differs from the page. Before, a cold activation showed one frame of the page's bare background ([more](#snapshots-and-page-memory)) |
| Covers on the cover screen (1080 × 2520 at 2.625), a 480 dp embed at the screen's density, stored and decoded, against the screen | an agent demo: mean 0.63 of 255, p99 7, 90 KB stored; an article: 0.48 and 7, 169 KB, against 5.4 MB of pixels. The page's own draw: 13 to 21 ms (p50), 14 to 28 ms for the whole screen ([more](#snapshots-and-page-memory)) |
| The perf lab, 200 messages in a `LazyColumn`, a debug build, on the cover screen (120 Hz) | Reading test (2026-10-29): 4783 frames, p50 8.3 / p99 8.5 ms, 31 dropped; peak live 4 of 4, peak parked 3; all 200 covers kept, in 4.9 MB. Scroll test on this tree (2026-10-31): 3795 frames, p99 8.3 ms, 3 dropped; before pages were kept alive (2026-10-29): 3825 frames, p50 8.3 / p99 8.3 ms, 6 dropped, peak live 2 of 4, and on the fake engine 3836 frames, 8 dropped ([The perf lab](#the-perf-lab)) |

**By hand.** A 17-item checklist went to the user, to be done on the phone: the perf lab's runs watched, a drag over
a demo, a plugin's button, memory pressure; dialogs, the file picker, Turkish and Japanese typing, a menu and a dialog
over the page, the bridge both ways, back, a link out, `<select>`; dark mode, folding and rotating, DeX or XREAL, and
the inspector. Its answers aren't in yet.

**Not run yet:** the manual checklist ([By hand](#android), above), with the user; TalkBack; an emulator, another
phone, a tablet; Android below 16, a WebView older than 156 (and with it the keyboard's older path), or one
lacking a feature the engine needs; a renderer killed by the OS rather than terminated, and the OS's own
`onTrimMemory`; first paint and the bridge's round trip, timed; an app's release build.

## Building and testing

```
./gradlew :webview-compose:check                # on a Mac: every suite below that runs there, Android lint, the ABI check
./gradlew :webview-compose:jvmTest              # common and JVM tests; Compose UI tests on the fake engine
./gradlew :webview-compose:wasmJsBrowserTest    # common tests, the bridge shim and the web engine, in headless Chrome
./gradlew :webview-compose:testDebugUnitTest    # the common tests on Android's host JVM (those needing a real Bitmap skip)
./gradlew :webview-compose:updateKotlinAbi      # on a Mac: writes the API dump in api/ again, after a deliberate change
./gradlew :webview-compose-testing:check        # the testing module: its test, lint and its own API dump
./gradlew :webview-compose:compileDebugKotlinAndroid :webview-compose:compileKotlinIosSimulatorArm64
./gradlew :webview-sample:jvmTest               # the perf lab's scripted runs
./gradlew :webview-sample:wasmJsBrowserDevelopmentRun   # the sample in a browser on the iframe engine (localhost)
./gradlew :webview-compose:iosSimulatorArm64Test        # on a Mac: the common tests and the iOS engine's, in a simulator
webview-sample/device-checks/ios-engine-checks.sh       # on a Mac: the iOS engine's conformance checks, in the sample app
webview-sample/device-checks/mac-engine-checks.sh       # on a Mac: the macOS engine's checks, in the sample's window
webview-compose/runtime/linux/runtime.sh image && webview-compose/runtime/linux/runtime.sh build   # the WPE runtime, built on desktop (about 3 hours)
webview-sample/device-checks/linux-engine-checks.sh     # from the dev box: the Linux engine's conformance checks, on desktop (SANDBOX=on)
webview-sample/device-checks/linux-sandbox-check.sh     # from the dev box: the Linux engine with its sandbox on, outside any container
webview-compose/native/linux/test.sh <WPE prefix>       # in the runtime's build image: the Linux glue's own tests
webview-compose/native/windows/winvm.sh build [arm64]   # from the dev box: the Windows glue built on the VM, its tests run, the DLL fetched
webview-compose/native/windows/winvm.sh gradle TASK...  # from the dev box: Gradle in Docker on desktop, with the fetched DLLs
webview-sample/device-checks/windows-engine-checks.sh   # from the dev box: the Windows engine's conformance checks, on the VM
webview-sample/device-checks/windows-release-check.sh shrunk   # the same in a release build (also unkept, packaged, cache)
webview-compose/device-checks/android-instrumented.sh   # from the dev box: the Android engine's tests, on the phone
webview-sample/device-checks/android-sample.sh scroll   # from the dev box: the sample on the phone, and a perf-lab run
```

The Apple targets build on a Mac only; elsewhere their tasks are skipped. A Kotlin/Native test process isn't an app, so
no page loads in it: the iOS tests cover what needs none, and the sample app runs the rest. A library test task that
runs past 10 minutes, or a sample one past 15, fails instead of hanging.

On a Mac, `jvmTest` also builds the macOS glue (it needs the Xcode command-line tools) and runs the tests that load it;
elsewhere the jar has no glue, those tests are skipped, and the macOS engine reports `Unavailable`.

The Windows glue is built on the VM (`winvm.sh build`: MSVC Build Tools 17.14, Windows SDK 10.0.26100, the WebView2
SDK), which fetches it into `webview-compose/build/native/windows-<arch>/`; the jar carries what is there. Without it
the Windows engine reports `Unavailable(NativeLibraryFailed)`, and `jvmTest` runs the engine on a fake page either way.

## Not built yet

- On Windows:
  - A HiDPI screen: the checks ran at 100 % only.
  - Windows on ARM64: the DLL is built, never run.
  - Pen pressure and touch (`SendPointerInput`): mouse emulation only.
  - Drag and drop into pages: off.
  - Accessibility: WebView2's UI Automation tree isn't bridged to Compose's, so screen readers see no page.
  - A `ComposePanel` inside a Swing frame.
  - Zero-copy into skiko's Direct3D: each frame is copied GPU → staging → memory → skiko's texture. Tried and dropped
    (Task 34): skiko can't hand a foreign D3D12 resource back to D3D11 in the right state without native code
    against its bundled Skia.
  - A machine whose display runs on another GPU than skiko's.
  - Autoplay per engine: it is the profile's per origin ([Windows](#windows)). An app that mixes both settings on one
    profile and origin, or runs two processes with autoplay, would need it; so would resetting, when the first process
    opens the folder, the grants a killed app left.
  - The follow-ups in the review notes, each with what would start it: the UI thread's copy off it, damage of the
    engine's own, the press's parts, an exclusive data folder per process.
- A memory-pressure hook in a browser: browsers have no signal for it.
- On Android:
  - The bridge's channel in one of WebView's isolated worlds (`JS_INJECTION_IN_FRAME_AND_WORLD`): the shim and its
    channel share the page's world, and the reply proxies keep each message to its document.
  - Service workers and `Range` requests for `Bundle` content. A service worker's script never reaches
    `shouldInterceptRequest`: it goes to the network, where `.invalid` never resolves, and fails. A file is served
    whole.
  - A restore into a recycled engine that keeps its WebView: it builds a new one, since Chromium appends restored
    history to a used WebView's.
  - Engines that outlive their Activity: when it is recreated, its engines are built again (`configChanges` avoids
    it).
  - Horizontal drags for the app around a page: nested scrolling is vertical only, so a horizontal pager or a
    swipe-to-dismiss can't take a horizontal drag that starts on the page, which keeps every gesture it starts.
- On Linux:
  - IME composition: Compose's text input can't drive an editing state that lives in WebKit yet.
  - A colour picker: WPE has no colour chooser hook (WebKitGTK's `run-color-chooser`), so `<input type=color>` opens
    nothing.
  - Tab leaving the page: WPE has no `takeFocus` (WebKitGTK's), so keyboard focus can't leave a page by Tab.
  - Which frame a `mailto:`, `tel:` or `sms:` link is in: WPE says only at a response, and these have none, so a
    third-party iframe's is asked about as the main frame's.
  - WebKit's cache model for small embeds (`DOCUMENT_VIEWER` against the default's back/forward cache and memory
    caches in every web process): not measured, so the default stays.
  - A memory-pressure hook for the pool (Linux's PSI or cgroup events).
  - An arm64 runtime.
  - Compose's draw on a GPU, measured in a real desktop session, and the GPU gate measured again on a desktop's GPU.
  - Cheaper uploads for full-frame animations: a texture per engine updated in its damaged areas, which Compose
    Desktop's public API doesn't reach (its `DirectContext`), or a capped frame rate while the whole frame repaints.
  - CPU painting for machines whose GL is software (NVIDIA's driver without Mesa's EGL, VMs): the glue has the switch
    (`INIT_CPU`), and no setting reaches it yet.
  - Accessibility: the runtime is built without ATK, so screen readers see no page.
- On the web:
  - `WebContent.Bundle`. Each bundle needs an origin of its own, which the app's server has to provide.
  - A page's own back and forward.
  - A packaged shim for servers that put the bridge into `Url` pages. Until one exists, `Url` pages don't chain scrolls
    to the list around them either.
- On macOS: the web engine's scroll chaining for embeds (an embed taller than its `max` can't be scrolled inside with
  the wheel), trackpad swipe navigation, a `ComposePanel` inside a Swing frame, and the manual checks' results.
- A disk cache for snapshots.
- Batching bridge messages per frame, if real engines' numbers show it is needed.
- Publishing: neither the library nor its testing module is published to a repository yet.
