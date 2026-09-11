# Web → KMP, Plan 2 of 5: the real app shell in the browser

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `apps/web` mounts the shared `SupermuxApp` root behind a `WebPlatform`: cookie-session bootstrap (`/me` → secretless claim → pair screen), localStorage-backed settings/host/snapshot stores, URL ⇄ route sync, browser theme/input-mode, and browser tests running in headless Chrome. Chat, sessions, launcher and settings screens work end to end against a broker. Terminal/editor/VNC/mic stay on their "unavailable" no-ops until plan 3.

**Architecture:** Mirror `apps/ios/.../MainViewController.kt` line for line where it makes sense (deps → seeds → pairing gate → fleet → `SupermuxApp`), with browser-specific replacements: no `runBlocking` on wasm (seeds are awaited in a `MainScope` coroutine before mounting), pairing is decided by `GET /me` (the browser holds no bearer), the sole `PairedHost` is a synthetic record for the page origin with a blank token, and the URL is the browser's own back stack. The four chat seams that have no commonMain null-object (`ClipboardAccess`, `FileAccess`, `MicCapture`, `TtsEngine`) get small browser implementations that never throw during composition.

**Tech Stack:** Kotlin 2.3.21 / Compose Multiplatform 1.11.1 wasmJs, kotlinx-browser 0.5.0, Ktor 3.5 Js engine + `ktor-client-mock`, Karma + ChromeHeadless (`/usr/bin/google-chrome`) for `:web` tests.

**Spec:** `docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md` §3, §4 (`Platform` table), §5 (auth bootstrap), §7, §10 step 2. Plan 1 results and carry-forward notes: `docs/superpowers/plans/2026-09-11-web-kmp-plan1-wasm-targets.md`.

