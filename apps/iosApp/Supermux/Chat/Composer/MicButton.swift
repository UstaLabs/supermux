// apps/iosApp/Supermux/Chat/Composer/MicButton.swift
import SwiftUI

/// Mic / dictation entry button, shared by both composers. Shows a spinner while the
/// first-run speech model is preparing *or* while a recorded clip is being transcribed
/// (broker STT can take tens of seconds), and disables re-entry during both.
struct MicButton: View {
    let model: ComposerModel

    var body: some View {
        Button {
            Task { await model.toggleMic() }
        } label: {
            Group {
                if model.micStarting || model.transcribing {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "mic").font(.body.weight(.medium)).foregroundStyle(.secondary)
                }
            }
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .smMacPlainButton()
        .disabled(model.transcribing || model.micStarting)
        .accessibilityLabel(model.transcribing ? "Transcribing" : (model.micStarting ? "Preparing speech" : "Dictate"))
    }
}
