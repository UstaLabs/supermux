// apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift
import SwiftUI

/// Horizontal strip of staged-attachment chips, shared by both composers. Stateless: the
/// screen owns the `pending` array (on `ComposerModel`) and the remove/retry actions.
/// A chip shows a determinate ring while its upload is in flight, and a red "· Retry" state
/// on failure (tap to re-upload) — never a silent drop.
struct AttachmentTray: View {
    let pending: [PendingAttachment]
    let onRemove: (PendingAttachment) -> Void
    var onRetry: (PendingAttachment) -> Void = { _ in }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) { ForEach(pending) { chip($0) } }
        }
    }

    /// SF Symbol for a staged-attachment chip: waveform for audio, video for movies, else photo.
    private func chipIcon(_ mime: String) -> String {
        if mime.hasPrefix("audio") { return "waveform" }
        if mime.hasPrefix("video") { return "video" }
        return "photo"
    }

    @ViewBuilder private func chip(_ p: PendingAttachment) -> some View {
        if p.failed {
            HStack(spacing: 5) {
                Image(systemName: "exclamationmark.circle.fill").font(.caption2)
                Text("\(p.filename) · Retry").font(.caption2).lineLimit(1)
                Button { onRemove(p) } label: {
                    Image(systemName: "xmark.circle.fill").font(.caption2)
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(Color.red.opacity(0.16), in: Capsule())
            .foregroundStyle(.red)
            .contentShape(Capsule())
            .onTapGesture { onRetry(p) }
        } else {
            HStack(spacing: 5) {
                if p.uploading {
                    ProgressView(value: p.progress)
                        .progressViewStyle(.circular)
                        .controlSize(.small)
                } else {
                    Image(systemName: chipIcon(p.mime)).font(.caption2)
                }
                Text(p.filename).font(.caption2).lineLimit(1)
                if !p.uploading {
                    Button { onRemove(p) } label: {
                        Image(systemName: "xmark.circle.fill").font(.caption2)
                    }
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 5)
            .background(Color(.tertiarySystemFill), in: Capsule())
            .foregroundStyle(.secondary)
        }
    }
}
