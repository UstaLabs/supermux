# Resumable Uploads — Phase 2: Shared KMP client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add one shared `BrokerApi.uploadResumable()` that uploads a file via the Phase-1 chunked protocol — small files in a single POST, large files chunk-by-chunk with per-chunk progress and automatic resume from the server offset on a dropped connection.

**Architecture:** A `ChunkSource` interface abstracts "give me bytes [offset, offset+len)" with a **synchronous** `read` (deliberately non-suspend, so no Swift closure is captured across a SKIE suspension point — the K/N GC-pinning trap). `ByteArrayChunkSource` (commonMain) covers in-memory/small bodies and all unit tests; platform file-backed sources (iOS `NSFileHandle`, Android `ContentResolver`) arrive in Phases 4–5. `uploadResumable` orchestrates: `size ≤ threshold` → reuse the existing single `POST /upload`; else `POST /upload/init` → loop `PATCH` (server-dictated `chunk_size`) → the last `PATCH` returns `file_id`. On a network throw mid-chunk it `HEAD`s the server offset and resumes.

**Tech Stack:** Kotlin Multiplatform, Ktor 3.5.0 client, `kotlinx.serialization`, `kotlin.test` + Ktor `MockEngine` (commonTest). Module `:shared`; JVM test task `:shared:jvmTest` (runs on this Linux host). Mirrors the capture pattern in `apps/shared/src/commonTest/.../BrokerApiUploadTest.kt`.

**Design refinement vs spec:** The spec said `expect/actual ChunkSource`. An **interface + a commonMain `ByteArrayChunkSource`** is cleaner: it needs no per-target `actual`, keeps Phase 2 fully JVM-testable, and the platform file-backed impls live naturally in their own source sets in Phases 4–5. Same synchronous-read intent, less ceremony.

**Progress model:** deterministic per-chunk emission (`onProgress(bytesAcked, total)` after each chunk) — testable without depending on whether `MockEngine` fires Ktor's `onUpload`. Large files get N steps; small single-POST files emit `(0,total)` then `(total,total)`. Smooth intra-chunk progress via Ktor `onUpload` is a later feel-refinement, out of scope here.

**Phase scope:** Phase 2 of 5. Produces a tested shared client. Depends on Phase 1's endpoints (merged/available on this branch). The web↔Kotlin **parity** vectors the spec mentions are authored here as Kotlin test data and mirrored in Phase 3's web tests.

---

## File Structure

- **Create** `apps/shared/src/commonMain/kotlin/dev/supermux/net/ChunkSource.kt` — the `ChunkSource` interface + `ByteArrayChunkSource`.
- **Modify** `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt` — add `InitResponse`/`PatchResponse` DTOs (near `UploadResponse`), the `uploadResumable()` method + a private `headUpload()` (near `upload()`), and the `io.ktor.client.request.head` import.
- **Create** `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt` — unit tests via a stateful `MockEngine` (init/patch/HEAD), incl. the resume round-trip.

---

## Task 1: `ChunkSource` interface + `ByteArrayChunkSource`

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/net/ChunkSource.kt`
- Create: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt` (test added here; more tests appended in later tasks)

- [ ] **Step 1: Write the source file**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/net/ChunkSource.kt`:

```kotlin
package dev.supermux.net

/**
 * A byte source for resumable upload. `read` is SYNCHRONOUS on purpose: it must
 * not capture a Swift closure across a coroutine suspension point (the Kotlin/Native
 * GC-pinning trap). Platform file-backed implementations (iOS NSFileHandle,
 * Android ContentResolver) live in their own source sets; [ByteArrayChunkSource]
 * covers in-memory bodies and all common tests.
 */
interface ChunkSource {
    /** Total byte length of the source. */
    val size: Long

    /** Bytes in range [offset, min(offset+len, size)). May return fewer than [len]
     *  bytes only at end-of-source; never more. */
    fun read(offset: Long, len: Int): ByteArray
}

