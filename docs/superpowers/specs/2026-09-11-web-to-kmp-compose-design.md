# Web client → Kotlin/Wasm Compose, retire Vue — Design (2026-09-11)

- **Date:** 2026-09-11
- **Status:** Approved by the user (approach + both design parts, 2026-09-11).
- **Area:** KMP (`apps/shared`, `apps/ui`, new `apps/web`), broker static serving
  (`src/channels/web/static-serve.ts`), build/CI (`scripts/build-binary.sh`, `Dockerfile`,
  `.github/workflows/ci.yml`, `release.yml`), tests (`tests/ui/core-journey.spec.ts`), and the
  deletion of `src/web-app` (Vue 3 PWA).
- **Goal:** The browser client becomes the same Compose Multiplatform app that already ships on
  Android, iOS and desktop (`:ui` on `:shared`), compiled to Kotlin/Wasm and served by the broker at
  the same URL. The Vue PWA is deleted. This completes the 2026-09-03 "Compose everywhere" ruling:
  one app, five hosts, ≥90 % shared.

## 1. Decisions made during brainstorming

| Question | Decision |
|---|---|
| Approach | **Big-bang host.** Add `wasmJs` to `:shared` + `:ui`, new thin `apps/web` host, swap the served bundle, delete Vue. Rejected: strangler (two runtimes on one page) and a Kotlin/JS DOM rewrite (no UI sharing). |
| PWA install + Web Push | **Keep.** `sw.ts` (no fetch handler, by design) and the manifest move to `apps/web` unchanged. Subscription is the web actual of the existing `PushRegistrar` seam. |
| First-run onboarding in the browser | **Keep, port to `:ui`.** The 5-step wizard and the secretless TOFU `POST /pair/claim` exist only in Vue today and are the sole first-run path for a fresh broker. |
| Auth | **Cookie session stays.** The broker accepts cookie OR bearer on the same `/ws`; the web `SecureTokenStore` holds no bearer and `BrokerApi` omits the header when the token is blank. |
| Editor + LSP | **Keep.** The committed `cm6.js` bundle (already shared byte-identical by Android/iOS/desktop, LSP client included, driven by Kotlin `LspBridge`) is mounted through `HtmlElementView`. The web-only symbol-locations panel is dropped (natives never had it). |
| Displays | **VNC now, scrcpy later.** `VncClient` is commonMain; web gets a Skia `VncFramebuffer`. `Caps.scrcpy=false` (WebCodecs H.264 is a follow-up spec). |
| Browser floor | **WasmGC only:** Chrome 119+, Firefox 120+, Safari 18.2+ / iOS 18.2+. Compose's JS compatibility mode is not built now. |
| Dropped web-only features | Customisable keyboard-shortcuts page (`:ui` uses fixed chords), the forge repo omnibox in the launcher (natives never had it), `xr-probe-cm-entry.mjs` (orphan diagnostic). |
| Terminal | xterm.js (+ WebGL addon) in an `HtmlElementView`, fed by the shared Kotlin `TerminalClient` and the shared `PredictiveEcho` engine — the same shape as iOS's SwiftTerm factory. |
| Order | Five plans: wasm targets → seams/Platform → terminal/editor/VNC → wizard/push/PWA → tests/CI/delete Vue. |

## 2. Baseline (measured 2026-09-11, main sources)

| Piece | Size | Notes |
|---|---|---|
| `src/web-app` (Vue) | 27.7 k lines, 22 views, ~65 components, 63 `bun:test` files (pure logic only) | Builds into `src/channels/web/static` via Vite. |
| `:shared` commonMain | 13.5 k | 5 `expect` seams: `SecureTokenStore`, `ZlibInflater`, `openSealedPush`, `localUtcOffsetMs`, `formatLocalDateTimeMedium`. |
| `:ui` commonMain | 48.8 k | 12 `expect` declarations in 8 files (see §4). Zero `java.*` / `android.*` imports. |
| Hosts | Android 6.5 k · desktop 8.5 k · iOS 1.8 k Kotlin + Swift glue | `apps/web` targets the iOS size class. |
| Deps | Ktor 3.5.0, coroutines 1.9.0, serialization 1.7.3, jetbrains-markdown 0.7.6, coil 3.4.0, Nav3 1.1.1, navigationevent 1.0.1, composemediaplayer 0.10.0, atomicfu 0.29.0, CMP 1.11.1 (`ui-backhandler`, `components-resources`) | **All publish `wasmJs`** (verified against Maven Central `.module` metadata). CMP 1.11 has `HtmlElementView` in `webMain`. |

