# Browser tab (#105) step 2a — webview-compose additions, common API + macOS — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give webview-compose the five operations #105's Browser tab needs: find in page, zoom, the page's icon, a cookie in the view's profile (for #105's preview token) and the inspector. Each goes through the common API and the fake engine, and is implemented on the macOS engine first.

**Architecture:**
- **Engine.** `WebEngine` gains `find`, `clearFind`, `setZoom`, `setCookie` and `openInspector`. Each has a default that means "this engine can't", so the engines not yet done keep compiling and say so.
- **Navigator.** `WebNavigator` exposes them through `StateOwner`, guarded like today's commands. A new suspending `withEngine` serves the asynchronous ones.
- **Favicon.** Engine-independent: the navigator evaluates one script.
- **macOS.** The engine sends each request through `PageGlue` to new JNI functions in `WVPage.m`, using WebKit's own APIs (`findString:withConfiguration:`, `pageZoom`, `WKHTTPCookieStore`). Answers come back as the existing `GlueEvent.Result`, so the event protocol doesn't change. The glue ABI goes from 5 to 6.

**Tech Stack:** Kotlin Multiplatform (common, JVM), Compose, JNI, Objective-C (WebKit, macOS 14+), kotlinx-coroutines, kotlin.test.

**Out of this plan** (later plans 2b–2d): Android, iOS, Windows, Linux and web engines. Until then those engines inherit the "can't" defaults: `find` answers null, `setZoom` and `setCookie` answer false, `openInspector` answers `Unsupported`.

**How to run things** (the box is shared, so Gradle always runs under the heavy lock):
- JVM tests: `flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- nice ./gradlew :webview-compose:jvmTest --tests '<pattern>' --max-workers=2` from `apps/`.
- Glue build and real-engine checks: on the Mac (`~/work/supermux-hotreload`), under a `.lease` that Tally grants.

---

## File structure

- Create `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/BrowserTypes.kt`: `FindResult`, `WebCookie`, `InspectorResult`.
- Modify `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt`: the five members, with "can't" defaults.
- Modify `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewState.kt`: `StateOwner.withEngine`, and the navigator's `find`, `clearFind`, `setZoom`, `setCookie`, `openInspector`, `favicon`.
- Modify `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewHost.kt`: `withEngine`, guarded like `onEngine`.
- Modify `apps/webview-compose-testing/src/commonMain/kotlin/dev/supermux/webview/testing/FakeWebEngine.kt`: the members, recorded, with programmable answers.
- Modify `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/PageGlue.kt`: the `find`, `clearFind`, `setPageZoom` and `setCookie` glue calls.
- Modify `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacGlue.kt`: the externals, and `ABI = 6`.
- Modify `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacWebEngine.kt`: the overrides.
- Modify `apps/webview-compose/native/macos/WVGlue.h`: `WV_ABI_VERSION 6`.
- Modify `apps/webview-compose/native/macos/WVPage.m`: the WVPage methods and JNI functions.
- Tests:
  - `src/commonTest/.../WebViewStateTest.kt` (its `Owner` double gains `withEngine`);
  - `webview-compose-testing/src/commonTest/.../FakeWebEngineTest.kt`, or the existing `src/commonTest/.../testing/FakeWebEngineTest.kt`;
  - `src/jvmTest/.../engine/mac/FakePageGlue.kt` and `MacWebEngineTest.kt`.
- Docs: `apps/webview-compose/README.md` (API section, platform table).

---

### Task 1: The types, the engine's defaults, and the navigator

**Files:**
- Create: `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/BrowserTypes.kt`
- Modify: `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt` (inside `public interface WebEngine`, after `stop()`)
- Modify: `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewState.kt` (`StateOwner` at line ~346, `WebNavigator` at line ~363)
- Modify: `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewHost.kt` (next to `onEngine`, line ~524)
- Test: `apps/webview-compose/src/commonTest/kotlin/dev/supermux/webview/WebViewStateTest.kt`

- [ ] **Step 1: Write the failing tests** (append to `WebViewStateTest.kt`; give its private `Owner` the new member at the same time, so the file compiles once Task 1 is done):

