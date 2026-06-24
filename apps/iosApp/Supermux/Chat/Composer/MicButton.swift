// apps/iosApp/Supermux/Chat/Composer/MicButton.swift
import SwiftUI

/// Mic / on-device-dictation entry button, shared by both composers. Shows the `micStarting`
/// spinner (first-run model download) and disables during transcription.
struct MicButton: View {
    let model: ComposerModel

    var body: some View {
        Button {
            Task { await model.toggleMic() }
        } label: {
            Group {
                if model.micStarting {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "mic").font(.body.weight(.medium)).foregroundStyle(.secondary)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .disabled(model.transcribing || model.micStarting)
    }
}
