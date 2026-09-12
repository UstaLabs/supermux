# Web → KMP, Plan 4 of 5: setup wizard, Web Push, PWA shell

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The browser host owns the three things only the Vue app had: the first-run setup wizard (ported into `:ui` behind `Caps.setupWizard`, driven by a live `onboarded` flag), Web Push (VAPID subscribe through a `PushRegistrar`, a committed `sw.js`, notification-click → chat with Android-parity tap handling), and the PWA shell (manifest, icons, service-worker registration, update banner). Plus the viewing heartbeat the browser needs against the broker's 5-minute presence TTL.

**Architecture:** The wizard is a `:ui` commonMain screen (`intro/SetupWizardScreen.kt`) composed from the existing settings screens (`AgentSettingsScreen`, `GitHostingScreen`) and a new phone-pairing step over `DevicesSettingsActions`; it is rendered by the web host ABOVE `SupermuxApp`, like the pairing gate, when `Caps.setupWizard && onboarded == false`. `onboarded` becomes part of `ServerFrame.Snapshot` and a `StateFlow` on `HostStore`. Push is entirely Kotlin/Wasm: `WebPushRegistrar` does permission → VAPID subscribe → `POST /push/subscribe`; `sw.js` is the Vue worker with the types stripped, committed at `apps/web/pwa/`, staged to the root with the manifest and icons. The push-tap path is rewired through `PushTap.kt` exactly as Android's `MainActivity`.

**Tech Stack:** Kotlin/Wasm + kotlinx-browser, Web Push API (`PushManager`, VAPID), Service Worker (push + notificationclick only, no fetch handler), Web App Manifest, `:ui` Material 3, `qrBitmap` QR encoder.

**Spec:** `docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md` §5 (onboarding, push), §7 (PWA shell, versioning), §10 step 4. Plans 1–3 Results.

