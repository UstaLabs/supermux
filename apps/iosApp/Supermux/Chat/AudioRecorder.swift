import SwiftUI
import AVFoundation

/// Records a voice memo to a temporary AAC `.m4a` file and hands back the bytes.
/// Mirrors the PWA's MediaRecorder flow: audio is delivered as a *file* attachment
/// (kind "voice") — there is no transcription anywhere in the broker.
@Observable
@MainActor
final class AudioRecorder {
    private(set) var isRecording = false
    private(set) var elapsed: TimeInterval = 0

    private var recorder: AVAudioRecorder?
    private var url: URL?
    private var ticker: Task<Void, Never>?

    enum StartResult { case started, denied, failed }

    func start() async -> StartResult {
        guard await requestPermission() else { return .denied }
        return startGranted() ? .started : .failed
    }

    /// Begin recording with the permission ALREADY granted — everything `start()` does after its
    /// one asynchronous step.
    ///
    /// It exists because the Compose composer's `MicCapture.start()` is synchronous on every host:
    /// the recorder has to be running by the time the composer redraws itself as a RecordingBar,
    /// or the first word is lost. Nothing here needs to await — the audio session and
    /// `AVAudioRecorder.record()` are both synchronous — and the caller that has NOT yet asked for
    /// permission (`IosBridge.requestMicPermission`) asks first.
    @discardableResult
    func startGranted() -> Bool {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default)
            try session.setActive(true)
        } catch { return false }
        #endif
        // macOS: no audio session — AVAudioEngine drives the mic directly.

        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        guard let rec = try? AVAudioRecorder(url: file, settings: settings), rec.record() else {
            return false
        }
        recorder = rec
        url = file
        elapsed = 0
        isRecording = true
        startTicker()
        return true
    }

    /// Stop and return the recorded bytes + a friendly filename, or nil if the clip
    /// was too short / unreadable (the temp file is always cleaned up).
    func stop() -> (data: Data, filename: String)? {
        defer { cleanup() }
        guard let recorder, let url else { return nil }
        let duration = recorder.currentTime
        recorder.stop()
        guard duration >= 0.3, let data = try? Data(contentsOf: url) else { return nil }
        return (data, "voice-\(Int(duration.rounded()))s.m4a")
    }

    func cancel() {
        recorder?.stop()
        cleanup()
    }

    private func cleanup() {
        ticker?.cancel(); ticker = nil
        if let url { try? FileManager.default.removeItem(at: url) }
        recorder = nil; url = nil
        isRecording = false; elapsed = 0
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        // Hand the category back. `.playAndRecord` routes playback to the receiver rather than the
        // speaker and keeps the mic indicator alive, so leaving it set after a recording made the
        // NEXT read-aloud come out quiet and earpiece-shaped. Only matters now that the same
        // process both records and speaks (cluster H3).
        try? session.setCategory(.playback)
        #endif
        // macOS: no audio session — AVAudioEngine drives the mic directly.
    }

    private func startTicker() {
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 200_000_000)
                guard let self, let r = self.recorder, r.isRecording else { return }
                self.elapsed = r.currentTime
            }
        }
    }

    private func requestPermission() async -> Bool {
        await withCheckedContinuation { cont in
            AVAudioApplication.requestRecordPermission { granted in cont.resume(returning: granted) }
        }
    }
}
