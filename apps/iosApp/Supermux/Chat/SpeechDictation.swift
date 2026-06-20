import SwiftUI
import AVFoundation
import Speech

/// On-device speech dictation for the chat composer.
///
/// Mirrors `AudioRecorder`'s shape (`@Observable @MainActor`, permission → start →
/// stop lifecycle, the shared `RecordingBar` UI) but instead of capturing a clip to
/// upload, it runs Apple's on-device speech recognizer and accumulates a live
/// `transcript`. Audio NEVER leaves the device for the on-device path.
///
/// If the device locale can't recognize on-device (`supportsOnDeviceRecognition`
/// is false, or auth is denied), `start()`/`stop()` signal `onDeviceUnavailable`
/// so the caller can fall back to recording audio + a multipart `/transcribe` POST
/// — we deliberately do NOT fall back to Apple's cloud recognition.
@Observable
@MainActor
final class SpeechDictation {
    enum Phase: Equatable { case idle, requesting, listening, finishing, error }

    private(set) var phase: Phase = .idle
    private(set) var transcript = ""
    private(set) var elapsed: TimeInterval = 0

    var isListening: Bool { phase == .listening }

    private let recognizer = SFSpeechRecognizer(locale: Locale.current)
    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var ticker: Task<Void, Never>?
    private var startedAt: Date?

    /// `.unavailable` → caller should fall back to audio upload (no cloud).
    enum StartResult { case started, denied, unavailable, failed }

    func start() async -> StartResult {
        // On-device recognition for the current locale is the hard requirement: if the
        // recognizer is missing or can't run on-device, bail to the audio-upload path.
        guard let recognizer, recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            phase = .idle
            return .unavailable
        }
        phase = .requesting
        guard await requestSpeechAuth() else { phase = .idle; return .denied }
        guard await requestMicPermission() else { phase = .idle; return .denied }

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            phase = .error
            return .failed
        }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = true   // never upload audio for this path
        request = req

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak req] buffer, _ in
            req?.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            request = nil
            deactivateSession()
            phase = .error
            return .failed
        }

        // The recognition callback hops to the main actor to mutate observable state.
        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.transcript = result.bestTranscription.formattedString
                }
                // On error / final result the task is done; we keep whatever transcript we
                // have. We don't flip to `.error` here because `stop()` owns the teardown
                // and the partials are still usable.
                if error != nil || (result?.isFinal ?? false) {
                    self.task = nil
                }
            }
        }

        startedAt = Date()
        elapsed = 0
        transcript = ""
        phase = .listening
        startTicker()
        return .started
    }

    /// Stop listening and return the accumulated transcript. `onDeviceUnavailable`
    /// is true only when `start()` never actually began an on-device session, so the
    /// caller can decide to fall back; on a normal stop it's false.
    func stop() async -> (text: String, onDeviceUnavailable: Bool) {
        guard phase == .listening else {
            let wasUnavailable = phase == .idle && transcript.isEmpty
            cleanup()
            return (transcript.trimmingCharacters(in: .whitespacesAndNewlines), wasUnavailable)
        }
        phase = .finishing
        // Stop feeding audio and tell the recognizer no more buffers are coming, then
        // give the recognizer a brief moment to emit the final transcription.
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        let deadline = Date().addingTimeInterval(1.2)
        while task != nil && Date() < deadline {
            try? await Task.sleep(nanoseconds: 60_000_000)
        }
        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        cleanup()
        return (text, false)
    }

    func cancel() {
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        cleanup()
    }

    private func cleanup() {
        ticker?.cancel(); ticker = nil
        task?.cancel(); task = nil
        request = nil
        startedAt = nil
        elapsed = 0
        phase = .idle
        deactivateSession()
    }

    private func deactivateSession() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func startTicker() {
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 200_000_000)
                guard let self, let started = self.startedAt else { return }
                self.elapsed = Date().timeIntervalSince(started)
            }
        }
    }

    private func requestSpeechAuth() async -> Bool {
        await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
    }

    private func requestMicPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in cont.resume(returning: granted) }
        }
    }

    deinit {
        // Backstop only — the UI always drives teardown via stop()/cancel() (same lifecycle
        // pattern as VncSession/TerminalSession, which have no deinit). Cancelling the
        // outstanding tasks here just guarantees no recognition/ticker work outlives us;
        // ARC releases `engine`/`request` and the engine stops when the input is released.
        ticker?.cancel()
        task?.cancel()
    }
}
