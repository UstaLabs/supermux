# Android App — Video Upload Phase 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the Android app pick or record a video (not just photos) and play it back inline, matching the approved cross-client video-upload spec.
**Architecture:** The composer's attach flow already routes every source (Photos / Files / Camera / paste / drag) through one generic `stageFromUri` → `onUpload(bytes, name, mime, null)` sink, and the timeline already branches on `att.kind == "video"`. This plan widens the four image-only gates (photo picker, camera, clipboard, drag) to include video, adds a distinct "Record video" capture action, and replaces the video attachment's system-viewer chip with an inline media3/ExoPlayer (tap-to-play, chip fallback on error). No upload-transport or kind-string changes happen here — Android keeps passing `kind=null` and the broker infers `"video"`.
**Tech Stack:** Jetpack Compose, Kotlin, AndroidX Activity Result APIs, media3/ExoPlayer
**Depends on:** the broker plan + the shared-KMP upload plan.

**SHARED CONTRACT (must match the other plans exactly):**
- Kind string is **`"video"`** — but Android never sends it. `stageFromUri` uploads with `kind=null`; the broker's `kindFromMime` (broker plan) maps `video/* → "video"` and echoes it back. Timeline then renders via `att.kind == "video"`.
- Upload goes through the shared KMP `BrokerApi.upload(session, bytes, filename, mime, kind?)` — **signature unchanged** (confirmed at `apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt:1307`). The multipart→streaming switch is the SEPARATE shared-KMP plan; Android's `onUpload` call site is unaffected either way.
- Cap **500 MB** is enforced server-side (broker plan) + surfaced by the KMP upload path. **Android adds no client-side size cap** — see "Ambiguity resolved" below.

---

## File structure

| File | Change | Task |
| --- | --- | --- |
| `apps/android/src/main/kotlin/dev/supermux/android/chat/MediaMime.kt` | **NEW** — pure `isAttachableMediaMime` predicate | 1 |
| `apps/android/src/test/kotlin/dev/supermux/android/chat/MediaMimeTest.kt` | **NEW** — JVM unit test for the predicate | 1 |
| `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt` | wire predicate into 3 clipboard/drag sites (Task 1); photo picker `ImageOnly → ImageAndVideo` (Task 2); `CaptureVideo` launcher + "Record video" menu item + `createVideoUri` (Task 3) | 1, 2, 3 |
| `apps/gradle/libs.versions.toml` | add `media3` version + `media3-exoplayer`/`media3-ui` libraries | 4 |
| `apps/android/build.gradle.kts` | add the two media3 `implementation` deps | 4 |
| `apps/android/src/main/kotlin/dev/supermux/android/chat/Timeline.kt` | make the `isVideo` kind check explicit for `video_note`; replace the video chip with an inline `InlineVideo` ExoPlayer composable (chip fallback) | 4 |

**Module / commands** (module is `:android`; gradle wrapper lives in `apps/`):
- Compile: `cd apps && ./gradlew :android:compileDebugKotlin`
- Unit tests: `cd apps && ./gradlew :android:testDebugUnitTest`

**The bar per piece:** the widened MIME predicate is pure JVM logic → a **real unit test** (Task 1). Every Compose UI change (picker contract swap, camera launcher + menu item, inline ExoPlayer) is verified by **compile + a concise manual check** — that is the bar for the UI pieces; there is no instrumented-UI harness in this module (`apps/android/src/test` is JVM-only: `LauncherStateTest`, `FinishChoicesTest`, `PushRouterTest`).

**Ambiguity resolved:** The spec's manual-test line wants a "clean too-large message" per client, but the Android scope items (spec lines 120–127) list no client-side cap or error string, and the shared `onUpload: suspend (…) -> String?` sink **returns `null` on any failure with no error detail** — so a video that exceeds the server's 500 MB cap already fails cleanly today: `stageFromUri` drops the pending chip and the app does not crash. Distinguishing "too large" (413) from a generic failure would require changing the `onUpload` contract and the `AppViewModel` wiring, which is outside this plan's anchors. **Decision: no Android-side size cap and no new error toast in this plan** — the 500 MB enforcement + user-facing message belong to the broker + shared-KMP plans and a follow-up. This plan keeps the existing silent-drop-on-failure behavior. This is called out here so a reviewer knows it was deliberate, not missed.

---

