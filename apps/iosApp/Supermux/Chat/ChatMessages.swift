import SwiftUI
import Shared
import UIKit

struct MessageRow: View {
    let entry: LogEntry
    let broker: BrokerSession
    private var isAgent: Bool { entry.direction.hasPrefix("out") }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            let text = entry.text ?? ""
            if !text.isEmpty {
                if isAgent {
                    MarkdownView(text: text).font(.subheadline)
                        .transcriptBody()
                        .contextMenu { Button { UIPasteboard.general.string = text } label: { Label("Copy", systemImage: "doc.on.doc") } }
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
    @State private var showLightbox = false
    @State private var shareURL: URL?
    private var isImage: Bool { (att.mime ?? "").hasPrefix("image") || (att.kind ?? "") == "photo" }

    var body: some View {
        Group {
            if isImage { imageView } else { fileRow }
        }
        .task {
            if isImage, image == nil, let data = await broker.loadFile(att.file_id) { image = UIImage(data: data) }
        }
    }

    @ViewBuilder private var imageView: some View {
        if let image {
            Image(uiImage: image).resizable().scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: 240, alignment: .leading)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onTapGesture { showLightbox = true }
                .fullScreenCover(isPresented: $showLightbox) { Lightbox(image: image) }
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color(.secondarySystemBackground)).frame(height: 140).overlay(ProgressView())
        }
    }

    private var fileRow: some View {
        Button {
            Task {
                if let data = await broker.loadFile(att.file_id) { shareURL = tmpURL(data, name: att.name ?? "file") }
            }
        } label: {
            HStack(spacing: 10) {
                Image(systemName: fileIcon).font(.title3).foregroundStyle(.secondary).frame(width: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(att.name ?? "file").font(.caption.weight(.medium)).lineLimit(1)
                    if let sz = att.size?.int64Value, sz > 0 {
                        Text(fmtSize(sz)).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 4)
                Image(systemName: "arrow.down.circle").foregroundStyle(Theme.teal)
            }
            .padding(10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let u = shareURL { ShareSheet(items: [u]) }
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
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? data.write(to: url)
        return url
    }
}

struct Lightbox: View {
    let image: UIImage
    @Environment(\.dismiss) private var dismiss
    @State private var scale: CGFloat = 1
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            Image(uiImage: image).resizable().scaledToFit().scaleEffect(scale)
                .gesture(MagnificationGesture().onChanged { scale = max(1, $0) }
                    .onEnded { _ in withAnimation { if scale < 1 { scale = 1 } } })
            Button { dismiss() } label: {
                Image(systemName: "xmark").font(.title2.weight(.semibold)).foregroundStyle(.white)
                    .padding(12).background(.ultraThinMaterial, in: Circle())
            }.padding()
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
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
