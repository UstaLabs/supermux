// apps/iosApp/Supermux/Chat/Composer/AttachmentTray.swift
import SwiftUI

/// Horizontal strip of staged-attachment chips, shared by both composers. Stateless: the
/// screen owns the `pending` array (on `ComposerModel`) and the remove action.
struct AttachmentTray: View {
    let pending: [PendingAttachment]
    let onRemove: (PendingAttachment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) { ForEach(pending) { chip($0) } }
        }
    }

    private func chip(_ p: PendingAttachment) -> some View {
        HStack(spacing: 5) {
            Image(systemName: p.mime.hasPrefix("audio") ? "waveform" : "photo").font(.caption2)
            Text(p.filename).font(.caption2).lineLimit(1)
            Button { onRemove(p) } label: {
                Image(systemName: "xmark.circle.fill").font(.caption2)
            }
        }
        .padding(.horizontal, 8).padding(.vertical, 5)
        .background(Color(.tertiarySystemFill), in: Capsule())
        .foregroundStyle(.secondary)
    }
}