/** In-memory [ChunkSource] over a [ByteArray]. Used for small/pasted bodies and tests. */
class ByteArrayChunkSource(private val bytes: ByteArray) : ChunkSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, len: Int): ByteArray {
        val start = offset.toInt()
        val end = minOf(start + len, bytes.size)
        if (start >= end) return ByteArray(0)
        return bytes.copyOfRange(start, end)
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt`:

```kotlin
package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrokerApiResumableUploadTest {
    private fun OutgoingContent.bytes(): ByteArray = (this as OutgoingContent.ByteArrayContent).bytes()

    @Test fun byteArrayChunkSource_reads_ranges_and_clamps_at_end() {
        val src = ByteArrayChunkSource(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(5L, src.size)
        assertEquals(listOf<Byte>(1, 2, 3), src.read(0, 3).toList())
        assertEquals(listOf<Byte>(4, 5), src.read(3, 3).toList()) // clamped
        assertEquals(0, src.read(5, 3).size)                       // at end
    }
}
```

- [ ] **Step 3: Run to verify it passes (source compiles + reads)**

Run (from the `apps/` dir): `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: PASS (1 test). First run compiles the KMP module — allow time.

- [ ] **Step 4: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/ChunkSource.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt
git commit -m "feat(uploads): ChunkSource interface + ByteArrayChunkSource (KMP)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: DTOs + `uploadResumable` small-file (single-POST) branch

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt`

- [ ] **Step 1: Add the DTOs**

In `BrokerApi.kt`, after the `UploadResponse` data class (line ~138):

```kotlin
/** POST /upload/init → a new resumable upload handle + the server-dictated chunk size. */
@Serializable
data class InitResponse(val upload_id: String, val offset: Long = 0, val chunk_size: Long = 0)

/** PATCH /upload/<id> → either the new byte offset, or (on the final chunk) the
 *  finalized attachment. `file_id != null` means the upload is complete. */
@Serializable
data class PatchResponse(
    val offset: Long? = null,
    val file_id: String? = null,
    val size: Long = 0,
    val mime: String = "",
    val name: String = "",
)
```

- [ ] **Step 2: Add the `head` import**

Add near the other `io.ktor.client.request.*` imports (line ~5):

```kotlin
import io.ktor.client.request.head
```

- [ ] **Step 3: Write the failing test**

Append to `BrokerApiResumableUploadTest.kt` (inside the class):

```kotlin
    @Test fun uploadResumable_small_file_uses_single_post_and_reports_progress() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val engine = MockEngine { req ->
            reqs.add(req)
            respond(
                ByteReadChannel("""{"file_id":"f_small","size":3,"mime":"video/mp4","name":"a.mp4"}"""),
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = BrokerApi("http://h", "tok", HttpClient(engine))
        val progress = mutableListOf<Pair<Long, Long>>()
        val res = api.uploadResumable("s1", ByteArrayChunkSource(byteArrayOf(1, 2, 3)), "a.mp4", "video/mp4", "video") { s, t -> progress.add(s to t) }

        assertEquals("f_small", res.file_id)
        // one single-POST to /upload, NOT the chunked endpoints
        assertEquals(1, reqs.size)
        assertEquals(HttpMethod.Post, reqs[0].method)
        assertEquals("http://h/upload", reqs[0].url.toString())
        // progress ends at 100%
        assertEquals(3L to 3L, progress.last())
    }
```

- [ ] **Step 4: Run to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: FAIL — `uploadResumable` unresolved / compile error.

- [ ] **Step 5: Add `uploadResumable` (small branch only for now)**

In `BrokerApi.kt`, after the `uploadBase64` method (line ~1373):

```kotlin
    /** Threshold below which a file uploads in a single POST (no init round-trip).
     *  Only decides single-POST vs chunked entry; both handle any size ≤ server cap.
     *  `internal var` so commonTest can force the chunked path with tiny bodies. */
    internal var resumableThresholdBytes = 5L * 1024 * 1024

    /**
     * Upload [source] via the chunked/resumable protocol, reporting absolute
     * progress `(bytesAcked, total)` after each step. Small files take a single
     * POST /upload; large files init → PATCH loop → finalize, resuming from the
     * server offset (HEAD) if a chunk throws. Returns the finalized attachment.
     */
    suspend fun uploadResumable(
        session: String,
        source: ChunkSource,
        filename: String,
        mime: String,
        kind: String? = null,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): UploadResponse {
        val total = source.size
        if (total <= resumableThresholdBytes) {
            onProgress(0, total)
            val res = upload(session, source.read(0, total.toInt()), filename, mime, kind)
            onProgress(total, total)
            return res
        }
        return uploadChunked(session, source, filename, mime, kind, total, onProgress)
    }
```

- [ ] **Step 6: Add a temporary stub for `uploadChunked` so it compiles**

Directly below `uploadResumable`, add (real body lands in Task 3):

```kotlin
    private suspend fun uploadChunked(
        session: String, source: ChunkSource, filename: String, mime: String,
        kind: String?, total: Long, onProgress: (Long, Long) -> Unit,
    ): UploadResponse = throw NotImplementedError("chunked upload — Task 3")
```

- [ ] **Step 7: Run to verify the small-file test passes**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: PASS (the small-file + ByteArrayChunkSource tests; chunked not yet exercised).

- [ ] **Step 8: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt
git commit -m "feat(uploads): uploadResumable small-file single-POST branch + DTOs (KMP)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `uploadChunked` — init → PATCH loop → finalize

**Files:**
- Modify: `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt`

- [ ] **Step 1: Write the failing test (stateful mock server)**

Append to `BrokerApiResumableUploadTest.kt`. This mock tracks a per-upload offset across init/patch:

```kotlin
    /** A stateful mock broker: init returns chunk_size, each PATCH appends and
     *  echoes the new offset, the final PATCH returns the finalized attachment. */
    private fun chunkedApi(
        chunkSize: Long,
        total: Long,
        reqs: MutableList<HttpRequestData>,
        failFirstPatch: Boolean = false,
    ): BrokerApi {
        var received = 0L
        var patchCalls = 0
        val engine = MockEngine { req ->
            reqs.add(req)
            val path = req.url.encodedPath
            when {
                req.method == HttpMethod.Post && path == "/upload/init" ->
                    respond(ByteReadChannel("""{"upload_id":"up_1","offset":0,"chunk_size":$chunkSize}"""),
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

                req.method == HttpMethod.Patch && path == "/upload/up_1" -> {
                    patchCalls++
                    if (failFirstPatch && patchCalls == 1) throw RuntimeException("boom")
                    val len = (req.body as OutgoingContent.ByteArrayContent).bytes().size
                    received += len
                    if (received >= total)
                        respond(ByteReadChannel("""{"file_id":"up_1","size":$received,"mime":"video/mp4","name":"a.mp4"}"""),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    else
                        respond(ByteReadChannel("""{"offset":$received}"""),
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }

                req.method == HttpMethod.Head && path == "/upload/up_1" ->
                    respond(ByteReadChannel(""), HttpStatusCode.OK, headersOf("Upload-Offset", "$received"))

                else -> respond(ByteReadChannel(""), HttpStatusCode.NotFound)
            }
        }
        // threshold 0 → any non-empty source takes the chunked path (the test bodies
        // are tiny; the threshold only gates single-POST vs chunked entry).
        return BrokerApi("http://h", "tok", HttpClient(engine)).also { it.resumableThresholdBytes = 0 }
    }

    @Test fun uploadResumable_large_file_chunks_and_reports_per_chunk_progress() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val bytes = ByteArray(10) { it.toByte() }        // 10 bytes, chunk_size 4 → 4+4+2
        val api = chunkedApi(chunkSize = 4, total = 10, reqs = reqs)
        val progress = mutableListOf<Pair<Long, Long>>()
        val res = api.uploadResumable("s1", ByteArrayChunkSource(bytes), "a.mp4", "video/mp4", "video") { s, t -> progress.add(s to t) }

        assertEquals("up_1", res.file_id)
        assertEquals(10L, res.size)
        // init + 3 PATCHes
        assertEquals(1, reqs.count { it.method == HttpMethod.Post && it.url.encodedPath == "/upload/init" })
        assertEquals(3, reqs.count { it.method == HttpMethod.Patch })
        // Upload-Offset header advanced 0,4,8
        val offsets = reqs.filter { it.method == HttpMethod.Patch }.map { it.headers["Upload-Offset"] }
        assertEquals(listOf("0", "4", "8"), offsets)
        // deterministic per-chunk progress, ending at 100%
        assertEquals(listOf(4L to 10L, 8L to 10L, 10L to 10L), progress)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: FAIL — `NotImplementedError: chunked upload — Task 3`.

- [ ] **Step 3: Implement `uploadChunked` + `headUpload`**

Replace the `uploadChunked` stub in `BrokerApi.kt` with:

```kotlin
    private suspend fun uploadChunked(
        session: String, source: ChunkSource, filename: String, mime: String,
        kind: String?, total: Long, onProgress: (Long, Long) -> Unit,
    ): UploadResponse {
        // 1) init
        val init: InitResponse = decode(http.post("$httpBase/upload/init") {
            header(HttpHeaders.Authorization, bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(InitRequest(session, mime, filename, kind, total)))
        })
        val uploadId = init.upload_id
        val chunkSize = if (init.chunk_size > 0) init.chunk_size else resumableThresholdBytes

        // 2) PATCH loop, resuming from the server offset on a network throw.
        var offset = init.offset
        var attempts = 0
        val maxAttempts = 5
        while (true) {
            val len = minOf(chunkSize, total - offset).toInt()
            val chunk = source.read(offset, len)
            try {
                val resp = http.patch("$httpBase/upload/$uploadId") {
                    header(HttpHeaders.Authorization, bearerHeader())
                    header("Upload-Offset", offset.toString())
                    contentType(ContentType.Application.OctetStream)
                    setBody(chunk)
                }
                when (resp.status.value) {
                    200 -> {
                        val pr = json.decodeFromString<PatchResponse>(resp.bodyAsText())
                        if (pr.file_id != null) {
                            onProgress(total, total)
                            return UploadResponse(pr.file_id, pr.size, pr.mime, pr.name)
                        }
                        offset = pr.offset ?: (offset + len)
                        attempts = 0
                        onProgress(offset, total)
                    }
                    409 -> { // server-driven resync
                        offset = resp.headers["Upload-Offset"]?.toLongOrNull() ?: offset
                    }
                    else -> throw IllegalStateException("resumable upload failed: HTTP ${resp.status.value}")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                // Network drop: resync from the server offset and retry (capped).
                if (++attempts > maxAttempts) throw e
                val serverOffset = headUpload(uploadId)
                    ?: throw IllegalStateException("resumable upload lost (HEAD 404 after ${e.message})")
                offset = serverOffset
            }
        }
    }

    /** HEAD /upload/<id> → the server's current stored offset, or null if the
     *  upload is unknown (never created, or already finalized/GC'd). */
    private suspend fun headUpload(uploadId: String): Long? {
        val resp = http.head("$httpBase/upload/$uploadId") {
            header(HttpHeaders.Authorization, bearerHeader())
        }
        return if (resp.status.value == 200) resp.headers["Upload-Offset"]?.toLongOrNull() else null
    }
```

- [ ] **Step 4: Add the `InitRequest` DTO**

In `BrokerApi.kt`, next to `InitResponse`:

```kotlin
@Serializable
private data class InitRequest(
    val session: String, val mime: String, val name: String,
    val kind: String? = null, val total_size: Long,
)
```

Also ensure `bodyAsText` is imported (it is — `import io.ktor.client.statement.bodyAsText` already present).

- [ ] **Step 5: Run to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: PASS (small + large + ByteArrayChunkSource).

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt
git commit -m "feat(uploads): uploadChunked init/PATCH/finalize loop (KMP)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Resume-on-drop test (HEAD resync)

**Files:**
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt`

- [ ] **Step 1: Write the test (first PATCH throws, then resumes)**

Append to `BrokerApiResumableUploadTest.kt`:

```kotlin
    @Test fun uploadResumable_resumes_after_a_dropped_chunk() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val bytes = ByteArray(10) { it.toByte() }
        // failFirstPatch: the first PATCH throws; the client HEADs (offset still 0)
        // and retries, eventually finalizing.
        val api = chunkedApi(chunkSize = 4, total = 10, reqs = reqs, failFirstPatch = true)
        val res = api.uploadResumable("s1", ByteArrayChunkSource(bytes), "a.mp4", "video/mp4", "video")

        assertEquals("up_1", res.file_id)
        assertEquals(10L, res.size)
        // A HEAD probe happened after the drop, and the upload still completed.
        assertTrue(reqs.any { it.method == HttpMethod.Head })
        assertTrue(reqs.count { it.method == HttpMethod.Patch } >= 3)
    }
```

- [ ] **Step 2: Run to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest --tests "dev.supermux.net.BrokerApiResumableUploadTest" -Dorg.gradle.jvmargs=-Xmx2g`
Expected: PASS (all resumable tests, incl. resume). `runTest` fast-forwards the backoff `delay`, so this does not actually wait.

> If this FAILS because the retry loop never HEADs, re-check Task 3's `catch` block resyncs via `headUpload` before `continue`.

- [ ] **Step 3: Commit**

```bash
git add apps/shared/src/commonTest/kotlin/dev/supermux/net/BrokerApiResumableUploadTest.kt
git commit -m "test(uploads): resumable upload resumes after a dropped chunk (KMP)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Full `:shared` suite green

**Files:** none (verification)

- [ ] **Step 1: Run the whole shared JVM test suite**

Run: `TMPDIR=/home/ahmet/.cache/muxtmp ./gradlew :shared:jvmTest -Dorg.gradle.jvmargs=-Xmx2g`
Expected: PASS — the new resumable tests plus all existing `BrokerApi*`/proto/ui tests. (If a Gradle daemon OOMs on this shared box, stop other daemons and re-run solo; heap is capped at 2g.)

- [ ] **Step 2: Commit (if any incidental fixes were needed; otherwise skip)**

Only if Step 1 surfaced a fix.

---

## Done criteria (Phase 2)

- `:shared:jvmTest` green, including: `ByteArrayChunkSource` range/clamp, small-file single-POST + progress, large-file chunk loop with correct `Upload-Offset` sequence + per-chunk progress ending at 100%, and resume-after-drop via HEAD.
- `uploadResumable` returns the same `UploadResponse` shape as the legacy `upload()`, so iOS/Android call sites (Phases 4–5) swap in with no downstream change.
- No change to the existing `upload()`/`uploadBase64` (their tests still pass).

## Next phases

3. **Web** — XHR single-POST progress + a chunked client mirroring this loop + the shared parity vectors + determinate UI + retry.
4. **iOS** — an `NSFileHandle`-backed `ChunkSource` + progress/failed composer UI + wire `uploadResumable`.
5. **Android** — a `ContentResolver`-backed `ChunkSource` + progress/failed chip + wire `uploadResumable`.
