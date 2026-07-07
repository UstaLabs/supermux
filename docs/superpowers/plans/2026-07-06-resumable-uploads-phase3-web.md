# Resumable Uploads — Phase 3: Web (PWA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Give the web composer a chunked, resumable upload with a determinate progress bar — mirroring the Phase-2 KMP loop — so big videos show real progress, resume across a dropped connection, and never hold the whole file in JS memory.

**Architecture:** `useUploader().upload()` gains an `onProgress(sent,total)` callback and an internal small-vs-chunked split at a 5 MB threshold. Small files keep the single `POST /upload`. Large files: `POST /upload/init` → loop `fetch PATCH` of `file.slice(offset, offset+chunk_size)` (the browser streams each Blob slice from disk — bounded memory) → the last chunk returns `file_id`; a network throw resyncs via `HEAD` and retries. Progress is per-chunk (`onProgress` after each ack), matching KMP — no XHR needed.

**Tech Stack:** Vue 3 + Pinia + TypeScript, `bun test` with a `globalThis.fetch` mock (see `useUploader.test.ts`). Web auth is same-origin cookies (existing `upload()` sets no Authorization header — neither do the new requests).

**Deviation from spec (documented):** the spec floated swapping `fetch`→XHR for single-POST progress. Instead the web client chunks large files (the ones that need a progress bar) and reports per-chunk progress over plain `fetch` — symmetric with the KMP client, and keeps the fetch-mock test harness. Intra-chunk smoothness (XHR `upload.onprogress`) is a later refinement, consistent with skipping Ktor `onUpload` in Phase 2.

**Phase scope:** Phase 3 of 5. Depends on Phase 1 endpoints. Deployable restart-free (web-app-only rebuild into the served static dir).

---

## File Structure

- **Modify** `src/web-app/src/composables/useUploader.ts` — `onProgress` param, threshold split, chunked client (`uploadChunked`), `headOffset`; a `useUploader({thresholdBytes})` option for tests.
- **Modify** `src/web-app/src/stores/uploads.ts` — add `progress` to the `uploading` state + a `setProgress(id, fraction)` action.
- **Modify** `src/web-app/src/composables/useComposerSubmit.ts` — pass an `onProgress` that calls `uploads.setProgress`.
- **Modify** `src/web-app/src/components/ai-elements/prompt-input/PromptInputAttachments.vue` — a determinate progress bar driven by `uploads.byId[f.id].progress`.
- **Modify** `src/web-app/src/composables/useUploader.test.ts` — chunked + progress tests (stateful fetch mock).

---

## Task 1: `uploads` store — progress state

**Files:**
- Modify: `src/web-app/src/stores/uploads.ts`
- Test: covered indirectly by Task 4 composer/uploader tests (the store is a thin Pinia holder; no dedicated test file exists).

- [ ] **Step 1: Add `progress` to the uploading state + `setProgress`**

In `src/web-app/src/stores/uploads.ts`, change the `UploadState` union and add the action:

```typescript
export type UploadState =
  | { status: "pending" }
  | { status: "uploading"; startedAt: number; progress: number }
  | { status: "uploaded"; result: UploadResult }
  | { status: "failed"; error: string }
```

In `start`, seed progress 0:

```typescript
  function start(id: string): void {
    byId.value[id] = { status: "uploading", startedAt: Date.now(), progress: 0 }
  }
```

Add after `start`:

```typescript
  function setProgress(id: string, fraction: number): void {
    const s = byId.value[id]
    if (s?.status === "uploading") s.progress = Math.max(0, Math.min(1, fraction))
  }
```

And export it — add `setProgress` to the returned object:

```typescript
  return { byId, get, start, setProgress, succeed, fail, reset, clearAll }
```

- [ ] **Step 2: Type-check**

