import SwiftUI
import Shared
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
import QuickLook
import UniformTypeIdentifiers
import AVKit

struct MessageRow: View, Equatable {
    let entry: LogEntry
    let broker: BrokerSession
    let sessionId: String
    let workdir: String
    private var isAgent: Bool { entry.direction.hasPrefix("out") }

    /// Skip re-render when the parent transcript rebuilds for an unrelated observation
    /// (other sessions' messages, agent phase ticks) but this entry is unchanged.
    static func == (lhs: MessageRow, rhs: MessageRow) -> Bool {
        lhs.entry.id == rhs.entry.id
            && lhs.entry.text == rhs.entry.text
            && lhs.entry.direction == rhs.entry.direction
            && (lhs.entry.attachments?.count ?? 0) == (rhs.entry.attachments?.count ?? 0)
            && lhs.sessionId == rhs.sessionId
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let text = entry.text ?? ""
            if !text.isEmpty {
                if isAgent {
                    MarkdownView(text: text, onOpenFile: { ref in
                        broker.openFileFromMessage(sessionId: sessionId, workdir: workdir, ref: ref)
                    })
                    .equatable()
                    .transcriptBody()
                    MessageMetaRow(text: text, broker: broker)
                } else {
                    // Same bare-URL linkify as agent MarkdownView — plain Text never makes
                    // https://… tappable, so user bubbles would show dead links otherwise.
                    SelectableText(attributed: UserMessageText.attributed(text))
                        .userMessageSurface()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let atts = entry.attachments, !atts.isEmpty {
                ForEach(atts, id: \.file_id) { AttachmentView(att: $0, broker: broker) }
            }
        }
    }
}

/// Plain user-bubble text with bare http(s) URL linkification (no full markdown parse —
/// user turns are not agent markdown).
enum UserMessageText {
    static func attributed(_ text: String) -> NSAttributedString {
        #if os(macOS)
        let size = PlatformFont.preferredFont(forTextStyle: .body).pointSize
        #else
        let size = PlatformFont.preferredFont(forTextStyle: .subheadline).pointSize
        #endif
        // Medium weight matches the previous `messageFont.weight(.medium)` look.
        let font = PlatformFont.systemFont(ofSize: size, weight: .medium)
        let out = NSMutableAttributedString(string: text, attributes: [
            .font: font,
            .foregroundColor: PlatformColor.smLabel,
        ])
        BareUrlLinks.decorate(out)
        return out
    }
}

struct AttachmentView: View {
    let att: Attachment
    let broker: BrokerSession
    @State private var image: PlatformImage?
    @State private var imageData: Data?
    @State private var previewURL: URL?
    @State private var fileURL: URL?
    @State private var player: AVPlayer?
    @State private var downloading = false
    @State private var progress: Double = 0
    @State private var failed = false
    private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo" }
    private var isVideo: Bool { (att.mime ?? "").hasPrefix("video") || att.kind == "video" || att.kind == "video_note" }

    var body: some View {
        Group {
            if isImage { imageView }
            else if isVideo { videoView }
            else { fileRow }
        }
        .task {
            if isImage, image == nil, let data = await broker.loadFile(att.file_id) {
                imageData = data
                image = PlatformImage(data: data)
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
                        .fill(Color.smSecondaryBackground).frame(height: 200)
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

    @ViewBuilder private var imageView: some View {
        if let image {
            Button {
                if previewURL == nil, let data = imageData {
                    previewURL = tmpURL(data, name: imageFileName)
                }
            } label: {
                Image(platform: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 240, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .quickLookPreview($previewURL)
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.smSecondaryBackground).frame(height: 140).overlay(ProgressView())
        }
    }

    /// A filename with an extension Quick Look can key off (falls back to the mime subtype).
    private var imageFileName: String {
        previewFilename(name: att.name, mime: att.mime, fallbackBase: "image", defaultExt: "jpg")
    }

    private var fileRow: some View {
        Button {
            if downloading { return }
            if fileURL != nil { previewURL = fileURL } else { startDownload() }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: fileIcon).font(.title3).foregroundStyle(.secondary).frame(width: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(att.name ?? "file").font(.caption.weight(.medium))
                        .lineLimit(2).truncationMode(.middle)
                    if failed {
                        Text("Download failed — tap to retry").font(.caption2).foregroundStyle(.red)
                    } else if let sz = att.size?.int64Value, sz > 0 {
                        Text(fmtSize(sz)).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 4)
                if downloading {
                    HStack(spacing: 6) {
                        ProgressView(value: progress).progressViewStyle(.linear).frame(width: 70)
                        Text("\(Int(progress * 100))%").font(.caption2.monospaced())
                            .foregroundStyle(.secondary).monospacedDigit()
                    }
                } else if failed {
                    Image(systemName: "arrow.clockwise.circle").foregroundStyle(.red)
                } else {
                    Image(systemName: fileURL == nil ? "arrow.down.circle" : "eye.circle")
                        .foregroundStyle(Theme.teal)
                }
            }
            .padding(10)
            .background(Color.smSecondaryBackground, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .quickLookPreview($previewURL)
    }

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

    private var fileIcon: String {
        let m = att.mime ?? ""
        if m.hasPrefix("audio") || att.kind == "voice" || att.kind == "audio" { return "waveform" }
        if m.hasPrefix("video") || att.kind == "video" || att.kind == "video_note" { return "video" }
        return "doc"
    }
    private func fmtSize(_ n: Int64) -> String {
        if n >= 1_000_000 { return String(format: "%.1f MB", Double(n) / 1_000_000) }
        if n >= 1_000 { return String(format: "%.0f KB", Double(n) / 1_000) }
        return "\(n) B"
    }
    private func tmpURL(_ data: Data, name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(att.file_id, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(name)
        try? data.write(to: url)
        return url
    }
}

#if os(iOS)
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
#endif
