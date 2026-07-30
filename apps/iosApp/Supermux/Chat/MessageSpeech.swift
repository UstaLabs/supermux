import Foundation
import AVFoundation
import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// Process-wide read-aloud: AVSpeechSynthesizer (platform) or ChatGPT via broker /speak.
@MainActor
final class MessageSpeech: NSObject, ObservableObject, AVSpeechSynthesizerDelegate {
    static let shared = MessageSpeech()

    private let synth = AVSpeechSynthesizer()
    private var gen = 0
    private var audioPlayer: AVAudioPlayer?

    @Published private(set) var speakingKey: String?

    private override init() {
        super.init()
        synth.delegate = self
    }

    func isSpeaking(_ key: String) -> Bool { speakingKey == key }

    func toggle(rawText: String, broker: BrokerSession?) {
        let plain = Self.plainTextForSpeech(rawText)
        guard !plain.isEmpty else { return }
        if speakingKey == plain {
            stop()
            return
        }
        Task {
            let engine = await Self.resolveEngine(broker: broker)
            if engine == "codex", let broker {
                await speakCodex(rawText: rawText, plain: plain, broker: broker)
            } else {
                speakPlatform(plain)
            }
        }
    }

    func stop() {
        gen &+= 1
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        audioPlayer?.stop()
        audioPlayer = nil
        speakingKey = nil
    }

    private static func resolveEngine(broker: BrokerSession?) async -> String {
        guard let broker else { return "platform" }
        let cfg = await broker.config()
        let e = cfg?.voiceTtsEngine ?? ""
        return e.isEmpty ? "platform" : e
    }

    private func speakPlatform(_ plain: String) {
        gen &+= 1
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        audioPlayer?.stop()
        audioPlayer = nil
        speakingKey = plain
        let u = AVSpeechUtterance(string: plain)
        u.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
            ?? AVSpeechSynthesisVoice(language: "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        synth.speak(u)
    }

    private func speakCodex(rawText: String, plain: String, broker: BrokerSession) async {
        gen &+= 1
        let myGen = gen
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        audioPlayer?.stop()
        audioPlayer = nil
        speakingKey = plain

        // Collect chunks as they arrive; play each to completion before the next,
        // starting as soon as the first piece is ready (stream continues in parallel).
        actor ChunkQueue {
            private var items: [Data] = []
            private var cont: CheckedContinuation<Data?, Never>?
            private var closed = false

            func push(_ d: Data) {
                if let c = cont {
                    cont = nil
                    c.resume(returning: d)
                } else {
                    items.append(d)
                }
            }

            func close() {
                closed = true
                if let c = cont {
                    cont = nil
                    c.resume(returning: nil)
                }
            }

            func next() async -> Data? {
                if !items.isEmpty { return items.removeFirst() }
                if closed { return nil }
                return await withCheckedContinuation { (c: CheckedContinuation<Data?, Never>) in
                    cont = c
                }
            }
        }

        let queue = ChunkQueue()
        let streamTask = Task {
            let ok = await broker.speakStream(rawText, engine: "codex") { data in
                Task { await queue.push(data) }
            }
            _ = ok
            await queue.close()
        }

        while true {
            guard gen == myGen else {
                streamTask.cancel()
                break
            }
            guard let data = await queue.next() else { break }
            guard gen == myGen else { break }
            await playDataAndWait(data, myGen: myGen)
        }
        if gen == myGen { speakingKey = nil }
    }

    private var finishBox: FinishBox?

    private func playDataAndWait(_ data: Data, myGen: Int) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            do {
                let player = try AVAudioPlayer(data: data)
                audioPlayer = player
                let box = FinishBox {
                    cont.resume()
                }
                finishBox = box
                player.delegate = box
                if !player.play() {
                    cont.resume()
                }
            } catch {
                cont.resume()
            }
        }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        Task { @MainActor in self.clearIfCurrent() }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.clearIfCurrent() }
    }

    private func clearIfCurrent() {
        if speakingKey != nil { speakingKey = nil }
    }

    static func plainTextForSpeech(_ md: String) -> String {
        if md.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var s = md
        s = s.replacingOccurrences(of: #"```[\s\S]*?```"#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"`([^`]+)`"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"!\[([^\]]*)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\[([^\]]+)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?m)^#{1,6}\s+"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?m)^\s*[-*+]\s+"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?m)^\s*\d+\.\s+"#, with: "", options: .regularExpression)
        s = s.replacingOccurrences(of: #"(?m)^\s*>\s?"#, with: "", options: .regularExpression)
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

/// AVAudioPlayerDelegate that resumes a single continuation when playback ends.
private final class FinishBox: NSObject, AVAudioPlayerDelegate {
    private let onFinish: () -> Void
    private var done = false
    init(_ onFinish: @escaping () -> Void) { self.onFinish = onFinish }
    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard !done else { return }
        done = true
        onFinish()
    }
    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        guard !done else { return }
        done = true
        onFinish()
    }
}

/// Copy + Read aloud under an agent reply (web/Android parity).
struct MessageMetaRow: View {
    let text: String
    var broker: BrokerSession?
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
                speech.toggle(rawText: text, broker: broker)
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