Broker facts the design relies on:

- Static serving is disk-first from `src/channels/web/static`, then the embedded manifest, then SPA
  fallback to `index.html` (`src/channels/web/static-serve.ts`). `API_PREFIXES` in
  `src/channels/web/index.ts` is the negative list; everything else is the SPA shell.
- Cache rule: `/assets/*` → `immutable`; everything else `no-cache, must-revalidate`.
- `guessMime` has no `.wasm` entry. There is no CSP.
- `/pair?t=` is handled server-side (302 + `Set-Cookie`), never by the SPA.
- Web push: `GET /push/vapid-public-key`, `POST|DELETE /push/subscribe`; plaintext VAPID payload,
  never via the relay. Native push is a separate E2E-encrypted pipeline.
- `/s/<id>` is produced only by `sw.ts` (`notificationclick`) and the Vue router. No channel or
  broker code deep-links into the PWA.

## 3. Module layout

```
apps/
  shared/      + wasmJs { browser() }   webMain intermediate for browser actuals
  ui/          + wasmJs { browser() }   webMain intermediate for browser actuals
  web/         NEW  dev.supermux.web — entry + WebPlatform + JS glue
    build.gradle.kts
    src/wasmJsMain/kotlin/dev/supermux/web/
      Main.kt                 ComposeViewport(document.body) { App(...) }
      WebPlatform.kt          Platform impl + Caps
      WebAppState.kt          store wiring (mirrors IosAppState)
      UrlSync.kt              window.location ⇄ Nav3 routes
      auth/CookieSession.kt   /me probe, TOFU claim, pair-link dialog trigger
      push/WebPushRegistrar.kt
      terminal/XtermTerminalViewFactory.kt
      editor/WebEditorEngine(Factory).kt
      files/BlobChunkSource.kt, FilePicker.kt, SaveAs.kt
      media/WebMic.kt, WebTts.kt
      prefs/LocalStorageSettingsStore.kt, LocalStorageHostPersistence.kt
    src/wasmJsMain/resources/
      index.html, manifest.webmanifest, icons/, editor/{index.html,cm6.js}
    src/jsGlue/                 TypeScript, bundled by bun into resources at build time
      sw.ts                     (moved from src/web-app/sw.ts, unchanged)
      xterm-host.ts             xterm.js + fit + webgl addon, exposes a tiny window API
    src/wasmJsTest/kotlin/...   browser tests (headless Chromium)
```

- Settings: `settings.gradle.kts` adds `:web`. `gradle/libs.versions.toml` adds nothing new except
  the npm coordinates (`pako`, `@xterm/xterm`, `@xterm/addon-fit`, `@xterm/addon-webgl`).
- `apps/android/codemirror/` gets its own `package.json` + `bun.lock` so the cm6 bundle recipe no
  longer depends on `src/web-app/node_modules`. `scripts/test-android.sh` follows.
- `src/web-app` is deleted in plan 5.

## 4. Seams — every `expect`/factory and its web actual

### `:shared` (5)