Run: `cd src/web-app && bunx vue-tsc --noEmit` (or the repo's web typecheck script if different)
Expected: no new errors from `uploads.ts`. (If `vue-tsc` isn't wired, rely on Task 4's `bun test` + the build in Task 5.)

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/stores/uploads.ts
git commit -m "feat(uploads): web upload progress state in the uploads store

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `useUploader` — onProgress + chunked client

**Files:**
- Modify: `src/web-app/src/composables/useUploader.ts`
- Test: `src/web-app/src/composables/useUploader.test.ts`

- [ ] **Step 1: Write the failing tests (stateful fetch mock)**

Append to `src/web-app/src/composables/useUploader.test.ts`:

```typescript
// A stateful mock broker for the chunked path: /upload/init → PATCH* → finalize.
function mockChunkedFetch() {
  const calls: { url: string; method: string; offset: string | null }[] = []
  let received = 0
  globalThis.fetch = (async (url: any, init?: any) => {
    const u = String(url)
    const method = (init?.method ?? "GET").toUpperCase()
    const offset = init?.headers?.["upload-offset"] ?? null
    calls.push({ url: u, method, offset })
    if (u === "/upload/init") {
      return new Response(JSON.stringify({ upload_id: "up_1", offset: 0, chunk_size: 4 }), { status: 200 })
    }
    if (u === "/upload/up_1" && method === "PATCH") {
      const body = init.body as Blob
      received += body.size
      const total = 10
      if (received >= total) return new Response(JSON.stringify({ file_id: "up_1", size: received, mime: "video/mp4", name: "v.mp4" }), { status: 200 })
      return new Response(JSON.stringify({ offset: received }), { status: 200 })
    }
    if (u === "/upload/up_1" && method === "HEAD") {
      return new Response(null, { status: 200, headers: { "upload-offset": String(received) } })
    }
    return new Response("no", { status: 404 })
  }) as any
  return { calls }
}

test("chunked upload: init → PATCH slices → finalize, reports per-chunk progress", async () => {
  const { calls } = mockChunkedFetch()
  const file = new File([new Uint8Array(10)], "v.mp4", { type: "video/mp4" })
  const progress: number[] = []
  const { upload } = useUploader({ thresholdBytes: 0 }) // force chunked
  const res = await upload("s1", file, "video", (sent, total) => progress.push(sent / total))

  expect(res.file_id).toBe("up_1")
  expect(res.size).toBe(10)
  expect(calls.filter((c) => c.method === "PATCH").map((c) => c.offset)).toEqual(["0", "4", "8"])
  expect(progress[progress.length - 1]).toBe(1) // ends at 100%
})

test("small file stays single-POST and still reports 100% at the end", async () => {
  const { calls } = mockFetch({ file_id: "f1", size: 3, mime: "video/mp4", name: "a.mp4" })
  const file = new File([new Uint8Array([1, 2, 3])], "a.mp4", { type: "video/mp4" })
  const progress: number[] = []
  const { upload } = useUploader() // default 5 MB threshold → single POST
  await upload("s1", file, undefined, (sent, total) => progress.push(sent / total))

  expect(calls).toHaveLength(1)
  expect(calls[0].url).toBe("/upload")
  expect(progress[progress.length - 1]).toBe(1)
})
```

- [ ] **Step 2: Run to verify it fails**

Run: `bun test src/web-app/src/composables/useUploader.test.ts`
Expected: FAIL — `useUploader` takes no args / `upload` ignores the 4th arg / no chunked path.

- [ ] **Step 3: Rewrite `useUploader.ts`**

Replace the whole file `src/web-app/src/composables/useUploader.ts` with:

```typescript
import type { AttachmentRef } from "@/stores/messages"

export interface UploadResult {
  file_id: string
  size: number
  mime: string
  name: string
}

export type UploadProgress = (sent: number, total: number) => void

const DEFAULT_THRESHOLD_BYTES = 5 * 1024 * 1024
const MAX_RESUME_ATTEMPTS = 5

export function useUploader(opts?: { thresholdBytes?: number }) {
  const threshold = opts?.thresholdBytes ?? DEFAULT_THRESHOLD_BYTES

  // Small files: one raw octet-stream POST (unchanged wire shape). fetch can't
  // report progress, so we emit 0 then 100 — small files are near-instant.
  async function uploadSingle(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    onProgress?.(0, file.size)
    const headers: Record<string, string> = {
      "Content-Type": "application/octet-stream",
      "X-Mux-Session": session,
      "X-Mux-Mime": file.type,
      "X-Mux-Filename": encodeURIComponent(file.name),
    }
    if (kindHint) headers["X-Mux-Kind"] = kindHint
    const res = await fetch("/upload", { method: "POST", headers, body: file })
    if (!res.ok) {
      const text = await res.text().catch(() => "")
      throw new Error(`upload failed: ${res.status} ${text}`)
    }
    onProgress?.(file.size, file.size)
    return res.json() as Promise<UploadResult>
  }

  // Large files: init → PATCH slices → finalize, resuming from the server offset
  // (HEAD) on a dropped chunk. The browser streams each Blob slice from disk, so
  // memory stays bounded to one chunk.
  async function uploadChunked(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    const initRes = await fetch("/upload/init", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ session, mime: file.type, name: file.name, kind: kindHint, total_size: file.size }),
    })
    if (!initRes.ok) throw new Error(`upload init failed: ${initRes.status}`)
    const { upload_id, chunk_size } = (await initRes.json()) as { upload_id: string; chunk_size: number }

    let offset = 0
    let attempts = 0
    for (;;) {
      const end = Math.min(offset + chunk_size, file.size)
      const slice = file.slice(offset, end)
      try {
        const resp = await fetch(`/upload/${upload_id}`, {
          method: "PATCH",
          headers: { "upload-offset": String(offset) },
          body: slice,
        })
        if (resp.status === 200) {
          const j = (await resp.json()) as { offset?: number; file_id?: string; size?: number; mime?: string; name?: string }
          if (j.file_id) {
            onProgress?.(file.size, file.size)
            return { file_id: j.file_id, size: j.size ?? file.size, mime: j.mime ?? file.type, name: j.name ?? file.name }
          }
          offset = j.offset ?? end
          attempts = 0
          onProgress?.(offset, file.size)
        } else if (resp.status === 409) {
          offset = Number(resp.headers.get("upload-offset") ?? offset)
        } else {
          const text = await resp.text().catch(() => "")
          throw new Error(`upload chunk failed: ${resp.status} ${text}`)
        }
      } catch (err) {
        if (++attempts > MAX_RESUME_ATTEMPTS) throw err
        const serverOffset = await headOffset(upload_id)
        if (serverOffset === null) throw err
        offset = serverOffset
      }
    }
  }

  async function headOffset(upload_id: string): Promise<number | null> {
    const resp = await fetch(`/upload/${upload_id}`, { method: "HEAD" })
    if (resp.status !== 200) return null
    const h = resp.headers.get("upload-offset")
    return h === null ? null : Number(h)
  }

  async function upload(session: string, file: File, kindHint?: AttachmentRef["kind"], onProgress?: UploadProgress): Promise<UploadResult> {
    return file.size <= threshold
      ? uploadSingle(session, file, kindHint, onProgress)
      : uploadChunked(session, file, kindHint, onProgress)
  }

  return { upload }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `bun test src/web-app/src/composables/useUploader.test.ts`
Expected: PASS — the 3 existing tests (small file → single POST, unchanged) plus the 2 new ones.

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/composables/useUploader.ts src/web-app/src/composables/useUploader.test.ts
git commit -m "feat(uploads): web chunked/resumable uploader with progress

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Wire progress through the composer submit

**Files:**
- Modify: `src/web-app/src/composables/useComposerSubmit.ts`

- [ ] **Step 1: Pass an onProgress callback to `uploader.upload`**

In `src/web-app/src/composables/useComposerSubmit.ts`, find the upload call (currently `const result = await uploader.upload(id, f.file, kindHint)`) and change it to:

```typescript
        const result = await uploader.upload(id, f.file, kindHint, (sent, total) => {
          uploads.setProgress(f.id, total > 0 ? sent / total : 0)
        })
```

(`id` is the session id; `f.id` is the attachment id — the store is keyed by attachment id.)

- [ ] **Step 2: Type-check + run the composer tests if present**

Run: `bun test src/web-app/src/composables`
Expected: PASS (no regression; `useComposerSubmit` tests, if any, still pass).

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/composables/useComposerSubmit.ts
git commit -m "feat(uploads): feed per-chunk progress into the uploads store

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Determinate progress bar in the attachment chip

**Files:**
- Modify: `src/web-app/src/components/ai-elements/prompt-input/PromptInputAttachments.vue`

- [ ] **Step 1: Replace the indeterminate overlay with a determinate bar**

In `PromptInputAttachments.vue`, replace the uploading overlay block:

```html
        <div
          v-if="uploads.byId[f.id]?.status === 'uploading'"
          class="absolute inset-0 bg-background/60 flex items-center justify-center pointer-events-none"
        >
          <Loader2 class="size-4 animate-spin text-foreground" />
        </div>
```

with an overlay that shows the percentage plus a bottom progress bar:

```html
        <div
          v-if="uploads.byId[f.id]?.status === 'uploading'"
          class="absolute inset-0 bg-background/60 flex items-center justify-center pointer-events-none"
        >
          <span class="text-[10px] font-medium tabular-nums text-foreground">
            {{ Math.round(((uploads.byId[f.id] as any)?.progress ?? 0) * 100) }}%
          </span>
        </div>
        <div
          v-if="uploads.byId[f.id]?.status === 'uploading'"
          class="absolute bottom-0 left-0 h-0.5 bg-primary transition-all pointer-events-none"
          :style="{ width: `${Math.round(((uploads.byId[f.id] as any)?.progress ?? 0) * 100)}%` }"
        />
```

(`Loader2` may now be unused — if so, remove it from the `lucide-vue-next` import to avoid a lint warning.)

- [ ] **Step 2: Build the web app to confirm it compiles**

Covered by Task 5's build. (No unit test for the .vue chip; verified visually in Task 5 / on deploy.)

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/components/ai-elements/prompt-input/PromptInputAttachments.vue
git commit -m "feat(uploads): determinate upload progress bar on the web attachment chip

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Web build + suite green

**Files:** none (verification)

- [ ] **Step 1: Run the web app unit tests**

Run: `bun test src/web-app`
Expected: PASS (uploader + any composer/store tests).

- [ ] **Step 2: Production build the web app**

Run the repo's web build (e.g. `cd src/web-app && bun run build`, or the documented PWA build command).
Expected: build succeeds with no type errors. (This is the deploy artifact — a web-app-only rebuild needs no broker restart.)

- [ ] **Step 3: Commit any incidental fixes (else skip)**

---

## Done criteria (Phase 3)

- `bun test src/web-app` green, incl. chunked init→PATCH→finalize with the correct offset sequence and per-chunk progress ending at 100%, and the small-file single-POST path unchanged.
- The composer chip shows a live percentage + bar during upload; failure still surfaces the existing red chip + toast + retry.
- Web build succeeds (deployable restart-free).

## Next phases

4. **iOS** — `NSFileHandle` `ChunkSource` + progress/failed composer UI + wire `uploadResumable`.
5. **Android** — `ContentResolver` `ChunkSource` + progress/failed chip + wire `uploadResumable`.
