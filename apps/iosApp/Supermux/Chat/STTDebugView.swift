import SwiftUI

/// Minimal DEBUG screen for evaluating on-device (SpeechAnalyzer) STT: record →
/// show the RAW transcript + timing + the locale it actually used. Deliberately NOT
/// wired to the composer or the agent cleanup — purely to see on-device quality + speed.
struct STTDebugView: View {
    @State private var dictation = SpeechDictation()
    @State private var status = "Tap the mic to test on-device transcription"
    @State private var result = ""
    @State private var info = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(status)
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)

                // Live partial transcript while listening; the final result after stop.
                ScrollView {
                    Text(dictation.isListening ? (dictation.transcript.isEmpty ? "…" : dictation.transcript) : result)
                        .font(.body)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(12)
                .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

                if !info.isEmpty {
                    Text(info).font(.caption.monospaced()).foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Button {
                    Task { await toggle() }
                } label: {
                    Image(systemName: dictation.isListening ? "stop.fill" : "mic.fill")
                        .font(.title).foregroundStyle(.white)
                        .frame(width: 76, height: 76)
                        .background(dictation.isListening ? Color.red : Theme.teal, in: Circle())
                }
                .disabled(busy)

                if dictation.isListening {
                    Text(String(format: "Listening… %.1fs", dictation.elapsed))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding()
            .navigationTitle("On-device STT debug")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private func toggle() async {
        if dictation.isListening {
            busy = true
            status = "Finalizing…"
            let recorded = dictation.elapsed          // captured BEFORE stop() resets it
            let t0 = Date()
            let (text, unavailable) = await dictation.stop()
            let finalizeMs = Int(Date().timeIntervalSince(t0) * 1000)
            result = text.isEmpty ? "(nothing transcribed)" : text
            info = "recorded \(String(format: "%.1f", recorded))s · finalized \(finalizeMs)ms · locale \(dictation.usedLocale ?? "—")"
                + (unavailable ? " · on-device UNAVAILABLE" : "")
            status = "Done — tap the mic to try again"
            busy = false
            return
        }
        result = ""; info = ""
        switch await dictation.start() {
        case .started: status = "Listening — speak, then tap stop"
        case .denied: status = "Microphone/speech permission denied (enable in Settings)"
        case .downloading: status = "On-device language model is downloading — try again shortly"
        case .unavailable: status = "On-device STT unavailable for this device/locale"
        case .failed: status = "Failed to start on-device STT"
        }
    }
}
