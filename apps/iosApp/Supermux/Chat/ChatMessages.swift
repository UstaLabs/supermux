import SwiftUI
import Shared
import UIKit
import QuickLook

struct MessageRow: View {
    let entry: LogEntry
    let broker: BrokerSession
    let sessionId: String
    let workdir: String
    private var isAgent: Bool { entry.direction.hasPrefix("out") }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let text = entry.text ?? ""
            if !text.isEmpty {
                if isAgent {
                    MarkdownView(text: text, onOpenFile: { ref in
                        broker.openFileFromMessage(sessionId: sessionId, workdir: workdir, ref: ref)
                    })
                        .font(.subheadline)
                        .transcriptBody()
                } else {
                    Text(text).font(.subheadline.weight(.medium))
                        .textSelection(.enabled)
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

struct AttachmentView: View {
    let att: Attachment
    let broker: BrokerSession
    @State private var image: UIImage?
    @State private var imageData: Data?
    @State private var previewURL: URL?
    @State private var fileURL: URL?
    @State private var downloading = false
    @State private var progress: Double = 0
    @State private var failed = false
    private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo" }

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

    @ViewBuilder private var imageView: some View {
        if let image {
            Button {
                if previewURL == nil, let data = imageData {
                    previewURL = tmpURL(data, name: imageFileName)
                }
            } label: {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity, maxHeight: 240, alignment: .leading)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
            .quickLookPreview($previewURL)
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(.secondarySystemBackground)).frame(height: 140).overlay(ProgressView())
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
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .quickLookPreview($previewURL)
    }

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

    private var fileIcon: String {
        let m = att.mime ?? ""
        if m.hasPrefix("audio") || att.kind == "voice" || att.kind == "audio" { return "waveform" }
        if m.hasPrefix("video") || att.kind == "video_note" { return "video" }
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