| Seam | Web actual |
|---|---|
| `SecureTokenStore` | Cookie session. `load()` returns `null`; `save/clear` are no-ops; base URL = `window.location.origin`. `BrokerApi.bearerHeader()` becomes conditional: no `Authorization` header when the token is blank (broker's `cookieToken(req) \|\| bearerToken(req)` then uses the cookie). Existing hosts are unaffected. |
| `ZlibInflater` | `pako.Inflate` via npm (synchronous, matches the feed/inflate contract; `DecompressionStream` is async and cannot). |
| `openSealedPush` | `throw UnsupportedOperationException` — web push is plaintext VAPID and never reaches Kotlin (the SW shows it). |
| `localUtcOffsetMs` | `-Date(epochMs).getTimezoneOffset() * 60_000`. |
| `formatLocalDateTimeMedium` | `Intl.DateTimeFormat(undefined, {dateStyle:"medium", timeStyle:"short"})`. |
| HTTP engine | `JsHttpFactory` in `webMain` (Ktor's bundled Js engine, `credentials: same-origin` is the fetch default). |

### `:ui` (12 declarations, 8 files)

| Seam | Web actual |
|---|---|
| `VncFramebuffer` | Copy of the jvm actual (Skia `BGRA_8888` upload through `VncFrameOps.uploadRaw`) — wasm is Skia too. Lives in `webMain`. |
| `EditorEngineHost(engine, visible, modifier)` | `HtmlElementView` hosting an `<iframe src="editor/index.html">`; `visible=false` hides the element (`display:none`), matching desktop's swap-not-overlay rule. |
| `FileDrop.externalFileDropTarget` | DOM `dragover/drop` on the viewport, files wrapped as `PickedFile(BlobChunkSource)`. |
| `ComposerContextMenu`, `RowContextMenu`, `platformContextMenuAvailable=true` | Compose `DropdownMenu` on secondary click (same as jvm). |
| `KeepAlivePanel` | Same as the jvm actual (Compose-only, no heavyweight child). |
| `ColResizeIcon` / `RowResizeIcon` | `PointerIcon` from CMP's web cursor set (`col-resize` / `row-resize`). |
| `KeyEvent.isFromPhysicalKeyboard()` | `true` (browser key events are keyboard events). |
| `isSecondaryButtonPress` / `isTertiaryButtonPress` | `buttons` bitmask, as jvm. |

### `Platform` (web)

| Member | Web implementation |
|---|---|
| `caps` | push ✓ · camera ✗ · tray ✗ · externalDisplay ✓ (VNC) · hardwareVideoDecode ✗ · localBroker ✗ · multiWindow ✗ · fileSystem ✗ · clipboardImages ✓ (`navigator.clipboard.read`) · saveAs ✓ · walkthrough ✓ · appearanceControls ✓ · dynamicColor ✗ · appUpdate ✗ · terminal ✓ · scrcpy ✗ · **setupWizard ✓ (new cap, §5)** |
| `openUrl` | `window.open(url, "_blank", "noopener")`. |
| `copyToClipboard` | `navigator.clipboard.writeText`. |
| `pickFiles(kind)` | Hidden `<input type=file multiple accept=…>`; each `File` → `PickedFile(name, type, BlobChunkSource)`. `BlobChunkSource` reads `file.slice(off, off+n).arrayBuffer()` on demand — bounded RAM, as on the other hosts. |
| `scanQr` | `null` (no camera cap). |
| `captureImage/Video` | `null`. |
| `clipboard` | `ClipboardAccess` over `navigator.clipboard.read()` (image items only). |
| `files.saveAs` | `Blob` + object URL + synthetic `<a download>` click. |
| `mic` | `MediaRecorder` (`audio/webm;codecs=opus`), same broker STT upload path as the Vue `useMediaRecorder`. |
| `tts` | `speechSynthesis` + the broker audio playback path `MessageTts` already uses. |
| `notices` | Compose snackbar (as desktop). |
| `terminalView()` | `XtermTerminalViewFactory` (§6). |
| `videoDecoder()` | `null`. |
| `updates` | `NoAppUpdater` (the PWA updates by reload; a build-id banner comes from `UrlSync`'s version probe — see §7). |
| `notifications` | `NoopNotificationManager` (pushes arrive via the SW). |
| `windows` | `null`. |
| `push` | `WebPushRegistrar` (§5). |
| `editorEngine` | `WebEditorEngineFactory` (§6). |
| `haptics` | no-op. |
| `SettingsStore` | `localStorage`, keys prefixed `supermux:`; `string(key)` is a `MutableStateFlow` map fed on write (no cross-tab sync needed). |
| `HostPersistence` | `localStorage` JSON; web is single-host (`loadAll()` returns the current origin only). |

## 5. Onboarding wizard, auth bootstrap, push

**Wizard.** New `apps/ui/.../intro/SetupWizardScreen.kt`, gated by `Caps.setupWizard` (default
`false`; only `WebPlatform` sets it now). Steps and behaviour copy the Vue wizard:

1. Welcome (`SetupStepWelcome.vue`) — text + Continue.
2. Agents — embeds `AgentSettingsScreen` content; Continue enabled once ≥1 agent is installed and
   logged in (the Vue `canProceed` rule).
3. Git Hosting — embeds `GitHostingScreen` content; skippable.
4. Connect your phone — mints a device via `POST /devices` (existing `BrokerApi.addDevice`) and shows
   the QR + copy link, reusing `DevicesSettingsScreen`'s add-device sheet.
5. Done — `saveAppConfig(onboarded = true)`.

The shell shows the wizard instead of the session list while `config.onboarded == false` and the cap
is on. `onboarded` already arrives in the WS snapshot and is parsed by `BrokerApi`. Android/iOS/desktop
behaviour is unchanged (cap off).

**Auth bootstrap (`CookieSession.kt`)** mirrors `App.vue`:

1. `GET /me` — paired ⇒ start the app.
2. Unpaired ⇒ `POST /pair/claim` with no secret. `200` ⇒ the broker set the cookie (TOFU on a fresh
   broker) ⇒ start. `403` ⇒ show the existing pair-link dialog ("open the link from your other
   device"); `/pair?t=` navigation is server-side and lands back on `/` paired.
3. `POST /logout` clears the cookie; the app returns to step 2.

**Push (`WebPushRegistrar`)**: `ensureChannel` = register `sw.js`; `requestPermission` =
`Notification.requestPermission()`; `registerIfPaired` = `GET /push/vapid-public-key` →
`pushManager.subscribe` → `POST /push/subscribe` (re-sync of an existing subscription, unsubscribe
on a 4xx that is not transient — the same state machine as `useNotifications.ts`);
`cancelForSession` = `registration.getNotifications({tag})` → close. The SW's `notificationclick`
`postMessage({type:"navigate", to:"/s/<id>"})` is consumed by `UrlSync`.

## 6. Terminal and editor overlays

Both are DOM elements positioned by `HtmlElementView` over the Compose canvas. Rules carried over
from desktop's SwingPanel lesson: nothing Compose draws can sit above them, so panes **swap**, never
overlay; the element is created lazily at first non-zero size and hidden (not destroyed) when its
pane is not visible.

**Terminal.** `xterm-host.ts` exposes `window.smxTerm.create(el, opts) → {write(bytes), onData(cb),
onResize(cb), focus(), dispose(), cursor(), renderOps(ops)}`. `XtermTerminalViewFactory` implements
`TerminalViewFactory` + `TerminalSurface`: bytes from the shared `TerminalClient` go to `write`,
keystrokes go through the shared `PredictiveEcho` engine (the `PredictionSink` actual renders dim
cells/caret via ANSI, exactly like iOS's SwiftTerm adapter), resize propagates to the broker. Touch
scroll uses the shared `TerminalScroll` drag→wheel bridge. The mobile key bar is the shared
`TerminalKeyBar`.

**Editor.** `WebEditorEngine` implements `EditorEngine` by `postMessage` to the iframe; the iframe
runs the committed `cm6.js` whose `window.cm*` API is what desktop's JCEF engine already calls
(`cmInit/cmSetContent/cmGetContent/cmSetLineWrap/cmSetFontSize/cmSetLanguage/cmRevealLine/
cmLspConnect/cmLspMessage/cmShowDiffRegion…`). A 40-line shim inside `editor/index.html` forwards
`postMessage` ⇄ those globals and the `onChange/onSave/onReady/lspOut` callbacks. LSP transport stays
in Kotlin (`LspBridge` over the broker WS), as on every host.

## 7. URL routing, PWA shell, versioning

- `UrlSync` maps `window.location.pathname` to Nav3 destinations at start and on `popstate`, and
  pushes `history.pushState` on route changes. Routes preserved: `/`, `/new`, `/s/<id>`,
  `/archived`, `/devices`, `/usage`, `/proxies`, `/displays`, `/settings`, `/settings/<section>`,
  `/personal-assistants`, `/setup`. Unknown paths → `/`.
- `index.html` is minimal: viewport meta, theme-color `#0b0b0b`, manifest link, a loading splash
  (brand mark + progress from the wasm fetch), the SW registration snippet, `<script src="app-<hash>.js">`.
- Build id: Gradle injects `git rev-parse --short HEAD` + time as `BuildInfo` (replaces
  `__APP_BUILD_ID__`). `UrlSync` polls `/index.html` `ETag`/build-id every 10 min while visible and
  shows the existing "update available → reload" banner behaviour (was `AppUpdateBanner.vue`).

## 8. Build, serve, CI

**Gradle.** `:web:wasmJsBrowserDistribution` (production, optimised, `-Xwasm-…` DCE on) →
`:web:stageForBroker` copies into `src/channels/web/static/`:

- `index.html`, `manifest.webmanifest`, `sw.js`, `registerSW` inline, `icons/` at the root
  (`no-cache` rule).
- `assets/app-<hash>.js`, `assets/app-<hash>.wasm`, `assets/skiko-<hash>.js|wasm`, fonts,
  `editor/cm6.js` under `assets/editor/` — all content-hashed so the existing `immutable` rule
  applies. The task rewrites the loader's references to the hashed names.
- A size guard: fail when gzipped `app.wasm + skiko.wasm + app.js` exceeds **6 MB** (replaces
  `check-bundle-size.ts`; the number is a ceiling to catch accidental bloat, not a target).

**Broker.** `static-serve.ts`: `.wasm → application/wasm`; gzip applies to it like any `/assets/*`
file. No routing change: the SPA fallback and `API_PREFIXES` stay. `generate-static-manifest.ts`
embeds the new files unchanged (add `wasm` to `src/types/assets.d.ts`).

**Pipeline.**

- `scripts/build-binary.sh`: replace the three-step Vite ladder with `./gradlew :web:stageForBroker`.
- `Dockerfile`: add a JDK-17 build stage that runs the same Gradle task and copies
  `src/channels/web/static` into the bun stage.
- `.github/workflows/ci.yml`: delete the `vue-tsc` step and the `src/web-app` installs; the `ui`
  lane runs `:web:stageForBroker` (Gradle cache shared with the android/desktop lanes).
- `scripts/test-broker.sh`: build via Gradle instead of `bun run build`.
- `scripts/feel-deploy.sh`: "web changed" = `apps/web/*`, `apps/ui/*`, `apps/shared/*`.
- `scripts/generate-logo-assets.ts`: write icons to `apps/web/src/wasmJsMain/resources/icons/`.
- Root `tsconfig.json`: drop the `src/web-app` exclude.

## 9. Testing

| Layer | What runs |
|---|---|
| `:shared` / `:ui` jvmTest | Unchanged (shared 737 · ui 95+). The wizard screen gets jvm tests like the other `:ui` screens. |
| `:web` wasmJsTest (headless Chromium via Kotlin's browser runner) | `UrlSync` round-trips, `BlobChunkSource` chunking, `CookieSession` state machine against a fake `/me`/`/pair/claim`, `WebPlatform.caps`, `LocalStorageSettingsStore`. |
| Broker `bun test` | New cases in `static-serve.test.ts` for `.wasm` MIME + gzip. The 62 pure-logic `src/web-app` tests are deleted with Vue; their logic already has Kotlin twins (predictive echo parity, markdown, path refs, formatting). |
| Playwright `tests/ui/core-journey.spec.ts` | Rewritten against the wasm build using Compose Web's accessibility tree (roles + existing `testTag`s). Done in plan 5 **before** deleting Vue, so both clients are exercised on the same hermetic broker. |
| Live | `feel-deploy.sh` on the :9898 broker; manual pass on Chrome desktop, Safari 18.2 iPhone, Android Chrome. |

`.mux/verify.sh` stays `bun test`; the Gradle suites are run by the plans' verification steps and CI.

## 10. Order of work

1. **Targets.** `wasmJs` on `:shared` + `:ui` compile green (5 + 12 actuals stubbed), hello-world
   `apps/web` staged into `src/channels/web/static` and served by a local broker; `.wasm` MIME.
2. **App shell.** `WebPlatform`, cookie auth bootstrap, `UrlSync`, settings/host persistence; chat,
   sessions, launcher, settings screens usable end to end.
3. **Overlays.** xterm terminal + predictive echo, CodeMirror iframe engine + LSP, VNC display,
   uploads via `BlobChunkSource`, mic, TTS, save-as, clipboard.
4. **First run.** `SetupWizardScreen` in `:ui` (+ jvm tests), `WebPushRegistrar`, `sw.ts` +
   manifest + icons, update banner.
5. **Cut-over.** Playwright rewrite, CI/Docker/build-binary/feel-deploy changes, cm6 bundle gets its
   own lockfile, delete `src/web-app`, docs + memory updated.

Each step is its own plan and branch off this one; step 5 is the only one that removes Vue.

## 11. Known risks (accepted)

- **Initial download** ~4–6 MB gzipped (Skiko + app) vs ~250 KB today. First paint on a cold
  phone is seconds. Mitigation: the splash progress bar, `immutable` caching, and the native apps
  for daily phone use.
- **Text input on mobile browsers** is the weakest area of Compose Web Beta (IME, autocorrect).
- **Canvas UI**: no browser text selection, find-in-page, or reader mode; accessibility relies on
  Compose's a11y tree.
- **Compose Web is Beta** on CMP 1.11.1; a CMP bump to 1.12 (wake lock, haptics on web, auto Noto
  fonts) is desirable but is a separate change because it moves Kotlin too.
- **Playwright rewrite** is the largest test change and the last gate before deleting Vue.

## 12. Out of scope

scrcpy/H.264 on web (WebCodecs) · Compose's JS compatibility mode for pre-WasmGC browsers ·
multi-host on web (stays single-origin) · customisable keyboard shortcuts · forge repo omnibox ·
a Compose-native terminal renderer.
