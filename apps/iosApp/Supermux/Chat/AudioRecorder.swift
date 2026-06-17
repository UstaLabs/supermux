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
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default)
            try session.setActive(true)
        } catch { return .failed }

        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]
        guard let rec = try? AVAudioRecorder(url: file, settings: settings), rec.record() else {
            return .failed
        }
        recorder = rec
        url = file
        elapsed = 0
        isRecording = true
        startTicker()
        return .started
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
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
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

/// Compact "● Recording 0:07 … Cancel" bar shown in the composer while recording.
struct RecordingBar: View {
    let elapsed: TimeInterval
    var onCancel: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Circle().fill(.red).frame(width: 9, height: 9)
                .opacity(Int(elapsed * 2) % 2 == 0 ? 1 : 0.3)   // blink with the timer ticks
            Text("Recording \(formatRecordTime(elapsed))")
                .font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer(minLength: 0)
            Button(action: onCancel) {
                Text("Cancel").font(.caption.weight(.semibold)).foregroundStyle(.red)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Color(.tertiarySystemFill), in: Capsule())
    }
}

func formatRecordTime(_ t: TimeInterval) -> String {
    let s = Int(t)
    return String(format: "%d:%02d", s / 60, s % 60)
}