```kotlin
    @Test
    fun navigatorReachesTheBrowserOperations() = runTest {
        val engine = FakeWebEngine()
        engine.findAnswer = { query, backwards -> FindResult(found = query == "pass", count = if (backwards) 2 else 1) }
        val state = WebViewState(WebContent.Html("<p>x</p>"))
        state.owner = Owner(engine)
        assertEquals(FindResult(found = true, count = 1), state.navigator.find("pass"))
        assertEquals(FindResult(found = true, count = 2), state.navigator.find("pass", backwards = true))
        state.navigator.clearFind()
        assertTrue(state.navigator.setZoom(1.5f))
        val cookie = WebCookie(name = "cmux_preview", value = "t", url = "https://app.test/p/", path = "/p/")
        assertTrue(state.navigator.setCookie(cookie))
        assertEquals(InspectorResult.Opened, state.navigator.openInspector())
        assertEquals(listOf(cookie), engine.cookies)
        assertTrue(engine.calls.containsAll(listOf("find:pass:false", "find:pass:true", "clearFind", "zoom:1.5", "inspector")), "${engine.calls}")
    }

    @Test
    fun navigatorWithoutAnEngineSaysSo() = runTest {
        val state = WebViewState(WebContent.Html("<p>x</p>"))
        assertNull(state.navigator.find("x"))
        assertFalse(state.navigator.setZoom(2f))
        assertFalse(state.navigator.setCookie(WebCookie(name = "a", value = "b", url = "https://app.test/")))
        assertEquals(InspectorResult.Unsupported, state.navigator.openInspector())
    }

    @Test
    fun zoomStaysWithinItsBounds() = runTest {
        val engine = FakeWebEngine()
        val state = WebViewState(WebContent.Html("<p>x</p>"))
        state.owner = Owner(engine)
        state.navigator.setZoom(9f)
        state.navigator.setZoom(0.01f)
        assertEquals(listOf("zoom:5.0", "zoom:0.25"), engine.calls.filter { it.startsWith("zoom:") })
    }
```

The `Owner` double in the same file:

```kotlin
    private class Owner(override val engine: WebEngine?) : StateOwner {
        override fun onEngine(command: (WebEngine) -> Unit): Boolean = engine?.let { command(it); true } ?: false
        override suspend fun <T> withEngine(command: suspend (WebEngine) -> T): T? = engine?.let { command(it) }
    }
```

(Keep whatever else the existing `Owner` already does; add only `withEngine`.)

