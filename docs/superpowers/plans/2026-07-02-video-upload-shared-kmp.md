# Shared KMP Upload — Video Upload Phase 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Switch the shared `BrokerApi.upload`/`uploadBase64` transport from a `multipart/form-data` POST to the raw-body streaming `POST /upload` request (octet-stream body + `X-Mux-*` headers) without changing the public function signatures, so iOS and Android call sites are unaffected.

**Architecture:** `BrokerApi` (Kotlin Multiplatform, `apps/shared`) is a thin Ktor REST wrapper shared verbatim by the iOS (SwiftUI, via SKIE) and Android (Compose) apps. Today `upload()` builds a `MultiPartFormDataContent`; this plan rewrites it to send the file bytes as an `application/octet-stream` request body with metadata carried in headers (`X-Mux-Session`, `X-Mux-Mime`, `X-Mux-Filename`, optional `X-Mux-Kind`), matching the broker's new streaming ingest path. The response JSON `{file_id,size,mime,name}` and the existing `decode()` error handling are unchanged, and `uploadBase64()` continues to delegate to `upload()`.

**Tech Stack:** Kotlin Multiplatform, Ktor client 3.5.0 (`io.ktor:ktor-client-core`), kotlinx.serialization. Tests use Ktor `MockEngine` (`io.ktor:ktor-client-mock`, already a `commonTest` dependency).

**Depends on:** the broker plan (streaming `/upload` endpoint contract). iOS + Android plans depend on THIS.

---

## Shared contract (must match the broker + iOS + Android plans exactly)

Streaming upload request the KMP client now sends:

```
POST {base}/upload
  Authorization:  Bearer <token>                 // unchanged
  Content-Type:   application/octet-stream        // selects the broker streaming path
  X-Mux-Session:  <session id>                    // required
  X-Mux-Mime:     <real mime, e.g. video/mp4>     // the `mime` arg
  X-Mux-Filename: <RFC3986 percent-encoded name>  // the `filename` arg, header-safe
  X-Mux-Kind:     <kind>                           // the `kind` arg, ONLY when non-null
  <body> = raw file bytes                          // the `bytes` arg, verbatim
→ 200 { "file_id": string, "size": number, "mime": string, "name": string }
```

The broker keeps the OLD `multipart/form-data` path too (for un-updated app-store builds), but this KMP client now always sends the streaming form.

## What the code looks like today (verified)

- `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
  - Class `BrokerApi(baseUrl, private val token, private val http: HttpClient)`; `internal val httpBase` (ws→http normalized, trailing slash trimmed); `private fun bearerHeader() = "Bearer $token"`; `private suspend inline fun <reified T> decode(resp): T` (the crash-safe response reader — reused unchanged).
  - `upload(session, bytes, filename, mime, kind?)` at **lines 1306–1326** currently POSTs `MultiPartFormDataContent(formData { append("session", …); if (kind != null) append("kind", …); append("file", bytes, …) })` and returns `decode(resp)`.
  - `uploadBase64(session, base64, filename, mime, kind?)` at **lines 1328–1333** just calls `upload(session, Base64.decode(base64), filename, mime, kind)` — **no change needed** here; it inherits the new transport for free.
  - `private fun urlEncode(s)` at **lines 923–930** is a small ASCII-only `.replace` chain for path segments — it does NOT handle spaces-as-nonpath or non-ASCII/UTF-8, so it is NOT reusable for the filename header (see ambiguity note). A new UTF-8 percent-encoder is added.
  - `UploadResponse` (**lines 133–138**): `data class UploadResponse(val file_id: String, val size: Long = 0, val mime: String = "", val name: String = "")` — parse target, unchanged.
  - Imports already include `io.ktor.http.ContentType`, `io.ktor.http.HttpHeaders`, `io.ktor.http.contentType`, `io.ktor.client.request.header`, `io.ktor.client.request.post`, `io.ktor.client.request.setBody`. The `MultiPartFormDataContent`/`formData`/`Headers` imports are STILL used by `transcribeAudio()` (lines 1355–1368), so they must NOT be removed. → **No import changes are required.**
- `apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt` **lines 70–76**: `Attachment.kind` is a free-form `String? = null` — confirmed, **no change**.
- `apps/shared/build.gradle.kts`: `commonTest` already depends on `libs.ktor.client.mock`. Gradle module path is `:shared` (root project `supermux-apps`, `apps/settings.gradle.kts`); wrapper is `apps/gradlew`. `commonTest` runs on the JVM target via `:shared:jvmTest` (Apple targets are disabled on this Linux host).
- Existing test to mirror: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiVoiceTest.kt` — a capturing `MockEngine` that appends each `HttpRequestData` to a sink, asserts method/URL/headers/body. This plan copies its `captured(...)` pattern.

