import SwiftUI

/// Voice input via the watch's native text-input controller (dictation + scribble +
/// emoji). The rough draft is sent through the broker's LLM cleanup (which applies the
/// voice glossary) before posting; on cleanup failure we send the raw draft.
struct VoiceInputView: View {
    let broker: WatchBrokerSession
    let sessionId: String

    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var sending = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(spacing: 10) {
            TextField("Dictate a message", text: $draft, axis: .vertical)
                .focused($focused)
                .submitLabel(.send)
                .onSubmit(send)

            if sending {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text("Cleaning…").font(.caption2).foregroundStyle(.secondary)
                }
            } else {
                Button("Send", action: send)
                    .buttonStyle(.borderedProminent)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding()
        .onAppear { focused = true }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !sending else { return }
        sending = true
        Task {
            let cleaned = (try? await broker.transcribeDraft(sessionId: sessionId, draft: text)) ?? text
            broker.send(sessionId, cleaned.isEmpty ? text : cleaned)
            dismiss()
        }
    }
}
