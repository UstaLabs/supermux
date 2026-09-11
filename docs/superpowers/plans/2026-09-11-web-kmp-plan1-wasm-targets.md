# Web → KMP, Plan 1 of 5: wasm targets + hello-world `apps/web` served by the broker

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `:shared` and `:ui` compile for `wasmJs`, a new `apps/web` module renders a Compose page in the browser, its bundle is staged into `src/channels/web/static` with content-hashed assets, and the broker serves `.wasm` correctly.

**Architecture:** Kotlin/Wasm target added to the two shared modules with browser actuals for their 5 + 12 `expect`s (mirroring the jvm actuals; Skia code copies verbatim because wasm is Skia too). `apps/web` is a thin host like `apps/ios`: entry point, resources, a Gradle `stageForBroker` task. The broker learns one MIME type. Nothing in `src/web-app` is touched yet — Vue keeps being built and served until plan 5.

**Tech Stack:** Kotlin 2.3.21, Compose Multiplatform 1.11.1 (`ComposeViewport`, `HtmlElementView`, `BrowserCursor`), Ktor 3.5 `Js` engine, Gradle 8.14, npm `pako` 2.1.0 via KGP, Bun broker.

**Spec:** `docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md` §3, §4, §8, §10 step 1.

**Operating notes (read before running Gradle):**
- Run every Gradle command from `apps/`. First wasm build downloads Node + Yarn + webpack through KGP and the Skiko wasm runtime; expect 5–15 min. Run long commands in the background and poll; do not run two Gradle invocations at once.
- Apple targets are disabled on this Linux host (`kotlin.native.ignoreDisabledTargets`) — an Apple-only break is invisible here, so touch only wasm/common files.
- Never edit files under `apps/ui/src/jvmMain`, `androidMain`, `iosMain` in this plan.

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/build.gradle.kts` | + `wasmJs` target, `wasmJsMain` depends on `nonWatchMain`, npm `pako`. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/auth/SecureTokenStore.wasmJs.kt` | Cookie-session store (no bearer). |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/Inflate.wasmJs.kt` | `ZlibInflater` over pako. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/Pako.kt` | `@JsModule("pako")` externals. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/push/PushCrypto.wasmJs.kt` | `openSealedPush` = unsupported. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/chat/ChatTime.wasmJs.kt` | `localUtcOffsetMs` via JS `Date`. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/util/LocalDateTimeLabel.wasmJs.kt` | `formatLocalDateTimeMedium` via `Intl`. |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/state/JsHttpFactory.kt` | Ktor `Js` engine factory (same shape as `cioHttpFactory`). |
| `apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/BlobChunkSource.kt` | Synchronous, bounded-RAM `ChunkSource` over a browser `File`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` | `bearerHeader()` callers skip the header when the token is blank. |
| `apps/ui/build.gradle.kts` | + `wasmJs` target. |
| `apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/...` | 8 actual files (§Task 4). |
| `apps/web/build.gradle.kts` | New module: wasm executable, `stageForBroker`, size guard. |
| `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt` | `ComposeViewport` entry + hello screen. |
| `apps/web/src/wasmJsMain/resources/index.html` | Shell page. |
| `apps/settings.gradle.kts` | `include(":web")`. |
| `src/channels/web/static-serve.ts` (+ test) | `.wasm` MIME + gzip. |
| `src/types/assets.d.ts` | `*.wasm` module declaration. |
| `.gitignore` | ignore `apps/web/build`, `apps/web/kotlin-js-store` is COMMITTED (yarn lock). |

---

### Task 1: `:shared` gains a `wasmJs` target

**Files:**
- Modify: `apps/shared/build.gradle.kts`
- Create: the seven `wasmJsMain` files listed above

- [ ] **Step 1: Add the target and source-set wiring**

In `apps/shared/build.gradle.kts`, inside `kotlin { ... }` right after `androidTarget()` add:

```kotlin
    // Browser client (plan 1 of the web→KMP migration). `browser()` only — no Node target. The
    // wasm test task is disabled: every commonTest already runs on the JVM, and headless-Chromium
    // Karma is wired for `:web` alone (its tests are the browser-only ones).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
```

Inside `sourceSets { ... }`, after the `iosMain { dependsOn(nonWatchMain) }` line add:

```kotlin
        // wasmJs sits under nonWatchMain (it renders markdown) but NOT nonAppleMain (java.time,
        // CIO): the browser has its own clock/format/HTTP actuals in wasmJsMain.
        wasmJsMain {
            dependsOn(nonWatchMain)
            dependencies {
                // zlib for the RFB ZRLE decoder — synchronous, which DecompressionStream is not.
                implementation(npm("pako", "2.1.0"))
            }
        }
```

- [ ] **Step 2: Write the five actuals + HTTP factory + BlobChunkSource**

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/auth/SecureTokenStore.wasmJs.kt`:

```kotlin
package dev.supermux.auth

import kotlinx.browser.window

/**
 * The browser holds NO bearer: the credential is the HttpOnly `cmux_token` cookie the broker set on
 * `/pair?t=` or a successful `POST /pair/claim`, and the browser attaches it to every same-origin
 * request and WebSocket upgrade itself. So [load] is always null and the broker's
 * `cookieToken(req) || bearerToken(req)` picks the cookie. The base URL is the page origin.
 */
actual class SecureTokenStore actual constructor() {
    actual fun save(token: String) = Unit
    actual fun load(): String? = null
    actual fun clear() = Unit
    actual fun saveBaseUrl(url: String) = Unit
    actual fun loadBaseUrl(): String? = window.location.origin
}
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/Pako.kt`:

```kotlin
package dev.supermux.net

import org.khronos.webgl.Uint8Array

/** The subset of pako 2.x this module uses: a streaming zlib inflater. */
@JsModule("pako")
external object Pako {
    class Inflate {
        /** Feed a chunk; `flush` 2 = Z_SYNC_FLUSH so output is produced without waiting for stream end. */
        fun push(data: Uint8Array, flush: Int): Boolean
        val result: Uint8Array?
        val err: Int
        val msg: String?
        var onData: ((Uint8Array) -> Unit)?
    }
}
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/Inflate.wasmJs.kt`:

```kotlin
package dev.supermux.net

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * pako-backed zlib inflater. pako's streaming `Inflate` calls `onData` with each produced block;
 * we collect those blocks and hand them out one per [inflate] call, which is exactly the
 * "call until empty" contract the RFB ZRLE decoder relies on.
 */
actual class ZlibInflater actual constructor() {
    private val pending = ArrayDeque<ByteArray>()
    private var closed = false
    private val inflater = Pako.Inflate().also { inf ->
        inf.onData = { chunk -> pending.addLast(chunk.toByteArray()) }
    }

    actual fun feed(input: ByteArray) {
        if (closed || input.isEmpty()) return
        val ok = inflater.push(input.toUint8Array(), Z_SYNC_FLUSH)
        if (!ok && inflater.err != 0) {
            println("[ZlibInflater] pako error ${inflater.err}: ${inflater.msg}")
        }
    }

    actual fun inflate(): ByteArray = if (closed) ByteArray(0) else pending.removeFirstOrNull() ?: ByteArray(0)

    actual fun close() {
        closed = true
        pending.clear()
        inflater.onData = null
    }

    private companion object {
        const val Z_SYNC_FLUSH = 2
    }
}

internal fun ByteArray.toUint8Array(): Uint8Array {
    val out = Uint8Array(size)
    for (i in indices) out[i] = this[i]
    return out
}

internal fun Uint8Array.toByteArray(): ByteArray {
    val out = ByteArray(length)
    for (i in 0 until length) out[i] = this[i]
    return out
}
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/push/PushCrypto.wasmJs.kt`:

```kotlin
package dev.supermux.push

/**
 * Never called in the browser: web push is plaintext VAPID handled entirely by the service
 * worker (`sw.js`), so no sealed blob ever reaches Kotlin. Kept as a loud failure rather than a
 * silent empty string so a future caller finds out immediately.
 */
actual fun openSealedPush(blob: String, privateKeyPkcs8B64: String): String =
    throw UnsupportedOperationException("sealed push is native-only; the browser uses plaintext web push")
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/chat/ChatTime.wasmJs.kt`:

```kotlin
package dev.supermux.chat

import kotlin.js.Date

/** JS reports minutes WEST of UTC; the expect wants an offset to ADD to UTC, hence the sign flip. */
actual fun localUtcOffsetMs(epochMs: Long): Long =
    -(Date(epochMs.toDouble()).getTimezoneOffset().toLong() * 60_000L)
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/util/LocalDateTimeLabel.wasmJs.kt`:

```kotlin
package dev.supermux.util

@Suppress("UNUSED_PARAMETER")
private fun formatMedium(epochMs: Double): String = js(
    "new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(epochMs))",
)

/** The browser's locale, medium date + short time — the same intent as the JVM's FormatStyle pair. */
actual fun formatLocalDateTimeMedium(epochMs: Long): String = formatMedium(epochMs.toDouble())
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/state/JsHttpFactory.kt`:

```kotlin
package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Browser-backed factory, the wasm twin of `cioHttpFactory`. Ktor's Js engine is `fetch` +
 * the browser `WebSocket`, both of which attach the same-origin `cmux_token` cookie themselves —
 * that is the whole auth story on the web (see `SecureTokenStore.wasmJs.kt`).
 */
fun jsHttpFactory(): (Long?) -> HttpClient = { timeoutMs ->
    HttpClient(Js) {
        install(WebSockets)
        if (timeoutMs != null) install(HttpTimeout) { requestTimeoutMillis = timeoutMs }
    }
}
```