## File structure

```
apps/shared/
  src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt          # MODIFY: rewrite upload(); add percentEncode() helper
  src/commonTest/kotlin/dev/supermux/net/BrokerApiUploadTest.kt # NEW: MockEngine test pinning the streaming wire shape
```

No change to `Frames.kt`, `build.gradle.kts`, or any import.

---

### Task 1 — Write the failing MockEngine test for the streaming upload (RED)

Test-first: this test asserts the NEW wire shape (octet-stream body, `X-Mux-*` headers, raw bytes). Against the current multipart `upload()` it MUST fail (the content-type is `multipart/form-data…`, the `X-Mux-*` headers are absent, and the body is a multipart blob, not a `ByteArrayContent`). Task 2 makes it pass.

**Files:**
- `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiUploadTest.kt` (new)

Steps:
- [ ] Read `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiVoiceTest.kt` to confirm the `captured(...)` + `HttpRequestData` capture pattern and its imports.
- [ ] Create `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiUploadTest.kt` with EXACTLY this content:

```kotlin
package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [BrokerApi.upload] / [BrokerApi.uploadBase64] via a capturing [MockEngine].
 * The upload transport switched from a multipart form to a raw-body streaming
 * request (POST /upload, application/octet-stream, X-Mux-* headers); these tests
 * pin that wire shape. Mirrors [BrokerApiVoiceTest]'s capture pattern.
 */
class BrokerApiUploadTest {
    /** Build a BrokerApi whose engine records every request and replies [body]. */
    private fun captured(
        body: String = "{}",
        sink: MutableList<HttpRequestData>,
    ): BrokerApi {
        val engine = MockEngine { req ->
            sink.add(req)
            respond(
                content = ByteReadChannel(body),
                status = io.ktor.http.HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    /** The transformed outgoing body's raw bytes (ByteArray bodies only). */
    private fun HttpRequestData.rawBody(): ByteArray =
        (this.body as OutgoingContent.ByteArrayContent).bytes()

    @Test fun upload_posts_octet_stream_with_headers_and_raw_body() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(
            body = """{"file_id":"f_1","size":4,"mime":"video/mp4","name":"clip.mp4"}""",
            sink = reqs,
        )
        val res = api.upload("s1", byteArrayOf(1, 2, 3, 4), "clip.mp4", "video/mp4", "video")

        // Response decoded from the same JSON shape as the old multipart path.
        assertEquals("f_1", res.file_id)
        assertEquals(4L, res.size)
        assertEquals("video/mp4", res.mime)
        assertEquals("clip.mp4", res.name)

        val r = reqs.single()
        assertEquals(HttpMethod.Post, r.method)
        assertEquals("http://h/upload", r.url.toString())
        // Raw-body streaming request, NOT multipart.
        assertEquals("application/octet-stream", r.body.contentType?.toString())
        assertEquals("Bearer tok", r.headers[HttpHeaders.Authorization])
        assertEquals("s1", r.headers["X-Mux-Session"])
        assertEquals("video/mp4", r.headers["X-Mux-Mime"])
        assertEquals("clip.mp4", r.headers["X-Mux-Filename"])
        assertEquals("video", r.headers["X-Mux-Kind"])
        // Body is the file bytes verbatim.
        assertEquals(listOf<Byte>(1, 2, 3, 4), r.rawBody().toList())
    }

    @Test fun upload_omits_kind_header_when_null() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_2"}""", sink = reqs)
        api.upload("s1", byteArrayOf(9), "a.bin", "application/octet-stream")
        val r = reqs.single()
        assertNull(r.headers["X-Mux-Kind"])
        assertEquals("s1", r.headers["X-Mux-Session"])
        assertEquals("application/octet-stream", r.headers["X-Mux-Mime"])
    }

    @Test fun upload_percent_encodes_filename() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_3"}""", sink = reqs)
        // Space + non-ASCII must be RFC3986 (UTF-8) percent-encoded so the value is
        // header-safe and the broker recovers it via decodeURIComponent().
        api.upload("s1", byteArrayOf(0), "my résumé video.mp4", "video/mp4", null)
        val r = reqs.single()
        assertEquals("my%20r%C3%A9sum%C3%A9%20video.mp4", r.headers["X-Mux-Filename"])
    }

    @Test fun upload_base64_decodes_body_and_delegates_to_streaming() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val api = captured(body = """{"file_id":"f_4"}""", sink = reqs)
        // "AQID" == base64([1, 2, 3]); uploadBase64 must decode then hit the streaming path.
        val res = api.uploadBase64("s1", "AQID", "a.bin", "application/octet-stream")
        assertEquals("f_4", res.file_id)
        val r = reqs.single()
        assertEquals("http://h/upload", r.url.toString())
        assertEquals("application/octet-stream", r.body.contentType?.toString())
        assertEquals(listOf<Byte>(1, 2, 3), r.rawBody().toList())
    }
}
```