### Task 1 — Widen the clipboard/drag MIME gate to accept video (pure predicate + unit test + 3 call sites)

**Files:**
- `apps/android/src/main/kotlin/dev/supermux/android/chat/MediaMime.kt` (NEW)
- `apps/android/src/test/kotlin/dev/supermux/android/chat/MediaMimeTest.kt` (NEW)
- `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`

This is the only pure-logic change, so do it test-first. The three clipboard/drag sites (`ChatScreen.kt:255`, `:263`, `:1029`) currently hard-code `startsWith("image/")`; extract that decision into one testable top-level function and widen it to also accept `video/`.

- [ ] **Write the failing test first.** Create `apps/android/src/test/kotlin/dev/supermux/android/chat/MediaMimeTest.kt` (mirrors the existing `FinishChoicesTest` `kotlin.test` style exactly). COMPLETE file:

  ```kotlin
  package dev.supermux.android.chat

  import kotlin.test.Test
  import kotlin.test.assertTrue
  import kotlin.test.assertFalse

  class MediaMimeTest {
      @Test fun accepts_image_mimes() {
          assertTrue(isAttachableMediaMime("image/png"))
          assertTrue(isAttachableMediaMime("image/jpeg"))
      }

      @Test fun accepts_video_mimes() {
          assertTrue(isAttachableMediaMime("video/mp4"))
          assertTrue(isAttachableMediaMime("video/quicktime"))
          assertTrue(isAttachableMediaMime("video/x-matroska"))
      }

      @Test fun rejects_other_and_null() {
          assertFalse(isAttachableMediaMime("application/pdf"))
          assertFalse(isAttachableMediaMime("audio/mpeg"))
          assertFalse(isAttachableMediaMime("text/plain"))
          assertFalse(isAttachableMediaMime(null))
          assertFalse(isAttachableMediaMime(""))
      }
  }
  ```

- [ ] **Run the test — expect a COMPILE failure** (the function does not exist yet), confirming the test actually exercises new code:

  ```
  cd apps && ./gradlew :android:testDebugUnitTest
  ```
  Expected: build fails with `unresolved reference: isAttachableMediaMime`.

- [ ] **Create the predicate.** Add `apps/android/src/main/kotlin/dev/supermux/android/chat/MediaMime.kt`. COMPLETE file:

  ```kotlin
  package dev.supermux.android.chat

  /**
   * Whether a clipboard/drag/paste MIME type is an attachable inline-media type (image OR video).
   * Video-upload Phase 1 widened this from image-only; the actual upload stays generic via
   * stageFromUri, which reads each URI's real MIME and lets the broker infer the "video" kind.
   * Null/blank → false so text and arbitrary binary content falls through to normal handling.
   */
  fun isAttachableMediaMime(mime: String?): Boolean =
      mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))
  ```

- [ ] **Run the test — expect GREEN:**

  ```
  cd apps && ./gradlew :android:testDebugUnitTest
  ```
  Expected: `BUILD SUCCESSFUL`, `MediaMimeTest` passing (all 3 tests), and the pre-existing `FinishChoicesTest` / `LauncherStateTest` / `PushRouterTest` still passing.

- [ ] **Wire site A — `clipboardHasImage()` (`ChatScreen.kt:255`).** No import needed (same `dev.supermux.android.chat` package). BEFORE:

  ```kotlin
          return (0 until desc.mimeTypeCount).any { desc.getMimeType(it).startsWith("image/") }
  ```
  AFTER:
  ```kotlin
          return (0 until desc.mimeTypeCount).any { isAttachableMediaMime(desc.getMimeType(it)) }
  ```

- [ ] **Wire site B — `clipboardImageUris()` (`ChatScreen.kt:263`).** BEFORE:

  ```kotlin
              .filter { context.contentResolver.getType(it)?.startsWith("image/") == true }
  ```
  AFTER:
  ```kotlin
              .filter { isAttachableMediaMime(context.contentResolver.getType(it)) }
  ```

  > Note: `clipboardHasImage` / `clipboardImageUris` keep their names (they are private locals used only by the "Paste" menu item at `:1101`/`:1110`); leaving the names avoids churn. They now also match video, which is the intended behavior (paste a copied video).