- [ ] **Step 2: Run them, expect a compile failure** (`FindResult`, `withEngine` and `findAnswer` don't exist):

Run: `flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- nice ./gradlew :webview-compose:jvmTest --tests 'dev.supermux.webview.WebViewStateTest' --max-workers=2` (from `apps/`)
Expected: FAIL, unresolved references.

- [ ] **Step 3: The types** (`BrowserTypes.kt`):

```kotlin
package dev.supermux.webview

/**
 * A find-in-page answer. [count] and [current] are null where the engine only says found or not (Apple's WebKit answers
 * found / not found); [current] is 1-based.
 */
public data class FindResult(val found: Boolean, val count: Int? = null, val current: Int? = null)

/**
 * A cookie for the view's profile, set before a page needs it: #105's preview token (`cmux_preview`, from the broker's
 * `POST /devices/preview-token`). Host-only when [domain] is null: it belongs to [url]'s host.
 */
public data class WebCookie(
    val name: String,
    val value: String,
    val url: String,
    val path: String = "/",
    val domain: String? = null,
    val secure: Boolean = true,
    val httpOnly: Boolean = true,
    /** `Lax`, `Strict` or `None`. */
    val sameSite: String = "Lax",
    /** Seconds; null for a session cookie. */
    val maxAgeSeconds: Long? = null,
)

/** What the engine did when asked for its inspector. */
public sealed interface InspectorResult {
    public data object Opened : InspectorResult

    /** The engine can't open it from the app; [hint] says where the user can (Safari's Develop menu, for Apple's WebKit). */
    public data class Elsewhere(val hint: String) : InspectorResult

    public data object Unsupported : InspectorResult
}
```

- [ ] **Step 4: The engine's members with "can't" defaults** (in `WebEngine`, after `public fun stop()`; add `import dev.supermux.webview.FindResult`, `WebCookie`, `InspectorResult` at the top):

```kotlin
    /** Finds [query] in the page, the next match after the current one, or the previous one when [backwards]. Null when the engine can't. */
    public suspend fun find(query: String, backwards: Boolean): FindResult? = null

    /** Clears find's highlight and selection. */
    public fun clearFind() {}

    /** Sets the page's zoom, 1.0 being 100 %. False when the engine can't. */
    public fun setZoom(factor: Float): Boolean = false

    /** Puts [cookie] in the view's profile, done when this returns true. False when the engine can't. */
    public suspend fun setCookie(cookie: WebCookie): Boolean = false

    /** Opens the engine's inspector on this page, or says where the user can. */
    public fun openInspector(): InspectorResult = InspectorResult.Unsupported
```

- [ ] **Step 5: `StateOwner.withEngine` and the navigator** (`WebViewState.kt`).

In `internal interface StateOwner`, after `onEngine`:

```kotlin
    /** Runs a suspending [command] on [engine], guarded like [onEngine]; null without an engine, or when it threw. */
    suspend fun <T> withEngine(command: suspend (WebEngine) -> T): T?
```

In `public class WebNavigator`, after `stop()`:

```kotlin
    /** Finds [query] in the page; null without a live engine, or on an engine that can't. */
    public suspend fun find(query: String, backwards: Boolean = false): FindResult? =
        state.owner?.withEngine { it.find(query, backwards) }

    public fun clearFind() {
        state.owner?.onEngine { it.clearFind() }
    }

    /** Sets the page's zoom (1.0 = 100 %, kept within 25–500 %); false without a live engine, or on one that can't. */
    public fun setZoom(factor: Float): Boolean {
        var applied = false
        state.owner?.onEngine { applied = it.setZoom(factor.coerceIn(MIN_ZOOM, MAX_ZOOM)) }
        return applied
    }

    /** Puts [cookie] in the view's profile; true once it's there. Set it before loading the page that needs it. */
    public suspend fun setCookie(cookie: WebCookie): Boolean = state.owner?.withEngine { it.setCookie(cookie) } ?: false

    public fun openInspector(): InspectorResult {
        var result: InspectorResult = InspectorResult.Unsupported
        state.owner?.onEngine { result = it.openInspector() }
        return result
    }

    private companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 5f
    }
```

- [ ] **Step 6: `WebViewHost.withEngine`** (next to `onEngine`; import `kotlin.coroutines.cancellation.CancellationException`):

```kotlin
    /** [onEngine] for suspending commands: the same guard; a throw marks the engine crashed and answers null. */
    override suspend fun <T> withEngine(command: suspend (WebEngine) -> T): T? {
        val current = engine ?: return null
        if (!owns()) return null
        return try {
            command(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runtime.report(e)
            onCrashed(current)
            null
        }
    }
```

- [ ] **Step 7:** Tests still don't compile (`FakeWebEngine.findAnswer`). That is Task 2. Don't commit yet.

---

### Task 2: The fake engine answers them

**Files:**
- Modify: `apps/webview-compose-testing/src/commonMain/kotlin/dev/supermux/webview/testing/FakeWebEngine.kt` (next to `stop()`, line ~307; `record`/`allowed` at ~495)
- Test: `apps/webview-compose/src/commonTest/kotlin/dev/supermux/webview/testing/FakeWebEngineTest.kt`

- [ ] **Step 1: Write the failing test** (append to `FakeWebEngineTest.kt`):

```kotlin
    @Test
    fun browserOperationsAreRecordedAndAnswered() = runTest {
        val engine = FakeWebEngine()
        assertNull(engine.find("x", backwards = false)) // no answer programmed: an engine that can't
        engine.findAnswer = { q, _ -> FindResult(found = q.isNotEmpty(), count = 3, current = 1) }
        assertEquals(FindResult(true, 3, 1), engine.find("word", backwards = false))
        assertTrue(engine.setZoom(1.25f))
        val c = WebCookie(name = "n", value = "v", url = "https://h.test/")
        assertTrue(engine.setCookie(c))
        assertEquals(listOf(c), engine.cookies)
        engine.inspectorAnswer = InspectorResult.Elsewhere("Safari ▸ Develop")
        assertEquals(InspectorResult.Elsewhere("Safari ▸ Develop"), engine.openInspector())
        assertEquals(listOf("find:x:false", "find:word:false", "zoom:1.25", "cookie:n", "inspector"), engine.calls.takeLast(5))
    }

    @Test
    fun browserOperationsAfterReleaseAreViolations() = runTest {
        val engine = FakeWebEngine()
        engine.release()
        assertFalse(engine.setZoom(2f))
        assertFalse(engine.setCookie(WebCookie(name = "n", value = "v", url = "https://h.test/")))
        assertTrue(engine.violations.any { it.startsWith("setZoom after release") }, "${engine.violations}")
    }
```

(`violations` is the fake's existing list of misuse; check its exact name in `FakeWebEngine.kt` — `violation(...)` appends to it — and use that name here.)

- [ ] **Step 2: Run, expect a compile failure.** Run the Task 1 command with `--tests 'dev.supermux.webview.testing.FakeWebEngineTest'`.

- [ ] **Step 3: Implement** (in `FakeWebEngine`, next to `stop()`; imports `dev.supermux.webview.FindResult`, `WebCookie`, `InspectorResult`):

```kotlin
    /** What [find] answers; null (the default) is an engine that can't. */
    public var findAnswer: ((query: String, backwards: Boolean) -> FindResult?)? = null

    /** What [openInspector] answers. */
    public var inspectorAnswer: InspectorResult = InspectorResult.Opened

    private val cookieJar = mutableListOf<WebCookie>()

    /** Every cookie [setCookie] put in this engine's profile, in order. */
    public val cookies: List<WebCookie> get() = cookieJar

    override suspend fun find(query: String, backwards: Boolean): FindResult? {
        record("find:$query:$backwards")
        if (!allowed("find")) return null
        return findAnswer?.invoke(query, backwards)
    }

    override fun clearFind() {
        record("clearFind")
        allowed("clearFind")
    }

    override fun setZoom(factor: Float): Boolean {
        record("zoom:$factor")
        return allowed("setZoom")
    }

    override suspend fun setCookie(cookie: WebCookie): Boolean {
        record("cookie:${cookie.name}")
        if (!allowed("setCookie")) return false
        cookieJar += cookie
        return true
    }

    override fun openInspector(): InspectorResult {
        record("inspector")
        return if (allowed("openInspector")) inspectorAnswer else InspectorResult.Unsupported
    }
```

- [ ] **Step 4: Run both test classes** (the Task 1 command with `--tests 'dev.supermux.webview.WebViewStateTest' --tests 'dev.supermux.webview.testing.FakeWebEngineTest'`). Expected: PASS. Also run the library's whole common suite once, since every engine now inherits defaults: `--tests 'dev.supermux.webview.*'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/BrowserTypes.kt \
  apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/engine/WebEngine.kt \
  apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewState.kt \
  apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewHost.kt \
  apps/webview-compose-testing/src/commonMain/kotlin/dev/supermux/webview/testing/FakeWebEngine.kt \
  apps/webview-compose/src/commonTest/kotlin/dev/supermux/webview/WebViewStateTest.kt \
  apps/webview-compose/src/commonTest/kotlin/dev/supermux/webview/testing/FakeWebEngineTest.kt
git commit -m "feat(webview): find, zoom, cookies and the inspector in the engine API and the navigator (#105)"
```

---

### Task 3: The page's icon, the same on every engine

**Files:**
- Modify: `apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewState.kt` (`WebNavigator`)
- Modify: `apps/webview-compose-testing/src/commonMain/kotlin/dev/supermux/webview/testing/FakeWebEngine.kt` (`evaluate`, line ~318)
- Test: `WebViewStateTest.kt`

- [ ] **Step 1: Write the failing test:**

```kotlin
    @Test
    fun faviconComesFromThePage() = runTest {
        val engine = FakeWebEngine()
        engine.evaluateAnswer = { script -> if ("rel~=\"icon\"" in script) "\"https://vitejs.dev/logo.svg\"" else null }
        val state = WebViewState(WebContent.Html("<p>x</p>"))
        state.owner = Owner(engine)
        assertEquals("https://vitejs.dev/logo.svg", state.navigator.favicon())
        engine.evaluateAnswer = { "null" }
        assertNull(state.navigator.favicon())
    }
```

- [ ] **Step 2: Run, expect a compile failure** (`evaluateAnswer` and `favicon` don't exist).

- [ ] **Step 3: Implement.** In `FakeWebEngine`, make `evaluate` programmable:

```kotlin
    /** What [evaluate] answers for a script: its JSON result, or null. */
    public var evaluateAnswer: (String) -> String? = { null }

    override suspend fun evaluate(script: String): String? {
        record("eval:", script)
        if (!allowed("evaluate")) return null
        return evaluateAnswer(script)
    }
```

In `WebNavigator` (import `kotlinx.serialization.json.Json`, `JsonPrimitive`, `contentOrNull`, `jsonPrimitive`):

```kotlin
    /** The page's icon: its `<link rel="icon">`, else `/favicon.ico` on its origin; null without a page or for a non-http one. */
    public suspend fun favicon(): String? {
        val json = state.owner?.withEngine { it.evaluate(FAVICON_SCRIPT) } ?: return null
        return runCatching { Json.parseToJsonElement(json).jsonPrimitive.contentOrNull }.getOrNull()
    }
```

And in the navigator's companion:

```kotlin
        const val FAVICON_SCRIPT =
            "(() => { if (!/^https?:/.test(location.protocol)) return null;" +
                " const l = document.querySelector('link[rel~=\"icon\"]');" +
                " return l && l.href ? l.href : location.origin + '/favicon.ico' })()"
```

- [ ] **Step 4: Run `WebViewStateTest`.** Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/webview-compose/src/commonMain/kotlin/dev/supermux/webview/WebViewState.kt \
  apps/webview-compose-testing/src/commonMain/kotlin/dev/supermux/webview/testing/FakeWebEngine.kt \
  apps/webview-compose/src/commonTest/kotlin/dev/supermux/webview/WebViewStateTest.kt
git commit -m "feat(webview): the navigator reads a page's icon, the same way on every engine (#105)"
```

---

### Task 4: The macOS glue calls, and their fake

**Files:**
- Modify: `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/PageGlue.kt` (interface at line 19; live implementation next to `stop` at ~140)
- Modify: `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacGlue.kt` (externals next to `stop`, line ~94; `ABI` at line 17)
- Modify: `apps/webview-compose/src/jvmTest/kotlin/dev/supermux/webview/engine/mac/FakePageGlue.kt` (next to `stop`, line ~53)

- [ ] **Step 1: The interface** (in `internal interface PageGlue`, after `fun stop()`):

```kotlin
    /** Finds [query]; answered by a Result whose json is `true` or `false` (WebKit says only found or not). */
    fun find(request: Long, query: String, backwards: Boolean)

    /** Clears find's selection. */
    fun clearFind()

    /** The page's zoom, WebKit's `pageZoom`. */
    fun setPageZoom(factor: Double)

    /** Puts a cookie in the page's data store; answered by a Result, ok once it's stored. */
    fun setCookie(request: Long, cookie: WebCookie)
```

- [ ] **Step 2: The externals** (in `MacGlue`, after `stop`), and `const val ABI: Int = 6`, with the doc line extended: `6: [find], [clearFind], [setPageZoom], [setCookie].`

```kotlin
    @JvmStatic external fun find(page: Long, request: Long, query: String, backwards: Boolean)

    @JvmStatic external fun clearFind(page: Long)

    @JvmStatic external fun setPageZoom(page: Long, factor: Double)

    @JvmStatic external fun setCookie(
        page: Long, request: Long, name: String, value: String, url: String, path: String, domain: String?,
        secure: Boolean, httpOnly: Boolean, sameSite: String, maxAgeSeconds: Long,
    )
```

- [ ] **Step 3: The live implementation** (next to `override fun stop()` in the live glue):

```kotlin
    override fun find(request: Long, query: String, backwards: Boolean) = live(Unit) { MacGlue.find(it, request, query, backwards) }
    override fun clearFind() = live(Unit) { MacGlue.clearFind(it) }
    override fun setPageZoom(factor: Double) = live(Unit) { MacGlue.setPageZoom(it, factor) }
    override fun setCookie(request: Long, cookie: WebCookie) = live(Unit) {
        MacGlue.setCookie(
            it, request, cookie.name, cookie.value, cookie.url, cookie.path, cookie.domain,
            cookie.secure, cookie.httpOnly, cookie.sameSite, cookie.maxAgeSeconds ?: -1L,
        )
    }
```

- [ ] **Step 4: The fake** (in `FakePageGlue`, next to `stop`):

```kotlin
    override fun find(request: Long, query: String, backwards: Boolean) {
        calls += "find $request $query $backwards"
    }

    override fun clearFind() {
        calls += "clearFind"
    }

    override fun setPageZoom(factor: Double) {
        calls += "setPageZoom $factor"
    }

    override fun setCookie(request: Long, cookie: WebCookie) {
        calls += "setCookie $request ${cookie.name} ${cookie.path} ${cookie.domain} ${cookie.httpOnly}"
    }
```

- [ ] **Step 5: Compile**, by running the Mac engine's tests: `--tests 'dev.supermux.webview.engine.mac.*'`. Expected: PASS. Nothing calls the new members yet. `MacNativeLibrary`'s ABI tests may expect 5: if they fail on the number, change the expected ABI there to 6, since that's the point.

- [ ] **Step 6: Commit**

```bash
git add apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/PageGlue.kt \
  apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacGlue.kt \
  apps/webview-compose/src/jvmTest/kotlin/dev/supermux/webview/engine/mac/FakePageGlue.kt
git commit -m "feat(webview/mac): glue calls for find, zoom and cookies; ABI 6 (#105)"
```

---

### Task 5: The macOS engine uses them

**Files:**
- Modify: `apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacWebEngine.kt` (next to `stop()` at line ~358; `request` helper at ~1386)
- Test: `apps/webview-compose/src/jvmTest/kotlin/dev/supermux/webview/engine/mac/MacWebEngineTest.kt` (use its `rig`: `rig.engine`, `rig.glue`, `rig.answer(call) { GlueEvent.Result(it, ok, json, null) }`)

- [ ] **Step 1: Write the failing tests** (in `MacWebEngineTest`, following its existing rig setup, e.g. the test around line 643):

```kotlin
    @Test
    fun findAsksWebKitAndReadsFoundOrNot() = runTest {
        val rig = liveRig() // the file's helper for an engine with a committed page; use the existing one
        val found = async { rig.engine.find("password", backwards = false) }
        rig.answer("find") { GlueEvent.Result(it, true, "true", null) }
        assertEquals(FindResult(found = true), found.await())
        val missing = async { rig.engine.find("nothing", backwards = true) }
        rig.answer("find") { GlueEvent.Result(it, true, "false", null) }
        assertEquals(FindResult(found = false), missing.await())
        assertTrue(rig.glue.calls.any { it.endsWith(" nothing true") }, "${rig.glue.calls}")
    }

    @Test
    fun zoomCookieAndInspector() = runTest {
        val rig = liveRig()
        assertTrue(rig.engine.setZoom(1.5f))
        assertEquals("setPageZoom 1.5", rig.glue.last("setPageZoom"))
        val stored = async { rig.engine.setCookie(WebCookie(name = "cmux_preview", value = "t", url = "https://app.test/p/", path = "/p/")) }
        rig.answer("setCookie") { GlueEvent.Result(it, true, "true", null) }
        assertTrue(stored.await())
        assertTrue(rig.glue.last("setCookie")!!.endsWith("cmux_preview /p/ null true"), rig.glue.last("setCookie"))
        val inspector = rig.engine.openInspector()
        assertTrue(inspector is InspectorResult.Elsewhere && "Develop" in inspector.hint, "$inspector")
    }

    @Test
    fun afterReleaseNothingReachesTheGlue() = runTest {
        val rig = liveRig()
        rig.engine.release()
        assertNull(rig.engine.find("x", backwards = false))
        assertFalse(rig.engine.setZoom(2f))
        assertFalse(rig.engine.setCookie(WebCookie(name = "a", value = "b", url = "https://app.test/")))
        assertFalse(rig.glue.calls.any { it.startsWith("find") || it.startsWith("setPageZoom") || it.startsWith("setCookie") })
    }
```

`liveRig()` stands for the file's existing way of building a rig with a loaded page (read the file's top: `rig.engine.configure(setup); rig.engine.load(html); rig.send(GlueEvent.Committed(...))`). Factor it into a helper in this step if the file has none.

- [ ] **Step 2: Run them, expect FAIL** (the defaults answer null and false): `--tests 'dev.supermux.webview.engine.mac.MacWebEngineTest'`.

- [ ] **Step 3: Implement** (in `MacWebEngine`, after `stop()`):

```kotlin
    override suspend fun find(query: String, backwards: Boolean): FindResult? {
        if (released || query.isEmpty()) return null
        val result = request { glue.find(it, query, backwards) } as? GlueEvent.Result ?: return null
        return if (result.ok) FindResult(found = result.json == "true") else null
    }

    override fun clearFind() {
        if (!released) glue.clearFind()
    }

    override fun setZoom(factor: Float): Boolean {
        if (released) return false
        glue.setPageZoom(factor.toDouble())
        return true
    }

    override suspend fun setCookie(cookie: WebCookie): Boolean {
        if (released) return false
        val result = request { glue.setCookie(it, cookie) } as? GlueEvent.Result ?: return false
        return result.ok
    }

    /** WebKit opens its inspector only from Safari: the page is inspectable (its setting), and Develop lists it. */
    override fun openInspector(): InspectorResult =
        InspectorResult.Elsewhere("Safari ▸ Develop ▸ this Mac ▸ the page (turn on Show features for web developers in Safari's Advanced settings)")
```

- [ ] **Step 4: Run them.** Expected: PASS. Then run the whole Mac engine package: `--tests 'dev.supermux.webview.engine.mac.*'`. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/webview-compose/src/jvmMain/kotlin/dev/supermux/webview/engine/mac/MacWebEngine.kt \
  apps/webview-compose/src/jvmTest/kotlin/dev/supermux/webview/engine/mac/MacWebEngineTest.kt
git commit -m "feat(webview/mac): find, zoom, cookies and the inspector hint on the macOS engine (#105)"
```

---

### Task 6: The Objective-C side (on the Mac)

**Files:**
- Modify: `apps/webview-compose/native/macos/WVGlue.h:10`: `#define WV_ABI_VERSION 6`
- Modify: `apps/webview-compose/native/macos/WVPage.m`: the methods near `stop` (the `@implementation WVPage`), and the JNI functions near `MacGlue_stop` (line ~886)

- [ ] **Step 1: The WVPage methods** (inside `@implementation WVPage`; declare them in its `@interface` too):

```objc
- (void)find:(NSString *)query backwards:(BOOL)backwards request:(jlong)request {
    jlong token = _token;
    if (_released || query.length == 0) {
        WVPost(token, WVEventResult, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    WKFindConfiguration *config = [[WKFindConfiguration alloc] init];
    config.backwards = backwards;
    config.caseSensitive = NO;
    config.wraps = YES;
    [_webView findString:query withConfiguration:config completionHandler:^(WKFindResult *result) {
        WVPost(token, WVEventResult, 0, request, 1, 0, 0, result.matchFound ? @"true" : @"false", nil, nil, nil, nil);
    }];
}

// WebKit's find selects the match: clearing it is clearing the selection.
- (void)clearFind {
    if (_released) return;
    [_webView evaluateJavaScript:@"getSelection().removeAllRanges()" inFrame:nil
                  inContentWorld:WKContentWorld.defaultClientWorld completionHandler:nil];
}

- (void)setPageZoom:(double)factor {
    if (_released) return;
    _webView.pageZoom = factor;
}

- (void)setCookie:(NSDictionary<NSHTTPCookiePropertyKey, id> *)properties request:(jlong)request {
    jlong token = _token;
    NSHTTPCookie *cookie = _released ? nil : [NSHTTPCookie cookieWithProperties:properties];
    if (!cookie) {
        WVPost(token, WVEventResult, 0, request, 0, 0, 0, nil, nil, nil, nil, nil);
        return;
    }
    [_webView.configuration.websiteDataStore.httpCookieStore setCookie:cookie completionHandler:^{
        WVPost(token, WVEventResult, 0, request, 1, 0, 0, @"true", nil, nil, nil, nil);
    }];
}
```

- [ ] **Step 2: The JNI functions** (after `MacGlue_stop`):

```objc
JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_find(
    JNIEnv *env, jclass type, jlong handle, jlong request, jstring query, jboolean backwards) {
    WVPage *page = WVPageOf(handle);
    NSString *text = WVString(env, query);
    WVOnMain(NO, ^{
        [page find:text backwards:backwards request:request];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_clearFind(JNIEnv *env, jclass type, jlong handle) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page clearFind];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_setPageZoom(JNIEnv *env, jclass type, jlong handle, jdouble factor) {
    WVPage *page = WVPageOf(handle);
    WVOnMain(NO, ^{
        [page setPageZoom:factor];
    });
}

JNIEXPORT void JNICALL Java_dev_supermux_webview_engine_mac_MacGlue_setCookie(
    JNIEnv *env, jclass type, jlong handle, jlong request, jstring name, jstring value, jstring url, jstring path,
    jstring domain, jboolean secure, jboolean httpOnly, jstring sameSite, jlong maxAge) {
    WVPage *page = WVPageOf(handle);
    NSMutableDictionary<NSHTTPCookiePropertyKey, id> *p = [NSMutableDictionary dictionary];
    p[NSHTTPCookieName] = WVString(env, name);
    p[NSHTTPCookieValue] = WVString(env, value);
    p[NSHTTPCookiePath] = WVString(env, path);
    NSString *cookieDomain = domain ? WVString(env, domain) : nil;
    if (cookieDomain.length) p[NSHTTPCookieDomain] = cookieDomain;
    else p[NSHTTPCookieOriginURL] = [NSURL URLWithString:WVString(env, url)]; // host-only: the URL's host
    if (secure) p[NSHTTPCookieSecure] = @"TRUE";
    if (httpOnly) p[@"HttpOnly"] = @"TRUE";
    NSString *site = WVString(env, sameSite);
    if ([site isEqualToString:@"Strict"]) p[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteStrict;
    else if ([site isEqualToString:@"Lax"]) p[NSHTTPCookieSameSitePolicy] = NSHTTPCookieSameSiteLax;
    if (maxAge >= 0) p[NSHTTPCookieMaximumAge] = [NSString stringWithFormat:@"%lld", (long long)maxAge];
    WVOnMain(NO, ^{
        [page setCookie:p request:request];
    });
}
```

- [ ] **Step 3: Build on the Mac**, under the lease Tally grants: `cd ~/work/supermux-hotreload && git fetch && git checkout <this branch> && bash apps/webview-compose/native/macos/build.sh <out dir>` (build.sh's header names the out dir). Expected: `libwebview_macos.dylib`, universal, ad-hoc signed. Then the engine's glue-backed JVM tests on the Mac (the ones that load the real dylib): `./gradlew :webview-compose:jvmTest --tests 'dev.supermux.webview.engine.mac.*' --max-workers=1`. Expected: PASS, the ABI check included.

- [ ] **Step 4: Commit**

```bash
git add apps/webview-compose/native/macos/WVGlue.h apps/webview-compose/native/macos/WVPage.m
git commit -m "feat(webview/mac): WebKit's find, pageZoom and cookie store behind the glue; ABI 6 (#105)"
```

---

### Task 7: Real-engine checks on the Mac

**Files:**
- Modify: the macOS conformance checks the sample runs (the README's macOS row names them: `webview-sample/device-checks/`, the 48 checks "in the desktop sample's window"). Add three checks there, in the same style as their neighbours.

- [ ] **Step 1: Add the checks:**
  1. **find:** load `<p>password here</p>`; `find("password")` gives `found = true`; `find("absent")` gives `found = false`.
  2. **zoom:** `setZoom(2f)`, then evaluate `window.devicePixelRatio`. The page now reports twice the screen's ratio (WebKit's page zoom scales CSS pixels); `setZoom(1f)` restores it.
  3. **cookie:**
     - `setCookie(WebCookie(name = "probe", value = "1", url = "https://cookie.test/", httpOnly = false, secure = true))` on a view whose next page is served by the sample's own scheme for `https://cookie.test/`. Then load it and evaluate `document.cookie`: it contains `probe=1`.
     - Then an `httpOnly = true` cookie: `document.cookie` doesn't show it, but the page's own request does (the check's scheme handler logs the request's `Cookie` header).

- [ ] **Step 2: Run them on the Mac** (the macOS checks' own script, as the README's macOS row says). Expected: `CHECKS DONE passed=51 failed=0`, or whatever the new total is.

- [ ] **Step 3: Commit**

```bash
git add apps/webview-sample
git commit -m "test(webview/mac): find, zoom and cookie conformance checks with real WebKit (#105)"
```

---

### Task 8: Document the API

**Files:**
- Modify: `apps/webview-compose/README.md` (the API section where `WebNavigator` is described; the per-platform table)

- [ ] **Step 1: Write it:**
  - `navigator.find(query, backwards)`, `clearFind()`, `setZoom(factor)`, `setCookie(cookie)`, `openInspector()`, `favicon()`.
  - What each engine answers. macOS:
    - find says only found or not;
    - the inspector is Safari's Develop menu;
    - zoom is WebKit's `pageZoom`;
    - the cookie goes into the view's data store, so an ephemeral profile's is gone with it.
  - The other engines: "not yet, the defaults say so", until plans 2b–2d.

- [ ] **Step 2: Commit**

```bash
git add apps/webview-compose/README.md
git commit -m "docs(webview): find, zoom, cookies, the inspector and the page's icon (#105)"
```

---

## Self-review

- **Spec coverage** (#105 "What has to be built", item 2): find in page (Tasks 1, 2, 5, 6, 7), zoom (same), favicon (Task 3), set a cookie (same as find; it serves Decision 3a's preview token), open the inspector (Tasks 1, 5: Safari's Develop on macOS). The other engines are plans 2b–2d.
- **Types stay consistent across tasks:**
  - `FindResult(found, count, current)`, `WebCookie(name, value, url, path, domain, secure, httpOnly, sameSite, maxAgeSeconds)`, `InspectorResult.{Opened, Elsewhere(hint), Unsupported}`;
  - `StateOwner.withEngine`;
  - `PageGlue.find/clearFind/setPageZoom/setCookie` and `MacGlue.find/clearFind/setPageZoom/setCookie`, matching the JNI names `Java_dev_supermux_webview_engine_mac_MacGlue_<name>`.
- **Mac lease:** Tasks 6 and 7 need one. Ask Tally before them; Tasks 1–5 run on the box under the heavy lock.