- [ ] Run the test and CONFIRM IT FAILS (RED) for the expected reason (multipart content-type / missing `X-Mux-*` headers / non-`ByteArrayContent` body), from the `apps/` directory:
  ```
  cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiUploadTest"
  ```
- [ ] Commit:
  ```
  test(shared): pin streaming /upload wire shape (RED)

  Add BrokerApiUploadTest asserting BrokerApi.upload sends
  application/octet-stream with X-Mux-Session/Mime/Filename/Kind
  headers and the raw file bytes as the body, plus uploadBase64
  delegation and RFC3986 filename encoding. Fails against the
  current multipart implementation; Task 2 makes it pass.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 2 — Rewrite `upload()` to the raw-body streaming request + add the percent-encoder (GREEN)

**Files:**
- `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`

Steps:
- [ ] Add the UTF-8 percent-encoder in the helpers region, immediately after `urlEncode(...)` and before the `// ── public API` divider. Replace this exact block:

```kotlin
    private fun urlEncode(s: String): String = s
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("+", "%2B")
        .replace(" ", "%20")

    // ── public API ───────────────────────────────────────────────────────────
```

with:

```kotlin
    private fun urlEncode(s: String): String = s
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace("?", "%3F")
        .replace("#", "%23")
        .replace("&", "%26")
        .replace("+", "%2B")
        .replace(" ", "%20")

    /** Uppercase hex alphabet for [percentEncode] (RFC 3986 §2.1). */
    private val hexDigits = "0123456789ABCDEF"

    /**
     * RFC 3986 percent-encode [s] over its UTF-8 bytes so the broker can recover
     * the original name via `decodeURIComponent()`. Keeps the unreserved set
     * `A–Z a–z 0–9 - _ . ~` and encodes every other byte as `%XX`.
     *
     * Unlike [urlEncode] (a small ASCII-only replace chain for path segments) this
     * handles spaces and non-ASCII, so an arbitrary filename is safe to carry in
     * the `X-Mux-Filename` request header — a raw non-ASCII value would be a
     * malformed HTTP header.
     */
    private fun percentEncode(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val out = StringBuilder(s.length)
        for (byte in s.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && c.toChar() in unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%')
                out.append(hexDigits[c shr 4])
                out.append(hexDigits[c and 0x0F])
            }
        }
        return out.toString()
    }

    // ── public API ───────────────────────────────────────────────────────────
```

- [ ] Rewrite `upload()` to the streaming request. Replace this exact block:

```kotlin
    /** POST /upload — multipart {file, session, kind?} */
    suspend fun upload(
        session: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        kind: String? = null,
    ): UploadResponse {
        val resp = http.post("$httpBase/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(MultiPartFormDataContent(formData {
                append("session", session)
                if (kind != null) append("kind", kind)
                append("file", bytes, Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                })
            }))
        }
        return decode(resp)
    }
```

with:

```kotlin
    /**
     * POST /upload — raw-body streaming upload.
     *
     * Sends the file bytes verbatim as an `application/octet-stream` body with the
     * metadata in headers, so the broker can stream the body straight to disk
     * without buffering it (the streaming `/upload` contract). The signature is
     * unchanged from the old multipart form, so the iOS/Android call sites are
     * untouched.
     *
     *  - `X-Mux-Session`  — required; the owning session id.
     *  - `X-Mux-Mime`     — the real MIME (the octet-stream body type hides it).
     *  - `X-Mux-Filename` — RFC3986 percent-encoded (header-safe) original name.
     *  - `X-Mux-Kind`     — sent ONLY when [kind] is non-null; else the broker infers it.
     */
    suspend fun upload(
        session: String,
        bytes: ByteArray,
        filename: String,
        mime: String,
        kind: String? = null,
    ): UploadResponse {
        val resp = http.post("$httpBase/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header("X-Mux-Session", session)
            header("X-Mux-Mime", mime)
            header("X-Mux-Filename", percentEncode(filename))
            if (kind != null) header("X-Mux-Kind", kind)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }
        return decode(resp)
    }
```

- [ ] Do NOT touch `uploadBase64()` (lines 1328–1333): it calls `upload(session, Base64.decode(base64), filename, mime, kind)` and inherits the streaming transport automatically.
- [ ] Do NOT change imports: `ContentType`, `HttpHeaders`, `contentType`, `header`, `post`, `setBody` are already imported; `MultiPartFormDataContent`/`formData`/`Headers` stay because `transcribeAudio()` still uses them. (Removing them would break the build.)
- [ ] Run the test and CONFIRM IT PASSES (GREEN):
  ```
  cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiUploadTest"
  ```
- [ ] Run the module's full JVM test suite to confirm no regression (voice/multipart tests, etc.):
  ```
  cd apps && ./gradlew :shared:jvmTest
  ```
- [ ] Verify the shared common source still compiles for the metadata target:
  ```
  cd apps && ./gradlew :shared:compileKotlinMetadata
  ```
- [ ] Commit:
  ```
  feat(shared): stream uploads as raw octet-stream body

  Switch BrokerApi.upload from a multipart/form-data POST to the
  raw-body streaming /upload request: file bytes as an
  application/octet-stream body with X-Mux-Session/Mime/Filename
  (RFC3986 percent-encoded) and optional X-Mux-Kind headers. Add a
  UTF-8 percent-encoder for the header-safe filename. Signature is
  unchanged, so iOS/Android call sites and uploadBase64 are
  unaffected; response parsing and decode() error handling are
  preserved.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

## Verification

- `cd apps && ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiUploadTest"` — the new test passes (octet-stream content-type, all four `X-Mux-*` headers, raw body bytes, `X-Mux-Kind` omitted when null, percent-encoded filename, `uploadBase64` delegation).
- `cd apps && ./gradlew :shared:jvmTest` — the whole `:shared` common/JVM suite is green (no regression to `BrokerApiVoiceTest` or the other DTO/round-trip tests).
- `cd apps && ./gradlew :shared:compileKotlinMetadata` — common source compiles.
- The iOS (`BrokerSession.swift`) and Android call sites pass `session, bytes, filename, mime, kind?` and are verified in the **iOS** and **Android** plans; the unchanged signature guarantees they compile and behave identically apart from the new on-the-wire request.

## Ambiguity resolved

- **Filename encoding helper:** the spec says "percent-encode filename" and "RFC3986 percent-encoded." The existing `urlEncode()` (BrokerApi.kt:923) is an ASCII-only `.replace` chain intended for path segments and cannot encode non-ASCII (UTF-8) filenames — and a raw non-ASCII value in the `X-Mux-Filename` header would be a malformed HTTP header that Ktor rejects. This plan therefore adds a dedicated `percentEncode()` that encodes over UTF-8 bytes, keeping only the RFC3986 unreserved set, so the broker's `decodeURIComponent()` round-trips the original name (test `upload_percent_encodes_filename` guards `my résumé video.mp4` → `my%20r%C3%A9sum%C3%A9%20video.mp4`).
- **Content-Type assertion in the test:** asserted via `r.body.contentType` (not `r.headers`) because Ktor moves `Content-Type` onto the transformed `OutgoingContent`; this mirrors `BrokerApiVoiceTest`.
- **Compile/test task:** `commonTest` runs on the JVM target as `:shared:jvmTest` (Apple targets are disabled on Linux); `:shared:compileKotlinMetadata` is the common-source compile check. Both run from the `apps/` directory (`apps/gradlew`, module path `:shared`). No documented alternative command exists in the repo.
