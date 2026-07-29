import Foundation
import AVFoundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Process-wide AVSpeechSynthesizer for agent-message "Read aloud".
/// Only one message speaks at a time; [speakingKey] is the plain text currently spoken.
@MainActor
final class MessageSpeech: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = MessageSpeech()

    private let synth = AVSpeechSynthesizer()
    /// Monotonic generation so a cancelled/finished utterance can't clear a newer session.
    private var gen = 0
    private var activeGen = 0

    /// Plain text key of the utterance in flight, or nil when idle.
    @Published private(set) var speakingKey: String?

    private override init() {
        super.init()
        synth.delegate = self
    }

    func isSpeaking(_ key: String) -> Bool { speakingKey == key }

    func toggle(rawText: String) {
        let plain = Self.plainTextForSpeech(rawText)
        guard !plain.isEmpty else { return }
        if speakingKey == plain {
            stop()
            return
        }
        speak(plain)
    }

    func stop() {
        gen &+= 1
        activeGen = gen
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        speakingKey = nil
    }

    private func speak(_ plain: String) {
        gen &+= 1
        activeGen = gen
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        speakingKey = plain
        let u = AVSpeechUtterance(string: plain)
        u.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
            ?? AVSpeechSynthesisVoice(language: "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        synth.speak(u)
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.clearIfCurrent() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        // stop() already cleared; ignore cancel that follows an intentional stop.
        Task { @MainActor in self.clearIfCurrent() }
    }

    private func clearIfCurrent() {
        // Only clear if we still own the active generation (no newer toggle/stop).
        // When stop() bumps gen and clears, activeGen matches gen and speakingKey is already nil.
        // When utterance finishes naturally, activeGen still matches gen.
        if speakingKey != nil {
            speakingKey = nil
        }
    }

    /// Flatten markdown-ish agent text for TTS (mirrors web `plainTextForSpeech`).
    static func plainTextForSpeech(_ md: String) -> String {
        if md.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var s = md
        s = s.replacingOccurrences(of: #"```[\s\S]*?```"#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"`([^`]+)`"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"!\[([^\]]*)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\[([^\]]+)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        let lineStart: NSRegularExpression.Options = [.anchorsMatchLines]
        s = s.replacingOccurrences(of: #"^#{1,6}\s+"#, with: "", options: lineStart)
        s = s.replacingOccurrences(of: #"^\s*[-*+]\s+"#, with: "", options: lineStart)
        s = s.replacingOccurrences(of: #"^\s*\d+\.\s+"#, with: "", options: lineStart)
        s = s.replacingOccurrences(of: #"^\s*>\s?"#, with: "", options: lineStart)
        s = s.replacingOccurrences(of: #"(\*\*|__)(.*?)\1"#, with: "$2", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(\*|_)(.*?)\1"#, with: "$2", options: .regularExpression)
        s = s.replacingOccurrences(of: #"~~(.*?)~~"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\n{2,}"#, with: ". ", options: .regularExpression)
        s = s.replacingOccurrences(of: "\n", with: " ")
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?:\.\s*){2,}"#, with: ". ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

/// Copy + Read aloud under an agent reply (web/Android parity).
struct MessageMetaRow: View {
    let text: String
    @ObservedObject private var speech = MessageSpeech.shared
    @State private var copied = false

    private var speechKey: String { MessageSpeech.plainTextForSpeech(text) }
    private var speaking: Bool { speech.isSpeaking(speechKey) }

    var body: some View {
        HStack(spacing: 2) {
            metaButton(
                systemName: copied ? "checkmark" : "doc.on.doc",
                label: copied ? "Copied" : "Copy response",
                tinted: copied
            ) {
                #if canImport(UIKit)
                UIPasteboard.general.string = text
                #else
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(text, forType: .string)
                #endif
                copied = true
                Task {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    copied = false
                }
            }
            metaButton(
                systemName: speaking ? "stop.fill" : "speaker.wave.2",
                label: speaking ? "Stop reading" : "Read aloud",
                tinted: speaking
            ) {
                speech.toggle(rawText: text)
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 2)
    }

    @ViewBuilder
    private func metaButton(systemName: String, label: String, tinted: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(tinted ? Color.accentColor : Color.secondary.opacity(0.75))
                .frame(width: 28, height: 28)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}