- [ ] **Wire site C — composer `contentReceiver` paste/drag (`ChatScreen.kt:1027-1034`).** BEFORE:

  ```kotlin
                                      val uri = item.uri
                                      if (uri != null &&
                                          context.contentResolver.getType(uri)?.startsWith("image/") == true) {
                                          scope.launch { stageFromUri(uri) }
                                          true
                                      } else {
                                          false
                                      }
  ```
  AFTER:
  ```kotlin
                                      val uri = item.uri
                                      if (uri != null &&
                                          isAttachableMediaMime(context.contentResolver.getType(uri))) {
                                          scope.launch { stageFromUri(uri) }
                                          true
                                      } else {
                                          false
                                      }
  ```

- [ ] **Compile-verify:**

  ```
  cd apps && ./gradlew :android:compileDebugKotlin
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Manual-verify (compile + manual is the bar):** copy a video from a gallery/file app, open a session, tap **＋** → the "Paste" item appears (previously image-only) → tapping it stages the video chip and it uploads. Also drag a video onto the composer text box → it stages. Confirm copying/dragging an image still works (no regression), and copying plain text still shows no "Paste" item.

- [ ] **Commit:**

  ```
  feat(android): accept video in clipboard/drag attach gate

  Extract the image-only paste/drag MIME check into a pure, unit-tested
  isAttachableMediaMime() and widen it to accept video/* alongside image/*.
  Part of video-upload Phase 1.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 2 — Photo picker: `ImageOnly → ImageAndVideo`

**Files:**
- `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`

The "Photos" menu item launches the system visual-media picker filtered to images only. Widen it to images + videos. `ImageAndVideo` is a sibling object of `ImageOnly` under the already-imported `ActivityResultContracts.PickVisualMedia`, and `PickVisualMediaRequest` is already imported — no new imports.

- [ ] **Swap the request type (`ChatScreen.kt:1120-1125`).** BEFORE:

  ```kotlin
                                  onClick = {
                                      attachMenu = false
                                      photoPicker.launch(
                                          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                      )
                                  },
  ```
  AFTER:
  ```kotlin
                                  onClick = {
                                      attachMenu = false
                                      photoPicker.launch(
                                          PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                      )
                                  },
  ```

  > The `photoPicker` callback and `stageFromUri` are unchanged: a picked video URI resolves its real `video/*` MIME via `contentResolver.getType`, and `onUpload(bytes, name, mime, null)` lets the broker infer `"video"`.

- [ ] **Compile-verify:**

  ```
  cd apps && ./gradlew :android:compileDebugKotlin
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Manual-verify (compile + manual is the bar):** open a session, tap **＋** → **Photos** → the system picker now shows videos as well as images; pick a video → a chip stages and uploads; send it. Pick an image → still uploads (no regression).

- [ ] **Commit:**

  ```
  feat(android): let the Photos picker include videos

  Switch the visual-media picker from PickVisualMedia.ImageOnly to
  ImageAndVideo so a library video can be attached. Part of video-upload
  Phase 1.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 3 — Camera: add a distinct "Record video" capture action

**Files:**
- `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt`

Add an `ActivityResultContracts.CaptureVideo()` launcher beside `TakePicture()`, a `createVideoUri` helper (mirrors `createImageUri` but `.mp4`), and a "Record video" dropdown item. `CaptureVideo`'s contract takes a `Uri` input and returns `Boolean` (success), exactly like `TakePicture` — so the wiring mirrors the photo path. No new imports (`ActivityResultContracts`, `FileProvider`, `File`, `Uri`, `rememberLauncherForActivityResult` all already imported).

- [ ] **Add the video-capture launcher immediately after the `takePicture` launcher (`ChatScreen.kt`, insert after line 287).** BEFORE (context — the existing photo launcher):

  ```kotlin
      // Camera: delegated capture to the system camera app, writing into our FileProvider URI.
      var cameraUri by remember { mutableStateOf<Uri?>(null) }
      val takePicture = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.TakePicture(),
      ) { ok: Boolean ->
          val uri = cameraUri
          if (ok && uri != null) scope.launch { stageFromUri(uri) }
      }
  ```
  AFTER (add the new launcher below it):
  ```kotlin
      // Camera: delegated capture to the system camera app, writing into our FileProvider URI.
      var cameraUri by remember { mutableStateOf<Uri?>(null) }
      val takePicture = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.TakePicture(),
      ) { ok: Boolean ->
          val uri = cameraUri
          if (ok && uri != null) scope.launch { stageFromUri(uri) }
      }

      // Camera video: system camera records into our FileProvider URI; CaptureVideo() returns
      // true on a successful capture, mirroring TakePicture() above. A separate URI state so a
      // photo capture in flight can't clobber a video capture's output target.
      var videoCaptureUri by remember { mutableStateOf<Uri?>(null) }
      val captureVideo = rememberLauncherForActivityResult(
          contract = ActivityResultContracts.CaptureVideo(),
      ) { ok: Boolean ->
          val uri = videoCaptureUri
          if (ok && uri != null) scope.launch { stageFromUri(uri) }
      }
  ```

- [ ] **Add the "Record video" dropdown item right after the "Camera" item (`ChatScreen.kt`, insert between the Camera `DropdownMenuItem`'s closing `)` at line 1150 and the `DropdownMenu`'s closing `}` at line 1151).** BEFORE:

  ```kotlin
                              DropdownMenuItem(
                                  text = { Text("Camera") },
                                  leadingIcon = {
                                      Icon(painterResource(R.drawable.ic_camera), null, modifier = Modifier.size(18.dp))
                                  },
                                  modifier = Modifier.testTag("attach_menu_camera"),
                                  onClick = {
                                      attachMenu = false
                                      val uri = createImageUri(context)
                                      cameraUri = uri
                                      takePicture.launch(uri)
                                  },
                              )
                          }
  ```
  AFTER:
  ```kotlin
                              DropdownMenuItem(
                                  text = { Text("Camera") },
                                  leadingIcon = {
                                      Icon(painterResource(R.drawable.ic_camera), null, modifier = Modifier.size(18.dp))
                                  },
                                  modifier = Modifier.testTag("attach_menu_camera"),
                                  onClick = {
                                      attachMenu = false
                                      val uri = createImageUri(context)
                                      cameraUri = uri
                                      takePicture.launch(uri)
                                  },
                              )
                              DropdownMenuItem(
                                  text = { Text("Record video") },
                                  leadingIcon = {
                                      Icon(painterResource(R.drawable.ic_play), null, modifier = Modifier.size(18.dp))
                                  },
                                  modifier = Modifier.testTag("attach_menu_record_video"),
                                  onClick = {
                                      attachMenu = false
                                      val uri = createVideoUri(context)
                                      videoCaptureUri = uri
                                      captureVideo.launch(uri)
                                  },
                              )
                          }
  ```

  > Icon note: this module ships only `ic_camera.xml` and `ic_play.xml` for the camera/video glyphs (no `ic_video`). `ic_play` is already this app's video glyph (the pre-Phase-1 video chip used it at `Timeline.kt:736`), so reusing it keeps the "Record video" item visually distinct from "Camera" without adding a new asset.

- [ ] **Add the `createVideoUri` helper immediately after `createImageUri` (`ChatScreen.kt`, insert after line 1466 — end of file).** BEFORE (the existing helper):

  ```kotlin
  private fun createImageUri(context: android.content.Context): Uri {
      val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
      val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
      return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }
  ```
  AFTER (add the new helper below it):
  ```kotlin
  private fun createImageUri(context: android.content.Context): Uri {
      val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
      val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
      return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }

  /**
   * Create a FileProvider URI for a fresh camera video capture in cacheDir/attachments (the same
   * path createImageUri + openAttachment already use, so no file_paths.xml change is needed). The
   * system camera app writes the MP4 here; stageFromUri then reads it back — contentResolver
   * .getType() maps the .mp4 extension to video/mp4 — and uploads it with kind=null so the broker
   * infers "video".
   */
  private fun createVideoUri(context: android.content.Context): Uri {
      val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
      val file = File(dir, "camera_${System.currentTimeMillis()}.mp4")
      return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  }
  ```

- [ ] **Compile-verify:**

  ```
  cd apps && ./gradlew :android:compileDebugKotlin
  ```
  Expected: `BUILD SUCCESSFUL`.

- [ ] **Manual-verify (compile + manual is the bar):** open a session, tap **＋** → a new **Record video** item sits under **Camera** → tapping it opens the system camera in video mode → record a short clip → on accept, a chip stages and uploads. Confirm the existing **Camera** (photo) item still works and stages a JPEG.

- [ ] **Commit:**

  ```
  feat(android): add a Record video camera capture action

  Add a CaptureVideo() launcher and a distinct "Record video" attach-menu
  item that records into a FileProvider .mp4 URI and stages it through the
  existing generic upload path. Part of video-upload Phase 1.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 4 — Inline video playback (media3/ExoPlayer) + explicit `video_note` kind