**Facts (verified 2026-09-12 — do not re-derive):**
- Vue wizard: steps `["Welcome","Agents","Git Hosting","Connect Your Phone","Done"]` (`src/web-app/src/stores/onboarding.ts:6`); `SetupView.vue` = header "Step N of 5 — label", progress bar `(step+1)/5`, footer with Back (hidden on step 0) and "Start"/"Next" (disabled on Agents until `canProceed`; hidden on Done). **No Skip** — Git Hosting and Phone are skippable via Next. Agents `canProceed` = `statuses.some { authed || (installed && caps.usableWithoutAuth) }` from `GET /agents/status` (`AgentLoginPanel.vue:94`). Phone step (`SetupStepPhone.vue`): on mount `POST /devices {name:"phone"}` → `{url,name}`, QR of the URL (EC level M), poll `GET /devices` every 1 s until the named device has `last_seen_at`, "Copy pairing link", "Refresh code" (revokes the unused device first), revoke-if-unused on unmount. Done: `PUT /settings/config {onboarded:true}` then route to `/new` ("Create your first session"). Route guard: `/setup` whenever `onboarded === false`; the WS snapshot carries `onboarded`.
- Kotlin side: `AppConfigDto.onboarded` (BrokerApi.kt:99), `BrokerApi.getConfig()` (:1564), `BrokerApi.saveConfig(onboarded: Boolean? = null, …)` (:1576, partial PUT, tested in BrokerApiSettingsTest.kt:227). `HostStore.appConfig(): AppConfigDto?` (:765) and `FleetStore.appConfig()` (:1218) are one-shot; **no `setOnboarded` wrapper exists**. **`ServerFrame.Snapshot` (apps/shared/.../proto/Frames.kt:240–258) does NOT decode `onboarded`** although the broker sends it (src/channels/web/index.ts:980–983).
- `:ui` blocks: `AgentSettingsScreen(actions: AgentSettingsActions, modifier, onBack, topBarShown)` (:223) — statuses are private inside `AgentSettingsBody` (`AgentsLoadState { Empty | Ready(statuses) | Error }` :128); `rememberAgentSettingsActions(app|fleet)`; `AgentInstallStatus { kind, installed, authed }` (no `capabilities`). `GitHostingScreen(actions, modifier, onBack, topBarShown)` (:154), `rememberGitHostingActions(app|fleet)`. `DevicesSettingsScreen(...)` with `DevicesSettingsActions { devicesLoad: suspend () -> List<DeviceDto>?; deviceAdd: suspend (String) -> AddDeviceResponse?; deviceRevoke: suspend (String) -> Boolean }` (:107), `rememberDevicesSettingsActions(app|fleet)`; `AddDeviceDialog` is private + name-first (don't reuse). `DeviceDto.last_seen_at` (snake_case, BrokerApi.kt:132), `AddDeviceResponse(url, name)` (:339). QR: `qrBitmap(content, sizePx = 512, margin = 2): ImageBitmap` (widgets/QrCode.kt:69, throws `QrCapacityException` — wrap in runCatching). No stepper primitive in `:ui` (Intro.kt's pager is bespoke) — build a linear stepper.
- Where to render: `SupermuxApp` has no full-screen slot (`homeFallback` is a String); render the wizard in the web host between the `Paired` gate and `SupermuxApp`, exactly like `WebPairScreen` (apps/web/.../Main.kt ~:120–137).
- Push, Vue side: `useNotifications.ts` states `loading|unsupported|denied|not-subscribed|subscribed`; `probe()` re-POSTs an existing subscription (401/404 → local unsubscribe); `enable()` = `Notification.requestPermission()` → `GET /push/vapid-public-key {publicKey}` → `pushManager.subscribe({userVisibleOnly:true, applicationServerKey})` → `POST /push/subscribe {endpoint, keys:{p256dh,auth}}`; `disable()` = local unsubscribe + `DELETE /push/subscribe`. Banner dismissed key `cmux:push:banner-dismissed`. `sw.ts` (98 lines): no fetch handler (deliberate — plan 1 facts), `skipWaiting`, `clients.claim` + delete `*precache*` caches, `push` → `showNotification(session, {body: text ?? "New message", icon/badge "/icons/icon-192.png", tag "cmux:"+(sessionId ?? session), renotify, data})`, `notificationclick` → focus the `/s/<id>` tab, else focus any tab + `postMessage({type:"navigate", to:"/s/<id>"})`, else `openWindow`. **Never suppress a push client-side on iOS Safari** (WebKit revokes the subscription) — suppression is broker-side via the `viewing` WS frame.
- Broker: `GET /push/vapid-public-key` (no auth; 503 if unconfigured), `POST /push/subscribe` (cookie auth; validates `endpoint`, `keys.p256dh`, `keys.auth`; 503 without a push store), `DELETE /push/subscribe`. Push payload `{session, sessionId?, text?, kind?, ts, hostId?}`. Viewing: WS `{"type":"viewing","session":<id|null>,"visible":bool}` or `{"sessions":[…]}`; presence TTL 5 min (`viewing-tracker.ts:22`); the Vue app heartbeated every 60 s (`useViewing.ts:7`). Kotlin: `ClientFrame.Viewing(session, visible, sessions?)` (Frames.kt:506), `FleetStore.updateViewing(snapshot: WorkspaceViewingSnapshot?)` (:658), driven by `SupermuxApp` (:352–386). **No heartbeat on the Kotlin side.**
- Kotlin push seam: `PushRegistrar { ensureChannel(); requestPermission(); registerIfPaired(); cancelForSession(id) }` (ShellSeams.kt:185). Android/iOS delegate to native code; `BrokerApi` has NO `/push/subscribe` method. `PushTap.kt`: `resolvePushTap(sessionId, workspaces): PushTapResolution(sessionId, workspaceId?, activeViewId?)`, `notificationCancelSessionIds(visibleChatSessionIds, selectedSessionId?)`, `pushTapHandleDecision(extraSessionId?, handledSessionId?, workspacesReady): PushTapHandle {Skip, ApplyRetry, ApplyConsume}`; `visibleWorkspaceChatIdsAt(compact, workspace, layout)` (SupermuxApp.kt:192); Android's consumer is `apps/android/.../MainActivity.kt:247–263` (copy it). Web today: `WebAppState.pendingPushSessionId` + `startSwMessages()` exist; `Main.kt:173–179` just calls `ui.selectSession`.
- PWA shell: `apps/web/src/wasmJsMain/resources/index.html` has no manifest/icon/favicon/SW-registration tags. `stageForBroker` wipes and rewrites `src/channels/web/static`; root-level extras must be declared inputs copied into `staging` (precedent: `xtermCssFile`, `editorSrcDir` at build.gradle.kts:113–133). **Nothing under `src/wasmJsMain/resources/` may hold `sw.js`** (webpack would hash it into assets/). Broker serves non-`/assets/` files `no-cache`; `.webmanifest/.png/.ico/.js` MIME already handled; `Service-Worker-Allowed` not needed (SW at `/sw.js`, scope `/`). Icons are generated by `scripts/generate-logo-assets.ts:31–42` into `src/web-app/public/icons/` (+ `favicon.ico`); `bun run generate:logo`. Vue manifest: name/short_name = `PRODUCT_NAME`, theme/background `#0b0b0b`, `display: standalone`, icons 192/512/mask(512, maskable).
- `Caps` (Platform.kt:193) has no `setupWizard`; all constructions use named args → a trailing defaulted field is safe.
- Interop rules from plans 1–3 still apply (attach-once, `js()` helpers, no `runBlocking`). Karma lane: `CHROME_BIN=/usr/bin/google-chrome ./gradlew :web:wasmJsBrowserTest` (45+ tests).

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/.../proto/Frames.kt` | `ServerFrame.Snapshot.onboarded: Boolean = false`. |
| `apps/shared/.../state/HostStore.kt`, `FleetStore.kt` | `onboarded: StateFlow<Boolean?>` (null until snapshot); `setOnboarded(v): Boolean`; viewing re-assert. |
| `apps/shared/.../net/BrokerApi.kt` | `pushVapidPublicKey()`, `pushSubscribe(endpoint, p256dh, auth)`, `pushUnsubscribe()`. |
| `apps/ui/.../platform/Platform.kt` | `Caps.setupWizard: Boolean = false`. |
| `apps/ui/.../settings/AgentSettingsScreen.kt` | + `onStatusesChanged: (List<AgentInstallStatus>) -> Unit = {}`. |
| `apps/ui/.../intro/SetupWizardScreen.kt` | The 5-step wizard (stepper, footer, gating). |
| `apps/ui/.../intro/SetupPhoneStep.kt` | Auto-mint "phone", QR, poll, copy, refresh, revoke-if-unused. |
| `apps/ui/src/jvmTest/.../intro/SetupWizardScreenTest.kt`, `SetupPhoneStepTest.kt` | Compose UI tests with fake actions. |
| `apps/web/pwa/sw.js`, `manifest.webmanifest`, `icons/*.png`, `favicon.ico` | Committed PWA assets (staged to root). |
| `apps/web/src/wasmJsMain/resources/index.html` | manifest/icon/favicon links + inline SW registration + update reload guard. |
| `apps/web/build.gradle.kts` | stage `pwa/**` to the root as declared inputs. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/push/WebPushRegistrar.kt` | `PushRegistrar` over `PushManager` + `BrokerApi`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/push/WebPushBanner.kt` | "Enable notifications" overlay (once per install). |
| `apps/web/.../Main.kt`, `WebPlatform.kt` | wizard gate, push wiring, tap parity, heartbeat, `WEB_CAPS.push/setupWizard = true`. |
| `scripts/generate-logo-assets.ts` | + `apps/web/pwa/icons/*` targets. |

---

### Task 1: `onboarded` on the Kotlin side + `Caps.setupWizard`

**Files:** `Frames.kt`, `HostStore.kt`, `FleetStore.kt`, `Platform.kt`, tests in `apps/shared/src/commonTest` (`FramesTest`/snapshot decode) and `apps/shared/src/jvmTest/.../state/HostStoreTest` (find the existing snapshot-driven test and extend).

- [ ] **Step 1 (red):** snapshot JSON with `"onboarded":false` decodes to `Snapshot.onboarded == false`, absent → `false`; `HostStore.onboarded` is `null` before a snapshot and `false`/`true` after; `HostStore.setOnboarded(true)` calls `PUT /settings/config` with body `{"onboarded":true}` (MockEngine) and flips the flow to `true` on success.
- [ ] **Step 2 (green):** `val onboarded: Boolean = false` on `ServerFrame.Snapshot`; `HostStore`: `private val _onboarded = MutableStateFlow<Boolean?>(null)`, `val onboarded: StateFlow<Boolean?>`, set in the snapshot reducer path; `suspend fun setOnboarded(value: Boolean): Boolean = runApi("setOnboarded") { api.saveConfig(onboarded = value); _onboarded.value = value; true } ?: false` (mirror the existing `saveVoiceStt` shape); `FleetStore.onboarded: StateFlow<Boolean?>` derived from the active app (mirror how another per-app flow is lifted, e.g. `connection`), `FleetStore.setOnboarded(v)` delegate. `Caps.setupWizard: Boolean = false` with KDoc ("the host runs the first-run wizard when the broker reports `onboarded=false`; only the browser, which is the setup surface"). Nothing else changes.
- [ ] **Step 3:** `:shared:jvmTest`, `:shared:compileKotlinWasmJs`, `:ui:jvmTest` (xvfb) green. Commit `feat(shared,ui): onboarded flag from the snapshot + setOnboarded; Caps.setupWizard`.

---

### Task 2: `SetupWizardScreen` + `SetupPhoneStep` in `:ui`

**Files:** `AgentSettingsScreen.kt` (hoist callback), new `intro/SetupWizardScreen.kt`, `intro/SetupPhoneStep.kt`, jvm tests.

- [ ] **Step 1 (red):** `SetupWizardScreenTest` with fake actions (`AgentSettingsActions`/`GitHostingActions`/`DevicesSettingsActions` built from lambdas): starts on Welcome with "Start"; Back hidden on step 0; Agents step disables Next until `agentStatuses` returns one `authed=true`; Git Hosting and Phone steps have Next enabled; Done shows "Create your first session" and no footer; pressing it calls `onFinish()` then `onCreateFirstSession()`; the header reads "Step 3 of 5 — Git Hosting". `SetupPhoneStepTest`: on entry `deviceAdd("phone")` is called once and a QR + the URL appear; the poll observes `last_seen_at` non-null → "Your phone is connected"; leaving the step with the device unused calls `deviceRevoke(name)`; "Refresh code" revokes then mints again; "Connect another phone" mints again.
- [ ] **Step 2 (green):**
  - `AgentSettingsScreen`: add `onStatusesChanged: (List<AgentInstallStatus>) -> Unit = {}` and call it wherever `AgentsLoadState.Ready(statuses)` is set (both refresh sites, :278 and :293). Rule for the wizard: `statuses.any { it.authed }` — document in the wizard KDoc that the Vue rule also accepted `installed && usableWithoutAuth` (opencode free tier) and that `AgentInstallStatus` has no capabilities field; if `BrokerApi`'s `/agents/status` already returns `capabilities`, add `usableWithoutAuth` to the DTO and honour it (check the JSON first; do the cheap thing, say which).
  - `SetupWizardScreen(agents: AgentSettingsActions, forges: GitHostingActions, devices: DevicesSettingsActions, onFinish: suspend () -> Boolean, onCreateFirstSession: () -> Unit, modifier)`: `var step by rememberSaveable { mutableStateOf(0) }`, labels list, header + linear progress (`LinearProgressIndicator(progress = (step+1)/5f)`), body `when(step)`: 0 welcome (logo `Res.drawable.mux_logo` if present + title + blurb), 1 `AgentSettingsScreen(agents, topBarShown = true, onStatusesChanged = { canProceed = it.any { s -> s.authed } })`, 2 `GitHostingScreen(forges, topBarShown = true)`, 3 `SetupPhoneStep(devices)`, 4 done card with the single button (disabled while `onFinish` runs; on `false` show an inline error, stay). Footer for steps 0–3: Back (step>0), Next/Start (enabled unless step==1 && !canProceed). Keyboard: Escape does nothing (no exit — the wizard is mandatory on a fresh broker).
  - `SetupPhoneStep(devices: DevicesSettingsActions, modifier)`: state `pairing: AddDeviceResponse?`, `paired: Boolean`; `LaunchedEffect(Unit) { mint() }`; `mint(refresh)`: if refresh, revoke-if-unused first; `devices.deviceAdd("phone")`; poll loop `while (!paired) { delay(1000); devices.devicesLoad()?.firstOrNull { it.name == pairing.name }?.last_seen_at != null → paired = true }`; QR via `runCatching { qrBitmap(url, 480) }` in an `Image`, URL in mono text, "Copy pairing link" → `LocalPlatform.current.copyToClipboard(url)` + 1.5 s "Copied", "Refresh code"; `DisposableEffect(pairing) { onDispose { if (!paired) scope.launch { devices.deviceRevoke(name) } } }` — the revoke must use a scope that outlives the composable (pass `rememberCoroutineScope()`'s parent or a `CoroutineScope` param; simplest: launch on `GlobalScope`-free app scope injected as `scope: CoroutineScope` param defaulting to `rememberCoroutineScope()` and note the caveat).
- [ ] **Step 3:** `xvfb-run -a ./gradlew :ui:jvmTest` green (new tests + no regressions). Commit `feat(ui): first-run setup wizard (welcome, agents, git hosting, connect phone, done)`.

---

### Task 3: PWA assets, `sw.js`, manifest, icons, `index.html`, staging

**Files:** `apps/web/pwa/{sw.js,manifest.webmanifest,icons/icon-192.png,icon-512.png,icon-mask.png,apple-touch-icon.png,favicon.ico}`, `apps/web/src/wasmJsMain/resources/index.html`, `apps/web/build.gradle.kts`, `scripts/generate-logo-assets.ts`.

- [ ] **Step 1:** `apps/web/pwa/sw.js` = `src/web-app/sw.ts` with type annotations removed, semantics identical (no fetch handler, skipWaiting, claim + precache cleanup, push → showNotification with the `cmux:<id>` tag, notificationclick focus/postMessage/openWindow). Keep the header comment about why there is no fetch handler. `manifest.webmanifest` = the Vue manifest object (name/short_name "Supermux" — read `PRODUCT_NAME` from src/shared/brand.ts and hard-code the same string, `theme_color`/`background_color` `#0b0b0b`, `display: standalone`, `start_url: "/"`, `scope: "/"`, the three icons with `/icons/…` paths). Icons: copy the five PNG/ICO files from `src/web-app/public/` (they are committed there) into `apps/web/pwa/icons/` + `apps/web/pwa/favicon.ico`; add the same targets to `scripts/generate-logo-assets.ts`'s variant table (keep the Vue targets until plan 5).
- [ ] **Step 2:** `stageForBroker`: `val pwaDir = layout.projectDirectory.dir("pwa")` declared `inputs.dir(pwaDir).withPropertyName("pwa")`; copy `pwa/**` into the staged root (`sw.js`, `manifest.webmanifest`, `favicon.ico` at root; `icons/` dir) — all outside `assets/` (no hashing, no-cache rule, outside the gzip guard). Idempotent.
- [ ] **Step 3:** `index.html`: add `<link rel="manifest" href="manifest.webmanifest">`, `<link rel="icon" href="favicon.ico">`, `<link rel="apple-touch-icon" href="icons/apple-touch-icon.png">`, `<meta name="apple-mobile-web-app-capable" content="yes">`, and an inline script before `app.js`:
  ```html
  <script>
    if ("serviceWorker" in navigator) {
      // Register early — before the 6 MB wasm bundle — so push works even on a slow first load.
      navigator.serviceWorker.register("/sw.js").catch(function () {});
      // A new worker took control after an update: reload once so the page runs the new bundle.
      var had = !!navigator.serviceWorker.controller;
      navigator.serviceWorker.addEventListener("controllerchange", function () { if (had) location.reload(); });
    }
  </script>
  ```
- [ ] **Step 4:** stage; verify through a hermetic broker with curl: `/sw.js` (`application/javascript`, `no-cache`), `/manifest.webmanifest` (`application/manifest+json`), `/icons/icon-192.png` (`image/png`), `/favicon.ico`; headless Chrome: `navigator.serviceWorker.ready` resolves with scope `/`, manifest link present, no console errors. Commit `feat(web): PWA shell — committed sw.js, manifest, icons, early SW registration`.

---

### Task 4: `WebPushRegistrar`, push banner, `BrokerApi` push calls

**Files:** `BrokerApi.kt` (+ commonTest), `apps/web/.../push/WebPushRegistrar.kt`, `push/WebPushBanner.kt`, `WebPlatform.kt` (`push`, `WEB_CAPS.push = true`), Karma tests for the pure parts.

- [ ] **Step 1 (red, :shared commonTest):** `pushVapidPublicKey(): String?` → `GET /push/vapid-public-key` → `publicKey` (null on 503); `pushSubscribe(endpoint, p256dh, auth): Boolean` → `POST /push/subscribe` body exactly `{"endpoint":…,"keys":{"p256dh":…,"auth":…}}`; `pushUnsubscribe(): Boolean` → `DELETE /push/subscribe`. All three without bearer when the token is blank (`authHeader()`).
- [ ] **Step 2 (green):** implement in `BrokerApi` (mirror `logout`/`claimSecretless` style: non-2xx → false/null, never throws for status).
- [ ] **Step 3:** `WebPushRegistrar(api: () -> BrokerApi?, scope: CoroutineScope) : PushRegistrar`:
  - `ensureChannel()` no-op. `requestPermission()` → `Notification.requestPermission()` (promise via a `js()` helper; result stored). `registerIfPaired()`: if `Notification.permission != "granted"` → return (the banner asks); `navigator.serviceWorker.ready` → existing subscription? re-POST it (idempotent, like Vue's `probe()`; on 401/404 unsubscribe locally) : `pushVapidPublicKey()` (null → return quietly) → `subscribe({userVisibleOnly:true, applicationServerKey: base64url→Uint8Array})` → `pushSubscribe(endpoint, keys.p256dh, keys.auth)` (POST failure → local unsubscribe). `cancelForSession(id)` → `registration.getNotifications({tag: "cmux:"+id})` → `close()` each. `suspend fun disable()`: local unsubscribe + `pushUnsubscribe()`. Pure helper `vapidKeyBytes(b64url: String): ByteArray` in its own file with a Karma test (RFC 4648 §5 decode, padding).
  - `WebPushBanner(registrar, modifier)`: shown when `Notification` exists, permission is `"default"`, and `localStorage["cmux:push:banner-dismissed"] != "1"`: a Material `Surface` strip at the top with "Enable notifications for agent replies" + "Enable" (`requestPermission()` then `registerIfPaired()`; hide on granted/denied) + "Not now" (sets the key). Rendered by `Main.kt` in a `Box` above `SupermuxApp` (overlay at top, does not push content).
  - `WebPlatform`: `push = WebPushRegistrar(...)` (needs the fleet/api — give `WebPlatform` a `lateinit`/setter `pushApi: () -> BrokerApi?` set from `Main.kt` after the fleet exists, or construct the registrar in `Main.kt` and pass it into `WebPlatform(push = …)`; pick the latter), `WEB_CAPS.push = true`. `Main.kt`: `LaunchedEffect(Unit) { platform.push?.registerIfPaired() }` after the fleet is built (mirrors iOS :232).
- [ ] **Step 4:** Karma: `vapidKeyBytes` vectors; `WebPushRegistrar.registerIfPaired()` with permission != granted returns without touching the api (fake `BrokerApi` via MockEngine counting requests). Browser (hermetic broker): the broker's VAPID key exists in the fixture (`push-keys.json` is generated on first start — verify `GET /push/vapid-public-key` returns 200); with Chrome flags `--enable-features=…`? Push subscription against a real push service needs network to FCM — attempt `page.evaluate(() => Notification.permission)` after granting via `context.grantPermissions(["notifications"])`, click "Enable", and assert the app called `GET /push/vapid-public-key` and either `POST /push/subscribe` (if `subscribe()` succeeded) or logged the subscribe failure without a pageerror; record which happened. Commit `feat(web): Web Push — VAPID subscribe through PushRegistrar, enable banner, BrokerApi push calls`.

---

### Task 5: Push-tap parity + viewing heartbeat

**Files:** `apps/web/.../Main.kt`, `FleetStore.kt`/`HostStore.kt` (heartbeat), tests.

- [ ] **Step 1:** Replace the `pendingPush` block in `Main.kt` with Android's `MainActivity.kt:247–263` logic: `pushTapHandleDecision(pendingPush, handledPushSessionId, workspaces.isNotEmpty())` → `Skip` (consume if non-null) / `ApplyRetry` / `ApplyConsume`; `resolvePushTap(sid, owned ?: workspaces)`; `ui.selectSession(sid)`; `fleet.setActiveView(workspaceId, activeViewId)` when both non-null; consume + remember `handledPushSessionId`; reset it when `ui.selectedId == null`. Also the notification-withdraw effect from iOS (:242–252): `LaunchedEffect(ui.selectedId, workspaces, compact, foreground) { … for (id in notificationCancelSessionIds(visibleIds, sid)) platform.push?.cancelForSession(id) }`.
- [ ] **Step 2 (red, :shared jvmTest):** `HostStore.reassertViewing()` re-sends the last `ClientFrame.Viewing` it sent (or nothing if none); `FleetStore.reassertViewing()` fans out to the active app. Test through the existing `sendFrameOverride`/recording seam.
- [ ] **Step 3 (green):** keep `lastViewing: ClientFrame.Viewing?` in `HostStore.updateViewing`; `reassertViewing()` resends it; `FleetStore.reassertViewing()`. In `Main.kt`: `LaunchedEffect(foreground) { while (foreground) { delay(60_000); fleet.reassertViewing() } }` with a KDoc citing the broker's 5-minute presence TTL (the Vue app did the same at 60 s).
- [ ] **Step 4:** `:shared:jvmTest`, `:web:compileKotlinWasmJs`, Karma green. Browser: simulate a SW message `navigator.serviceWorker` → `postMessage` can't be injected from outside easily; instead `page.evaluate` dispatching a `MessageEvent("message", {data:{type:"navigate", to:"/s/<id>"}})` on `navigator.serviceWorker` and assert the chat opens. Commit `feat(web): push-tap parity with Android and a 60 s viewing heartbeat`.

---

### Task 6: Wizard wiring in the web host + browser verification + Results

**Files:** `apps/web/.../Main.kt`, `WebPlatform.kt` (`WEB_CAPS.setupWizard = true`), plan doc.

- [ ] **Step 1:** In `Main.kt`'s `Paired` branch, after the fleet is built: `val onboarded by fleet.onboarded.collectAsState()`; when `platform.caps.setupWizard && onboarded == false` render `SetupWizardScreen(agents = rememberAgentSettingsActions(fleet), forges = rememberGitHostingActions(fleet), devices = rememberDevicesSettingsActions(fleet), onFinish = { fleet.setOnboarded(true) }, onCreateFirstSession = { ui.openLauncher() })` INSTEAD of `SupermuxApp` (UrlSync must not run while the wizard shows — mount it in the `SupermuxApp` branch only; the wizard's own URL is `/setup` per `UrlRoutes` — extend `pathFor`/`parsePath` so `/setup` maps to the wizard while `onboarded == false` and to Home otherwise; simplest: `UrlSync` gets an `onboarded: Boolean?` param and, while false, `replaceState("/setup")` and ignores popstate). `onboarded == null` (no snapshot yet) → keep showing the splash-equivalent (a centered progress) rather than flashing the shell.
- [ ] **Step 2:** Browser verification on a hermetic broker: after pairing, `curl -X PUT $URL/settings/config -H "Authorization: Bearer $TOKEN" -d '{"onboarded":false}'` (the fixture seeds `onboarded=true` when sessions exist — check `scripts/test-broker-seed.ts`), reload → wizard Welcome (screenshot), Start → Agents (Next disabled — the fixture's fake claude reports whatever `/agents/status` says; if it reports `authed`, Next enables — record), Next → Git Hosting, Next → Connect phone: QR rendered and `POST /devices` seen; simulate the phone by `curl "$PAIR_URL"`-style claim of the minted link? (`/pair?t=` from a second Playwright context sets `last_seen_at` on that device) → "Your phone is connected"; Next → Done → "Create your first session" → `PUT /settings/config {"onboarded":true}` seen, shell appears at `/new`. Reload → no wizard. Screenshots to scratchpad `plan4/`.
- [ ] **Step 3:** `## Results` in this plan (what worked, push outcome, timings) and commit `feat(web): first-run wizard wired behind Caps.setupWizard; plan 4 results`.

---

## Not in this plan

Playwright journey rewrite, CI/Docker, cm6 lockfile, `font/ttf` MIME, hashing `editor/` + `sw.js` versioning, the CMP 1.12 bump for the interop-hole bug, deletion of `src/web-app` (and of the Vue icon targets) → plan 5.

---

## Results (2026-09-12)

All six tasks landed. The browser host now owns the three things only the Vue app had — the
first-run wizard, Web Push, and the PWA shell — plus the viewing presence the broker's 5-minute
TTL needs. Commits `744adf46`, `a9bd4e33`, `0a1e6e90`, `07e5c8a0`, `9f6ee7dd`, `a9fe0932`,
`ec53f2e1`, and this one.

| Task | Outcome |
|---|---|
| 1 — `onboarded` | `ServerFrame.Snapshot.onboarded` decodes (absent → `false`); `HostStore.onboarded: StateFlow<Boolean?>` is `null` until the first snapshot; `setOnboarded(v)` PUTs `{"onboarded":v}` and flips the flow; `FleetStore` lifts both off the ACTIVE host. `Caps.setupWizard` added, default false. |
| 2 — wizard in `:ui` | `SetupWizardScreen` (5 steps, linear stepper, no Skip, no exit) + `SetupPhoneStep` (auto-mint, QR, 1 s poll, copy, refresh, revoke-if-unused). Agents gate = Vue's rule with Vue's own fallback: `authed`, or an INSTALLED `opencode` (no `capabilities` field on `AgentInstallStatus`). `AgentSettingsScreen` gained `onStatusesChanged`. Both the Done step and the phone step's revoke run on an injected app scope — `onFinish` succeeding is exactly what unmounts the composable. |
| 3 — PWA shell | `apps/web/pwa/` (sw.js, manifest, icons, favicon) staged to the broker ROOT (outside `assets/`, so unhashed and `no-cache`); `index.html` registers `/sw.js` BEFORE the 6 MB bundle and reloads once on `controllerchange`. curl proof: `/sw.js` `application/javascript` + `no-cache`, `/manifest.webmanifest` `application/manifest+json`, `/icons/icon-192.png` `image/png`, `/favicon.ico` served; in Chrome `navigator.serviceWorker.ready` resolves with scope `/`. |
| 4 — Web Push | `BrokerApi.pushVapidPublicKey/pushSubscribe/pushUnsubscribe`; `WebPushRegistrar` is Vue's `useNotifications` state machine in Kotlin/Wasm; `WebPushBanner` keyed on the Vue app's `cmux:push:banner-dismissed`. Browser-verified against a hermetic broker: VAPID key 200, a **real FCM subscription** created, `POST /push/subscribe` 200, row in sqlite, no pageerror. Two caveats recorded there: Chrome refuses the Push API in incognito (so the run uses `launchPersistentContext` with a temp profile), and the FCM round trip takes **> 6 s**, long enough to need an explicit wait. `pushSubscribe` is tri-state (`Boolean?`) so a network blip does not discard a working subscription. |
| 5 — push tap + viewing | `Main.kt` now runs Android's `MainActivity` logic byte for byte (`pushTapHandleDecision` → `resolvePushTap` → `selectSession` + `setActiveView`, `Skip` still consumes the id) plus iOS's notification-withdraw effect (`notificationCancelSessionIds` over `visibleWorkspaceChatIdsAt`). `HostStore.reassertViewing()`/`FleetStore.reassertViewing()` + `ViewingReassertTest` (jvm). **The host-side 60 s loop this task added has been REMOVED in task 6** — see below. |
| 6 — wizard wired | Below. |
| Tests | Karma **65** (`CHROME_BIN=/usr/bin/google-chrome ./gradlew :web:wasmJsBrowserTest`, 1 m 44 s); `:shared:jvmTest` **985**; `:ui:jvmTest` **1613** (xvfb). All green. Staged bundle 6 123 KB gzip; `stageForBroker` 10 m 30–10 m 50 s cold. |

### Task 6 — the gate in `Main.kt`

Inside the `Paired` branch, after the fleet: `val onboarded by fleet.onboarded.collectAsState()`,
then three states — `null` → a centered `CircularProgressIndicator` (never the shell, never the
wizard: painting either before the snapshot is a visible wrong answer), `false && caps.setupWizard`
→ `SetupWizardScreen(..., onFinish = { fleet.setOnboarded(true) }, onCreateFirstSession = {
ui.openLauncher() }, scope = appScope)`, else the existing `SupermuxApp` box. `WEB_CAPS.setupWizard
= true`; the browser is the only host that is ever a setup surface.

The notification-withdraw effect, the push-tap effect and `UrlSync` all moved INSIDE the shell
branch (or, for `UrlSync`, above it but disabled): they read `fleet.workspaces`/`ui.selectedId`,
which mean nothing while the wizard is up. `registerIfPaired()` stayed outside — a subscription is
worth having before Done, since Done is when the user walks off to their phone.

**`UrlSync(ui, enabled, wizard)`** — the deviation from the plan's sketch. `parsePath("/setup")`
still resolves to `Route.Home` (unchanged: the address bar is user input). What changed is when the
initial apply runs:

- `wizard` → `history.replaceState("/setup")`, nothing else. A reload during setup comes back to
  setup.
- `enabled` (shell on screen) → the initial apply runs THEN, not at mount, and it SKIPS `/setup`
  (`shouldApplyInitialUrl`, a pure function with Karma coverage). Without that skip, Done's
  `openLauncher()` would be undone a frame later by an apply of `/setup` → Home, and the run would
  land on `/` instead of `/new`.
- **neither** — `onboarded == null` — the URL is left ALONE. This third state is a bug this task's
  own browser run caught: stamping `/setup` while the snapshot is in flight ate the deep link every
  cold load starts with, and `/s/<id>` came back as `/` a second later. Verified after the fix:
  `/s/<id>` and `/new` both survive a reload.

### The browser run (hermetic broker, headless Chrome 148 + Playwright)

`MUX_TEST_SKIP_WEB_BUILD=1 scripts/test-broker.sh bun <driver>` after `:web:stageForBroker`.
Screenshots in the session scratchpad `plan4/`.

| Step | What happened | Shot |
|---|---|---|
| Pair, `onboarded=false` forced (`PUT /settings/config`) | `/pair?t=…` → the app lands on **`/setup`** with "Step 1 of 5 — Welcome", the logo, the blurb and a single centered **Start** | `w1-welcome.png` |
| Reload | still `/setup`, still Welcome — the wizard survives a refresh | `w1b-reloaded.png` |
| Start | "Step 2 of 5 — Agents", the real `AgentSettingsScreen` inside the wizard chrome; **Next enabled** | `w2-agents.png` |
| Next | "Step 3 of 5 — Git Hosting" — `GitHostingScreen` with Import/Connect actions | `w3-forges.png` |
| Next | "Step 4 of 5 — Connect Your Phone": `POST /devices` → 200, QR rendered, the minted `http://127.0.0.1:<port>/pair?t=…` printed in mono below it, Copy/Refresh | `w4-phone.png` |
| The phone | a SECOND browser context (390×844) opens the minted link → `GET /devices` shows the `phone` row's `last_seen_at` set, and the step flips to **"Your phone is connected"** + "Connect another phone" | `w4b-phone-app.png`, `w4c-phone-connected.png` |
| Next | "Step 5 of 5 — Done": "You're all set!" and a single **Create your first session**, no footer | `w5-done.png` |
| Create your first session | `PUT /settings/config` → 200, config reads `"onboarded":true`, the wizard is replaced by the shell at **`/new`** (launcher, "Let's build", workspace list in the sidebar, push banner overlaid at the top) | `w6-shell.png` |
| Reload | no wizard — the shell comes back | `w7-reloaded-shell.png` |
| Deep links after setup | `/s/<id>` reopens that chat, `/new` reopens the launcher (the `onboarded == null` fix above) | `w8-deeplink-session.png`, `w9-deeplink-new.png` |

Network writes seen across the whole run: exactly `POST /devices -> 200` and
`PUT /settings/config -> 200`. **No `pageerror`**, no app console errors (only the known headless
WebGL driver warnings).

**The Agents gate was NOT observed holding**, and the reason is the fixture, not the code: this
host's real agent credential files are visible to the broker's detector, so the fixture's
`/agents/status` answered `installed:true, authed:true` for all five kinds (claude, codex, cursor,
opencode, grok) — Next was enabled the moment the step opened, and the same leak shows on the Git
Hosting step, which offered "Import from gh (@AhmetHuseyinDOK)". The gate itself is covered by
`SetupWizardScreenTest` (jvm): Next stays disabled until `onStatusesChanged` reports an authed
agent. No `onboarded=true` shortcut was used anywhere in the run.

### Carry-forward for plan 5

- **The host-side viewing heartbeat was a duplicate and is gone.** `HostStore.ensureViewingHeartbeat()`
  (HostStore.kt:476) already re-asserts every 60 s from the store's own timer, on every platform, so
  task 5's `LaunchedEffect(foreground) { while … delay(heartbeatMs); fleet.reassertViewing() }` in
  `Main.kt` — and the `window.__smxHeartbeatMs` test hook it needed — sent the frame twice a minute
  for nothing. Removed in this commit; `reassertViewing()` on `HostStore`/`FleetStore` and its jvm
  test stay, because they are the API a host needs if a throttled or bfcached tab ever proves the
  store's timer unreliable. If plan 5 sees presence lapse in a backgrounded tab, that API is the
  place to re-add a host-driven cadence — with evidence this time.
- **Compose testTags are NOT in the accessibility DOM in this build.** `document.getElementById
  ("setup_wizard")` (and every other tag, including `#chat-view`) returns null; `document.body`
  holds three empty `div`s and the canvas. Every browser check in this plan therefore drives the
  page by **mouse coordinates + screenshots + broker-side network/state assertions**. Plan 5's
  Playwright journey must re-derive its selectors: either enable CMP's a11y DOM (if the version
  bump exposes it) or standardise on coordinate/visual driving plus broker assertions.
- **Tapping a push while a settings overlay is up does not pop the overlay** — `selectSession` +
  `setActiveView` change what is UNDER the overlay, and the shared code has always behaved this
  way (Android and iOS included). Shared behaviour, not a web bug; fix it in `:ui` or leave it.
- **The fixture leaks the host's real agent/forge credentials** into `/agents/status` and the Git
  Hosting step (see above). A journey that wants to exercise the Agents gate needs the detector
  pointed at the fixture `MUX_HOME` (or a `MUX_TEST_AGENTS_STATUS` stub).
- `scripts/test-broker-seed.ts` does NOT seed `onboarded`; the broker's own default config answers
  `onboarded:true` once the seeded session exists, so any wizard run must force `false` over
  `PUT /settings/config` first.
- The fixture's `bun src/main.ts` child can outlive a killed wrapper; scope any cleanup to the
  fixture's own port and never touch the live `:9898`.
