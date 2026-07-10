// apps/iosApp/Supermux/Chat/Composer/AttachMenu.swift
import SwiftUI

/// The "+" attachment menu (Photos / Files / Camera), shared by both composers. Stateless: it
/// flips the screen-owned picker-presentation bindings; the screen wires the actual
/// `.photosPicker` / `.fileImporter` / `.fullScreenCover` modifiers.
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
            #if os(iOS)
            // The Mac offers no camera capture (CameraPicker + its covers are iOS-only).
            Button { showCamera = true } label: { Label("Camera", systemImage: "camera") }
            Button { showVideoCamera = true } label: { Label("Record video", systemImage: "video") }
            #endif
        } label: {
            Image(systemName: "plus")
                .font(.body.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .smMacBorderlessMenu()
    }
}