**Facts the implementer must not re-derive** (verified 2026-09-11 against the tree):
- `Platform` (apps/ui/.../platform/Platform.kt:26) abstract members: `caps`, `openUrl`, `copyToClipboard`, `pickFiles(kind, requester)`, `scanQr()`, `haptics`, `captureImage(requester)`, `captureVideo(requester)`, `pendingPicks(requester)`, `pendingScans()` (has default), `clipboard: ClipboardAccess`, `files: FileAccess`, `mic: MicCapture`, `tts: TtsEngine`, `notices: NoticeChannel`, `terminalView()`, `videoDecoder()`, `updates: AppUpdater`, `notifications: NotificationManager`, `windows: WindowHostController?`, `push: PushRegistrar?`, `editorEngine: EditorEngineFactory`. `Caps` fields: `push, camera, tray, externalDisplay, hardwareVideoDecode, localBroker, multiWindow, fileSystem` (required) + `clipboardImages, saveAs, walkthrough, appearanceControls, dynamicColor, appUpdate, terminal, scrcpy` (default false). `IOS_CAPS` at apps/ios/.../IosPlatform.kt:223 is the template.
- Chat seams (apps/ui/.../platform/ChatSeams.kt): `ClipboardAccess { suspend fun readImages(): List<PickedFile>; fun hasImage(): Boolean }`; `FileAccess { suspend fun saveAs(name, mime, bytes): SavedFile?; suspend fun openSaved(saved): Boolean; suspend fun openExternally(name, mime, bytes): Boolean; fun probeMime(name): String; suspend fun stageTemp(name, bytes): String? }`; `data class SavedFile(name, location)`; `MicCapture { fun start(): Boolean; fun stop(): CapturedAudio?; fun cancel(); suspend fun requestPermission(): Boolean; val available: Boolean; val liveTranscript: LiveTranscript? }`; `TtsEngine { suspend fun speak(text); fun stop(); suspend fun playAudioChunk(bytes); fun shutdown() }`; `NoticeChannel { fun show(text) }`. Ready no-ops: `NoAppUpdater`, `NoopNotificationManager` (ShellSeams.kt), `NoHaptics` (theme/Haptics.kt), `FlowNotices()` + `NoticeOverlay(notices, content)` (platform/FlowNotices.kt), `UnavailableTerminalViewFactory`, `UnavailableEditorEngineFactory(reason)`, `NoShellWindows`.
- `HostTheme(platform, appearance, textScale, uiPrefs, inputMode, pointerAvailable, widthClass, content)` (apps/ui/.../theme/HostTheme.kt:39) provides `LocalPlatform` & co. and wraps `SupermuxTheme`. `rememberWindowWidthClass(fallbackDp: Int? = null)` (adaptive/WindowSize.kt:116). `InputMode { Touch, Pointer }`. `AppearanceMode { SYSTEM, LIGHT, DARK }`.
- `UiPrefs(settings)`: `appearance(default): Flow<AppearanceMode>`, `putAppearance(mode)`, `textScale: Flow<Float>`, `collapsedProjectPaths: Flow<Set<String>>`, `suspend fun seedShellState(...)`: returns `ShellStateSeed(sidebarCollapsed, sidebarWidthDp, selectedSession)`, `seedLauncher(prefs, draft)`, `seedCollapsedProjectPaths(...)`, `seedAppearance(default, legacy)` — read the exact parameter lists in apps/ui/.../prefs/UiPrefs.kt before calling.
- `ShellUiState` (apps/ui/.../shell/ShellUiState.kt:84): `backStack: SnapshotStateList<Route>` rooted at `Route.Home`; `currentRoute`; `selectedId`; `navigate(route)` (pops to Home first); `goBack()`; `openLauncher(draftId?)`, `selectSession(id)`, `openArchived()`, `openDisplays()`, `openAddHost()`, `openUsage()`, `openSettings(section)`, `openLspSettings()`, `openPersonalAssistants()`, `openAppUpdate()`; `sidebarCollapsed`, `setSidebarWidth(Dp)`, `collapsedProjectPaths`, `appearance`, `windows`.
- `Route` (apps/ui/.../nav/Route.kt): `Home`, `NewSession(draftId="")`, `AddHost`, `Settings(section=Agents)`, `Usage`, `Devices`, `Archived`, `Proxies`, `Displays`, `Appearance`, `AppUpdate`. `SettingsSection`: `Agents, Devices, System, GitHosting, Proxies, Assistant, Curator, Voice, EditorLsp, PersonalAssistants`.
- `SupermuxApp(fleet, ui, modifier, notify, appearance, onToggleTheme, appForeground, homeFallback, stripChrome, sidebarTopPad, sidebarChrome, chatFallback, persistSelection, onLauncherDraftFlush, autoSelect, autoSelectName, defaultDeviceName, sessionListMode, groupByProject, onGroupByProjectChange, onAddedHost, settingsExtra, settingsSection)` (shell/SupermuxApp.kt:233). iOS passes: fleet, ui, appForeground, persistSelection=false, defaultDeviceName, groupByProject, onGroupByProjectChange, onAddedHost, settingsExtra = `FleetSettingsExtra(extra, scope)`, settingsSection = `FleetSettingsSection(section, scope, fleet)`.
- Fleet wiring: copy `buildFleet` from apps/ios/.../MainViewController.kt:362–399 verbatim, swapping `IosHostStores.store()`/`snapshotStore()` for the web stores and `IosWalkthroughSeam` for a `WebWalkthroughSeam` with the identical body (MainViewController.kt:61).
- Stores: `SettingsStore { fun string(key): Flow<String?>; suspend fun putString(key, value: String?) }` (shared/.../state/HostStoreDeps.kt:8) — the flow MUST be observed (iOS uses a callbackFlow; a `flowOf` would freeze the Appearance screen). `HostPersistence { loadAll(): List<PairedHost>; saveAll(hosts) }`; `SnapshotPersistence { loadAll(): List<HostSnapshot>; saveAll(snapshots) }`; `PairedHostStore(persistence) { newId }`; `HostSnapshotStore(persistence)`. `PairedHost(recordId, hostId?, displayName, directUrl?, relayUrl?, token, platform?, version?, lastSeenAt)`. iOS encodes host metadata with `HostMetaCodec` (shared/.../host, used by KeychainHostPersistence) and snapshots the way `IosSnapshotPersistence` does — reuse the same commonMain codecs.
- `BrokerApi(baseUrl, token, http)`: `me(): MeResult(paired, device)`, `getHost(): HostIdentity`, `pairClaim(claimSecret, deviceName): PairClaimResult`. **No `logout()`.** The secretless `POST /pair/claim` (no `claimSecret`) answers `{paired:true, name}` + `Set-Cookie` on a brand-new broker, `403 {error}` once any device exists or `onboarded` is true (src/channels/web/index.ts:1741–1756) — a different shape from `PairClaimResult`, so it needs its own DTO. `POST /logout` clears the cookie (index.ts:1697).
- Blank token is accepted end to end (`HttpRequestBuilder.bearer(token)` in shared/.../net/WsUrl.kt:25 sends nothing when blank); `FleetStore.effectiveUrl(h)` uses `relayUrl ?: directUrl`.
- On wasm there is **no `runBlocking`**; `SecureTokenStore.load()` is always null (web's `isPaired()` must come from `/me`).

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` | + `logout()`, + `claimSecretless(deviceName): SecretlessClaimResult`. |
| `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiWebSessionTest.kt` | MockEngine tests for both. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/state/LocalStorageSettingsStore.kt` | Observed `SettingsStore` over `localStorage` + sync `stringNow`. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/host/LocalStorageHostPersistence.kt` | `HostPersistence` + `SnapshotPersistence` over `localStorage`. |
| `apps/web/build.gradle.kts` | Karma/ChromeHeadless test task; `ktor-client-mock` test dep. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebPlatform.kt` | `Platform` impl + `WEB_CAPS`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebClipboard.kt` | `ClipboardAccess` (images via async clipboard API). |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebFiles.kt` | `FileAccess` (anchor download / blob tab) + `pickFiles` input element. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebMic.kt` | `NoWebMic` (available=false; real MediaRecorder in plan 3). |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/seams/WebTts.kt` | `speechSynthesis` speak/stop; audio chunks no-op until plan 3. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebAppState.kt` | foreground / pendingPushSessionId / pendingPairLink flows from DOM events. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/auth/CookieSession.kt` | `/me` → secretless claim → `Paired`/`Unpaired` state; `logout()`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/auth/WebPairScreen.kt` | Unpaired screen: instructions + paste a pairing link. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/nav/UrlRoutes.kt` | Pure `pathFor` / `parsePath` (testable, no DOM). |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/nav/UrlSync.kt` | `history.pushState` / `popstate` ⇄ `ShellUiState`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebTheme.kt` | `HostTheme` + pointer detection + width class + `NoticeOverlay`. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebHostStores.kt` | Store singletons + synthetic origin host. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt` | Rewritten: seeds → gate → fleet → `SupermuxApp`. |
| `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/...` | `UrlRoutesTest`, `LocalStorageSettingsStoreTest`, `CookieSessionTest`. |

---

### Task 1: Browser test lane + `:shared` web-session API

**Files:**
- Modify: `apps/web/build.gradle.kts`
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
- Create: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiWebSessionTest.kt`
- Create: `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/SmokeTest.kt`

- [ ] **Step 1: Enable Karma + ChromeHeadless for `:web`**

In `apps/web/build.gradle.kts` change the `browser { … }` block to:

```kotlin
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
            // Browser-only tests (URL sync, localStorage stores, the cookie bootstrap) run in
            // headless Chrome through Karma. CHROME_BIN must point at a WasmGC-capable Chrome;
            // this host has /usr/bin/google-chrome. `:shared`/`:ui` keep their wasm test task off.
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
```
and add to `wasmJsTest.dependencies`: `implementation(libs.ktor.client.mock)`.

Create `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/SmokeTest.kt`:

```kotlin
package dev.supermux.web

import kotlinx.browser.window
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun runsInARealBrowser() {
        assertTrue(window.location.href.startsWith("http"), "Karma should serve the test page over http")
    }
}
```

Run: `cd apps && CHROME_BIN=/usr/bin/google-chrome ./gradlew :web:wasmJsBrowserTest --console=plain 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`, 1 test passed. If Karma complains about the sandbox, add a `apps/web/karma.config.d/chrome.js`:
```js
config.set({ browsers: ["ChromeHeadlessNoSandbox"], customLaunchers: { ChromeHeadlessNoSandbox: { base: "ChromeHeadless", flags: ["--no-sandbox", "--disable-gpu"] } } });
```

- [ ] **Step 2: Failing tests for `logout()` and `claimSecretless()`**

`apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiWebSessionTest.kt`:

```kotlin
package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrokerApiWebSessionTest {
    private fun json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        { _: Any -> respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    @Test
    fun logoutPostsToLogoutWithoutABody() = runTest {
        var seen: Pair<HttpMethod, String>? = null
        val engine = MockEngine { req -> seen = req.method to req.url.encodedPath; respond("", HttpStatusCode.NoContent) }
        BrokerApi("http://b.test", "", HttpClient(engine)).logout()
        assertEquals(HttpMethod.Post to "/logout", seen)
    }

    @Test
    fun secretlessClaimParsesThePairedShape() = runTest {
        var body = ""
        val engine = MockEngine { req ->
            body = req.body.toByteArray().decodeToString()
            respond("""{"paired":true,"name":"chrome"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val r = BrokerApi("http://b.test", "", HttpClient(engine)).claimSecretless("chrome")
        assertTrue(r.paired); assertEquals("chrome", r.name)
        assertTrue(body.contains("\"name\":\"chrome\""), body)
        assertFalse(body.contains("claimSecret"), "secretless claim must not send a claimSecret key: $body")
    }

    @Test
    fun secretlessClaimOn403IsUnpairedNotAnException() = runTest {
        val engine = MockEngine { respond("""{"error":"already set up — use normal pairing"}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json")) }
        val r = BrokerApi("http://b.test", "", HttpClient(engine)).claimSecretless("chrome")
        assertFalse(r.paired)
        assertEquals("already set up — use normal pairing", r.error)
    }
}
```
(`req.body.toByteArray()` — use `io.ktor.client.engine.mock.toByteArray`. If `BrokerApi`'s constructor parameter names differ, use positional args as above.)

Run: `cd apps && ./gradlew :shared:jvmTest --tests 'dev.supermux.net.BrokerApiWebSessionTest' --console=plain 2>&1 | tail -8` → expected: compilation FAILS (`logout`, `claimSecretless` unresolved).

- [ ] **Step 3: Implement in `BrokerApi`**

Next to `pairClaim` (BrokerApi.kt ~:1354) add:

```kotlin
    /** POST /logout — expire the browser's `cmux_token` cookie. Native hosts never call it. */
    suspend fun logout() {
        http.post("$baseUrl/logout") { authHeader() }
    }

    /**
     * POST /pair/claim with NO claim secret — trust-on-first-connect on a brand-new broker. The
     * broker answers `{paired:true,name}` and sets the session cookie; once any device exists (or
     * onboarding finished) it answers 403 with `{error}`. A different shape from [pairClaim], so a
     * different DTO: the browser bootstrap reads [SecretlessClaimResult.paired], never a token.
     */
    suspend fun claimSecretless(deviceName: String): SecretlessClaimResult {
        val res = http.post("$baseUrl/pair/claim") {
            authHeader()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", deviceName) })
        }
        val text = res.bodyAsText()
        return runCatching { json.decodeFromString<SecretlessClaimResult>(text) }
            .getOrElse { SecretlessClaimResult(paired = false, error = "HTTP ${res.status.value}") }
            .let { if (res.status.value == 403 && it.error == null) it.copy(error = "already set up") else it }
    }
```
and the DTO near `PairClaimResult` (~:67):
```kotlin
/** `POST /pair/claim` without a secret. `paired=false` + [error] on 403. */
@Serializable
data class SecretlessClaimResult(val paired: Boolean = false, val name: String = "", val error: String? = null)
```
Use whatever `Json` instance and `postJson`/`setBody` helpers `pairClaim` itself uses (open it and mirror; the class has a lenient `json` with `ignoreUnknownKeys = true`). Imports: `io.ktor.client.request.post`, `setBody`, `io.ktor.http.contentType`, `ContentType`, `io.ktor.client.statement.bodyAsText`, `kotlinx.serialization.json.buildJsonObject`, `put`.

Run the test again → 3 pass. Then `./gradlew :shared:compileKotlinWasmJs --console=plain 2>&1 | tail -3` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add apps/web/build.gradle.kts apps/web/src/wasmJsTest apps/web/karma.config.d apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiWebSessionTest.kt
git commit -m "feat(web,shared): headless-Chrome test lane for :web; BrokerApi logout() and the secretless cookie claim

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 2: localStorage stores in `:shared` wasmJsMain

**Files:**
- Create: `apps/shared/src/wasmJsMain/kotlin/dev/supermux/state/LocalStorageSettingsStore.kt`
- Create: `apps/shared/src/wasmJsMain/kotlin/dev/supermux/host/LocalStorageHostPersistence.kt`
- Test: `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/LocalStorageSettingsStoreTest.kt`, `.../LocalStorageHostPersistenceTest.kt`

- [ ] **Step 1: Failing tests (they run in `:web`'s Karma lane)**

`apps/web/src/wasmJsTest/kotlin/dev/supermux/web/LocalStorageSettingsStoreTest.kt`:

```kotlin
package dev.supermux.web

import dev.supermux.state.LocalStorageSettingsStore
import kotlinx.browser.localStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStorageSettingsStoreTest {
    @BeforeTest fun clear() = localStorage.clear()

    @Test fun missingKeyIsNull() = runTest {
        assertNull(LocalStorageSettingsStore().string("nope").first())
    }

    @Test fun putThenRead() = runTest {
        val s = LocalStorageSettingsStore()
        s.putString("appearance:mode", "DARK")
        assertEquals("DARK", s.string("appearance:mode").first())
        assertEquals("DARK", s.stringNow("appearance:mode"))
        assertEquals("DARK", localStorage.getItem("supermux:appearance:mode"))
    }

    @Test fun putNullRemoves() = runTest {
        val s = LocalStorageSettingsStore()
        s.putString("k", "v"); s.putString("k", null)
        assertNull(s.string("k").first()); assertNull(localStorage.getItem("supermux:k"))
    }

    @Test fun flowObservesLaterWrites() = runTest {
        val s = LocalStorageSettingsStore()
        val seen = mutableListOf<String?>()
        val job = kotlinx.coroutines.launch { s.string("k").collect { seen += it; if (seen.size == 3) cancel() } }
        s.putString("k", "1"); s.putString("k", "2")
        job.join()
        assertEquals(listOf(null, "1", "2"), seen)
    }
}
```

`apps/web/src/wasmJsTest/kotlin/dev/supermux/web/LocalStorageHostPersistenceTest.kt`:

```kotlin
package dev.supermux.web

import dev.supermux.host.HostSnapshot
import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.LocalStorageSnapshotPersistence
import dev.supermux.host.PairedHost
import kotlinx.browser.localStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalStorageHostPersistenceTest {
    @BeforeTest fun clear() = localStorage.clear()

    @Test fun hostsRoundTrip() {
        val p = LocalStorageHostPersistence()
        val h = PairedHost(recordId = "web", hostId = "h1", displayName = "ustalabs", directUrl = "http://x", token = "", platform = "linux", version = "dev", lastSeenAt = 5L)
        p.saveAll(listOf(h))
        assertEquals(listOf(h), LocalStorageHostPersistence().loadAll())
    }

    @Test fun snapshotsRoundTrip() {
        val p = LocalStorageSnapshotPersistence()
        p.saveAll(listOf(HostSnapshot(recordId = "web", fetchedAt = 9L, brokerVersion = "dev")))
        assertEquals(listOf("web"), LocalStorageSnapshotPersistence().loadAll().map { it.recordId })
    }

    @Test fun corruptJsonLoadsEmpty() {
        localStorage.setItem("supermux:hosts", "{not json")
        assertEquals(emptyList(), LocalStorageHostPersistence().loadAll())
    }
}
```

Run `CHROME_BIN=/usr/bin/google-chrome ./gradlew :web:wasmJsBrowserTest` → compile failure (classes missing).

- [ ] **Step 2: Implement**

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/state/LocalStorageSettingsStore.kt`:

```kotlin
package dev.supermux.state

import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * `SettingsStore` over `window.localStorage`, keys prefixed `supermux:`.
 *
 * [string] must be OBSERVED, not a one-shot read — the Appearance screen repaints from it (iOS
 * uses a callbackFlow for the same reason). localStorage has no same-tab change event, so every
 * [putString] bumps a revision that re-reads the key; the cross-tab `storage` event bumps it too.
 */
class LocalStorageSettingsStore(private val prefix: String = "supermux:") : SettingsStore {
    private val revision = MutableStateFlow(0)

    init {
        window.addEventListener("storage", { revision.value++ })
    }

    /** Synchronous read for the launch-time seeds (wasm has no `runBlocking`). */
    fun stringNow(key: String): String? = localStorage[prefix + key]

    override fun string(key: String): Flow<String?> =
        revision.map { stringNow(key) }.distinctUntilChanged()

    override suspend fun putString(key: String, value: String?) {
        if (value == null) localStorage.removeItem(prefix + key) else localStorage[prefix + key] = value
        revision.value++
    }
}
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/host/LocalStorageHostPersistence.kt`:

```kotlin
package dev.supermux.host

import kotlinx.browser.localStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.get
import org.w3c.dom.set

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The browser's host registry. There is exactly one host — the page origin — and its token is
 * blank (the cookie is the credential), so this is metadata only. Corrupt or missing JSON loads
 * as empty, which the caller treats as "not set up yet".
 */
class LocalStorageHostPersistence(private val key: String = "supermux:hosts") : HostPersistence {
    override fun loadAll(): List<PairedHost> =
        localStorage[key]?.let { runCatching { json.decodeFromString(ListSerializer(PairedHost.serializer()), it) }.getOrNull() }.orEmpty()

    override fun saveAll(hosts: List<PairedHost>) {
        localStorage[key] = json.encodeToString(ListSerializer(PairedHost.serializer()), hosts)
    }
}

/** Last-seen session lists per host, so the sidebar paints before the socket connects. */
class LocalStorageSnapshotPersistence(private val key: String = "supermux:snapshots") : SnapshotPersistence {
    override fun loadAll(): List<HostSnapshot> =
        localStorage[key]?.let { runCatching { json.decodeFromString(ListSerializer(HostSnapshot.serializer()), it) }.getOrNull() }.orEmpty()

    override fun saveAll(snapshots: List<HostSnapshot>) {
        localStorage[key] = json.encodeToString(ListSerializer(HostSnapshot.serializer()), snapshots)
    }
}
```
If `PairedHost` / `HostSnapshot` are not `@Serializable`, do NOT annotate them in commonMain blindly: check how `KeychainHostPersistence` (`HostMetaCodec`) and `IosSnapshotPersistence` encode them and reuse those commonMain codecs instead (same shape, different call). `SessionInfo` inside `HostSnapshot` is already `@Serializable` (it comes off the wire).

Run the Karma tests → all pass. Also `./gradlew :shared:compileKotlinWasmJs`.

- [ ] **Step 3: Commit**

```bash
git add apps/shared/src/wasmJsMain apps/web/src/wasmJsTest
git commit -m "feat(shared): localStorage-backed settings, host and snapshot stores for the browser

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 3: `WebPlatform` and the four chat seams

**Files:**
- Create: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebPlatform.kt`, `seams/WebClipboard.kt`, `seams/WebFiles.kt`, `seams/WebMic.kt`, `seams/WebTts.kt`

- [ ] **Step 1: Seams**

`seams/WebMic.kt`:
```kotlin
package dev.supermux.web.seams

import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture

/** Plan 2 has no dictation; `available=false` hides the mic button. Plan 3 brings MediaRecorder. */
object NoWebMic : MicCapture {
    override fun start(): Boolean = false
    override fun stop(): CapturedAudio? = null
    override fun cancel() = Unit
    override suspend fun requestPermission(): Boolean = false
    override val available: Boolean = false
    override val liveTranscript: LiveTranscript? = null
}
```

`seams/WebTts.kt`:
```kotlin
package dev.supermux.web.seams

import dev.supermux.ui.platform.TtsEngine

@Suppress("UNUSED_PARAMETER")
private fun speakJs(text: String): Unit = js("{ window.speechSynthesis.cancel(); window.speechSynthesis.speak(new SpeechSynthesisUtterance(text)); }")
private fun cancelJs(): Unit = js("window.speechSynthesis.cancel()")

/**
 * Read-aloud through the browser's own synthesiser. Broker-streamed audio chunks
 * ([playAudioChunk]) need an AudioContext decoder and arrive in plan 3; until then they are
 * dropped silently rather than thrown — this object is read during composition.
 */
object WebTts : TtsEngine {
    override suspend fun speak(text: String) = speakJs(text)
    override fun stop() = cancelJs()
    override suspend fun playAudioChunk(bytes: ByteArray) = Unit
    override fun shutdown() = cancelJs()
}
```

`seams/WebClipboard.kt`:
```kotlin
package dev.supermux.web.seams

import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.PickedFile

/**
 * Pasted images. The async Clipboard API can only be queried inside a user gesture and is
 * promise-based, so [hasImage] cannot answer synchronously; plan 2 reports none
 * (`Caps.clipboardImages=false` hides the affordance). Plan 3 wires `navigator.clipboard.read()`.
 */
object WebClipboard : ClipboardAccess {
    override suspend fun readImages(): List<PickedFile> = emptyList()
    override fun hasImage(): Boolean = false
}
```

`seams/WebFiles.kt`:
```kotlin
package dev.supermux.web.seams

import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.toUint8Array
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.get
import kotlin.coroutines.resume

@OptIn(ExperimentalUnsignedTypes::class)
private fun blobOf(bytes: ByteArray, mime: String): Blob =
    Blob(arrayOf<Any?>(bytes.asUByteArray().toUint8Array()).toJsArray(), BlobPropertyBag(type = mime))

/** "Save as…" is a download; "open with" is a new tab on a blob URL. Nothing touches a real path. */
object WebFiles : FileAccess {
    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        val url = URL.createObjectURL(blobOf(bytes, mime))
        val a = document.createElement("a") as HTMLAnchorElement
        a.href = url; a.download = name; a.click()
        window.setTimeout({ URL.revokeObjectURL(url) }, 10_000)
        return SavedFile(name = name, location = "Downloads")
    }
    override suspend fun openSaved(saved: SavedFile): Boolean = false
    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean {
        val url = URL.createObjectURL(blobOf(bytes, mime))
        window.open(url, "_blank", "noopener")
        window.setTimeout({ URL.revokeObjectURL(url) }, 60_000)
        return true
    }
    override fun probeMime(name: String): String = mimeForFileName(name) ?: "application/octet-stream"
    override suspend fun stageTemp(name: String, bytes: ByteArray): String? = null
}

/** A hidden `<input type=file>` per pick; resolves with the chosen files or empty on cancel. */
suspend fun pickFilesViaInput(kind: PickKind): List<PickedFile> = suspendCancellableCoroutine { cont ->
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"; input.multiple = true
    input.accept = when (kind) { PickKind.Any -> ""; PickKind.Images -> "image/*"; PickKind.Media -> "image/*,video/*" }
    input.style.display = "none"
    document.body?.appendChild(input)
    var done = false
    fun finish(files: List<PickedFile>) { if (!done) { done = true; input.remove(); cont.resume(files) } }
    input.addEventListener("change", {
        val list = input.files
        finish((0 until (list?.length ?: 0)).mapNotNull { list!![it] }.map { f ->
            PickedFile(f.name, f.type.ifBlank { mimeForFileName(f.name) ?: "application/octet-stream" }, BlobChunkSource(f))
        })
    })
    // No reliable cancel event; the next focus-in after the dialog closes with no change means cancel.
    window.addEventListener("focus", { window.setTimeout({ finish(emptyList()) }, 500) }, js("({ once: true })"))
    input.click()
    cont.invokeOnCancellation { input.remove() }
}
```
(`toJsArray()` / `arrayOf<Any?>` may need the `JsArray` helpers from kotlinx-browser or a small `js("[bytes]")` helper — adapt to what compiles; the BlobPropertyBag constructor is `BlobPropertyBag(type = mime)`.)

- [ ] **Step 2: `WebPlatform`**

`apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebPlatform.kt`:
```kotlin
package dev.supermux.web

import dev.supermux.ui.display.VideoSurfaceFactory
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.UnavailableEditorEngineFactory
import dev.supermux.ui.platform.*
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.terminal.UnavailableTerminalViewFactory
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import dev.supermux.web.seams.*
import kotlinx.browser.window
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach

/** What the browser can do. Plan 3 turns on terminal/clipboardImages; plan 4 turns on push. */
val WEB_CAPS = Caps(
    push = false, camera = false, tray = false, externalDisplay = true, hardwareVideoDecode = false,
    localBroker = false, multiWindow = false, fileSystem = false,
    clipboardImages = false, saveAs = true, walkthrough = true, appearanceControls = true,
    dynamicColor = false, appUpdate = false, terminal = false, scrcpy = false,
)

/**
 * The browser's [Platform] — the fourth host of the shared Compose root. Every member ANSWERS
 * (several are read during composition); "not here" is said through [caps], never by throwing.
 */
class WebPlatform : Platform {
    override val caps: Caps = WEB_CAPS
    override fun openUrl(url: String) { window.open(url, "_blank", "noopener") }
    override fun copyToClipboard(text: String) { copyTextJs(text) }
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> = pickFilesViaInput(kind)
    override suspend fun scanQr(): String? = null
    override val haptics: Haptics = NoHaptics
    override suspend fun captureImage(requester: String): PickedFile? = null
    override suspend fun captureVideo(requester: String): PickedFile? = null
    override fun pendingPicks(requester: String): Flow<PickedFile> = emptyFlow()
    /** A `/pair?t=` link opened while already paired = "add this host", same door as iOS. */
    override fun pendingScans(): Flow<String> =
        WebAppState.pendingPairLink.filterNotNull().onEach { WebAppState.consumePendingPairLink() }
    override val clipboard: ClipboardAccess = WebClipboard
    override val files: FileAccess = WebFiles
    override val mic: MicCapture = NoWebMic
    override val tts: TtsEngine = WebTts
    override val notices: FlowNotices = FlowNotices()
    override fun terminalView(): TerminalViewFactory = UnavailableTerminalViewFactory
    override fun videoDecoder(): VideoSurfaceFactory? = null
    override val updates: AppUpdater = NoAppUpdater
    override val notifications: NotificationManager = NoopNotificationManager
    override val windows: WindowHostController? = null
    override val push: PushRegistrar? = null
    override val editorEngine: EditorEngineFactory = UnavailableEditorEngineFactory("The editor arrives in the next step of the web migration")
}

@Suppress("UNUSED_PARAMETER")
private fun copyTextJs(text: String): Unit = js("{ navigator.clipboard && navigator.clipboard.writeText(text); }")
```
(`FlowNotices` type on `notices` so `WebTheme` can hand it to `NoticeOverlay`.)

- [ ] **Step 3: Compile + commit**

`./gradlew :web:compileKotlinWasmJs --console=plain 2>&1 | tail -20` → BUILD SUCCESSFUL (WebAppState comes in Task 4 — create it first if the compiler needs it: Task 4 Step 1 is independent, do it now and fold both into this commit).

```bash
git add apps/web/src/wasmJsMain
git commit -m "feat(web): WebPlatform with browser clipboard/file/tts seams and caps

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 4: `WebAppState`, `CookieSession`, `WebPairScreen`

**Files:**
- Create: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebAppState.kt`, `auth/CookieSession.kt`, `auth/WebPairScreen.kt`
- Test: `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/CookieSessionTest.kt`

- [ ] **Step 1: `WebAppState`** (the DOM twin of `IosAppState`)

```kotlin
package dev.supermux.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.w3c.dom.MessageEvent

/**
 * Signals that flow from the browser INTO the Compose root, as state (a tap can arrive before
 * the root exists). [foreground] follows `document.visibilityState` — VISIBLE, not focused, the
 * same choice iOS makes, so a chat readable behind another window keeps suppressing pushes.
 */
object WebAppState {
    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _pendingPushSessionId = MutableStateFlow<String?>(null)
    val pendingPushSessionId: StateFlow<String?> = _pendingPushSessionId.asStateFlow()
    fun setPendingPushSessionId(id: String?) { _pendingPushSessionId.value = id }
    fun consumePendingPushSessionId() { _pendingPushSessionId.value = null }

    private val _pendingPairLink = MutableStateFlow<String?>(null)
    val pendingPairLink: StateFlow<String?> = _pendingPairLink.asStateFlow()
    fun setPendingPairLink(url: String?) { _pendingPairLink.value = url }
    fun consumePendingPairLink() { _pendingPairLink.value = null }

    /** Install the DOM listeners once, from `main()`. */
    fun install() {
        _foreground.value = document.visibilityState.toString() != "hidden"
        document.addEventListener("visibilitychange", { _foreground.value = document.visibilityState.toString() != "hidden" })
        // sw.js (plan 4) posts {type:"navigate", to:"/s/<id>"} on notification click.
        window.navigator.serviceWorker?.addEventListener("message", { ev ->
            val to = navigateTarget((ev as MessageEvent).data)
            if (to != null) setPendingPushSessionId(to.removePrefix("/s/"))
        })
    }
}

@Suppress("UNUSED_PARAMETER")
private fun navigateTarget(data: JsAny?): String? =
    js("(data && data.type === 'navigate' && typeof data.to === 'string' && data.to.startsWith('/s/')) ? data.to : null")
```
(`document.visibilityState` is an enum-like external in kotlinx-browser — compare via `.toString()` or the `DocumentVisibilityState` constants; `navigator.serviceWorker` may be typed non-null — adapt.)

- [ ] **Step 2: Failing `CookieSessionTest`**

```kotlin
package dev.supermux.web

import dev.supermux.web.auth.CookieSession
import dev.supermux.web.auth.SessionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CookieSessionTest {
    private fun client(vararg replies: Pair<String, Pair<Int, String>>): HttpClient {
        val map = replies.toMap()
        return HttpClient(MockEngine { req ->
            val (code, body) = map[req.url.encodedPath] ?: (404 to "{}")
            respond(body, HttpStatusCode.fromValue(code), headersOf(HttpHeaders.ContentType, "application/json"))
        })
    }

    @Test fun pairedWhenMeSaysSo() = runTest {
        val s = CookieSession("http://b.test", client("/me" to (200 to """{"paired":true,"device":"chrome"}""")))
        assertEquals(SessionState.Paired("chrome"), s.probe())
    }

    @Test fun freshBrokerClaimsSecretlessly() = runTest {
        val s = CookieSession("http://b.test", client(
            "/me" to (401 to """{"error":"unauthorized"}"""),
            "/pair/claim" to (200 to """{"paired":true,"name":"setup"}"""),
        ))
        assertEquals(SessionState.Paired("setup"), s.probe())
    }

    @Test fun setUpBrokerIsUnpaired() = runTest {
        val s = CookieSession("http://b.test", client(
            "/me" to (401 to """{"error":"unauthorized"}"""),
            "/pair/claim" to (403 to """{"error":"already set up — use normal pairing"}"""),
        ))
        assertIs<SessionState.Unpaired>(s.probe())
    }

    @Test fun networkFailureIsOffline() = runTest {
        val s = CookieSession("http://b.test", HttpClient(MockEngine { throw RuntimeException("boom") }))
        assertIs<SessionState.Offline>(s.probe())
    }
}
```
(Check how `BrokerApi.me()` behaves on 401 — if it throws, `CookieSession` must catch and treat it as unpaired; if it returns `paired=false`, branch on that.)

- [ ] **Step 3: `CookieSession`**

```kotlin
package dev.supermux.web.auth

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import kotlinx.browser.window

sealed interface SessionState {
    data class Paired(val deviceName: String?) : SessionState
    data class Unpaired(val reason: String) : SessionState
    data class Offline(val error: String) : SessionState
}

/**
 * The browser's pairing gate — the web twin of iOS's `isPaired()`, which cannot work here because
 * the credential is an HttpOnly cookie Kotlin can never read. Mirrors `App.vue`:
 *  1. `GET /me` — paired ⇒ start.
 *  2. Otherwise `POST /pair/claim` with no secret: 200 ⇒ the broker set the cookie (trust on
 *     first connect, brand-new broker); 403 ⇒ someone else already paired, show the pair screen.
 * Both requests carry no bearer (`token = ""`), so the cookie is the only credential in play.
 */
class CookieSession(baseUrl: String = window.location.origin, http: HttpClient) {
    private val api = BrokerApi(baseUrl, "", http)

    suspend fun probe(deviceName: String = "browser"): SessionState {
        val me = runCatching { api.me() }
        me.getOrNull()?.let { if (it.paired) return SessionState.Paired(it.device) }
        val meError = me.exceptionOrNull()
        if (meError != null && !meError.isHttpStatus()) return SessionState.Offline(meError.message ?: "offline")
        val claim = runCatching { api.claimSecretless(deviceName) }.getOrElse { e ->
            return if (e.isHttpStatus()) SessionState.Unpaired(e.message ?: "claim rejected") else SessionState.Offline(e.message ?: "offline")
        }
        return if (claim.paired) SessionState.Paired(claim.name.ifBlank { deviceName })
        else SessionState.Unpaired(claim.error ?: "not paired")
    }

    /** `POST /logout` expires the cookie; the page reloads into the pair screen. */
    suspend fun logout() {
        runCatching { api.logout() }
        window.location.reload()
    }
}

/** Ktor's engine throws for transport failures; an HTTP status is a *response*, not "offline". */
private fun Throwable.isHttpStatus(): Boolean =
    this is io.ktor.client.plugins.ResponseException || this is io.ktor.client.plugins.ClientRequestException
```
Adjust to `BrokerApi.me()`'s real failure mode (read `getJson`/`decode` in BrokerApi.kt: if a 401 becomes an exception, that exception's class decides `isHttpStatus`). Run the Karma tests → 4 pass.

- [ ] **Step 4: `WebPairScreen`**

```kotlin
package dev.supermux.web.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.window

/**
 * Shown when the cookie session is missing and the broker refused the secretless claim. A browser
 * pairs by NAVIGATING to a `/pair?t=<token>` link on this origin — the broker sets the cookie and
 * redirects back — so the only affordance is "open that link here". Links for another origin are
 * refused with a notice rather than silently sending the token elsewhere.
 */
@Composable
fun WebPairScreen(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        val origin = window.location.origin
        val trimmed = link.trim()
        when {
            trimmed.startsWith("$origin/pair?t=") -> window.location.href = trimmed
            trimmed.startsWith("/pair?t=") -> window.location.href = origin + trimmed
            else -> error = "That link is for a different host. Open it in this browser, or paste the link this broker generated."
        }
    }
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pair this browser", style = MaterialTheme.typography.headlineSmall)
            Text("This broker is already set up ($reason). From a paired device open Settings › Devices, add a device, and open the link it shows here — or paste it below.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(value = link, onValueChange = { link = it; error = null }, label = { Text("Pairing link") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), isError = error != null,
                supportingText = error?.let { { Text(it) } }, keyboardActions = KeyboardActions(onGo = { go() }))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::go, enabled = link.isNotBlank()) { Text("Open link") }
                TextButton(onClick = onRetry) { Text("Check again") }
            }
        }
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add apps/web/src
git commit -m "feat(web): cookie-session bootstrap (/me → secretless claim → pair screen) and the DOM app-state bus

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 5: URL ⇄ route sync

**Files:**
- Create: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/nav/UrlRoutes.kt`, `nav/UrlSync.kt`
- Test: `apps/web/src/wasmJsTest/kotlin/dev/supermux/web/UrlRoutesTest.kt`

- [ ] **Step 1: Failing tests (pure, no DOM)**

```kotlin
package dev.supermux.web

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.web.nav.UrlTarget
import dev.supermux.web.nav.parsePath
import dev.supermux.web.nav.pathFor
import kotlin.test.Test
import kotlin.test.assertEquals

class UrlRoutesTest {
    @Test fun homeIsRoot() = assertEquals("/", pathFor(Route.Home, selectedId = null))
    @Test fun selectedSessionIsS() = assertEquals("/s/abc", pathFor(Route.Home, selectedId = "abc"))
    @Test fun launcherWithDraft() = assertEquals("/new?draft=d1", pathFor(Route.NewSession("d1"), null))
    @Test fun launcherNoDraft() = assertEquals("/new", pathFor(Route.NewSession(), null))
    @Test fun settingsSections() {
        assertEquals("/settings/voice", pathFor(Route.Settings(SettingsSection.Voice), null))
        assertEquals("/settings/git-hosting", pathFor(Route.Settings(SettingsSection.GitHosting), null))
        assertEquals("/settings/editor", pathFor(Route.Settings(SettingsSection.EditorLsp), null))
        assertEquals("/personal-assistants", pathFor(Route.Settings(SettingsSection.PersonalAssistants), null))
    }
    @Test fun topLevels() {
        assertEquals("/usage", pathFor(Route.Usage, null)); assertEquals("/devices", pathFor(Route.Devices, null))
        assertEquals("/archived", pathFor(Route.Archived, null)); assertEquals("/proxies", pathFor(Route.Proxies, null))
        assertEquals("/displays", pathFor(Route.Displays, null)); assertEquals("/settings/appearance", pathFor(Route.Appearance, null))
        assertEquals("/settings/updates", pathFor(Route.AppUpdate, null)); assertEquals("/add-host", pathFor(Route.AddHost, null))
    }
    @Test fun parseRoundTrips() {
        val cases = listOf(Route.Home, Route.NewSession("d1"), Route.Settings(SettingsSection.Curator), Route.Usage, Route.Devices,
            Route.Archived, Route.Proxies, Route.Displays, Route.Appearance, Route.AppUpdate, Route.AddHost,
            Route.Settings(SettingsSection.PersonalAssistants))
        for (r in cases) assertEquals(UrlTarget.Screen(r), parsePath(pathFor(r, null)), r.toString())
        assertEquals(UrlTarget.Session("abc"), parsePath("/s/abc"))
    }
    @Test fun unknownAndLegacyPathsGoHome() {
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/nope/what"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/setup"))              // wizard arrives in plan 4
        assertEquals(UrlTarget.Screen(Route.Settings(SettingsSection.Agents)), parsePath("/settings"))
        assertEquals(UrlTarget.Screen(Route.Home), parsePath("/settings/keyboard")) // dropped page
    }
}
```

- [ ] **Step 2: `UrlRoutes.kt`**

```kotlin
package dev.supermux.web.nav

import dev.supermux.ui.nav.Route
import dev.supermux.ui.nav.SettingsSection

/** What a browser path means: a screen, or the chat of one session. */
sealed interface UrlTarget {
    data class Screen(val route: Route) : UrlTarget
    data class Session(val id: String) : UrlTarget
}

private val sectionSlugs = mapOf(
    SettingsSection.Agents to "agents", SettingsSection.Devices to "devices", SettingsSection.System to "system",
    SettingsSection.GitHosting to "git-hosting", SettingsSection.Proxies to "proxies", SettingsSection.Assistant to "assistant",
    SettingsSection.Curator to "curator", SettingsSection.Voice to "voice", SettingsSection.EditorLsp to "editor",
)
private val slugSections = sectionSlugs.entries.associate { (k, v) -> v to k }

/** The URL for a route — the Vue router's table, kept so old bookmarks and `sw.js` clicks still land. */
fun pathFor(route: Route, selectedId: String?): String = when (route) {
    Route.Home -> if (selectedId != null) "/s/$selectedId" else "/"
    is Route.NewSession -> if (route.draftId.isBlank()) "/new" else "/new?draft=${route.draftId}"
    Route.AddHost -> "/add-host"
    is Route.Settings -> if (route.section == SettingsSection.PersonalAssistants) "/personal-assistants" else "/settings/${sectionSlugs.getValue(route.section)}"
    Route.Usage -> "/usage"
    Route.Devices -> "/devices"
    Route.Archived -> "/archived"
    Route.Proxies -> "/proxies"
    Route.Displays -> "/displays"
    Route.Appearance -> "/settings/appearance"
    Route.AppUpdate -> "/settings/updates"
}

fun parsePath(pathAndQuery: String): UrlTarget {
    val path = pathAndQuery.substringBefore('?').trimEnd('/').ifEmpty { "/" }
    val query = pathAndQuery.substringAfter('?', "")
    val segs = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    return when {
        segs.isEmpty() -> UrlTarget.Screen(Route.Home)
        segs[0] == "s" && segs.size == 2 -> UrlTarget.Session(segs[1])
        segs[0] == "new" -> UrlTarget.Screen(Route.NewSession(queryParam(query, "draft") ?: ""))
        segs[0] == "add-host" -> UrlTarget.Screen(Route.AddHost)
        segs[0] == "usage" -> UrlTarget.Screen(Route.Usage)
        segs[0] == "devices" -> UrlTarget.Screen(Route.Devices)
        segs[0] == "archived" -> UrlTarget.Screen(Route.Archived)
        segs[0] == "proxies" -> UrlTarget.Screen(Route.Proxies)
        segs[0] == "displays" -> UrlTarget.Screen(Route.Displays)
        segs[0] == "personal-assistants" -> UrlTarget.Screen(Route.Settings(SettingsSection.PersonalAssistants))
        segs[0] == "settings" && segs.size == 1 -> UrlTarget.Screen(Route.Settings(SettingsSection.Agents))
        segs[0] == "settings" && segs[1] == "appearance" -> UrlTarget.Screen(Route.Appearance)
        segs[0] == "settings" && segs[1] == "updates" -> UrlTarget.Screen(Route.AppUpdate)
        segs[0] == "settings" -> slugSections[segs[1]]?.let { UrlTarget.Screen(Route.Settings(it)) } ?: UrlTarget.Screen(Route.Home)
        else -> UrlTarget.Screen(Route.Home)
    }
}

private fun queryParam(query: String, key: String): String? =
    query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }
```

Run the Karma tests → all pass.

- [ ] **Step 3: `UrlSync.kt`**

```kotlin
package dev.supermux.web.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import dev.supermux.ui.nav.Route
import dev.supermux.ui.shell.ShellUiState
import kotlinx.browser.window
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Two-way binding between the address bar and [ShellUiState]. The shell's back stack stays the
 * source of truth; the URL mirrors it (`pushState` on change) and `popstate` (browser back/forward,
 * a `sw.js` click, a typed URL) applies the parsed target back onto the shell.
 */
fun applyTarget(ui: ShellUiState, target: UrlTarget) {
    when (target) {
        is UrlTarget.Session -> { ui.navigate(Route.Home); ui.selectSession(target.id) }
        is UrlTarget.Screen -> when (val r = target.route) {
            is Route.Settings -> ui.openSettings(r.section)
            is Route.NewSession -> ui.openLauncher(r.draftId.ifBlank { null })
            else -> ui.navigate(r)
        }
    }
}

@Composable
fun UrlSync(ui: ShellUiState) {
    // 1. Initial URL → shell, once.
    LaunchedEffect(Unit) { applyTarget(ui, parsePath(window.location.pathname + window.location.search)) }
    // 2. Shell → URL.
    LaunchedEffect(ui) {
        snapshotFlow { pathFor(ui.currentRoute, ui.selectedId) }.distinctUntilChanged().collect { path ->
            val current = window.location.pathname + window.location.search
            if (current != path) window.history.pushState(null, "", path)
        }
    }
    // 3. Browser back/forward → shell.
    DisposableEffect(ui) {
        val handler: (org.w3c.dom.events.Event) -> Unit = { applyTarget(ui, parsePath(window.location.pathname + window.location.search)) }
        window.addEventListener("popstate", handler)
        onDispose { window.removeEventListener("popstate", handler) }
    }
}
```
Note the ordering hazard: step 2's first emission must not `pushState` over the initial URL before step 1 applied it — `applyTarget` runs first because both effects start in composition order and step 1 is synchronous; if a visible flicker or a spurious history entry appears in the smoke test, gate step 2 behind a `var initialApplied` flag set at the end of step 1.

- [ ] **Step 4: Commit**

```bash
git add apps/web/src
git commit -m "feat(web): URL ⇄ route sync with the Vue router's path table

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 6: `WebTheme`, `WebHostStores`, the real `Main.kt`

**Files:**
- Create: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/WebTheme.kt`, `WebHostStores.kt`
- Rewrite: `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt`

- [ ] **Step 1: `WebTheme`** — the twin of `IosTheme`

```kotlin
package dev.supermux.web

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.rememberWindowWidthClass
import dev.supermux.ui.platform.NoticeOverlay
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.HostTheme
import kotlinx.browser.window

private fun finePointer(): Boolean = window.matchMedia("(pointer: fine)").matches

@Composable
fun WebTheme(platform: WebPlatform, appearance: AppearanceMode, textScale: Float, uiPrefs: UiPrefs, content: @Composable () -> Unit) {
    val pointer = remember { finePointer() }
    HostTheme(
        platform = platform, appearance = appearance, textScale = textScale, uiPrefs = uiPrefs,
        inputMode = if (pointer) InputMode.Pointer else InputMode.Touch, pointerAvailable = pointer,
        widthClass = rememberWindowWidthClass(),
    ) { NoticeOverlay(platform.notices, content) }
}
```

- [ ] **Step 2: `WebHostStores`**

```kotlin
package dev.supermux.web

import dev.supermux.host.HostSnapshotStore
import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.LocalStorageSnapshotPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import kotlinx.browser.window

/** The browser has exactly one host: the origin that served the page. Token blank = cookie. */
object WebHostStores {
    const val RECORD_ID = "web-origin"
    val store: PairedHostStore by lazy { PairedHostStore(LocalStorageHostPersistence()) { RECORD_ID } }
    val snapshots: HostSnapshotStore by lazy { HostSnapshotStore(LocalStorageSnapshotPersistence()) }

    /** Make sure the origin host exists (first paired launch) and its display name is current. */
    fun ensureOriginHost(displayName: String, hostId: String?, platform: String?, version: String?) {
        val existing = store.list().firstOrNull { it.recordId == RECORD_ID }
        if (existing == null) {
            store.add(displayName = displayName, token = "", relayUrl = null, directUrl = window.location.origin, hostId = hostId, platform = platform, version = version)
        }
    }
}
```
(Match `PairedHostStore.add`'s real parameter list; if `add` mints its own recordId, pass the `newId` lambda as above so the record is stable.)

- [ ] **Step 3: `Main.kt`** — replace the hello screen

```kotlin
package dev.supermux.web

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.host.WalkthroughSeam
import dev.supermux.net.ServerFrame
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.LocalStorageSettingsStore
import dev.supermux.state.jsHttpFactory
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.editor.applyServerFrame
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.settings.FleetSettingsExtra
import dev.supermux.ui.settings.FleetSettingsSection
import dev.supermux.ui.shell.SessionListMode
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.web.auth.CookieSession
import dev.supermux.web.auth.SessionState
import dev.supermux.web.auth.WebPairScreen
import dev.supermux.web.nav.UrlSync
import androidx.compose.ui.unit.dp
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.configureWebResources

object WebWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

private const val GROUP_BY_PROJECT_KEY = "web:groupByProject"

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.addEventListener("contextmenu", { it.preventDefault() })
    configureWebResources { resourcePathMapping { path -> "assets/$path" } }
    WebAppState.install()

    val settings = LocalStorageSettingsStore()
    val deps = HostStoreDeps(httpFactory = jsHttpFactory(), settings = settings)
    val uiPrefs = UiPrefs(settings)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val session = CookieSession(http = deps.httpFactory(null))

    // wasm has no runBlocking: await the seeds (localStorage-backed, they resolve without yielding)
    // and the pairing probe in a coroutine, then mount once.
    appScope.launch {
        val appearanceSeed = uiPrefs.appearance(AppearanceMode.SYSTEM).first()
        val textScaleSeed = uiPrefs.textScale.first()
        val shellSeed = uiPrefs.seedShellState()
        val collapsedSeed = uiPrefs.collapsedProjectPaths.first()
        var gate by mutableStateOf(session.probe())

        ComposeViewport(document.body!!) {
            val platform = remember { WebPlatform() }
            val appearance by uiPrefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by uiPrefs.textScale.collectAsState(textScaleSeed)
            LaunchedEffect(Unit) { window.requestAnimationFrame { document.getElementById("splash")?.remove() } }

            WebTheme(platform, appearance, textScale, uiPrefs) {
                when (val g = gate) {
                    is SessionState.Unpaired -> { WebPairScreen(reason = g.reason, onRetry = { appScope.launch { gate = session.probe() } }); return@WebTheme }
                    is SessionState.Offline -> { WebPairScreen(reason = "the broker did not answer: ${g.error}", onRetry = { appScope.launch { gate = session.probe() } }); return@WebTheme }
                    is SessionState.Paired -> Unit
                }

                val fleet = remember {
                    // The origin host's identity is filled in from /host lazily by FleetStore's probe;
                    // ensure the record exists so the fleet has a host to connect.
                    WebHostStores.ensureOriginHost(displayName = window.location.host, hostId = null, platform = null, version = null)
                    buildFleet(deps, appScope)
                }
                val ui = remember {
                    ShellUiState().apply {
                        sidebarCollapsed = shellSeed.sidebarCollapsed
                        setSidebarWidth(shellSeed.sidebarWidthDp.dp)
                        selectedId = shellSeed.selectedSession
                        collapsedProjectPaths = collapsedSeed
                        this.appearance = appearance
                    }
                }
                var groupByProject by remember { mutableStateOf(settings.stringNow(GROUP_BY_PROJECT_KEY) != "false") }
                val foreground by WebAppState.foreground.collectAsState()
                LaunchedEffect(foreground) { if (!foreground) MessageTts.stop(platform.tts) }

                UrlSync(ui)

                // Push tap (plan 4's sw.js posts it; harmless now).
                val pendingPush by WebAppState.pendingPushSessionId.collectAsState()
                LaunchedEffect(pendingPush) { pendingPush?.let { ui.selectSession(it); WebAppState.consumePendingPushSessionId() } }

                SupermuxApp(
                    fleet = fleet,
                    ui = ui,
                    appForeground = foreground,
                    appearance = appearance,
                    onToggleTheme = { appScope.launch { uiPrefs.putAppearance(if (appearance == AppearanceMode.DARK) AppearanceMode.LIGHT else AppearanceMode.DARK) } },
                    persistSelection = true,
                    defaultDeviceName = "Browser",
                    sessionListMode = SessionListMode.Fleet,
                    groupByProject = groupByProject,
                    onGroupByProjectChange = { v -> groupByProject = v; appScope.launch { settings.putString(GROUP_BY_PROJECT_KEY, v.toString()) } },
                    settingsExtra = { extra, scope -> FleetSettingsExtra(extra, scope) },
                    settingsSection = { section, scope -> FleetSettingsSection(section, scope, fleet) },
                )
            }
        }
    }
}

private fun buildFleet(deps: HostStoreDeps, scope: CoroutineScope): FleetStore {
    val snapshots = WebHostStores.snapshots
    val fleet = FleetStore(
        store = WebHostStores.store,
        scope = scope,
        deps = deps,
        snapshots = snapshots,
        appFactory = { url, token, onConn ->
            HostStore(url, token, scope, deps,
                onConnectionChange = onConn,
                walkthroughSeam = WebWalkthroughSeam,
                bindTts = { resolve, speak ->
                    MessageTts.resolveEngine = resolve
                    MessageTts.speakRemoteStream = speak
                })
        },
    )
    fleet.bindMessageTts()
    scope.launch {
        combine(fleet.sessions, fleet.sessionHost) { s, o -> s to o }.collect { (sessions, owners) ->
            val records = fleet.store.list()
            snapshots.retainOnly(records.map { it.recordId })
            val now = deps.nowMs()
            records.forEach { host ->
                val mine = sessions.filter { owners[it.id] == host.recordId }
                if (mine.isNotEmpty()) snapshots.replace(host.recordId, mine, now, host.version)
            }
        }
    }
    return fleet
}
```
Copy the `FleetStore`/`HostStore` constructor calls EXACTLY from `apps/ios/.../MainViewController.kt:362–399` (parameter names there are authoritative; the block above is transcribed from it). `WalkthroughState`/`applyServerFrame` imports: mirror the iOS file's imports. `ui.appearance` — check `ShellUiState.appearance`'s type. If `seedShellState()` has required parameters (desktop passes legacy values), pass nulls.

Sign-out: `FleetStore.removeHost`/the Devices screen "Unpair" path ends in `HostPersistence.saveAll(emptyList())`. Make `LocalStorageHostPersistence.saveAll` call an optional `onEmptied: () -> Unit` hook (constructor param, default no-op) and have `WebHostStores` pass `{ appScope.launch { CookieSession(...).logout() } }` — i.e. removing the origin host = `POST /logout` + reload. Add this to `WebHostStores` (it needs the `CookieSession`; pass it in from `main()` via a `WebHostStores.init(session, scope)` call before `ensureOriginHost`). Keep it to ~10 lines and document it.

- [ ] **Step 4: Build, stage, verify against a broker**

```bash
cd apps && ./gradlew :web:compileKotlinWasmJs --console=plain 2>&1 | tail -30   # fix compile errors against the real signatures
./gradlew :web:stageForBroker --console=plain 2>&1 | tail -5
```
Then from the repo root (not `apps/`):

```bash
S=$(mktemp -d); (MUX_TEST_SKIP_WEB_BUILD=1 scripts/test-broker.sh bash -c 'echo "$MUX_TEST_BASE_URL" > '$S'/url; echo "$MUX_TEST_PAIR_TOKEN" > '$S'/tok; sleep 900' > $S/log 2>&1 &); sleep 25; cat $S/url
```
Drive headless Chrome with Playwright through bun (see `docs/superpowers/plans/2026-09-11-web-kmp-plan1-wasm-targets.md` Results — `chromium.launch({ headless: true, executablePath: "/usr/bin/google-chrome", args: ["--no-sandbox"] })`):
1. Open `$URL/` unpaired → expect the pair screen (screenshot `unpaired.png`).
2. Open `$URL/pair?t=$TOKEN` → redirected to `/` → the session list shows the fixture session `test-journey` (screenshot `paired.png`). Console must show no `pageerror`.
3. Click the session row → URL becomes `/s/00000000-0000-4000-8000-000000000001`; reload that URL → same chat opens (deep link).
4. Open `$URL/settings/voice` → Voice settings section (screenshot).
5. Drag the sidebar divider, reload → width persisted (`localStorage["supermux:shell:sidebarWidthDp"]` changed).
6. Browser back from settings → home.
Record what worked and what didn't in the plan's Results section (add it), with screenshots under the scratchpad.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src apps/shared/src/wasmJsMain
git commit -m "feat(web): mount the shared SupermuxApp in the browser — seeds, cookie gate, fleet, URL sync

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

## Not in this plan

Terminal (xterm), CodeMirror engine, VNC panel wiring, mic, clipboard images, audio TTS chunks, uploads beyond the picker → plan 3. Setup wizard, web push, `sw.js`, manifest/icons → plan 4. Playwright journey, CI/Docker, Vue deletion → plan 5.

---

## Results (2026-09-11, tasks 1–6; hermetic broker via `scripts/test-broker.sh`, headless Google Chrome 148 + Playwright)

The shared `SupermuxApp` runs in a browser. Pairing gate, fleet, chat, settings, URL sync and the
persisted shell state all work against a real broker; nothing in `:ui` or `:shared` had to change
for the shell itself to render.

### What the browser run found (all fixed here unless said otherwise)

1. **The fleet never dialled.** `FleetStore.sync` skips every host whose token is blank
   (`FleetStore.kt:377` — that check is how every other platform says "this record is not
   configured yet"), so the cookie-credential origin host was stored, shown and never connected:
   an empty session list forever, no `/host` probe, no WebSocket. Worked around INSIDE `:web`:
   `WebHostStores.COOKIE_TOKEN` (`"cookie"`) is stored as the record's token. It is not a
   credential — the broker resolves `cookieToken(req) || bearerToken(req)` (`channels/web/cookies.ts`),
   so the cookie wins on every HTTP request and the bogus bearer is ignored, and a browser cannot
   set headers on a WS upgrade at all. **Carry-forward for plan 3:** this belongs in `:shared` —
   `PairedHost` should be able to say "my credential is ambient" (a flag, or a blank-token host
   that is dialled when `directUrl` is the page origin) instead of a sentinel string.
2. **Every nested deep link loaded nothing.** `index.html` referenced the bundle relatively
   (`<script src="assets/app-<hash>.js">`), so `/settings/voice` asked for
   `/settings/assets/app-*.js`, got the SPA fallback (HTML, 404 for the sub-resource) and painted
   a permanent splash. Fixed with `<base href="/">` in `apps/web/src/wasmJsMain/resources/index.html`,
   which also fixes Compose's own relative `assets/composeResources/…` fetches at depth. Scenario 4
   failed before this and passes after.
3. Review follow-ups on tasks 4–5, committed separately (`f935762e`): `/me` 429/5xx/undecodable →
   `Offline`, not `Unpaired`; `replaceState` for URL normalisation so Back is not trapped in a
   normalise loop; pair links are accepted from ANY origin by extracting the token and replaying it
   against this origin (the broker mints links on the relay/public origin).

### Browser scenarios (screenshots under the session scratchpad `…/scratchpad/plan2/`)

| # | Scenario | Result | Shot |
|---|---|---|---|
| 1 | `$URL/` with no cookie | Pair screen, "already set up — use normal pairing". `/me` 401 → secretless claim 403, exactly as designed. First paint ~3.9 s. | `1-unpaired.png` |
| 2 | `$URL/pair?t=$TOKEN` | 302 → `/`, cookie set, app paints, WS connects (`[BrokerClient] connected`, `rx Snapshot`), sidebar shows workspace `workdir` › session `test-journey`. No `pageerror`. Ready ~6.8 s. | `2-paired.png` |
| 3 | Click the session row; reload | URL becomes `/s/00000000-0000-4000-8000-000000000001`; reloading that URL reopens the same chat (composer, suggestions, `workdir / test-journey` breadcrumb). | `3-session.png`, `3-session-reloaded.png` |
| 4 | `$URL/settings/voice` | Settings hub opens on the Voice section (speech engine / read aloud / cleanup / glossary). | `4-settings-voice.png` |
| 5 | Back | In-app gear → `/settings/agents`, browser Back → `/`; session row → `/s/…`, Back → `/`. No loop, no stuck entry. | `5a-settings-inapp.png`, `5b-back-home.png` |
| 6 | Sidebar width | Divider DRAGGED (320 → `459.875`), `supermux:shell:sidebarWidthDp` written, reload re-paints the wide sidebar and keeps the value. | `6a-dragged.png`, `6b-after-reload.png` |

### Console noise

No `pageerror` in any scenario. Expected: two `console.error`s on the unpaired path (the 401 `/me`
and the 403 claim — the fetches Chrome logs, not app errors), and WebGL driver performance warnings
from the headless GPU (`GPU stall due to ReadPixels`, `WEBGL_debug_renderer_info not enabled`).
`[BrokerClient] send dropped (not connected)` fires once per page load — a queued frame before the
socket opens; harmless but worth a look in plan 3. Two tofu glyphs render inside the Voice chips
(a missing icon glyph in the bundled font) — cosmetic, plan 3.

### Timings

| Step | Time |
|---|---|
| `:web:compileKotlinWasmJs` (incremental) | 5–8 s |
| `:web:stageForBroker` (cold, includes webpack + dist) | 8 m 50 s |
| `:web:stageForBroker` (index.html only) | 16 s |
| `:web:wasmJsBrowserTest` (Karma, ChromeHeadless) | 1 m 43 s, 35 tests |
| Bundle after the real app lands | 5 980 KB gzip staged (plan 1's hello world: ≈4.1 MB) |

### Not verified

Sign-out (`LocalStorageHostPersistence(onEmptied)` → `CookieSession.logout()` → reload) is wired and
compiles but was not exercised in the browser — the Devices-screen unpair gesture needs a click path
this run did not drive. Terminal/editor/VNC/mic remain on their "unavailable" no-ops (plan 3).
