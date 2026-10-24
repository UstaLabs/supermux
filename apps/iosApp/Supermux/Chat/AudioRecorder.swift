import SwiftUI
import AVFoundation

/// Records dictation to a temporary 16 kHz mono 16-bit `.wav` and hands back the bytes.
///
/// WAV rather than AAC `.m4a` because the live dictation passes send the audio SO FAR at every
/// pause, and an MPEG-4 file is unreadable until the recorder writes its index at stop. A WAV only
/// needs its two length fields patched ([snapshot]). Metering is on for the pause detection.
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
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .default)
            try session.setActive(true)
        } catch { return false }

        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString).wav")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16_000,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
        ]
        guard let rec = try? AVAudioRecorder(url: file, settings: settings) else { return false }
        rec.isMeteringEnabled = true
        guard rec.record() else { return false }
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
        return (data, "voice-\(Int(duration.rounded()))s.wav")
    }

    /// The recording so far as a decodable WAV, without stopping. Mid-recording the file's RIFF
    /// and `data` lengths are still placeholders, so both are patched to what is on disk now.
    func snapshot() -> Data? {
        guard let recorder, recorder.isRecording, let url, var data = try? Data(contentsOf: url) else { return nil }
        guard data.count > 44, let dataChunk = Self.chunkOffset("data", in: data) else { return nil }
        Self.putLE32(UInt32(data.count - 8), at: 4, in: &data)
        Self.putLE32(UInt32(data.count - dataChunk - 8), at: dataChunk + 4, in: &data)
        return data
    }

    /// Input loudness, 0...1 (the meter's average power, dB → linear amplitude).
    func level() -> Float {
        guard let recorder, recorder.isRecording else { return 0 }
        recorder.updateMeters()
        return powf(10, recorder.averagePower(forChannel: 0) / 20)
    }

    /// Byte offset of the RIFF sub-chunk [id] (Apple writes a `FLLR` pad before `data`).
    private static func chunkOffset(_ id: String, in data: Data) -> Int? {
        let tag = Array(id.utf8)
        var i = 12
        while i + 8 <= data.count {
            let size = Int(data[i + 4]) | Int(data[i + 5]) << 8 | Int(data[i + 6]) << 16 | Int(data[i + 7]) << 24
            if Array(data[i..<(i + 4)]) == tag { return i }
            i += 8 + size + (size & 1)
        }
        return nil
    }

    private static func putLE32(_ v: UInt32, at offset: Int, in data: inout Data) {
        for k in 0..<4 { data[offset + k] = UInt8((v >> (8 * UInt32(k))) & 0xff) }
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
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        // Hand the category back. `.playAndRecord` routes playback to the receiver rather than the
        // speaker and keeps the mic indicator alive, so leaving it set after a recording made the
        // NEXT read-aloud come out quiet and earpiece-shaped. Only matters now that the same
        // process both records and speaks (cluster H3).
        try? session.setCategory(.playback)
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
        await MicPermission.request()
    }
}

/// The microphone grant across the deployment range. `AVAudioApplication` is iOS 17+; below it the
/// same grant lives on `AVAudioSession`, which iOS 17 deprecated — so each side of the
/// `#available` uses the API its OS considers current.
enum MicPermission {
    /// False only once the user has refused; granted and not-yet-asked are both true.
    static var notDenied: Bool {
        if #available(iOS 17.0, *) {
            return AVAudioApplication.shared.recordPermission != .denied
        }
        return AVAudioSession.sharedInstance().recordPermission != .denied
    }

    static func request(_ completion: @escaping (Bool) -> Void) {
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: completion)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(completion)
        }
    }

    static func request() async -> Bool {
        await withCheckedContinuation { cont in
            request { granted in cont.resume(returning: granted) }
        }
    }
}