`apps/shared/src/wasmJsMain/kotlin/dev/supermux/net/BlobChunkSource.kt`:

```kotlin
package dev.supermux.net

import org.w3c.files.Blob
import org.w3c.xhr.XMLHttpRequest
import kotlinx.browser.window

/**
 * A [ChunkSource] over a browser [Blob]/`File` that reads on demand, so a 500 MB video never sits
 * in the wasm heap.
 *
 * `ChunkSource.read` is synchronous by contract (the resumable uploader calls it per 5 MB chunk).
 * The only synchronous byte read the main thread has is a sync `XMLHttpRequest` against an object
 * URL, so each read slices the blob, mints a URL for the slice, fetches it as
 * `x-user-defined` text (one char per byte) and revokes the URL. Bounded RAM: one slice at a time.
 */
class BlobChunkSource(private val blob: Blob) : ChunkSource {
    override val size: Long get() = blob.size.toLong()

    override fun read(offset: Long, len: Int): ByteArray {
        val total = size
        if (offset >= total || len <= 0) return ByteArray(0)
        val end = minOf(offset + len, total)
        val slice = blob.slice(offset.toInt(), end.toInt())
        val url = window.URL.createObjectURL(slice)
        try {
            val xhr = XMLHttpRequest()
            xhr.open("GET", url, async = false)
            xhr.overrideMimeType("text/plain; charset=x-user-defined")
            xhr.send()
            val text = xhr.responseText
            val out = ByteArray(text.length)
            for (i in text.indices) out[i] = (text[i].code and 0xFF).toByte()
            return out
        } finally {
            window.URL.revokeObjectURL(url)
        }
    }
}
```

- [ ] **Step 3: Make the bearer header optional in `BrokerApi`**

In `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` find `private fun bearerHeader() = "Bearer $token"` (line ~1201). Replace it, and every `header("Authorization", bearerHeader())` call, with a single helper so a blank token sends no header:

```kotlin
    /**
     * Attach `Authorization: Bearer …` — unless the token is blank, which is the browser (cookie
     * session; the broker resolves `cookieToken(req) || bearerToken(req)`). A literal `Bearer `
     * would not match the broker's regex anyway, but sending nothing is the honest shape.
     */
    private fun io.ktor.client.request.HttpRequestBuilder.authHeader() {
        if (token.isNotBlank()) header("Authorization", "Bearer $token")
    }
```

Then run:

```bash
grep -n 'header("Authorization", bearerHeader())' apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt | wc -l
sed -i 's/header("Authorization", bearerHeader())/authHeader()/g' apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt
grep -n "bearerHeader" apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt
```

Expected after sed: the last grep prints nothing except the doc comment (delete the old one-liner `private fun bearerHeader()` if it survived). If any other file in `apps/` references `bearerHeader` (`grep -rn bearerHeader apps --include=*.kt`), apply the same substitution there.

- [ ] **Step 4: Add a jvm test for the blank-token case**

Create `apps/shared/src/jvmTest/kotlin/dev/supermux/net/BrokerApiAuthHeaderTest.kt`:

```kotlin
package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrokerApiAuthHeaderTest {
    private fun apiWithToken(token: String, seen: MutableList<String?>): BrokerApi {
        val engine = MockEngine { req ->
            seen += req.headers[HttpHeaders.Authorization]
            respond("""{"paired":true,"device":"x"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi(baseUrl = "http://broker.test", token = token, http = HttpClient(engine))
    }

    @Test
    fun bearerIsSentWhenTokenPresent() = runBlocking {
        val seen = mutableListOf<String?>()
        apiWithToken("abc", seen).me()
        assertEquals("Bearer abc", seen.single())
    }

    @Test
    fun noAuthorizationHeaderWhenTokenBlank() = runBlocking {
        val seen = mutableListOf<String?>()
        apiWithToken("", seen).me()
        assertNull(seen.single())
    }
}
```

Adjust the constructor call to `BrokerApi`'s real signature (open the class at line ~1170: it takes `baseUrl`, `token`, and an `HttpClient`; if the parameter names differ, use the real ones) and `me()` to the real `/me` method name (grep `"/me"` in the file).

- [ ] **Step 5: Compile wasm + run the jvm suite**

```bash
cd apps && ./gradlew :shared:compileKotlinWasmJs --console=plain 2>&1 | tail -20
```
Expected: `BUILD SUCCESSFUL`. Typical first-time failures and fixes: `Unresolved reference: Js` → the engine class is `io.ktor.client.engine.js.Js` (in `ktor-client-core` for wasm; if still unresolved add `implementation("io.ktor:ktor-client-js:3.5.0")` to `wasmJsMain.dependencies`). `js()` call must be the sole expression of a top-level function with primitive/String params — keep `formatMedium` exactly as written.

