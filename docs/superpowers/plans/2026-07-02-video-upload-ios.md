# iOS App — Video Upload Phase 1 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let the iOS app pick or record a video (not just photos), stage it with the correct MIME/kind/filename, upload it via the unchanged shared `BrokerApi`, and play it back inline in the message list.
**Architecture:** SwiftUI composer (`ComposerModel`) stages `PendingAttachment`s from the Photos picker, the file importer, or the camera; on send/spawn each is uploaded through `BrokerSession.upload → BrokerApi.upload`. The picker filter widens to include videos, `loadPhotos` stops hardcoding `image/jpeg`, a "Record video" camera path is added, and the message row renders `video`/`video_note`/`video/*` attachments with an `AVKit.VideoPlayer` (QuickLook file row remains the fallback for everything else).
**Tech Stack:** SwiftUI, PhotosUI, AVKit, UIKit (UIImagePickerController)
**Depends on:** the broker plan + the shared-KMP upload plan.

---

## Shared contract (must match the broker + KMP plans exactly)

- **Kind string is `"video"`** (reserve `"video_note"` for Telegram round clips). iOS never sends `"video"` explicitly — it leaves `kind = nil` for video and lets the broker infer it from the MIME type (`video/* → "video"` server-side). Renderers accept **both** `video` and `video_note`.
- **`BrokerApi.upload(session, bytes, filename, mime, kind?)` is UNCHANGED.** The multipart→streaming switch happens *inside* KMP (a separate plan). iOS's only job is to feed the correct `mime` / `filename` / `kind`. `BrokerSession.upload` (`apps/iosApp/Supermux/Broker/BrokerSession.swift:192-194`) and its call sites stay as-is except for the correct `mime`/`kind` this plan already produces.
- **Cap: 500 MB.** Enforced server-side (413) and by the KMP streaming plan. This iOS plan does **not** add a client-side size guard or a "too large" toast — that belongs with the KMP upload plan that owns the upload error path (see *Known limitations*).

## Remote-Mac execution note (READ FIRST — no local Xcode)

This is a Linux host with **no Xcode**. Every build/test/manual step below runs on a **remote Mac with Xcode over SSH**, via the **`mux:ios-simulator-on-remote-mac`** skill. Reusable recipe (invoke the skill for the exact sync/boot mechanics — tar-over-ssh, `simctl bootstatus`, screenshots):

```bash
# 0. Sync this worktree to the Mac (tar over ssh — macOS rsync is openrsync; see the skill).
# 1. Generate the Xcode project (the .xcodeproj is gitignored; project.yml is the source of truth).
cd apps/iosApp && xcodegen generate
# 2. Build (the app target's pre-build phase runs ./gradlew :shared:embedAndSignAppleFrameworkForXcode,
#    so the Mac's KMP/Gradle toolchain builds Shared.framework — this is expected, per the skill).
xcodebuild -project Supermux.xcodeproj -scheme Supermux -configuration Debug \
  -destination 'generic/platform=iOS Simulator' -derivedDataPath build build
# 3. Unit tests (Task 2 only):
xcodebuild -project Supermux.xcodeproj -scheme Supermux \
  -destination "platform=iOS Simulator,id=$UDID" \
  test -only-testing:SupermuxTests/ComposerModelTests
# 4. Manual: xcrun simctl install/launch + `simctl io <UDID> screenshot` are your eyes (per the skill).
```

**The verification bar per change type:**
- **Mislabel fix (Task 2)** has a unit-test surface → write a **real `ComposerModelTests` XCTest** and run it via the recipe above (step 3).
- **Pure-SwiftUI view changes (Tasks 1, 4, 5)** have no unit-test surface → the bar is **`xcodebuild` build success (step 2) + a manual on-simulator check (step 4)**. Build-green alone is NOT sufficient for these; the manual sim check is required.

---

## File structure

```
apps/iosApp/
  Supermux/
    Chat/
      ChatPane.swift                       # Task 1 (picker), 3 (upload-kind comment), 4 (camera wiring)
      ChatMessages.swift                   # Task 4 (CameraPicker video mode), 5 (inline VideoPlayer)
      Composer/
        ComposerModel.swift                # Task 2 (loadPhotos + attachmentMeta), 4 (addCameraVideo)
        AttachMenu.swift                   # Task 4 (Record video menu item)
        AttachmentTray.swift               # Task 2 (staged-video chip icon)
    Sessions/
      NewSessionView.swift                 # Task 1 (picker), 3 (upload-kind comment), 4 (camera wiring)
  SupermuxTests/
      ComposerModelTests.swift             # Task 2 (attachmentMeta assertions)
```