**Files:**
- `apps/gradle/libs.versions.toml`
- `apps/android/build.gradle.kts`
- `apps/android/src/main/kotlin/dev/supermux/android/chat/Timeline.kt`

`Timeline.kt:691` already computes `isVideo = att.kind == "video" || mime.startsWith("video/")` (the `"video"` check becomes correct once the broker emits the new kind — no change needed there). This task (a) makes `video_note` explicit (belt-and-suspenders; a round clip carries a `video/*` mime so the fallback already catches it, but the spec's decision #1 says renderers accept both kinds), and (b) replaces the video's system-viewer `AttachmentChip` with an inline media3 ExoPlayer that falls back to that same chip on load/decode error. media3 is not yet a dependency, so add it first.

- [ ] **Add the media3 version to the catalog (`apps/gradle/libs.versions.toml`, `[versions]`).** BEFORE:

  ```toml
  firebaseBom = "33.7.0"
  googleServices = "4.4.2"
  ```
  AFTER:
  ```toml
  firebaseBom = "33.7.0"
  googleServices = "4.4.2"
  media3 = "1.7.1"
  ```

  > `1.7.1` is a published stable androidx.media3 release compatible with this module's `compileSdk = 36`. If the Gradle sync/compile below fails to resolve it, bump to the current stable listed on maven.google.com (the compile step is the gate).

- [ ] **Add the two media3 libraries to the catalog (`apps/gradle/libs.versions.toml`, `[libraries]`).** BEFORE:

  ```toml
  firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebaseBom" }
  firebase-messaging = { module = "com.google.firebase:firebase-messaging" }
  ```
  AFTER:
  ```toml
  firebase-bom = { module = "com.google.firebase:firebase-bom", version.ref = "firebaseBom" }
  firebase-messaging = { module = "com.google.firebase:firebase-messaging" }
  media3-exoplayer = { module = "androidx.media3:media3-exoplayer", version.ref = "media3" }
  media3-ui = { module = "androidx.media3:media3-ui", version.ref = "media3" }
  ```

- [ ] **Declare the deps (`apps/android/build.gradle.kts`, `dependencies { }`).** BEFORE:

  ```kotlin
      implementation(libs.termlib)
      implementation(libs.zxing.android.embedded)
      debugImplementation(libs.compose.ui.tooling)
  ```
  AFTER:
  ```kotlin
      implementation(libs.termlib)
      implementation(libs.zxing.android.embedded)
      implementation(libs.media3.exoplayer)
      implementation(libs.media3.ui)
      debugImplementation(libs.compose.ui.tooling)
  ```

- [ ] **Add imports to `Timeline.kt` — android.* block (insert after line 66 `import android.content.Intent`).** BEFORE:

  ```kotlin
  import android.content.Context
  import android.content.Intent
  import android.widget.Toast
  ```
  AFTER:
  ```kotlin
  import android.content.Context
  import android.content.Intent
  import android.net.Uri
  import android.view.ViewGroup
  import android.widget.Toast
  ```

- [ ] **Add imports to `Timeline.kt` — Compose runtime (insert at line 55, next to the existing `LaunchedEffect` import).** BEFORE:

  ```kotlin
  import androidx.compose.runtime.LaunchedEffect
  ```
  AFTER:
  ```kotlin
  import androidx.compose.runtime.DisposableEffect
  import androidx.compose.runtime.LaunchedEffect
  ```

- [ ] **Add imports to `Timeline.kt` — viewinterop + media3 (insert after line 49 `import androidx.compose.ui.res.painterResource`).** BEFORE:

  ```kotlin
  import androidx.compose.ui.res.painterResource
  ```
  AFTER:
  ```kotlin
  import androidx.compose.ui.res.painterResource
  import androidx.compose.ui.viewinterop.AndroidView
  import androidx.media3.common.MediaItem
  import androidx.media3.common.PlaybackException
  import androidx.media3.common.Player
  import androidx.media3.common.util.UnstableApi
  import androidx.media3.exoplayer.ExoPlayer
  import androidx.media3.ui.PlayerView
  ```

- [ ] **Make the `video_note` kind explicit (`Timeline.kt:691`).** BEFORE:

  ```kotlin
      val isVideo = att.kind == "video" || mime.startsWith("video/")
  ```
  AFTER:
  ```kotlin
      val isVideo = att.kind == "video" || att.kind == "video_note" || mime.startsWith("video/")
  ```

- [ ] **Route video to the inline player (`Timeline.kt:736`).** BEFORE:

  ```kotlin
          isVideo -> AttachmentChip(R.drawable.ic_play, att.name ?: "video", att, loadBytes)
  ```
  AFTER:
  ```kotlin
          isVideo -> InlineVideo(att, loadBytes)
  ```

- [ ] **Add the `InlineVideo` composable (insert after `AttachmentItem` closes at line 740, before the `ImageLightbox` doc comment at line 742).** COMPLETE composable:

  ```kotlin
  /**
   * Inline video playback (design 2026-07-02 Phase 1). Renders `video`/`video_note` attachments
   * with a tap-to-play poster so the transcript never eagerly downloads every clip (a video can be
   * up to 500 MB). On tap we fetch the bytes via loadBytes, cache them to a file (same
   * cacheDir/attachments dir openAttachment uses), and mount a media3 ExoPlayer inside an
   * AndroidView (PlayerView + default controls, autoplay once the user opted in). A missing
   * download or a decode error falls back to the pre-Phase-1 system-viewer AttachmentChip.
   */
  @androidx.annotation.OptIn(UnstableApi::class)
  @Composable
  private fun InlineVideo(att: Attachment, loadBytes: suspend (String) -> ByteArray?) {
      val cs = MaterialTheme.colorScheme
      val context = LocalContext.current
      var playing by remember(att.file_id) { mutableStateOf(false) }
      var file by remember(att.file_id) { mutableStateOf<File?>(null) }
      var failed by remember(att.file_id) { mutableStateOf(false) }

      // Fetch + cache the bytes only once the user opts into playback.
      LaunchedEffect(playing) {
          if (!playing || file != null || failed) return@LaunchedEffect
          val bytes = loadBytes(att.file_id)
          if (bytes == null) {
              failed = true
              return@LaunchedEffect
          }
          val cached = withContext(Dispatchers.IO) {
              runCatching {
                  val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
                  // Name by the unique file_id so two clips never collide in the cache dir; keep the
                  // original extension as a container hint for ExoPlayer, defaulting to mp4.
                  val safeId = att.file_id.substringAfterLast('/')
                  val ext = (att.name ?: "").substringAfterLast('.', "").ifBlank { "mp4" }
                  File(dir, "video_$safeId.$ext").apply { writeBytes(bytes) }
              }.getOrNull()
          }
          if (cached != null) file = cached else failed = true
      }

      val f = file
      when {
          failed -> AttachmentChip(R.drawable.ic_play, att.name ?: "video", att, loadBytes)
          !playing -> Box(
              modifier = Modifier
                  .fillMaxWidth(0.7f)
                  .height(200.dp)
                  .clip(RoundedCornerShape(Radii.md))
                  .background(cs.surfaceContainer)
                  .clickable { playing = true }
                  .testTag("attachment_video_poster"),
              contentAlignment = Alignment.Center,
          ) {
              Icon(
                  painter = painterResource(R.drawable.ic_play),
                  contentDescription = att.name ?: "Play video",
                  tint = cs.onSurface,
                  modifier = Modifier.size(40.dp),
              )
          }
          f == null -> Box(
              modifier = Modifier
                  .fillMaxWidth(0.7f)
                  .height(200.dp)
                  .clip(RoundedCornerShape(Radii.md))
                  .background(cs.surfaceContainer),
              contentAlignment = Alignment.Center,
          ) {
              CircularProgressIndicator(Modifier.size(20.dp), color = cs.onSurfaceVariant, strokeWidth = 1.5.dp)
          }
          else -> {
              val exo = remember(f) {
                  ExoPlayer.Builder(context).build().apply {
                      setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
                      prepare()
                      playWhenReady = true
                      addListener(object : Player.Listener {
                          override fun onPlayerError(error: PlaybackException) {
                              failed = true
                          }
                      })
                  }
              }
              DisposableEffect(exo) {
                  onDispose { exo.release() }
              }
              AndroidView(
                  factory = { ctx ->
                      PlayerView(ctx).apply {
                          player = exo
                          useController = true
                          layoutParams = ViewGroup.LayoutParams(
                              ViewGroup.LayoutParams.MATCH_PARENT,
                              ViewGroup.LayoutParams.MATCH_PARENT,
                          )
                      }
                  },
                  modifier = Modifier
                      .fillMaxWidth()
                      .height(220.dp)
                      .clip(RoundedCornerShape(Radii.md))
                      .testTag("attachment_video_player"),
              )
          }
      }
  }
  ```

  > Design notes: (1) `@androidx.annotation.OptIn(UnstableApi::class)` is required — `ExoPlayer`, `ExoPlayer.Builder`, and `PlayerView` are media3 `@UnstableApi`; `MediaItem`/`Player`/`PlaybackException` are stable. It is the AndroidX `androidx.annotation.OptIn`, not Kotlin's. (2) `onPlayerError` flips `failed=true`; the `when` re-evaluates to the `AttachmentChip` branch and `DisposableEffect.onDispose` releases the player — that is the "fall back to the system-viewer chip" path. (3) Tap-to-play (rather than autoloading every clip) both avoids pulling 500 MB per video into RAM on scroll and reads as native.

- [ ] **Compile-verify (this is where an unresolvable media3 version or a missing opt-in would surface):**

  ```
  cd apps && ./gradlew :android:compileDebugKotlin
  ```
  Expected: `BUILD SUCCESSFUL`. If it fails on `media3` resolution, bump the catalog version to the current stable and re-run. If it fails with a `RequiresOptIn`/`UnstableApi` error, confirm the `@androidx.annotation.OptIn(UnstableApi::class)` annotation and the `androidx.media3.common.util.UnstableApi` import are present on `InlineVideo`.

- [ ] **Manual-verify (compile + manual is the bar):** send/receive a message carrying a video attachment (e.g. record one via Task 3, or have the broker plan land so a Telegram/WhatsApp video arrives). In the timeline it renders as a rounded poster with a play glyph → tap → a brief spinner → the media3 player mounts and plays with scrubber controls. Scroll the video off-screen and back → no crash, player released/re-created cleanly. Confirm an image attachment still renders inline (no regression) and a non-media file still renders as its file chip. To exercise the fallback, point at a broken/undecodable clip → it collapses to the download chip.

- [ ] **Commit:**

  ```
  feat(android): inline media3 video playback in the timeline

  Add androidx.media3 exoplayer+ui deps and replace the video attachment's
  system-viewer chip with a tap-to-play inline ExoPlayer (PlayerView in an
  AndroidView), falling back to the chip on load/decode failure. Also make
  the isVideo kind check accept video_note explicitly. Part of video-upload
  Phase 1.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 5 — Verify `stageFromUri` needs no change for video (verification only)

**Files:**
- `apps/android/src/main/kotlin/dev/supermux/android/chat/ChatScreen.kt` (read-only confirmation — expected: **no edit**)

Spec item 5 flags `stageFromUri` (`ChatScreen.kt:228-247`) as already video-generic. Confirm, do not change.

- [ ] **Confirm the generic path.** Re-read `stageFromUri` and verify it (a) reads the real MIME via `resolver.getType(uri) ?: "application/octet-stream"`, (b) derives `name` from the URI, and (c) calls `onUpload(bytes, name, mime, null)` with `kind=null`. For a picked/recorded/pasted video this yields a `video/*` MIME and `kind=null` → the broker infers `"video"` (SHARED CONTRACT). Expected result: **no code change**; if any step differs from this, stop and reconcile with the shared-KMP/broker plans before proceeding.

- [ ] **No commit** (verification only — nothing changed).

---

## Done criteria

- [ ] `cd apps && ./gradlew :android:testDebugUnitTest` passes (incl. the new `MediaMimeTest`).
- [ ] `cd apps && ./gradlew :android:compileDebugKotlin` passes.
- [ ] Manual: pick a library video, record a video, paste/drag a video → each stages + uploads; a received video plays inline; images and non-media files still render correctly (no regression).
- [ ] No change to `stageFromUri`, `onUpload`, or the KMP `BrokerApi.upload` signature (those belong to the broker + shared-KMP plans).
