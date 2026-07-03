# Web PWA — Video Upload Phase 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the web PWA pick or record a video, stream it to the broker (raw-body `POST /upload`, up to 500 MB), and play it back inline.
**Architecture:** Add a "Record video" attach action mirroring the existing camera action, switch `useUploader` from multipart `FormData` to a raw-body streaming POST with `X-Mux-*` metadata headers, raise the client size cap to 500 MB with a visible over-limit toast, and extend the composer chip + message renderer to treat the new `"video"` kind as video. Uploads still round-trip through `useComposerSubmit` → WS `send` frame unchanged; the server infers the `"video"` kind from the MIME.
**Tech Stack:** Vue 3, Pinia, TypeScript, Bun
**Depends on:** the broker plan (streaming `/upload` endpoint that accepts `Content-Type: application/octet-stream` + `X-Mux-*` headers and emits the `"video"` kind).

---

## Shared contract (must match the broker / iOS / Android plans exactly)

- Kind string: **`"video"`** (generic picked/recorded video). `"video_note"` stays reserved for Telegram round clips; renderers accept **both**.
- Streaming upload request: `POST /upload`
  - `Content-Type: application/octet-stream` (selects the broker's streaming path)
  - `X-Mux-Session: <session id>` **(required)**
  - `X-Mux-Mime: <file.type>`
  - `X-Mux-Filename: <encodeURIComponent(file.name)>` (percent-encoded, header-safe)
  - `X-Mux-Kind: <kind>` (optional — only sent when the composer has a kind hint; otherwise the server infers from the MIME)
  - body = raw file bytes (the `File`/`Blob` itself; **not** `FormData`)
- Response JSON (unchanged): `{ file_id, size, mime, name }`.
- Cap: **500 MB** client-side (`500 * 1024 * 1024`).

---

## File structure

**New files**
- `src/web-app/src/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue` — "Record video" attach action (`accept="video/*"` + `capture="environment"`).
- `src/web-app/src/composables/useUploader.test.ts` — `bun test` unit test for the streaming uploader.

**Modified files**
- `src/web-app/src/composables/useUploader.ts` — multipart → raw-body streaming POST.
- `src/web-app/src/stores/messages.ts` — add `"video"` to the `AttachmentRef["kind"]` union.
- `src/web-app/src/components/attachments/AttachmentList.vue` — map `video` **and** `video_note` → `AttachmentVideo.vue`.
- `src/web-app/src/components/ai-elements/prompt-input/PromptInputAttachments.vue` — video branch in the composer chip preview.
- `src/web-app/src/views/ChatView.vue` — wire the record-video action into the attach menu; bump `:max-file-size` to 500 MB; add an `@error` over-limit toast.
- `src/web-app/src/views/SessionLauncherView.vue` — same three edits as ChatView.

**Verified (no change) files**
- `src/web-app/src/components/ai-elements/prompt-input/context.ts` (`matchesAccept`) and `PromptInput.vue` (hidden `<input :accept="accept">`) — Files dialog + drag-drop already accept video.

**Verification bar for UI-only pieces:** components here have **no mount harness** (no `@vue/test-utils`/`happy-dom`/`vitest` in `package.json`; every existing `*.test.ts` is pure logic/store/composable). So for presentational Vue edits the bar is: precise before/after edit + `cd src/web-app && bun run build` succeeds (this runs `vue-tsc --noEmit && vite build`, so it is a real typecheck) + a concise manual check. Only `useUploader.ts` gets a real `bun test`.

---

### Task 1 — `useUploader.ts`: multipart → raw-body streaming POST (with unit test)

This is the contract core and is fully testable with a mocked `fetch`. Do it first (TDD).

**Files:**
- `src/web-app/src/composables/useUploader.test.ts` (new)
- `src/web-app/src/composables/useUploader.ts` (modify)

**Steps:**

- [ ] **Write the failing test first.** Create `src/web-app/src/composables/useUploader.test.ts` with this COMPLETE content (mirrors the `mockFetch` convention in `src/web-app/src/stores/forges.test.ts`):
  ```ts
  import { test, expect, afterEach } from "bun:test"
  import { useUploader } from "./useUploader"

  const realFetch = globalThis.fetch
  afterEach(() => {
    globalThis.fetch = realFetch
  })

  type Captured = { url: string; init: RequestInit }
  function mockFetch(response: unknown, status = 200): { calls: Captured[] } {
    const calls: Captured[] = []
    globalThis.fetch = (async (url: any, init?: any) => {
      calls.push({ url: String(url), init: init ?? {} })
      return new Response(JSON.stringify(response), {
        status,
        headers: { "content-type": "application/json" },
      })
    }) as any
    return { calls }
  }

  test("upload streams the raw body with octet-stream + X-Mux-* headers", async () => {
    const { calls } = mockFetch({ file_id: "f1", size: 3, mime: "video/mp4", name: "clip.mp4" })
    const file = new File([new Uint8Array([1, 2, 3])], "clip.mp4", { type: "video/mp4" })
    const { upload } = useUploader()

    const result = await upload("sess-1", file)

    expect(calls).toHaveLength(1)
    const { url, init } = calls[0]
    expect(url).toBe("/upload")
    expect(init.method).toBe("POST")
    const headers = new Headers(init.headers as HeadersInit)
    expect(headers.get("Content-Type")).toBe("application/octet-stream")
    expect(headers.get("X-Mux-Session")).toBe("sess-1")
    expect(headers.get("X-Mux-Mime")).toBe("video/mp4")
    expect(headers.get("X-Mux-Filename")).toBe("clip.mp4")
    // No kind hint → header omitted (server infers "video" from the mime).
    expect(headers.has("X-Mux-Kind")).toBe(false)
    // Raw file body, NOT multipart FormData.
    expect(init.body).toBe(file)
    expect(init.body instanceof FormData).toBe(false)
    // Returned JSON is passed through unchanged.
    expect(result).toEqual({ file_id: "f1", size: 3, mime: "video/mp4", name: "clip.mp4" })
  })

  test("upload percent-encodes the filename and forwards a kind hint", async () => {
    const { calls } = mockFetch({ file_id: "f2", size: 1, mime: "audio/webm", name: "my note.webm" })
    const file = new File([new Uint8Array([0])], "my note.webm", { type: "audio/webm" })
    const { upload } = useUploader()

    await upload("sess-2", file, "voice")

    const headers = new Headers(calls[0].init.headers as HeadersInit)
    expect(headers.get("X-Mux-Filename")).toBe("my%20note.webm")
    expect(headers.get("X-Mux-Kind")).toBe("voice")
  })

  test("upload throws on a non-ok response (e.g. 413 too large)", async () => {
    mockFetch("file too large", 413)
    const file = new File([new Uint8Array([9])], "big.mp4", { type: "video/mp4" })
    const { upload } = useUploader()

    await expect(upload("sess-3", file)).rejects.toThrow(/413/)
  })
  ```
- [ ] Run `cd src/web-app && bun test src/composables/useUploader.test.ts` — expect FAIL (uploader still sends `FormData`, so the header/body assertions fail).
- [ ] **Rewrite the uploader.** Replace the ENTIRE contents of `src/web-app/src/composables/useUploader.ts` with:
  ```ts
  import type { AttachmentRef } from "@/stores/messages"

  export interface UploadResult {
    file_id: string
    size: number
    mime: string
    name: string
  }

  export function useUploader() {
    // Streaming upload: the raw file bytes ARE the request body, with metadata in
    // headers. Content-Type: application/octet-stream selects the broker's streaming
    // ingest path (see the broker plan); the browser sets Content-Length from the
    // File size for the server's fast up-front 413. The legacy multipart path is
    // retained server-side only for old app-store builds.
    async function upload(session: string, file: File, kindHint?: AttachmentRef["kind"]): Promise<UploadResult> {
      const headers: Record<string, string> = {
        "Content-Type": "application/octet-stream",
        "X-Mux-Session": session,
        "X-Mux-Mime": file.type,
        "X-Mux-Filename": encodeURIComponent(file.name),
      }
      if (kindHint) headers["X-Mux-Kind"] = kindHint
      const res = await fetch("/upload", {
        method: "POST",
        headers,
        body: file,
      })
      if (!res.ok) {
        const text = await res.text().catch(() => "")
        throw new Error(`upload failed: ${res.status} ${text}`)
      }
      return res.json() as Promise<UploadResult>
    }

    return { upload }
  }
  ```
  Note: the exported `UploadResult` shape and the `upload(session, file, kindHint?)` signature are **unchanged**, so `useComposerSubmit.ts:88` and `stores/uploads.ts` need no edits.
- [ ] Run `cd src/web-app && bun test src/composables/useUploader.test.ts` — expect PASS (all 3 tests).
- [ ] Commit:
  ```
  feat(web): stream uploads as raw octet-stream body

  Switch useUploader from multipart FormData to a raw-body POST /upload:
  the File bytes are the request body, metadata rides in X-Mux-Session /
  X-Mux-Mime / X-Mux-Filename (percent-encoded) / X-Mux-Kind headers.
  Return shape and signature unchanged.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 2 — Render the new `"video"` kind inline

`AttachmentList.vue` maps a kind → renderer. `AttachmentVideo.vue` already exists (`<video controls playsinline>`) and is wired only for `video_note`. Map **both** kinds to it. This requires extending the hard-coded `AttachmentRef["kind"]` union first, or `vue-tsc` rejects the `kind === "video"` comparison ("no overlap").

**Files:**
- `src/web-app/src/stores/messages.ts` (modify)
- `src/web-app/src/components/attachments/AttachmentList.vue` (modify)

**Steps:**

- [ ] In `src/web-app/src/stores/messages.ts`, extend the `AttachmentRef["kind"]` union (line 6).
  - BEFORE:
    ```ts
      kind: "photo" | "document" | "voice" | "audio" | "video_note"
    ```
  - AFTER:
    ```ts
      kind: "photo" | "document" | "voice" | "audio" | "video" | "video_note"
    ```
- [ ] In `src/web-app/src/components/attachments/AttachmentList.vue`, update `rendererFor` so both video kinds render as video.
  - BEFORE:
    ```ts
  function rendererFor(kind: AttachmentRef["kind"]) {
    if (kind === "photo") return AttachmentImage
    if (kind === "audio" || kind === "voice") return AttachmentAudio
    if (kind === "video_note") return AttachmentVideo
    return AttachmentFile
  }
    ```
  - AFTER:
    ```ts
  function rendererFor(kind: AttachmentRef["kind"]) {
    if (kind === "photo") return AttachmentImage
    if (kind === "audio" || kind === "voice") return AttachmentAudio
    if (kind === "video" || kind === "video_note") return AttachmentVideo
    return AttachmentFile
  }
    ```
- [ ] Build-verify: `cd src/web-app && bun run build` — expect success (this is also the typecheck that proves the union + comparison line up).
- [ ] Manual-verify: with the broker plan running, an inbound message whose attachment `kind === "video"` renders an inline `<video>` player; a `video_note` still renders inline (no regression). If the broker isn't ready, this is deferred to the end-to-end manual pass in Task 6.
- [ ] Commit:
  ```
  feat(web): render the "video" attachment kind inline

  Add "video" to AttachmentRef.kind and map both video and video_note to
  AttachmentVideo so picked/recorded video plays inline; video_note is
  unchanged.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 3 — "Record video" attach action + wire into both menus

Mirror `PromptInputActionAddCamera.vue` exactly, changing only the icon, label, and `accept`. Wire it into the attach menus in both `ChatView.vue` and `SessionLauncherView.vue`. Both views already import the camera action as a **default import** (not via the barrel), so mirror that. No kind hint is set (same as camera) — the server infers `"video"` from `X-Mux-Mime`.

**Files:**
- `src/web-app/src/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue` (new)
- `src/web-app/src/views/ChatView.vue` (modify)
- `src/web-app/src/views/SessionLauncherView.vue` (modify)

**Steps:**

- [ ] Create `src/web-app/src/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue` with this COMPLETE content (identical to the camera action except `Video` icon, "Record video" label, `accept="video/*"`):
  ```vue
  <script setup lang="ts">
  import { DropdownMenuItem } from "@/components/ui/dropdown-menu"
  import { Video } from "lucide-vue-next"
  import { ref } from "vue"
  import { usePromptInput } from "./context"

  const { addFiles } = usePromptInput()
  const inputRef = ref<HTMLInputElement | null>(null)

  function open() {
    inputRef.value?.click()
  }

  function onChange(e: Event) {
    const target = e.target as HTMLInputElement
    if (target.files?.length) addFiles(target.files)
    target.value = ""
  }
  </script>

  <template>
    <DropdownMenuItem @select.prevent="open">
      <Video class="mr-2 size-4" />
      Record video
    </DropdownMenuItem>
    <input
      ref="inputRef"
      type="file"
      accept="video/*"
      capture="environment"
      class="hidden"
      @change="onChange"
    />
  </template>
  ```
- [ ] **ChatView.vue — add the import** directly after the existing camera import (line 63).
  - BEFORE:
    ```ts
  import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
    ```
  - AFTER:
    ```ts
  import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
  import PromptInputActionAddRecordVideo from "@/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue"
    ```
- [ ] **ChatView.vue — add the menu item** (lines 664-667; `<PromptInputActionMenuContent>` at 18 spaces of indent, children at 20). Preserve that exact indentation.
  - BEFORE:
    ```html
                    <PromptInputActionMenuContent>
                      <PromptInputActionAddAttachments label="Files" />
                      <PromptInputActionAddCamera />
                    </PromptInputActionMenuContent>
    ```
  - AFTER:
    ```html
                    <PromptInputActionMenuContent>
                      <PromptInputActionAddAttachments label="Files" />
                      <PromptInputActionAddCamera />
                      <PromptInputActionAddRecordVideo />
                    </PromptInputActionMenuContent>
    ```
- [ ] **SessionLauncherView.vue — add the import** directly after the existing camera import (line 38).
  - BEFORE:
    ```ts
  import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
    ```
  - AFTER:
    ```ts
  import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
  import PromptInputActionAddRecordVideo from "@/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue"
    ```
- [ ] **SessionLauncherView.vue — add the menu item** (lines 363-366; `<PromptInputActionMenuContent>` at 16 spaces of indent, children at 18). Preserve that exact indentation.
  - BEFORE:
    ```html
                  <PromptInputActionMenuContent>
                    <PromptInputActionAddAttachments label="Files" />
                    <PromptInputActionAddCamera />
                  </PromptInputActionMenuContent>
    ```
  - AFTER:
    ```html
                  <PromptInputActionMenuContent>
                    <PromptInputActionAddAttachments label="Files" />
                    <PromptInputActionAddCamera />
                    <PromptInputActionAddRecordVideo />
                  </PromptInputActionMenuContent>
    ```
- [ ] Build-verify: `cd src/web-app && bun run build` — expect success (proves the new component + `Video` import from `lucide-vue-next` resolve and typecheck). If the build reports `Video` is not an exported member, fall back to `Film` from `lucide-vue-next` (same import site) — but `Video` is the expected, standard lucide export.
- [ ] Manual-verify: open the attach menu in both the chat composer and the launcher composer → a "Record video" item appears below "Camera". On mobile it opens the camera in video-capture mode; on desktop it opens a file picker filtered to `video/*`. Selecting/recording a clip stages it in the composer.
- [ ] Commit:
  ```
  feat(web): add a "Record video" attach action

  New PromptInputActionAddRecordVideo mirrors the camera action with
  accept="video/*" + capture="environment"; wired into the ChatView and
  SessionLauncherView attach menus. No kind hint — the server infers "video".

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 4 — Raise the size cap to 500 MB + a visible over-limit message

Bump `:max-file-size` from `25 * 1024 * 1024` to `500 * 1024 * 1024` in both views. The client-side over-limit rejection already fires (`context.ts:79-84` → `onError({ code: 'max_file_size' })`), but neither view listens to `@error` today, so it's silently dropped. Add an `@error` handler that toasts. Both views already import `toast` from `vue-sonner` (ChatView line 23, SessionLauncherView line 5).

> The **server-side** 413 (mid-upload abort for lying/chunked clients) already surfaces via the existing `toast.error("Upload failed", …)` in `useComposerSubmit.ts:98-103` — no change needed there. This task only wires the **pre-upload** client-side rejection.

**Files:**
- `src/web-app/src/views/ChatView.vue` (modify)
- `src/web-app/src/views/SessionLauncherView.vue` (modify)

**Steps:**

- [ ] **ChatView.vue — bump the cap and add `@error`** on the `<PromptInput>` (lines 634-640).
  - BEFORE:
    ```html
            <PromptInput
              class="relative"
              :max-files="10"
              :max-file-size="25 * 1024 * 1024"
              :global-drop="true"
              @submit="onPromptSubmit"
            >
    ```
  - AFTER:
    ```html
            <PromptInput
              class="relative"
              :max-files="10"
              :max-file-size="500 * 1024 * 1024"
              :global-drop="true"
              @submit="onPromptSubmit"
              @error="onPromptError"
            >
    ```
- [ ] **ChatView.vue — add the handler** immediately after `onPromptSubmit` (lines 393-395).
  - BEFORE:
    ```ts
  async function onPromptSubmit(payload: PromptInputMessage) {
    await submitComposer(payload)
  }
    ```
  - AFTER:
    ```ts
  async function onPromptSubmit(payload: PromptInputMessage) {
    await submitComposer(payload)
  }

  function onPromptError(err: { code: string; message: string }) {
    if (err.code === "max_file_size") {
      toast.error("File too large", { description: "Attachments must be 500 MB or smaller." })
      return
    }
    if (err.code === "max_files") {
      toast.error("Too many files", { description: err.message })
      return
    }
    if (err.code === "accept") {
      toast.error("Unsupported file", { description: err.message })
      return
    }
    toast.error(err.message)
  }
    ```
- [ ] **SessionLauncherView.vue — bump the cap and add `@error`** on the `<PromptInput>` (lines 331-339).
  - BEFORE:
    ```html
          <PromptInput
            class="relative"
            group-class="rounded-2xl border-border/70 bg-card dark:bg-card shadow-lg shadow-black/[0.04] dark:shadow-black/30"
            :max-files="10"
            :max-file-size="25 * 1024 * 1024"
            :global-drop="isDesktop"
            :initial-input="launcherDraft.state.text"
            @submit="onPromptSubmit"
          >
    ```
  - AFTER:
    ```html
          <PromptInput
            class="relative"
            group-class="rounded-2xl border-border/70 bg-card dark:bg-card shadow-lg shadow-black/[0.04] dark:shadow-black/30"
            :max-files="10"
            :max-file-size="500 * 1024 * 1024"
            :global-drop="isDesktop"
            :initial-input="launcherDraft.state.text"
            @submit="onPromptSubmit"
            @error="onPromptError"
          >
    ```
- [ ] **SessionLauncherView.vue — add the handler** immediately after `onRecordingDone` (lines 229-231).
  - BEFORE:
    ```ts
  function onRecordingDone() {
    isRecording.value = false
  }
    ```
  - AFTER:
    ```ts
  function onRecordingDone() {
    isRecording.value = false
  }

  function onPromptError(err: { code: string; message: string }) {
    if (err.code === "max_file_size") {
      toast.error("File too large", { description: "Attachments must be 500 MB or smaller." })
      return
    }
    if (err.code === "max_files") {
      toast.error("Too many files", { description: err.message })
      return
    }
    if (err.code === "accept") {
      toast.error("Unsupported file", { description: err.message })
      return
    }
    toast.error(err.message)
  }
    ```
- [ ] Build-verify: `cd src/web-app && bun run build` — expect success.
- [ ] Manual-verify: attach a file > 500 MB in each composer → a red "File too large" toast appears and nothing is staged; a ≤ 500 MB video stages normally. (A quick way to exceed the cap without a real huge file is temporarily attaching any large media, or trust the unit-tested `context.ts` size gate.)
- [ ] Commit:
  ```
  feat(web): raise upload cap to 500 MB with an over-limit toast

  Bump :max-file-size to 500 MB in both composers and surface the existing
  client-side over-limit rejection via a new @error handler.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 5 — Video thumbnail in the composer chip

`PromptInputAttachments.vue` only special-cases `isImage`/`isAudio`; a staged video falls through to a generic `FileIcon`. Add an `isVideo` helper and a `<video>` first-frame thumbnail (the staged file has a blob URL, so `preload="metadata"` shows the first frame — a real preview, not a file icon).

**Files:**
- `src/web-app/src/components/ai-elements/prompt-input/PromptInputAttachments.vue` (modify)

**Steps:**

- [ ] Add the `isVideo` helper after `isAudio` (lines 15-17).
  - BEFORE:
    ```ts
  function isAudio(file: { mediaType?: string }): boolean {
    return !!file.mediaType?.startsWith("audio/")
  }
    ```
  - AFTER:
    ```ts
  function isAudio(file: { mediaType?: string }): boolean {
    return !!file.mediaType?.startsWith("audio/")
  }

  function isVideo(file: { mediaType?: string }): boolean {
    return !!file.mediaType?.startsWith("video/")
  }
    ```
- [ ] Add a video branch to the thumbnail box (lines 40-48).
  - BEFORE:
    ```html
          <div class="size-10 shrink-0 bg-muted flex items-center justify-center overflow-hidden">
            <img
              v-if="isImage(f) && f.url"
              :src="f.url"
              :alt="f.filename ?? 'attachment'"
              class="size-10 object-cover"
            />
            <FileIcon v-else class="size-5 text-muted-foreground" />
          </div>
    ```
  - AFTER:
    ```html
          <div class="size-10 shrink-0 bg-muted flex items-center justify-center overflow-hidden">
            <img
              v-if="isImage(f) && f.url"
              :src="f.url"
              :alt="f.filename ?? 'attachment'"
              class="size-10 object-cover"
            />
            <video
              v-else-if="isVideo(f) && f.url"
              :src="f.url"
              class="size-10 object-cover bg-black"
              muted
              playsinline
              preload="metadata"
            />
            <FileIcon v-else class="size-5 text-muted-foreground" />
          </div>
    ```
- [ ] Build-verify: `cd src/web-app && bun run build` — expect success.
- [ ] Manual-verify: pick or record a video → the composer chip shows a small video thumbnail (first frame / black box) instead of the generic file icon, with the same "Ready/Uploading/Uploaded" status labels as other attachments.
- [ ] Commit:
  ```
  feat(web): show a video thumbnail in the composer chip

  Add an isVideo branch to PromptInputAttachments so a staged video renders
  a <video> first-frame preview instead of the generic file icon.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 6 — Verify Files/drag-drop accept video (no code change) + end-to-end pass

The spec calls for confirming the library "Files" action and drag-drop already accept video. They do: neither view passes an `accept` prop to `<PromptInput>`, so the hidden `<input :accept="accept">` (PromptInput.vue:129) is unfiltered, and `matchesAccept` (context.ts:41-66) returns `true` when `accept` is empty. This task is verification only.

**Files:** none (verification).

**Steps:**

- [ ] Confirm by inspection: `ChatView.vue` and `SessionLauncherView.vue` do **not** pass `accept=` to `<PromptInput>` (grep: `grep -n "accept" src/web-app/src/views/ChatView.vue src/web-app/src/views/SessionLauncherView.vue` returns nothing for the `<PromptInput>`). No change.
- [ ] Manual-verify (Files): open the attach menu → "Files" → the OS file picker lets you select a `.mp4`/`.mov`, and it stages in the composer.
- [ ] Manual-verify (drag-drop): drag a video file onto the composer (desktop) → it stages.
- [ ] **End-to-end pass (requires the broker plan deployed):** in both composers — (a) pick a library video → it uploads via the streaming path and, after the server round-trips the message, plays inline via `AttachmentVideo`; (b) record a video from the camera action → uploads + plays; (c) attempt a > 500 MB file → "File too large" toast, nothing staged; (d) confirm an **image** still uploads and renders (no regression from the multipart→streaming switch). Verify in DevTools Network that `POST /upload` uses `Content-Type: application/octet-stream` with the `X-Mux-*` request headers and no multipart body.
- [ ] Full suite: `cd src/web-app && bun test` (the new uploader test passes, nothing else breaks) and `cd src/web-app && bun run build` (typecheck + build succeed).
- [ ] No commit (verification only). If the grep/inspection reveals an `accept` filter had been added, remove it and note the deviation.

---

## Notes for the executing agent

- **Kind hint stays `undefined` for video.** The record-video action mirrors the camera action and does **not** set `_cmuxKind`, so `useUploader` omits `X-Mux-Kind` and the broker infers `"video"` from `X-Mux-Mime` (`video/*`). Do **not** hard-code a `"video"` kind hint in the composer — the server is the source of truth (spec Decision 7 / shared contract).
- **Optimistic echo is `document`.** `useComposerSubmit.ts:72-97` sets the local optimistic attachment kind to `kindHint ?? "document"`, so a just-sent video briefly shows as a file in the local echo until the server echoes the real message with `kind: "video"` (then it renders inline via Task 2). This matches today's behavior for every non-voice attachment and is **out of scope** — do not modify `useComposerSubmit.ts` (the WS `send` frame and file-id handling are unchanged).
- **Do not add the new action to the barrel** (`prompt-input/index.ts`). The camera action isn't exported there either — both views use a direct default import; mirror that.