```bash
./gradlew :shared:jvmTest --console=plain 2>&1 | tail -15
```
Expected: `BUILD SUCCESSFUL`, includes `BrokerApiAuthHeaderTest` 2 tests passing.

- [ ] **Step 6: Commit**

```bash
git add apps/shared apps/kotlin-js-store 2>/dev/null; git add apps/shared
git commit -m "feat(shared): wasmJs target with browser actuals (cookie session, pako inflate, Intl labels, Js engine)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

(`apps/kotlin-js-store/yarn.lock` appears after the first wasm build; commit it — it is the npm lockfile.)

---

### Task 2: `:ui` gains a `wasmJs` target with its 12 actuals

**Files:**
- Modify: `apps/ui/build.gradle.kts`
- Create: 8 files under `apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/`

- [ ] **Step 1: Add the target**

In `apps/ui/build.gradle.kts` inside `kotlin { ... }` after `iosSimulatorArm64()` add:

```kotlin
    // Browser (plan 1 of web→KMP). Same reasoning as :shared: no Node target, wasm tests off
    // (the jvmTest suite covers commonMain; :web owns the browser-only tests).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask { enabled = false }
        }
    }
```

- [ ] **Step 2: Write the actuals**

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/adaptive/SecondaryClick.wasmJs.kt`:

```kotlin
package dev.supermux.ui.adaptive

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed

internal actual fun PointerEvent.isSecondaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isSecondaryPressed

internal actual fun PointerEvent.isTertiaryButtonPress(): Boolean =
    type == PointerEventType.Press && buttons.isTertiaryPressed
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/chat/ComposerKeyboard.wasmJs.kt`:

```kotlin
package dev.supermux.ui.chat

import androidx.compose.ui.input.key.KeyEvent

/** Browser key events are keyboard events; soft keyboards commit text through the IME path. */
actual fun KeyEvent.isFromPhysicalKeyboard(): Boolean = true
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/panes/ResizeIcons.wasmJs.kt`:

```kotlin
package dev.supermux.ui.panes

import androidx.compose.ui.input.pointer.BrowserCursor
import androidx.compose.ui.input.pointer.PointerIcon

// CSS cursor keywords — BrowserCursor is Compose-for-Web's PointerIcon over `style.cursor`.
actual val ColResizeIcon: PointerIcon = BrowserCursor("col-resize")
actual val RowResizeIcon: PointerIcon = BrowserCursor("row-resize")
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/widgets/KeepAlivePanel.wasmJs.kt` — identical to the jvm actual:

```kotlin
package dev.supermux.ui.widgets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Same as desktop: a hidden pane collapses to 0×0 and clips, which also moves any DOM overlay
 * (`HtmlElementView`) to a zero-size box — the web twin of SwingPanel's "nothing paints above me".
 */
@Composable
actual fun KeepAlivePanel(
    visible: Boolean,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        if (visible) modifier.fillMaxSize().zIndex(1f)
        else modifier.size(0.dp).clipToBounds().zIndex(0f),
    ) {
        content()
    }
}
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/display/VncFramebuffer.wasmJs.kt` — copy `apps/ui/src/jvmMain/kotlin/dev/supermux/ui/display/VncFramebuffer.jvm.kt` verbatim (`cp` it); wasm is Skia, the imports (`org.jetbrains.skia.Bitmap/ImageInfo/ColorType/ColorAlphaType`, `androidx.compose.ui.graphics.asComposeImageBitmap`) resolve unchanged. Only edit the header comment to say "wasm actual: the same Skia upload as desktop".

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/session/RowContextMenu.wasmJs.kt`:

```kotlin
package dev.supermux.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Right-click → a Material `DropdownMenu` at the pointer. Desktop uses foundation's
 * `ContextMenuArea`; on the web that API is not available, and a DropdownMenu is the same
 * affordance in the app's own theme.
 */
@Composable
actual fun RowContextMenu(
    items: () -> List<RowContextMenuEntry>,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                        val p = ev.changes.first().position
                        at = DpOffset(p.x.toDp(), p.y.toDp())
                        open = true
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = at) {
            items().forEach { entry ->
                DropdownMenuItem(
                    text = { Text(entry.label) },
                    onClick = { open = false; entry.onClick() },
                )
            }
        }
    }
}

actual val platformContextMenuAvailable: Boolean = true
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/chat/FileDrop.wasmJs.kt`:

```kotlin
package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.DpOffset
import dev.supermux.chat.mimeForFileName
import dev.supermux.net.BlobChunkSource
import dev.supermux.ui.platform.PickedFile
import kotlinx.browser.document
import org.w3c.dom.DragEvent
import org.w3c.dom.events.Event
import org.w3c.files.File
import org.w3c.files.get

