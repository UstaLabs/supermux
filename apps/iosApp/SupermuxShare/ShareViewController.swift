// #167: the share extension — "supermux" in any app's share sheet, or an agent in its suggestions row. Copies what was
// shared into the App Group at once (files never pass through memory: the extension has ~120 MB), shows the sheet,
// sends, and hands anything that couldn't go yet to the app's outbox.
import AVFoundation
import Intents
import SwiftUI
import UIKit
import UniformTypeIdentifiers

final class ShareViewController: UIViewController {
    private var model: ShareModel!

    override func viewDidLoad() {
        super.viewDidLoad()
        let preselect = (extensionContext?.intent as? INSendMessageIntent)?.conversationIdentifier
        model = ShareModel(jobId: ShareJobRow.newId(), preselect: preselect) { [weak self] in
            self?.extensionContext?.completeRequest(returningItems: nil)
        }
        let host = UIHostingController(rootView: ShareSheetView(model: model))
        addChild(host)
        host.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(host.view)
        NSLayoutConstraint.activate([
            host.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            host.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            host.view.topAnchor.constraint(equalTo: view.topAnchor),
            host.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        host.didMove(toParent: self)
        let items = (extensionContext?.inputItems as? [NSExtensionItem]) ?? []
        Task { await model.load(items) }
    }
}

struct SharedThing: Identifiable {
    enum Kind { case link, text, file }
    let id = UUID()
    let kind: Kind
    var text: String? = nil
    var file: ShareFile? = nil
    var thumb: UIImage? = nil
}

@MainActor
final class ShareModel: ObservableObject {
    enum Phase: Equatable { case editing, sending, done(String), queued(String), failed(String) }

    let jobId: String
    let targets: [ShareTargetRow]
    @Published var things: [SharedThing] = []
    @Published var loading = true
    @Published var picked: ShareTargetRow?
    @Published var agentsTab = true
    @Published var query = ""
    @Published var hostFilter: String? = nil
    @Published var message = ""
    @Published var phase: Phase = .editing
    private let finish: () -> Void

    init(jobId: String, preselect: String?, finish: @escaping () -> Void) {
        self.jobId = jobId
        self.finish = finish
        targets = ShareStore.targets()
        picked = preselect.flatMap { key in targets.first { $0.id == key } }
        if let p = picked { agentsTab = p.isAgent }
    }

    var hosts: [(id: String, name: String)] {
        var seen = Set<String>()
        return targets.compactMap { seen.insert($0.hostId).inserted ? ($0.hostId, $0.hostName) : nil }
    }

    var shown: [ShareTargetRow] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        return targets.filter { $0.isAgent == agentsTab && (hostFilter == nil || $0.hostId == hostFilter) && (q.isEmpty || $0.name.lowercased().contains(q)) }
    }

    func cancel() {
        if let dir = ShareStore.root?.appendingPathComponent(jobId, isDirectory: true) { try? FileManager.default.removeItem(at: dir) }
        finish()
    }

    func send() {
        guard let t = picked, phase == .editing, !loading else { return }
        phase = .sending
        let job = ShareJobRow(
            clientMessageId: jobId, hostId: t.hostId, sessionId: t.sessionId, targetName: t.name, message: message,
            texts: things.compactMap { $0.text }, files: things.compactMap { $0.file }, createdAt: Date().timeIntervalSince1970
        )
        ShareStore.put(job)
        Task {
            let outcome = await withTaskGroup(of: ShareSend.Outcome?.self) { group -> ShareSend.Outcome? in
                group.addTask { await ShareSend.send(job) }
                // A big video can take longer than a share sheet should stay up: then the app finishes it.
                group.addTask { try? await Task.sleep(nanoseconds: 25_000_000_000); return nil }
                let first = await group.next() ?? nil
                group.cancelAll()
                return first
            }
            switch outcome {
            case .sent:
                ShareStore.recordSent(job)
                ShareStore.remove(job)
                ShareDonation.donate(hostId: t.hostId, sessionId: t.sessionId, name: t.name)
                phase = .done(String(format: String(localized: "Sent to %@"), t.name))
                try? await Task.sleep(nanoseconds: 1_100_000_000)
                finish()
            case .failed(let why):
                ShareStore.remove(job)
                phase = .failed(String(format: String(localized: "Couldn't send to %@: %@"), t.name, why))
            case .retry(let next, _):
                ShareStore.put(next)
                fallthrough
            case nil:
                phase = .queued(String(format: String(localized: "Queued for %@ · sends when you open supermux"), t.name))
                try? await Task.sleep(nanoseconds: 1_600_000_000)
                finish()
            }
        }
    }

    // MARK: loading what was shared

    func load(_ items: [NSExtensionItem]) async {
        var out: [SharedThing] = []
        var index = 0
        for item in items {
            for provider in item.attachments ?? [] {
                if let t = await loadOne(provider, index: index) { out.append(t) }
                index += 1
            }
            // Some apps put the words in the item itself (a caption) rather than in an attachment.
            if let s = item.attributedContentText?.string.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty,
               !out.contains(where: { $0.text == s }) {
                out.append(SharedThing(kind: isLink(s) ? .link : .text, text: s))
            }
        }
        things = out
        loading = false
    }

    private func loadOne(_ p: NSItemProvider, index: Int) async -> SharedThing? {
        let fileTypes: [UTType] = [.movie, .image, .audio, .pdf, .data]
        let isFileURL = p.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier)
        if !isFileURL, p.hasItemConformingToTypeIdentifier(UTType.url.identifier),
           let url = try? await p.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) as? URL {
            return SharedThing(kind: .link, text: url.absoluteString)
        }
        if !isFileURL, !fileTypes.dropLast().contains(where: { p.hasItemConformingToTypeIdentifier($0.identifier) }),
           p.hasItemConformingToTypeIdentifier(UTType.plainText.identifier),
           let s = try? await p.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) as? String {
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : SharedThing(kind: isLink(t) ? .link : .text, text: t)
        }
        guard let type = (fileTypes + [.fileURL]).first(where: { p.hasItemConformingToTypeIdentifier($0.identifier) }) ?? p.registeredTypeIdentifiers.first.flatMap({ UTType($0) }) else { return nil }
        return await copyFile(p, type: type, index: index)
    }

    /// The provider's file, copied into share/<job>/ inside its callback (the file is gone after it).
    private func copyFile(_ p: NSItemProvider, type: UTType, index: Int) async -> SharedThing? {
        guard let root = ShareStore.root else { return nil }
        let jobId = self.jobId
        let suggested = p.suggestedName
        let copied: ShareFile? = await withCheckedContinuation { cont in
            _ = p.loadFileRepresentation(forTypeIdentifier: type.identifier) { url, _ in
                guard let url else { cont.resume(returning: nil); return }
                let dir = root.appendingPathComponent(jobId, isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                var name = (suggested.map { $0 + (url.pathExtension.isEmpty ? "" : ".\(url.pathExtension)") } ?? url.lastPathComponent)
                    .replacingOccurrences(of: "/", with: "_")
                if name.isEmpty { name = "file" }
                let rel = "\(jobId)/\(index)-\(name)"
                let dest = root.appendingPathComponent(rel)
                do {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.copyItem(at: url, to: dest)
                    let size = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? Int64) ?? 0
                    let mime = UTType(filenameExtension: dest.pathExtension)?.preferredMIMEType ?? type.preferredMIMEType ?? "application/octet-stream"
                    cont.resume(returning: ShareFile(path: rel, name: name, mime: mime, size: size))
                } catch {
                    cont.resume(returning: nil)
                }
            }
        }
        guard let f = copied else { return nil }
        return SharedThing(kind: .file, file: f, thumb: thumbnail(root.appendingPathComponent(f.path), mime: f.mime))
    }

    /// A small picture without decoding the whole file (ImageIO for images, a frame for videos).
    private func thumbnail(_ url: URL, mime: String) -> UIImage? {
        if mime.hasPrefix("image/"), let src = CGImageSourceCreateWithURL(url as CFURL, nil) {
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 200, kCGImageSourceCreateThumbnailWithTransform: true]
            return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary).map { UIImage(cgImage: $0) }
        }
        if mime.hasPrefix("video/") {
            let gen = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            gen.appliesPreferredTrackTransform = true
            gen.maximumSize = CGSize(width: 200, height: 200)
            return (try? gen.copyCGImage(at: .zero, actualTime: nil)).map { UIImage(cgImage: $0) }
        }
        return nil
    }
}

func isLink(_ s: String) -> Bool {
    let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
    return (t.hasPrefix("http://") || t.hasPrefix("https://")) && !t.contains(" ") && !t.contains("\n")
}
