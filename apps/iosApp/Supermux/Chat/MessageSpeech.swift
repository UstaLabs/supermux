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

    // MARK: - Compose shell (cluster H3)
    //
    // The three members below are the LOW half of this class, exposed for the Compose composer.
    // Everything above — deciding which engine to use, keeping `speakingKey`, queueing the codex
    // chunks — is duplicated on the Kotlin side by `MessageTts` in `:ui`, which owns that state
    // for all three hosts. So the bridge takes only the noise-making: speak this, play this chunk,
    // be quiet.
    //
    // They live on `MessageSpeech.shared` rather than in a new class so there is exactly ONE
    // `AVSpeechSynthesizer` and one audio player in the process. Two would talk over each other,
    // and `stop()` on one could not silence the other.

    /// The completion waiting on whatever is making noise right now — an utterance or an audio
    /// chunk. Fired on finish, on cancel, and by `stop()`, exactly once.
    ///
    /// ONE slot for both, because `stop()` has to release either of them and only one can be
    /// playing at a time. This matters most for the chunk path: `AVAudioPlayer.stop()` does NOT
    /// call `audioPlayerDidFinishPlaying`, so a silenced chunk would otherwise leave the Kotlin
    /// side awaiting a callback that is never coming — read-aloud stuck mid-message with no way
    /// to start it again.
    private var pendingDone: (() -> Void)?

    /// Which armed completion [pendingDone] is, so a LATE callback from a superseded utterance or
    /// chunk cannot fire the completion belonging to the one that replaced it.
    ///
    /// This is not hypothetical. `MessageTts.speakPlatform` calls `stop()` immediately before
    /// every `speak()`, and `AVSpeechSynthesizer.stopSpeaking(at: .immediate)` delivers its
    /// `didCancel` ASYNCHRONOUSLY — after the next utterance has already been armed. Without the
    /// tag, reading one message aloud while another was speaking finished the NEW one instantly:
    /// the row left its speaking state while the audio played on, and the next tap could not stop
    /// it because Kotlin already believed that utterance was over.
    private var pendingGen = 0

    /// The utterance [pendingDone] belongs to, for the same reason — identity is what tells a
    /// delegate callback whether it is about the CURRENT utterance or a cancelled predecessor.
    private var currentUtterance: AVSpeechUtterance?

    /// Speak with the OS synthesiser. `onDone` fires when the utterance completes OR is stopped.
    ///
    /// Order matters: the previous utterance is stopped and its waiter released BEFORE the new one
    /// is armed, so the `didCancel` that arrives asynchronously afterwards finds a generation that
    /// no longer matches and does nothing.
    func speakText(_ text: String, onDone: @escaping () -> Void) {
        guard !text.isEmpty else { return onDone() }
        gen &+= 1
        if synth.isSpeaking { synth.stopSpeaking(at: .immediate) }
        audioPlayer?.stop()
        audioPlayer = nil
        finishPending()
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: Locale.current.identifier)
            ?? AVSpeechSynthesisVoice(language: "en-US")
        u.rate = AVSpeechUtteranceDefaultSpeechRate
        arm(onDone)
        currentUtterance = u
        synth.speak(u)
    }

    /// Play one mp3 chunk from the broker's `/speak` stream, calling back when it has played out
    /// so Kotlin can queue the next one gaplessly. A chunk that will not decode calls back at
    /// once rather than stalling the queue.
    func playChunk(_ data: Data, onDone: @escaping () -> Void) {
        finishPending()
        guard let player = try? AVAudioPlayer(data: data) else { return onDone() }
        audioPlayer = player
        let myGen = arm(onDone)
        let box = FinishBox { [weak self] in
            Task { @MainActor in self?.finishPending(gen: myGen) }
        }
        finishBox = box
        player.delegate = box
        if !player.play() { finishPending(gen: myGen) }
    }

    /// Release the synthesiser and the audio session — the app is going away.
    func shutdownEngine() {
        stop()
    }

    /// Arm a completion and return the generation that identifies it.
    @discardableResult
    private func arm(_ onDone: @escaping () -> Void) -> Int {
        pendingGen &+= 1
        pendingDone = onDone
        return pendingGen
    }

    /// Fire the pending completion, at most once.
    ///
    /// [gen] is the generation the CALLER armed. A late callback from a superseded utterance or
    /// chunk passes its own and is ignored; `stop()` and the "make way for the next one" paths
    /// pass nothing, meaning "whatever is armed right now".
    private func finishPending(gen: Int? = nil) {
        if let gen, gen != pendingGen { return }
        let done = pendingDone
        pendingDone = nil
        currentUtterance = nil
        done?()
    }

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
        // A Compose-side `speak` awaiting this utterance must be released, or the message it
        // belongs to keeps its speaking state after the user has silenced it.
        finishPending()
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
        // Registered for the same reason the Compose path registers: `utteranceEnded` clears
        // `speakingKey` only for the utterance that is actually current.
        currentUtterance = u
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
        Task { @MainActor in self.utteranceEnded(utterance) }
    }

    nonisolated func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        Task { @MainActor in self.utteranceEnded(utterance) }
    }

    /// One utterance stopped making noise — finished, or cancelled to make way for another.
    ///
    /// The identity check is what keeps a cancelled predecessor from clearing the state of the
    /// utterance that replaced it: `stopSpeaking(at: .immediate)` delivers `didCancel` after the
    /// next `speak()` has already been issued.
    private func utteranceEnded(_ utterance: AVSpeechUtterance) {
        guard utterance === currentUtterance else { return }
        if speakingKey != nil { speakingKey = nil }
        finishPending()
    }

    static func plainTextForSpeech(_ md: String) -> String {
        if md.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        var s = md
        s = s.replacingOccurrences(of: #"```[\s\S]*?```"#, with: " ", options: .regularExpression)
        s = s.replacingOccurrences(of: #"`([^`]+)`"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"!\[([^\]]*)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        s = s.replacingOccurrences(of: #"\[([^\]]+)\]\([^)]*\)"#, with: "$1", options: .regularExpression)
        // (?m) = multiline so ^ matches each line start. String.CompareOptions has no
        // anchorsMatchLines (that's NSRegularExpression.Options only).
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