/**
 * Drop target = the whole document. The Compose canvas is one element, so per-composable hit
 * testing of a DOM drag is not available; the composer is the only drop target in the app, which
 * is why the desktop actual also highlights on any drag over the window.
 *
 * A `Modifier.Node` rather than `composed {}` so the listeners attach/detach with the composer.
 */
actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragOver: (Boolean) -> Unit,
    onFiles: (List<PickedFile>) -> Unit,
): Modifier = if (!enabled) this else this.then(DocumentDropElement(onDragOver, onFiles))

private data class DocumentDropElement(
    val onDragOver: (Boolean) -> Unit,
    val onFiles: (List<PickedFile>) -> Unit,
) : ModifierNodeElement<DocumentDropNode>() {
    override fun create() = DocumentDropNode(onDragOver, onFiles)
    override fun update(node: DocumentDropNode) {
        node.onDragOver = onDragOver
        node.onFiles = onFiles
    }
}

private class DocumentDropNode(
    var onDragOver: (Boolean) -> Unit,
    var onFiles: (List<PickedFile>) -> Unit,
) : androidx.compose.ui.Modifier.Node() {
    private val over: (Event) -> Unit = { e -> e.preventDefault(); onDragOver(true) }
    private val leave: (Event) -> Unit = { _ -> onDragOver(false) }
    private val drop: (Event) -> Unit = { e ->
        e.preventDefault()
        onDragOver(false)
        val list = (e as DragEvent).dataTransfer?.files
        val files = ArrayList<PickedFile>()
        if (list != null) for (i in 0 until list.length) list[i]?.let { files += pickedFileOf(it) }
        if (files.isNotEmpty()) onFiles(files)
    }

    override fun onAttach() {
        document.addEventListener("dragover", over)
        document.addEventListener("dragleave", leave)
        document.addEventListener("drop", drop)
    }

    override fun onDetach() {
        document.removeEventListener("dragover", over)
        document.removeEventListener("dragleave", leave)
        document.removeEventListener("drop", drop)
    }
}

internal fun pickedFileOf(file: File): PickedFile = PickedFile(
    name = file.name,
    mime = file.type.ifBlank { mimeForFileName(file.name) ?: "application/octet-stream" },
    source = BlobChunkSource(file),
)

/** Right-click on the composer → "Paste image", same shape as the row context menu. */
@Composable
actual fun ComposerContextMenu(
    pasteEnabled: Boolean,
    onPasteImage: () -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    Box(
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent()
                    if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                        val p = ev.changes.first().position
                        at = DpOffset(p.x.toDp(), p.y.toDp())
                        open = true
                        ev.changes.forEach { it.consume() }
                    }
                }
            }
        },
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = at) {
            DropdownMenuItem(
                text = { Text("Paste image") },
                enabled = pasteEnabled,
                onClick = { open = false; onPasteImage() },
            )
        }
    }
}
```

`apps/ui/src/wasmJsMain/kotlin/dev/supermux/ui/editor/EditorSurface.wasmJs.kt`:

```kotlin
package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.HtmlElementView
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.widgets.KeepAlivePanel
import kotlinx.browser.document
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLElement

/**
 * An [EditorEngine] that lives in the DOM. The web host's engine (plan 3) implements this: it
 * receives a container `<div>` positioned by Compose and mounts the CodeMirror iframe inside it.
 * The same split desktop has with `SwingEditorEngine`: the shared surface never names the browser.
 */
interface DomEditorEngine : EditorEngine {
    /** Called once, with the container Compose positions over its canvas. */
    fun attach(container: HTMLElement)
    /** Called when the surface leaves the composition; [attach] may be called again later. */
    fun detach()
}