No `Info.plist`/`project.yml` change: `NSCameraUsageDescription` and `NSMicrophoneUsageDescription` already exist (`apps/iosApp/project.yml:92-93`), which is all the movie-with-audio camera path needs.

---

### Task 1 — Widen the photo picker to include videos

**Files:** `apps/iosApp/Supermux/Chat/ChatPane.swift`, `apps/iosApp/Supermux/Sessions/NewSessionView.swift`

Pure-SwiftUI change (picker filter). Bar = build + manual.

- [ ] In `ChatPane.swift` (`.photosPicker` at ~line 214), change the `matching:` filter.
  Before:
  ```swift
  .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
  ```
  After:
  ```swift
  .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .any(of: [.images, .videos]))
  ```
- [ ] In `NewSessionView.swift` (`.photosPicker` at ~line 181), make the identical change.
  Before:
  ```swift
  .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .images)
  ```
  After:
  ```swift
  .photosPicker(isPresented: $showPhotos, selection: $photoItems, maxSelectionCount: 5, matching: .any(of: [.images, .videos]))
  ```
  (`.any(of:)`, `.images`, `.videos` are `PHPickerFilter` from `PhotosUI`, already imported in both files. `.fileImporter([.item])` already accepts video — no change.)
- [ ] **Build-verify** on the remote Mac (recipe step 2). Expected: build succeeds.
- [ ] **Manual on-simulator verify** (recipe step 4): open the chat composer's `+` → Photos, confirm the picker now shows videos (grant Photos with `xcrun simctl privacy <udid> grant photos <bundle>`; seed a video into the sim library via `xcrun simctl addmedia <udid> some.mov`). Repeat for the New Session launcher.
- [ ] Commit:
  ```
  feat(ios): allow picking videos in the photo picker

  Widen the PhotosUI filter from .images to .any(of: [.images, .videos]) in
  ChatPane and NewSessionView so the library picker offers videos alongside
  photos. Correct labeling of the picked item lands in the next commit.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 2 — Fix the mislabel bug in `ComposerModel.loadPhotos` (highest-value correctness fix)

**Files:** `apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift`, `apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift`, `apps/iosApp/SupermuxTests/ComposerModelTests.swift`

`loadPhotos` currently hardcodes `image/jpeg` + `image-N.jpg` for **every** item, so a picked video uploads with the wrong MIME/extension. Extract a pure, testable helper that inspects each item's advertised `UTType`s to decide image vs movie, and derive the correct MIME + extension.

- [ ] Add a pure `static` helper to `ComposerModel` (insert directly **above** `loadPhotos`, ~line 90). Complete Swift:
  ```swift
  /// Derive the upload MIME + filename for a picked photo-library item from the content types
  /// it advertises. A movie item (any `UTType` conforming to `.movie` — e.g. `public.movie`,
  /// `com.apple.quicktime-movie`, `public.mpeg-4`) keeps its real video type + extension, so a
  /// picked video uploads as `video/*` instead of the old hardcoded `image/jpeg`. Everything
  /// else is staged as a JPEG still (Photos hands JPEG `Data` back for images — the design
  /// keeps images as image/jpeg). Pure + `static` (takes the item's `supportedContentTypes`, no
  /// `PhotosPickerItem`) so it's unit-testable without a live photo library — the same split as
  /// `addPastedImage`.
  static func attachmentMeta(for contentTypes: [UTType], number: Int) -> (filename: String, mime: String) {
      if let movie = contentTypes.first(where: { $0.conforms(to: .movie) }) {
          let ext = movie.preferredFilenameExtension ?? "mov"
          let mime = movie.preferredMIMEType ?? "video/quicktime"
          return (filename: "video-\(number).\(ext)", mime: mime)
      }
      return (filename: "image-\(number).jpg", mime: "image/jpeg")
  }
  ```
- [ ] Rewrite `loadPhotos` (~lines 90-98) to use the helper and derive the number from the live `pending` count (gapless even when a `loadTransferable` fails).
  Before:
  ```swift
  func loadPhotos(_ items: [PhotosPickerItem]) async {
      for (i, item) in items.enumerated() {
          if let data = try? await item.loadTransferable(type: Data.self) {
              pending.append(PendingAttachment(data: data,
                                               filename: "image-\(pending.count + i + 1).jpg",
                                               mime: "image/jpeg"))
          }
      }
  }
  ```
  After:
  ```swift
  func loadPhotos(_ items: [PhotosPickerItem]) async {
      for item in items {
          guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
          let meta = Self.attachmentMeta(for: item.supportedContentTypes, number: pending.count + 1)
          pending.append(PendingAttachment(data: data, filename: meta.filename, mime: meta.mime))
      }
  }
  ```
- [ ] In `AttachmentTray.swift`, give a staged **video** chip a video glyph (it would otherwise show the "photo" icon now that videos can be staged). Replace the chip's `Image` (~line 18) and add a helper.
  Before:
  ```swift
      private func chip(_ p: PendingAttachment) -> some View {
          HStack(spacing: 5) {
              Image(systemName: p.mime.hasPrefix("audio") ? "waveform" : "photo").font(.caption2)
  ```
  After:
  ```swift
      /// SF Symbol for a staged-attachment chip: waveform for audio, video for movies, else photo.
      private func chipIcon(_ mime: String) -> String {
          if mime.hasPrefix("audio") { return "waveform" }
          if mime.hasPrefix("video") { return "video" }
          return "photo"
      }

      private func chip(_ p: PendingAttachment) -> some View {
          HStack(spacing: 5) {
              Image(systemName: chipIcon(p.mime)).font(.caption2)
  ```
- [ ] Add the `import UniformTypeIdentifiers` line to `SupermuxTests/ComposerModelTests.swift` (the test uses `UTType`). The import block becomes:
  ```swift
  import XCTest
  import Shared
  import UIKit
  import UniformTypeIdentifiers
  @testable import Supermux
  ```
- [ ] Add the mislabel-fix assertions to `ComposerModelTests.swift` — insert these methods **before** the final closing `}` of the class (after the existing paste tests, ~line 157). Complete Swift:
  ```swift
      // MARK: - Photo-library item typing (the mislabel-bug fix)

      /// A picked QuickTime movie must upload as `video/quicktime` + `.mov`, NOT the old
      /// hardcoded `image/jpeg` / `image-N.jpg`.
      func testAttachmentMetaQuickTimeMovieYieldsVideoMov() {
          let meta = ComposerModel.attachmentMeta(for: [.quickTimeMovie], number: 1)
          XCTAssertTrue(meta.mime.hasPrefix("video/"))
          XCTAssertEqual(meta.mime, "video/quicktime")
          XCTAssertEqual(meta.filename, "video-1.mov")
      }

      /// An MPEG-4 movie keeps its real `video/mp4` + `.mp4`.
      func testAttachmentMetaMpeg4MovieYieldsVideoMp4() {
          let meta = ComposerModel.attachmentMeta(for: [.mpeg4Movie], number: 2)
          XCTAssertEqual(meta.mime, "video/mp4")
          XCTAssertEqual(meta.filename, "video-2.mp4")
      }

      /// A still image stays `image/jpeg` + `.jpg` (the design keeps picked images as JPEG).
      func testAttachmentMetaImageYieldsJpeg() {
          let meta = ComposerModel.attachmentMeta(for: [.jpeg], number: 1)
          XCTAssertEqual(meta.mime, "image/jpeg")
          XCTAssertEqual(meta.filename, "image-1.jpg")
      }

      /// An abstract movie type with no concrete MIME/extension falls back to a video/* type
      /// and a video filename — never image/jpeg.
      func testAttachmentMetaAbstractMovieFallsBackToVideo() {
          let meta = ComposerModel.attachmentMeta(for: [.movie], number: 3)
          XCTAssertTrue(meta.mime.hasPrefix("video/"))
          XCTAssertTrue(meta.filename.hasPrefix("video-3."))
      }

      /// A picked video can advertise a still-frame image type too; the movie type must win.
      func testAttachmentMetaPrefersMovieWhenBothPresent() {
          let meta = ComposerModel.attachmentMeta(for: [.jpeg, .quickTimeMovie], number: 1)
          XCTAssertTrue(meta.mime.hasPrefix("video/"))
          XCTAssertEqual(meta.filename, "video-1.mov")
      }
  ```
- [ ] **Run the unit tests** on the remote Mac (recipe step 3): `xcodebuild ... test -only-testing:SupermuxTests/ComposerModelTests`. Expected: all `ComposerModelTests` pass, including the five new `testAttachmentMeta*` cases. Confirm from the `Test Suite 'ComposerModelTests' passed` line (evidence before asserting done — see superpowers:verification-before-completion).
- [ ] Commit:
  ```
  fix(ios): stop mislabeling picked videos as image/jpeg

  loadPhotos hardcoded image/jpeg + image-N.jpg for every PhotosPickerItem, so
  a picked video uploaded with the wrong MIME/extension. Add a pure, tested
  ComposerModel.attachmentMeta that inspects the item's supportedContentTypes:
  movie types keep their real video/* MIME + extension, images stay JPEG. Also
  give staged video chips a video glyph. Kind stays nil so the broker infers
  "video" from the MIME.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 3 — Confirm the upload-kind logic leaves `nil` for video (no logic change)

**Files:** `apps/iosApp/Supermux/Chat/ChatPane.swift`, `apps/iosApp/Supermux/Sessions/NewSessionView.swift`

The existing `kind = p.mime.hasPrefix("audio") ? "voice" : nil` is **already correct** for video: a `video/*` MIME does not start with `audio`, so `kind` stays `nil` and the broker infers `"video"` from the MIME. No behavior change is needed — only a clarifying comment so a future edit doesn't "helpfully" mislabel video as audio/voice.

- [ ] In `ChatPane.swift` `sendMessage()` (~line 339), add the comment above the `kind` line. Before:
  ```swift
          for p in toUpload {
              let kind = p.mime.hasPrefix("audio") ? "voice" : nil
  ```
  After:
  ```swift
          for p in toUpload {
              // Audio clips → "voice"; images and videos stay nil so the broker infers the kind
              // from the MIME (video/* → "video" server-side). Never mislabel a video as audio.
              let kind = p.mime.hasPrefix("audio") ? "voice" : nil
  ```
- [ ] In `NewSessionView.swift` `spawn()` (~line 362), make the identical change. Before:
  ```swift
              for p in toUpload {
                  let kind = p.mime.hasPrefix("audio") ? "voice" : nil
  ```
  After:
  ```swift
              for p in toUpload {
                  // Audio clips → "voice"; images and videos stay nil so the broker infers the kind
                  // from the MIME (video/* → "video" server-side). Never mislabel a video as audio.
                  let kind = p.mime.hasPrefix("audio") ? "voice" : nil
  ```
- [ ] **Build-verify** (recipe step 2). Expected: build succeeds (comment-only change).
- [ ] Commit:
  ```
  chore(ios): document that upload kind stays nil for video

  Comment-only. The kind heuristic already leaves nil for video (only audio
  becomes "voice"), so the broker infers "video" from the MIME. Pin the intent
  so a future edit doesn't route video through the audio branch.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 4 — Camera "Record video"

**Files:** `apps/iosApp/Supermux/Chat/ChatMessages.swift` (`CameraPicker`), `apps/iosApp/Supermux/Chat/Composer/ComposerModel.swift` (`addCameraVideo`), `apps/iosApp/Supermux/Chat/Composer/AttachMenu.swift` (menu item), `apps/iosApp/Supermux/Chat/ChatPane.swift` + `apps/iosApp/Supermux/Sessions/NewSessionView.swift` (wiring)

Pure-SwiftUI/UIKit change. Bar = build + manual. Parameterize `CameraPicker` for movie capture, add a distinct "Record video" menu entry, and stage the recorded clip with its real video MIME.

- [ ] In `ChatMessages.swift`, add the `UniformTypeIdentifiers` import (needed for `UTType.movie.identifier`). The import block (~lines 1-4) becomes:
  ```swift
  import SwiftUI
  import Shared
  import UIKit
  import QuickLook
  import UniformTypeIdentifiers
  ```
- [ ] In `ChatMessages.swift`, replace `CameraPicker` (~lines 159-181) with a mode-parameterized version that also captures movies. Complete before/after.
  Before:
  ```swift
  /// Camera capture → UIImage (device only; needs NSCameraUsageDescription).
  struct CameraPicker: UIViewControllerRepresentable {
      var onImage: (UIImage) -> Void
      @Environment(\.dismiss) private var dismiss
      func makeUIViewController(context: Context) -> UIImagePickerController {
          let p = UIImagePickerController()
          p.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
          p.delegate = context.coordinator
          return p
      }
      func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
      func makeCoordinator() -> Coordinator { Coordinator(self) }
      final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
          let parent: CameraPicker
          init(_ p: CameraPicker) { parent = p }
          func imagePickerController(_ picker: UIImagePickerController,
                                     didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
              if let img = info[.originalImage] as? UIImage { parent.onImage(img) }
              parent.dismiss()
          }
          func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
      }
  }
  ```
  After:
  ```swift
  /// Camera capture → still image or recorded movie (device only; needs NSCameraUsageDescription
  /// + NSMicrophoneUsageDescription for video, both already declared in project.yml). `mode`
  /// selects the media. The Simulator has no camera, so it falls back to the photo library
  /// filtered to the requested media type.
  struct CameraPicker: UIViewControllerRepresentable {
      enum Mode { case photo, video }
      var mode: Mode = .photo
      var onImage: (UIImage) -> Void = { _ in }
      var onVideo: (URL) -> Void = { _ in }
      @Environment(\.dismiss) private var dismiss
      func makeUIViewController(context: Context) -> UIImagePickerController {
          let p = UIImagePickerController()
          let hasCamera = UIImagePickerController.isSourceTypeAvailable(.camera)
          p.sourceType = hasCamera ? .camera : .photoLibrary
          if mode == .video {
              p.mediaTypes = [UTType.movie.identifier]
              // cameraCaptureMode is only valid for the .camera source; setting it on the
              // photo-library fallback (Simulator) would assert.
              if hasCamera { p.cameraCaptureMode = .video }
          }
          p.delegate = context.coordinator
          return p
      }
      func updateUIViewController(_ vc: UIImagePickerController, context: Context) {}
      func makeCoordinator() -> Coordinator { Coordinator(self) }
      final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
          let parent: CameraPicker
          init(_ p: CameraPicker) { parent = p }
          func imagePickerController(_ picker: UIImagePickerController,
                                     didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
              if let url = info[.mediaURL] as? URL {
                  parent.onVideo(url)
              } else if let img = info[.originalImage] as? UIImage {
                  parent.onImage(img)
              }
              parent.dismiss()
          }
          func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { parent.dismiss() }
      }
  }
  ```
- [ ] In `ComposerModel.swift`, add `addCameraVideo` directly **after** `addCameraImage` (~line 114). Complete Swift:
  ```swift
  /// Stage a movie recorded by the camera (a temp file URL from `UIImagePickerController`).
  /// Reads the clip into `Data` (Phase 1: the shared upload takes bytes; true streaming is a
  /// separate KMP change) and labels it with the file's real video MIME + extension.
  func addCameraVideo(_ url: URL) {
      guard let data = try? Data(contentsOf: url) else { return }
      let ext = url.pathExtension.isEmpty ? "mov" : url.pathExtension
      let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "video/quicktime"
      pending.append(PendingAttachment(data: data,
                                       filename: "video-\(pending.count + 1).\(ext)",
                                       mime: mime))
  }
  ```
- [ ] In `AttachMenu.swift`, add a `showVideoCamera` binding and a "Record video" menu item. Complete before/after.
  Before:
  ```swift
  struct AttachMenu: View {
      @Binding var showPhotos: Bool
      @Binding var showFiles: Bool
      @Binding var showCamera: Bool
      /// Show a "Paste" item — only worth offering when the clipboard actually holds an image.
      /// Defaulted so existing call sites (e.g. the new-session launcher) compile unchanged.
      var showPaste: Bool = false
      /// Stage whatever is on the clipboard as attachment(s).
      var onPaste: () -> Void = {}

      var body: some View {
          Menu {
              if showPaste {
                  Button { onPaste() } label: { Label("Paste", systemImage: "doc.on.clipboard") }
              }
              Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
              Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
              Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
          } label: {
  ```
  After:
  ```swift
  struct AttachMenu: View {
      @Binding var showPhotos: Bool
      @Binding var showFiles: Bool
      @Binding var showCamera: Bool
      /// Drives the movie-capture camera — distinct from the still-photo `showCamera`.
      @Binding var showVideoCamera: Bool
      /// Show a "Paste" item — only worth offering when the clipboard actually holds an image.
      /// Defaulted so existing call sites (e.g. the new-session launcher) compile unchanged.
      var showPaste: Bool = false
      /// Stage whatever is on the clipboard as attachment(s).
      var onPaste: () -> Void = {}

      var body: some View {
          Menu {
              if showPaste {
                  Button { onPaste() } label: { Label("Paste", systemImage: "doc.on.clipboard") }
              }
              Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
              Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
              Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
              Button { showVideoCamera = true } label: { Label("Record video", systemImage: "video") }
          } label: {
  ```
  (`showVideoCamera` is a required `@Binding` — both screens gain video recording, so all three call sites below are updated. That avoids a dead menu button that a `.constant(false)` default would leave.)
- [ ] In `ChatPane.swift`, add the presentation state next to `showCamera` (~line 32). Before:
  ```swift
      @State private var showCamera = false
  ```
  After:
  ```swift
      @State private var showCamera = false
      @State private var showVideoCamera = false
  ```
- [ ] In `ChatPane.swift`, pass the new binding to the **collapsed-composer** `AttachMenu` (~lines 252-254). Before:
  ```swift
                  AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                             showPaste: pasteboardHasAttachment,
                             onPaste: { Task { await composer.pasteClipboard() } })
  ```
  After:
  ```swift
                  AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                             showVideoCamera: $showVideoCamera,
                             showPaste: pasteboardHasAttachment,
                             onPaste: { Task { await composer.pasteClipboard() } })
  ```
- [ ] In `ChatPane.swift`, pass the new binding to the **expanded-composer** `AttachMenu` (~lines 276-278). Before:
  ```swift
                      AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                                 showPaste: pasteboardHasAttachment,
                                 onPaste: { Task { await composer.pasteClipboard() } })
  ```
  After:
  ```swift
                      AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                                 showVideoCamera: $showVideoCamera,
                                 showPaste: pasteboardHasAttachment,
                                 onPaste: { Task { await composer.pasteClipboard() } })
  ```
- [ ] In `ChatPane.swift`, make the existing photo `.fullScreenCover` explicit and add the video one (~line 216). The existing trailing-closure `CameraPicker { ... }` must become an explicit `onImage:` now that `onVideo` is the last closure param. Before:
  ```swift
          .fullScreenCover(isPresented: $showCamera) { CameraPicker { composer.addCameraImage($0) } }
  ```
  After:
  ```swift
          .fullScreenCover(isPresented: $showCamera) { CameraPicker(mode: .photo, onImage: { composer.addCameraImage($0) }) }
          .fullScreenCover(isPresented: $showVideoCamera) { CameraPicker(mode: .video, onVideo: { composer.addCameraVideo($0) }) }
  ```
  (Two `.fullScreenCover` modifiers on one view are supported on iOS 15+/26 — the app targets iOS 26.)
- [ ] In `NewSessionView.swift`, add the presentation state next to `showCamera` (~line 51). Before:
  ```swift
      @State private var showCamera = false
  ```
  After:
  ```swift
      @State private var showCamera = false
      @State private var showVideoCamera = false
  ```
- [ ] In `NewSessionView.swift`, pass the new binding to the action-row `AttachMenu` (~line 322). Before:
  ```swift
                  AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera)
  ```
  After:
  ```swift
                  AttachMenu(showPhotos: $showPhotos, showFiles: $showFiles, showCamera: $showCamera,
                             showVideoCamera: $showVideoCamera)
  ```
- [ ] In `NewSessionView.swift`, make the photo `.fullScreenCover` explicit and add the video one (~line 183). Before:
  ```swift
          .fullScreenCover(isPresented: $showCamera) { CameraPicker { composer.addCameraImage($0) } }
  ```
  After:
  ```swift
          .fullScreenCover(isPresented: $showCamera) { CameraPicker(mode: .photo, onImage: { composer.addCameraImage($0) }) }
          .fullScreenCover(isPresented: $showVideoCamera) { CameraPicker(mode: .video, onVideo: { composer.addCameraVideo($0) }) }
  ```
- [ ] **Build-verify** (recipe step 2). Expected: build succeeds.
- [ ] **Manual on-simulator verify** (recipe step 4): open `+` → confirm both "Camera" and "Record video" items appear in the chat composer and the launcher. On the Simulator (no camera) "Record video" opens the photo library filtered to movies; pick one and confirm it stages as a video chip (video glyph, not photo) via the Task 2 tray-icon change. On a physical device (if available) confirm "Record video" opens the camera in movie mode.
- [ ] Commit:
  ```
  feat(ios): add camera video recording to the attach menu

  Parameterize CameraPicker with a photo/video mode (mediaTypes = [movie],
  cameraCaptureMode = .video when a real camera exists), add a "Record video"
  entry to AttachMenu, stage the recorded clip via ComposerModel.addCameraVideo
  with its real video MIME/extension, and wire showVideoCamera through ChatPane
  and NewSessionView. Existing photo camera unchanged.

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

### Task 5 — Inline video playback in the message list

**Files:** `apps/iosApp/Supermux/Chat/ChatMessages.swift`

Pure-SwiftUI change. Bar = build + manual. Render `video`/`video_note`/`video/*` attachments with an inline `AVKit.VideoPlayer`. To keep the existing Bearer-authed download path and avoid an auth-header streaming hack, the row downloads the clip to a local temp file (reusing `broker.downloadFile` + its progress UI) and then plays it inline. The QuickLook file row remains the fallback for every non-video, non-image attachment.

- [ ] In `ChatMessages.swift`, add the `AVKit` import (the `UniformTypeIdentifiers` import was added in Task 4). The import block becomes:
  ```swift
  import SwiftUI
  import Shared
  import UIKit
  import QuickLook
  import UniformTypeIdentifiers
  import AVKit
  ```
- [ ] In `AttachmentView`, add a `player` state, an `isVideo` computed, a `videoView`, route video in `body`, parameterize `startDownload` so video downloads without auto-opening QuickLook, and recognize the new `"video"` kind in `fileIcon`. Complete before/after for each edit within `AttachmentView`.

  Add `player` state — before (~lines 40-46):
  ```swift
      @State private var image: UIImage?
      @State private var imageData: Data?
      @State private var previewURL: URL?
      @State private var fileURL: URL?
      @State private var downloading = false
      @State private var progress: Double = 0
      @State private var failed = false
      private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo" }
  ```
  After:
  ```swift
      @State private var image: UIImage?
      @State private var imageData: Data?
      @State private var previewURL: URL?
      @State private var fileURL: URL?
      @State private var player: AVPlayer?
      @State private var downloading = false
      @State private var progress: Double = 0
      @State private var failed = false
      private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo" }
      private var isVideo: Bool { (att.mime ?? "").hasPrefix("video") || att.kind == "video" || att.kind == "video_note" }
  ```

  Route video in `body` — before (~lines 49-59):
  ```swift
      var body: some View {
          Group {
              if isImage { imageView } else { fileRow }
          }
          .task {
              if isImage, image == nil, let data = await broker.loadFile(att.file_id) {
                  imageData = data
                  image = UIImage(data: data)
              }
          }
      }
  ```
  After:
  ```swift
      var body: some View {
          Group {
              if isImage { imageView }
              else if isVideo { videoView }
              else { fileRow }
          }
          .task {
              if isImage, image == nil, let data = await broker.loadFile(att.file_id) {
                  imageData = data
                  image = UIImage(data: data)
              }
          }
      }

      /// Inline movie playback. The clip is downloaded to a local temp file (Bearer-authed, via the
      /// same `broker.downloadFile` the file row uses) and then played with `AVKit.VideoPlayer`.
      /// The `AVPlayer` is held in `@State` so `videoView` re-renders don't recreate it (which would
      /// restart playback). Until downloaded, a tappable poster shows a play glyph / progress / retry.
      @ViewBuilder private var videoView: some View {
          if let player {
              VideoPlayer(player: player)
                  .frame(maxWidth: .infinity, minHeight: 200, maxHeight: 260)
                  .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
          } else {
              Button {
                  if !downloading { startDownload(autoPreview: false) }
              } label: {
                  ZStack {
                      RoundedRectangle(cornerRadius: 12, style: .continuous)
                          .fill(Color(.secondarySystemBackground)).frame(height: 200)
                      if downloading {
                          VStack(spacing: 8) {
                              ProgressView(value: progress).progressViewStyle(.linear).frame(width: 120)
                              Text("\(Int(progress * 100))%").font(.caption2.monospaced())
                                  .foregroundStyle(.secondary).monospacedDigit()
                          }
                      } else if failed {
                          VStack(spacing: 6) {
                              Image(systemName: "exclamationmark.triangle").font(.title2).foregroundStyle(.red)
                              Text("Download failed — tap to retry").font(.caption2).foregroundStyle(.red)
                          }
                      } else {
                          VStack(spacing: 6) {
                              Image(systemName: "play.circle.fill").font(.system(size: 44)).foregroundStyle(.white)
                              Text(att.name ?? "video").font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                          }
                      }
                  }
              }
              .buttonStyle(.plain)
          }
      }
  ```

  Parameterize `startDownload` — before (~lines 122-137):
  ```swift
      private func startDownload() {
          failed = false; downloading = true; progress = 0
          Task {
              do {
                  let u = try await broker.downloadFile(
                      att.file_id, name: previewFilename(name: att.name, mime: att.mime)
                  ) { p in
                      Task { @MainActor in progress = p }
                  }
                  // Download finished → present Quick Look immediately (setting previewURL auto-opens it).
                  await MainActor.run { downloading = false; fileURL = u; previewURL = u }
              } catch {
                  await MainActor.run { downloading = false; failed = true }
              }
          }
      }
  ```
  After:
  ```swift
      /// Download the attachment to a local temp file. `autoPreview` (file row) also opens Quick
      /// Look; the video row passes `false` and instead builds an `AVPlayer` for inline playback.
      private func startDownload(autoPreview: Bool = true) {
          failed = false; downloading = true; progress = 0
          Task {
              do {
                  let u = try await broker.downloadFile(
                      att.file_id, name: previewFilename(name: att.name, mime: att.mime)
                  ) { p in
                      Task { @MainActor in progress = p }
                  }
                  await MainActor.run {
                      downloading = false
                      fileURL = u
                      if isVideo {
                          player = AVPlayer(url: u)          // inline playback (no Quick Look)
                      } else if autoPreview {
                          previewURL = u                     // file row → Quick Look auto-opens
                      }
                  }
              } catch {
                  await MainActor.run { downloading = false; failed = true }
              }
          }
      }
  ```
  (The existing file-row call `startDownload()` at ~line 88 keeps working via the `autoPreview: true` default — no change there.)

  Recognize the new `"video"` kind in `fileIcon` — before (~lines 139-144):
  ```swift
      private var fileIcon: String {
          let m = att.mime ?? ""
          if m.hasPrefix("audio") || att.kind == "voice" || att.kind == "audio" { return "waveform" }
          if m.hasPrefix("video") || att.kind == "video_note" { return "video" }
          return "doc"
      }
  ```
  After:
  ```swift
      private var fileIcon: String {
          let m = att.mime ?? ""
          if m.hasPrefix("audio") || att.kind == "voice" || att.kind == "audio" { return "waveform" }
          if m.hasPrefix("video") || att.kind == "video" || att.kind == "video_note" { return "video" }
          return "doc"
      }
  ```
- [ ] **Build-verify** (recipe step 2). Expected: build succeeds.
- [ ] **Manual on-simulator verify** (recipe step 4): with the broker reachable from the sim, send a message carrying a video attachment (pick from library via Task 1, or record via Task 4). Confirm the message row shows the video poster; tap → progress → the clip plays inline in an `AVKit.VideoPlayer` with transport controls. Confirm an **image** attachment still renders inline (no regression) and a **non-video file** (e.g. a PDF) still uses the Quick Look file row. Capture `simctl io <udid> screenshot` before/after tap as evidence.
- [ ] Commit:
  ```
  feat(ios): play video attachments inline with VideoPlayer

  Render video / video_note / video-mime attachments with AVKit.VideoPlayer in
  the message list: the row downloads the clip (Bearer-authed, reusing
  broker.downloadFile + its progress UI) to a local temp file, holds the
  AVPlayer in @State so re-renders don't restart it, and plays inline. Quick
  Look stays the fallback for other files; fileIcon now recognizes "video".

  Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
  ```

---

## Final verification (superpowers:verification-before-completion)

Run before claiming the branch is done — evidence before assertions:

- [ ] Full test target passes on the remote-Mac simulator: `xcodebuild ... test -only-testing:SupermuxTests` (Task 2's five new assertions among them). Paste the `Test Suite … passed` line.
- [ ] Clean `xcodebuild ... build` of the `Supermux` scheme succeeds (all five tasks integrated).
- [ ] Manual sim pass of the end-to-end story: pick a library video → stages as `video/*` with a video chip → uploads → plays inline; record a video → same; an image still uploads + renders; a PDF still opens in Quick Look. Screenshots captured.
- [ ] Grep for regressions: no remaining `matching: .images` in `ChatPane.swift`/`NewSessionView.swift`; no remaining hardcoded `"image/jpeg"` in `ComposerModel.loadPhotos`.

## Known limitations (intentionally out of scope for this iOS plan)

- **In-memory upload.** `loadPhotos` / `addCameraVideo` read the whole clip into `Data`, and `BrokerSession.upload` base64-encodes it, because the shared `BrokerApi.upload(bytes:)` signature is fixed for Phase 1. True streaming (raw-body request) is the **separate shared-KMP upload plan**; when it lands, the signature stays the same, so no iOS call-site change is needed.
- **No client-side 500 MB guard / "too large" toast on iOS.** The broker returns 413 and the KMP upload plan owns surfacing that error; a native "Video too large (max 500 MB)" message is deferred to that plan (which owns `BrokerApi`'s error path). `BrokerSession.upload` currently returns `String?` (nil on failure) with no reason, so wiring a user-facing message would require the KMP error plumbing.
- **Model does not see video frames** — video reaches the agent as a file-path reference (broker/adapter behavior; Decision 7 in the spec). No iOS surface.
- **Inline playback downloads before playing** (Phase 1). Range/206 streaming via an `AVURLAsset` auth header is deliberately avoided here to reuse the proven Bearer-authed `downloadFile` path.