@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    val dom = engine as? DomEditorEngine ?: return
    KeepAlivePanel(visible = visible) {
        HtmlElementView<HTMLDivElement>(
            modifier = modifier,
            factory = {
                (document.createElement("div") as HTMLDivElement).also { div ->
                    div.style.width = "100%"
                    div.style.height = "100%"
                    dom.attach(div)
                }
            },
            onRelease = { dom.detach() },
        )
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd apps && ./gradlew :ui:compileKotlinWasmJs --console=plain 2>&1 | tail -30
```
Expected: `BUILD SUCCESSFUL`. Known adjustments if the compiler objects:
- `HtmlElementView` signature: CMP 1.11.1 exports `HtmlElementView(modifier, factory, update, onRelease)` in `androidx.compose.ui.viewinterop`. If a parameter name differs, check with `unzip -p ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.compose.ui/ui-wasm-js/1.11.1/*/ui-wasm-js-1.11.1.klib default/linkdata/package_androidx.compose.ui.viewinterop/*.knm | strings | grep -A3 HtmlElementView` and match it.
- `BrowserCursor` lives in `androidx.compose.ui.input.pointer` (confirmed in the klib).
- If a commonMain file fails ONLY on wasm (e.g. a `String.format` or `java.*` use that the earlier grep missed), fix it in commonMain with a common-Kotlin equivalent and note it in the commit message; do not add another `expect`.

Then confirm the JVM side is untouched:

```bash
./gradlew :ui:jvmTest --console=plain 2>&1 | tail -10
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add apps/ui
git commit -m "feat(ui): wasmJs target — browser actuals for the 12 seams (Skia VNC frame, DOM drop target, HtmlElementView editor host)

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 3: `apps/web` module — entry point, shell page, `stageForBroker`

**Files:**
- Create: `apps/web/build.gradle.kts`, `apps/web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt`, `apps/web/src/wasmJsMain/resources/index.html`
- Modify: `apps/settings.gradle.kts`, `.gitignore`

- [ ] **Step 1: Register the module**

In `apps/settings.gradle.kts` after `include(":ios")` add:

```kotlin
// The browser client: :ui compiled to Kotlin/Wasm, staged into src/channels/web/static for the
// broker to serve. Replaces the Vue PWA (see docs/superpowers/specs/2026-09-11-web-to-kmp-compose-design.md).
include(":web")
```

- [ ] **Step 2: Build script**

`apps/web/build.gradle.kts`:

```kotlin
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.serialization)
}

// The browser host of the shared Compose app. Thin by design, like apps/ios: entry point,
// WebPlatform + browser actuals, and the packaging that puts the bundle where the broker serves it.
kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "app.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
        }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
    }
}

// ── Staging for the broker ──────────────────────────────────────────────────────────────────
//
// The broker serves src/channels/web/static disk-first with `/assets/*` immutable and everything
// else no-cache (src/channels/web/static-serve.ts). So: every .js/.wasm goes under assets/ with a
// content hash in its name and every reference to it is rewritten; index.html stays at the root.

val brokerStaticDir: File = rootProject.projectDir.resolve("../src/channels/web/static")
val distDir = layout.buildDirectory.dir("dist/wasmJs/productionExecutable")

// Ceiling on the gzipped download (spec §8): app + skiko wasm + loader js. Catches accidental bloat.
val maxGzipBytes = 6L * 1024 * 1024

fun sha8(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(8)

fun gzipSize(bytes: ByteArray): Long {
    val bos = ByteArrayOutputStream()
    GZIPOutputStream(bos).use { it.write(bytes) }
    return bos.size().toLong()
}

val stageForBroker by tasks.registering {
    group = "distribution"
    description = "Build the wasm bundle and stage it (content-hashed) into src/channels/web/static"
    dependsOn("wasmJsBrowserDistribution")
    inputs.dir(distDir)
    outputs.dir(brokerStaticDir)

    doLast {
        val src = distDir.get().asFile
        val out = brokerStaticDir
        // Wipe everything the previous build (Vite or ours) left; the broker reads disk-first.
        out.deleteRecursively()
        out.mkdirs()
        val assets = out.resolve("assets").apply { mkdirs() }

        // Pass 1: hash every binary/script asset, remember old→new names.
        val renames = linkedMapOf<String, String>()
        val hashable = src.walkTopDown().filter { it.isFile && (it.extension == "wasm" || it.extension == "js" || it.extension == "mjs") }.toList()
        // wasm first: js files reference wasm names, and the rewrite below changes js content (and thus its hash).
        val wasmFiles = hashable.filter { it.extension == "wasm" }
        val jsFiles = hashable.filter { it.extension != "wasm" }

        fun stage(file: File, content: ByteArray): String {
            val hashed = "${file.nameWithoutExtension}-${sha8(content)}.${file.extension}"
            assets.resolve(hashed).writeBytes(content)
            renames[file.name] = "assets/$hashed"
            return hashed
        }
        wasmFiles.forEach { stage(it, it.readBytes()) }

        fun rewrite(text: String, relativeTo: String): String {
            var t = text
            for ((old, new) in renames) {
                // References are bare file names ("app.wasm", "./skiko.wasm") relative to the script.
                val target = if (relativeTo == "assets") new.removePrefix("assets/") else new
                t = t.replace("./$old", target).replace(Regex("(?<![\\w./-])" + Regex.escape(old))) { target }
            }
            return t
        }
        jsFiles.forEach { f -> stage(f, rewrite(f.readText(), "assets").toByteArray()) }

        // Pass 2: everything else copies as-is (index.html gets its references rewritten; other
        // files — icons, manifest, fonts, sw.js in plan 4 — keep their names and the no-cache rule).
        src.walkTopDown().filter { it.isFile && it !in hashable }.forEach { f ->
            val rel = f.relativeTo(src).path
            val dst = out.resolve(rel)
            dst.parentFile.mkdirs()
            if (f.name == "index.html") dst.writeText(rewrite(f.readText(), "")) else f.copyTo(dst, overwrite = true)
        }

        // Size guard.
        val gz = assets.listFiles()!!.filter { it.extension == "wasm" || it.extension == "js" }.sumOf { gzipSize(it.readBytes()) }
        println("stageForBroker: ${renames.size} hashed assets, gzip total ${gz / 1024} KB → $out")
        check(gz <= maxGzipBytes) { "web bundle gzip total ${gz / 1024} KB exceeds the ${maxGzipBytes / 1024} KB ceiling" }
        check(out.resolve("index.html").exists()) { "index.html missing from the staged bundle" }
    }
}
```

- [ ] **Step 3: Shell page**

`apps/web/src/wasmJsMain/resources/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#0b0b0b">
  <title>Supermux</title>
  <style>
    html, body { margin: 0; padding: 0; height: 100%; background: #0b0b0b; color: #e6e6e6;
      font-family: system-ui, -apple-system, sans-serif; overflow: hidden; }
    #splash { position: fixed; inset: 0; display: flex; align-items: center; justify-content: center;
      flex-direction: column; gap: 12px; font-size: 14px; letter-spacing: .02em; }
    #splash .bar { width: 160px; height: 3px; background: #222; border-radius: 2px; overflow: hidden; }
    #splash .bar i { display: block; height: 100%; width: 30%; background: #2dd4bf;
      animation: slide 1.2s ease-in-out infinite; }
    @keyframes slide { 0% { transform: translateX(-100%);} 100% { transform: translateX(400%);} }
    canvas { outline: none; }
  </style>
</head>
<body>
  <div id="splash"><div>Supermux</div><div class="bar"><i></i></div></div>
  <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 4: Entry point**

`apps/web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt`:

```kotlin
package dev.supermux.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.state.jsHttpFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch

/**
 * Plan 1: proves the toolchain end to end — Compose paints, Ktor's Js engine reaches the broker
 * on the page's own origin with the session cookie. Plan 2 replaces [HelloScreen] with the shared
 * `SupermuxApp` root behind `WebPlatform`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    document.getElementById("splash")?.remove()
    ComposeViewport(document.body!!) { HelloScreen() }
}

@Composable
private fun HelloScreen() {
    val scope = rememberCoroutineScope()
    val http = remember { jsHttpFactory()(null) }
    var host by remember { mutableStateOf("(not asked yet)") }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Supermux — Compose for Web", style = MaterialTheme.typography.headlineSmall)
                Text("origin: ${window.location.origin}")
                Button(onClick = {
                    scope.launch {
                        host = runCatching { http.get("${window.location.origin}/host").bodyAsText() }
                            .getOrElse { "error: $it" }
                    }
                }) { Text("GET /host") }
                Text(host, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

- [ ] **Step 5: gitignore**

Append to the repo root `.gitignore`:

```
# apps/web stages its wasm bundle here (same dir Vite used); never committed.
src/channels/web/static/
apps/web/build/
```

(Check `src/channels/web/static` is not already listed — `grep -n "channels/web/static" .gitignore`; skip the line if present.)

- [ ] **Step 6: Build and stage**

```bash
cd apps && ./gradlew :web:stageForBroker --console=plain 2>&1 | tail -25
```
Expected: `BUILD SUCCESSFUL`, a line `stageForBroker: N hashed assets, gzip total <n> KB`, and:

```bash
ls -la ../src/channels/web/static ../src/channels/web/static/assets
grep -o 'src="[^"]*"' ../src/channels/web/static/index.html
```
Expected: `index.html` at the root; `assets/app-<8hex>.js`, `assets/*-<8hex>.wasm` (an `app` wasm and a `skiko` wasm); index references `assets/app-<hash>.js`. Open the hashed js and confirm no bare `app.wasm`/`skiko.wasm` reference survived:

```bash
grep -c '"app.wasm"\|"skiko.wasm"\|\./app\.wasm\|\./skiko\.wasm' ../src/channels/web/static/assets/app-*.js
```
Expected: `0`. If not 0, the loader references the wasm through a form the regex misses — print the surrounding text (`grep -o '.\{40\}skiko\.wasm.\{40\}'`) and extend `rewrite` for that exact form.

- [ ] **Step 7: Commit**

```bash
git add apps/settings.gradle.kts apps/web .gitignore apps/kotlin-js-store
git commit -m "feat(web): apps/web — Compose-for-Web host with a hello screen and the stageForBroker packaging task

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 4: Broker serves `.wasm`

**Files:**
- Modify: `src/channels/web/static-serve.ts:11-25`
- Modify: `src/types/assets.d.ts`
- Test: `src/channels/web/static-serve.test.ts`

- [ ] **Step 1: Failing tests**

Append inside the `describe` in `src/channels/web/static-serve.test.ts`:

```ts
  test("wasm assets get application/wasm, immutable caching and gzip", async () => {
    const dir = tmp()
    const { mkdirSync } = await import("fs")
    mkdirSync(join(dir, "assets"))
    writeFileSync(join(dir, "assets", "app-0123abcd.wasm"), Buffer.alloc(4096, 0))
    const res = serveStatic({ staticDir: dir, embedded: {}, path: "/assets/app-0123abcd.wasm", acceptEncoding: "gzip, br" })
    expect(res).not.toBeNull()
    expect(res!.headers.get("content-type")).toBe("application/wasm")
    expect(res!.headers.get("cache-control")).toContain("immutable")
    expect(res!.headers.get("content-encoding")).toBe("gzip")
  })
```

- [ ] **Step 2: Run to see it fail**

```bash
bun test src/channels/web/static-serve.test.ts
```
Expected: 1 fail — content-type is `application/octet-stream`.

- [ ] **Step 3: Implement**

In `src/channels/web/static-serve.ts` add to `guessMime` before the `return "application/octet-stream"` line:

```ts
  if (p.endsWith(".wasm")) return "application/wasm"
  if (p.endsWith(".mjs"))  return "application/javascript"
```

and change the compressible regex to include both:

```ts
const COMPRESSIBLE = /\.(html|js|mjs|css|json|svg|webmanifest|wasm)$/
```

In `src/types/assets.d.ts` add after the `*.js` line:

```ts
declare module "*.wasm" { const path: string; export default path }
declare module "*.mjs" { const path: string; export default path }
```

- [ ] **Step 4: Run tests**

```bash
bun test src/channels/web/static-serve.test.ts
```
Expected: all pass (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/static-serve.ts src/channels/web/static-serve.test.ts src/types/assets.d.ts
git commit -m "feat(web-channel): serve .wasm/.mjs with the right MIME and gzip them

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

### Task 5: End-to-end smoke against a hermetic broker

**Files:** none modified.

- [ ] **Step 1: Serve the staged bundle from a throwaway broker**

`scripts/test-broker.sh` boots an isolated broker (fresh `MUX_HOME`, random port) and runs the given command with the broker's URL in the environment — read its header (lines 1–30) for the exact variable name (`BASE_URL` in the script body). `MUX_TEST_SKIP_WEB_BUILD=1` keeps it from rebuilding Vue over our staged bundle.

```bash
MUX_TEST_SKIP_WEB_BUILD=1 scripts/test-broker.sh bash -c '
  set -e
  echo "== index"; curl -s -D - -o /dev/null "$BASE_URL/" | grep -i "content-type\|cache-control"
  js=$(ls src/channels/web/static/assets/app-*.js | head -1 | xargs basename)
  wasm=$(ls src/channels/web/static/assets/*.wasm | head -1 | xargs basename)
  echo "== js";   curl -s -D - -o /dev/null -H "Accept-Encoding: gzip" "$BASE_URL/assets/$js"   | grep -i "content-type\|content-encoding\|cache-control"
  echo "== wasm"; curl -s -D - -o /dev/null -H "Accept-Encoding: gzip" "$BASE_URL/assets/$wasm" | grep -i "content-type\|content-encoding\|cache-control"
  echo "== spa";  curl -s -o /dev/null -w "%{http_code} %{content_type}\n" "$BASE_URL/s/nope"
'
```
Expected: index `text/html` + `no-cache`; js `application/javascript`, `gzip`, `immutable`; wasm `application/wasm`, `gzip`, `immutable`; SPA fallback `200 text/html`.

- [ ] **Step 2: Render in a real browser**

Use the `mux:browser` skill (headless Chrome) against the same hermetic broker (`MUX_TEST_KEEP_FIXTURE=1` keeps it up; or run `scripts/test-broker.sh sleep 600` in the background): open `$BASE_URL/`, wait 10 s, screenshot. Expected: a dark page with "Supermux — Compose for Web" and the origin line; clicking "GET /host" shows the broker's `/host` JSON. Check the console for errors (a WasmGC/`WebAssembly.instantiate` error means the browser is below the floor; a 404 on `*.wasm` means a rename the rewrite missed).

- [ ] **Step 3: Record the result**

Append the measured bundle size (gzip KB per asset) and the first-paint time you observed to `docs/superpowers/plans/2026-09-11-web-kmp-plan1-wasm-targets.md` under a new `## Results` heading, and commit:

```bash
git add -f docs/superpowers/plans/2026-09-11-web-kmp-plan1-wasm-targets.md
git commit -m "docs(plan): web→KMP plan 1 results

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_017Wc3ny1VJY4ZkcLD3ka54n"
```

---

## Not in this plan (next plans)

Plan 2: `WebPlatform`, `CookieSession`, `UrlSync`, localStorage stores, the real `SupermuxApp` root. Plan 3: xterm/CodeMirror/VNC/uploads/mic/TTS. Plan 4: setup wizard, `WebPushRegistrar`, `sw.ts`, manifest, icons. Plan 5: Playwright, CI/Docker, cm6 lockfile, delete `src/web-app`.
