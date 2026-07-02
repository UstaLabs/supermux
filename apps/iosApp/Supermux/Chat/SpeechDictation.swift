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
/// ## Engine
/// On iOS 26+ this uses the new `SpeechAnalyzer` / `SpeechTranscriber` stack, which is
/// dramatically more accurate than `SFSpeechRecognizer` (the legacy engine garbled
/// off-locale speech into phonetic gibberish). iOS 25 and earlier fall back to
/// `SFSpeechRecognizer`.
///
/// ## Contextual hints (voice glossary)
/// `start(contextualStrings:)` biases recognition toward domain/proper-noun terms (e.g.
/// "Supermux", "Codex"). BOTH engines support this: iOS 26 `SpeechAnalyzer` via
/// `AnalysisContext.contextualStrings[.general]` (set on the analyzer before it starts),
/// and the legacy path via `SFSpeechAudioBufferRecognitionRequest.contextualStrings`.
///
/// ## Language — DEVICE-DRIVEN, nothing hardcoded
/// The candidate language(s) come entirely from the **device**: `Locale.preferredLanguages`
/// is the ordered list of languages the user configured in iOS Settings. We intersect that
/// with what `SpeechTranscriber` actually supports (`supportedLocale(equivalentTo:)`) and:
///   • seed the transcriber with the user's **primary** preferred supported language, and
///   • install/reserve the on-device model assets for *all* of the user's preferred
///     supported languages (so switching the device language is instant, no re-download).
/// There is no literal language code anywhere — a Turkish-first user gets the Turkish model,
/// an English-first user gets English, a German-first user gets German, etc., purely from
/// their own iOS language order.
///
/// NOTE ON AUTO-DETECTION: as of iOS 26.5, a `SpeechTranscriber` binds to a single fixed
/// `Locale` — the on-device Speech stack exposes **no runtime spoken-language detection**
/// (verified against the iOS 26.5 SDK + Apple docs / WWDC25 session 277; "automatic language
/// management" there refers to automatic *model download*, not detection). So we transcribe
/// in the user's primary configured language. The asset reservation keeps the other
/// preferred languages' models resident so a future SDK that adds detection — or a manual
/// language switch — needs no download.
///
/// If on-device recognition is unavailable (no preferred language supported, auth denied, or
/// — on iOS 26 — the language asset is still downloading on first use) `start()` returns
/// `.unavailable`/`.downloading` so the caller can fall back to recording audio + a multipart
/// `/transcribe` POST. We deliberately do NOT fall back to Apple's cloud recognition.
@Observable
@MainActor
final class SpeechDictation {
    enum Phase: Equatable { case idle, requesting, listening, finishing, error }

    private(set) var phase: Phase = .idle
    private(set) var transcript = ""
    private(set) var elapsed: TimeInterval = 0
    private(set) var usedLocale: String?   // the on-device locale actually chosen (debug)
    private(set) var lastError: String?    // last failure reason, surfaced for the debug screen

    var isListening: Bool { phase == .listening }

    /// `.unavailable` → caller should fall back to audio upload (no cloud). `.downloading`
    /// → the on-device language model is still installing; the caller can fall back this
    /// time and it'll be ready next time.
    enum StartResult { case started, denied, unavailable, downloading, failed }

    private let engine = AVAudioEngine()
    private var ticker: Task<Void, Never>?
    private var startedAt: Date?

    // MARK: iOS 26 SpeechAnalyzer backend (created per session, torn down on stop).
    private var analyzerBox: Any?   // SpeechAnalyzerBackend, boxed to stay <iOS26-compilable.

    // MARK: legacy SFSpeechRecognizer backend (<iOS 26).
    private let legacyRecognizer = SFSpeechRecognizer(locale: Locale.current)
    private var legacyRequest: SFSpeechAudioBufferRecognitionRequest?
    private var legacyTask: SFSpeechRecognitionTask?

    /// `contextualStrings` are domain/proper-noun hints (the voice glossary — e.g.
    /// "Supermux", "Codex") the recognizer is biased toward so it spells them right at the
    /// source. Applied on BOTH engines: iOS 26 `SpeechAnalyzer` via `AnalysisContext`
    /// (`.contextualStrings[.general]`), and legacy via
    /// `SFSpeechAudioBufferRecognitionRequest.contextualStrings`.
    func start(contextualStrings: [String] = []) async -> StartResult {
        phase = .requesting
        guard await requestMicPermission() else { phase = .idle; return .denied }

        if #available(iOS 26.0, macOS 26.0, *) {
            return await startModern(contextualStrings: contextualStrings)
        } else {
            return await startLegacy(contextualStrings: contextualStrings)
        }
    }

    /// Stop listening and return the accumulated transcript. `onDeviceUnavailable`
    /// is true only when `start()` never actually began an on-device session, so the
    /// caller can decide to fall back; on a normal stop it's false.
    func stop() async -> (text: String, onDeviceUnavailable: Bool) {
        guard phase == .listening else {
            let wasUnavailable = phase == .idle && transcript.isEmpty
            await cleanup()
            return (transcript.trimmingCharacters(in: .whitespacesAndNewlines), wasUnavailable)
        }
        phase = .finishing

        // Stop feeding audio.
        if engine.isRunning { engine.stop() }
        engine.inputNode.removeTap(onBus: 0)

        if #available(iOS 26.0, macOS 26.0, *), let backend = analyzerBox as? SpeechAnalyzerBackend {
            // Drain the analyzer so any volatile result is finalized, then read it.
            await backend.finish()
            transcript = backend.transcript
        } else {
            // Legacy: tell the recognizer no more buffers are coming and let it emit the
            // final transcription.
            legacyRequest?.endAudio()
            let deadline = Date().addingTimeInterval(1.2)
            while legacyTask != nil && Date() < deadline {
                try? await Task.sleep(nanoseconds: 60_000_000)
            }
        }

        let text = transcript.trimmingCharacters(in: .whitespacesAndNewlines)
        await cleanup()
        return (text, false)
    }

    func cancel() {
        if engine.isRunning {
            engine.stop()
            engine.inputNode.removeTap(onBus: 0)
        }
        if #available(iOS 26.0, macOS 26.0, *), let backend = analyzerBox as? SpeechAnalyzerBackend {
            backend.cancel()
        }
        Task { await cleanup() }
    }

    private func cleanup() async {
        ticker?.cancel(); ticker = nil
        // Modern: release the analyzer/transcriber so we don't hit "Maximum number of
        // recognizers reached" after a few sessions.
        if #available(iOS 26.0, macOS 26.0, *), let backend = analyzerBox as? SpeechAnalyzerBackend {
            await backend.teardown()
        }
        analyzerBox = nil
        legacyTask?.cancel(); legacyTask = nil
        legacyRequest = nil
        startedAt = nil
        elapsed = 0
        phase = .idle
        deactivateSession()
    }

    // MARK: - Modern (iOS 26 SpeechAnalyzer)

    @available(iOS 26.0, macOS 26.0, *)
    private func startModern(contextualStrings: [String]) async -> StartResult {
        guard SpeechTranscriber.isAvailable else { phase = .idle; return .unavailable }
        guard await requestSpeechAuth() else { phase = .idle; return .denied }

        // Choose the language purely from the device: the user's ordered preferred
        // languages, mapped to what the transcriber supports. The first (primary) seeds
        // recognition; the rest are kept installed/reserved. Nothing is hardcoded.
        let plan = await SpeechAnalyzerBackend.localePlan()
        guard let primary = plan.primary else {
            phase = .idle
            return .unavailable
        }
        usedLocale = "\(primary)"

        let backend = SpeechAnalyzerBackend(primary: primary, preferred: plan.preferred,
                                            contextualStrings: contextualStrings)
        // Ensure the language model is installed/reserved. On first use this may need a
        // download; if it's not ready, surface `.downloading` so the caller can fall back
        // this time rather than getting an empty transcript. `prepare()` tears down its own
        // analyzer/transcriber on every non-ready path, so nothing leaks against the limit.
        switch await backend.prepare() {
        case .ready: break
        case .downloading: phase = .idle; return .downloading
        case .unsupported: phase = .idle; lastError = backend.lastError; return .unavailable
        case .failed: phase = .idle; lastError = backend.lastError ?? "on-device setup failed"; return .failed
        }

        if !configureAudioSession() {
            await backend.teardown()
            phase = .error
            return .failed
        }

        // Wire the mic tap into the analyzer's input format (converting as needed).
        let input = engine.inputNode
        let tapFormat = input.outputFormat(forBus: 0)
        // Guard against an invalid hardware format (0 Hz / 0 channels): installTap with such a
        // format is an uncatchable AVAudioEngine assertion crash, which can occur right after a
        // fresh mic-permission grant before the input route settles. Bail to the audio fallback.
        guard tapFormat.sampleRate > 0, tapFormat.channelCount > 0 else {
            deactivateSession()
            await backend.teardown()
            phase = .idle
            return .unavailable
        }
        input.installTap(onBus: 0, bufferSize: 4096, format: tapFormat) { [backend] buffer, _ in
            backend.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            deactivateSession()
            await backend.teardown()
            phase = .error
            return .failed
        }

        backend.onUpdate = { [weak self] text in
            Task { @MainActor in self?.transcript = text }
        }
        analyzerBox = backend

        beginSession()
        return .started
    }

    // MARK: - Legacy (<iOS 26, SFSpeechRecognizer)

    private func startLegacy(contextualStrings: [String]) async -> StartResult {
        guard let recognizer = legacyRecognizer, recognizer.isAvailable,
              recognizer.supportsOnDeviceRecognition else {
            phase = .idle
            return .unavailable
        }
        guard await requestSpeechAuth() else { phase = .idle; return .denied }
        if !configureAudioSession() { phase = .error; return .failed }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        req.requiresOnDeviceRecognition = true   // never upload audio for this path
        // Bias recognition toward the glossary (project/technical terms).
        if !contextualStrings.isEmpty { req.contextualStrings = contextualStrings }
        legacyRequest = req

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            legacyRequest = nil
            deactivateSession()
            phase = .idle
            return .unavailable
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak req] buffer, _ in
            req?.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            legacyRequest = nil
            deactivateSession()
            phase = .error
            return .failed
        }

        legacyTask = recognizer.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor in
                guard let self else { return }
                if let result {
                    self.transcript = result.bestTranscription.formattedString
                }
                if error != nil || (result?.isFinal ?? false) {
                    self.legacyTask = nil
                }
            }
        }

        beginSession()
        return .started
    }

    // MARK: - Shared helpers

    private func configureAudioSession() -> Bool {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            return true
        } catch {
            return false
        }
        #else
        // macOS: no audio session — AVAudioEngine drives the mic directly.
        return true
        #endif
    }

    private func beginSession() {
        startedAt = Date()
        elapsed = 0
        transcript = ""
        phase = .listening
        startTicker()
    }

    private func deactivateSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
        // macOS: no audio session — AVAudioEngine drives the mic directly.
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
}

// MARK: - SpeechAnalyzer backend (iOS 26+)

/// Wraps the iOS 26 `SpeechAnalyzer` + `SpeechTranscriber` lifecycle: model installation,
/// the mic→analyzer `AsyncStream<AnalyzerInput>` pump, results consumption, and teardown.
/// Kept in its own `@available` class so the rest of the file still compiles on older SDKs.
@available(iOS 26.0, macOS 26.0, *)
@MainActor
final class SpeechAnalyzerBackend {
    enum PrepareResult { case ready, downloading, unsupported, failed }

    /// The device-derived locales: `primary` seeds the transcriber, `preferred` (which
    /// includes `primary`) is the full set whose assets we keep installed/reserved.
    let primary: Locale
    let preferred: [Locale]
    /// Voice-glossary terms biased into recognition via the analyzer's `AnalysisContext`.
    let contextualStrings: [String]
    var onUpdate: ((String) -> Void)?
    private(set) var transcript = ""
    private(set) var lastError: String?   // last on-device setup/download failure (debug)

    private var transcriber: SpeechTranscriber?
    private var analyzer: SpeechAnalyzer?
    private var analyzerFormat: AVAudioFormat?
    private let converter = DictationBufferConverter()
    private var inputBuilder: AsyncStream<AnalyzerInput>.Continuation?
    private var resultsTask: Task<Void, Never>?

    private var finalized: AttributedString = ""

    init(primary: Locale, preferred: [Locale], contextualStrings: [String] = []) {
        self.primary = primary
        self.preferred = preferred
        self.contextualStrings = contextualStrings
    }

    /// The device-driven language plan, computed at runtime from `Locale.preferredLanguages`
    /// (the user's configured language order) intersected with what `SpeechTranscriber`
    /// supports. NOTHING is hardcoded. `primary` is the first preferred language with a
    /// supported model (what we transcribe in); `preferred` is every preferred language with
    /// a supported model, in order (assets for all are kept installed). `primary == nil`
    /// means none of the user's languages are supported on-device → caller falls back.
    static func localePlan() async -> (primary: Locale?, preferred: [Locale]) {
        let supported = await SpeechTranscriber.supportedLocales
        guard !supported.isEmpty else { return (nil, []) }

        // Walk the user's ordered preferred languages; keep each that has a supported
        // equivalent (Apple requires the *supported* Locale value — a raw Locale may fail
        // allocation due to identifier-format mismatches). Append the current locale as a
        // last-resort candidate so a user with an unusual preferred list still gets a model.
        var candidates = Locale.preferredLanguages.map { Locale(identifier: $0) }
        candidates.append(Locale.current)

        var resolved: [Locale] = []
        var seen = Set<String>()
        for candidate in candidates {
            guard let match = await SpeechTranscriber.supportedLocale(equivalentTo: candidate) else { continue }
            let key = match.identifier(.bcp47)
            if seen.insert(key).inserted { resolved.append(match) }
        }
        // If the user's languages map to nothing supported, fall back to any supported
        // locale so dictation still works (device-bounded, still not a hardcoded language).
        if resolved.isEmpty, let any = supported.first { resolved = [any] }
        return (resolved.first, resolved)
    }

    /// Create the transcriber/analyzer, ensure the model is installed, and start the
    /// results pump. Must be called before `append`. On ANY non-`.ready` outcome this tears
    /// down the analyzer/transcriber it created, so an un-started analyzer never leaks
    /// against the per-app recognizer limit.
    func prepare() async -> PrepareResult {
        let transcriber = SpeechTranscriber(
            locale: primary,
            transcriptionOptions: [],
            reportingOptions: [.volatileResults],
            attributeOptions: [])
        self.transcriber = transcriber
        self.analyzer = SpeechAnalyzer(modules: [transcriber])

        // Install/allocate the language model. If a download is needed and we can't get it
        // ready synchronously, report `.downloading` so the caller falls back this time.
        do {
            if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                // Asset isn't installed yet — DOWNLOAD IT (awaited). The old code fired this
                // off detached with `try?`, which swallowed any failure and stuck on
                // "installing" forever. Awaiting installs the model for real and surfaces the
                // real error; on success we fall through to reserve + start.
                do {
                    try await request.downloadAndInstall()
                } catch {
                    lastError = "model download failed: \(error)"
                    await teardown()
                    return .failed
                }
                await Self.installPreferredAssets(self.preferred)
            }
        } catch {
            lastError = "asset request failed: \(error)"
            await teardown()
            return .failed
        }

        // Reserve the primary locale so its model is allocated for our use. Over the per-app
        // limit, release the others first. (Also reserve the other preferred languages best-
        // effort so they stay resident.)
        do {
            try await reserve(locale: primary)
        } catch {
            await teardown()
            return .failed
        }
        await reservePreferred()

        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            await teardown()
            return .unsupported
        }
        self.analyzerFormat = format

        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        self.inputBuilder = continuation

        // Consume results: accumulate finalized text, show volatile (partial) text live.
        resultsTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await result in transcriber.results {
                    let text = result.text
                    if result.isFinal {
                        self.finalized += text
                        self.publish(volatile: nil)
                    } else {
                        self.publish(volatile: text)
                    }
                }
            } catch {
                // Stream ended/failed; whatever we accumulated stands.
            }
        }

        // Bias recognition toward the voice glossary (project/technical terms). iOS 26's
        // SpeechAnalyzer DOES support contextual strings — via `AnalysisContext`
        // (`.contextualStrings[.general]`), set on the analyzer before it starts. (This is
        // the modern equivalent of the legacy `SFSpeechAudioBufferRecognitionRequest
        // .contextualStrings`.) Best-effort: a failure here must not abort dictation.
        if !contextualStrings.isEmpty {
            let context = AnalysisContext()
            context.contextualStrings = [.general: contextualStrings]
            try? await analyzer?.setContext(context)
        }

        do {
            try await analyzer?.start(inputSequence: stream)
        } catch {
            await teardown()
            return .failed
        }
        return .ready
    }

    /// Feed a mic buffer (converted to the analyzer's format) into the analyzer.
    nonisolated func append(_ buffer: AVAudioPCMBuffer) {
        Task { @MainActor in
            guard let format = self.analyzerFormat, let builder = self.inputBuilder else { return }
            guard let converted = try? self.converter.convert(buffer, to: format) else { return }
            builder.yield(AnalyzerInput(buffer: converted))
        }
    }

    /// Finalize: stop accepting audio and drain any remaining/volatile results.
    func finish() async {
        inputBuilder?.finish()
        try? await analyzer?.finalizeAndFinishThroughEndOfInput()
        // Give the results loop a beat to flush the final result.
        try? await Task.sleep(nanoseconds: 150_000_000)
        publish(volatile: nil)
    }

    func cancel() {
        inputBuilder?.finish()
        Task { await analyzer?.cancelAndFinishNow() }
    }

    /// Release the analyzer/transcriber. Safe to call multiple times.
    func teardown() async {
        resultsTask?.cancel(); resultsTask = nil
        inputBuilder?.finish(); inputBuilder = nil
        await analyzer?.cancelAndFinishNow()
        analyzer = nil
        transcriber = nil
    }

    // MARK: helpers

    private func publish(volatile: AttributedString?) {
        var combined = finalized
        if let volatile { combined += volatile }
        let text = String(combined.characters)
        transcript = text
        onUpdate?(text)
    }

    /// Reserve a locale (idempotent), making room under the per-app cap if needed.
    private func reserve(locale: Locale) async throws {
        let reserved = await AssetInventory.reservedLocales
        if reserved.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) {
            return
        }
        if reserved.count >= AssetInventory.maximumReservedLocales, let drop = reserved.first {
            await AssetInventory.release(reservedLocale: drop)
        }
        try await AssetInventory.reserve(locale: locale)
    }

    /// Best-effort: reserve the other preferred languages so their models stay resident.
    /// Bounded by the per-app cap; failures are ignored (primary is already reserved).
    private func reservePreferred() async {
        for locale in preferred where locale.identifier(.bcp47) != primary.identifier(.bcp47) {
            let reserved = await AssetInventory.reservedLocales
            guard reserved.count < AssetInventory.maximumReservedLocales else { return }
            try? await reserve(locale: locale)
        }
    }

    /// Kick off downloads for any preferred languages whose assets aren't installed yet.
    /// Detached + best-effort so the primary-language download isn't blocked. Device-driven.
    nonisolated static func installPreferredAssets(_ preferred: [Locale]) async {
        let installed = await SpeechTranscriber.installedLocales
        let installedKeys = Set(installed.map { $0.identifier(.bcp47) })
        for locale in preferred where !installedKeys.contains(locale.identifier(.bcp47)) {
            let t = SpeechTranscriber(locale: locale, transcriptionOptions: [],
                                      reportingOptions: [], attributeOptions: [])
            if let req = try? await AssetInventory.assetInstallationRequest(supporting: [t]) {
                Task.detached { try? await req.downloadAndInstall() }
            }
        }
    }
}

/// Converts an `AVAudioPCMBuffer` from the mic's native format to the analyzer's required
/// format via a cached `AVAudioConverter`. (Mirrors Apple's WWDC sample BufferConverter.)
@available(iOS 26.0, macOS 26.0, *)
final class DictationBufferConverter {
    private var converter: AVAudioConverter?

    func convert(_ buffer: AVAudioPCMBuffer, to format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        let inputFormat = buffer.format
        if inputFormat == format { return buffer }

        if converter == nil || converter?.outputFormat != format {
            converter = AVAudioConverter(from: inputFormat, to: format)
            converter?.primeMethod = .none   // avoid timestamp drift from priming the first samples
        }
        guard let converter else { throw DictationError.converterUnavailable }

        let ratio = format.sampleRate / inputFormat.sampleRate
        let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up))
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else {
            throw DictationError.converterUnavailable
        }

        var consumed = false
        var nsError: NSError?
        let status = converter.convert(to: out, error: &nsError) { _, statusPtr in
            if consumed {
                statusPtr.pointee = .noDataNow
                return nil
            }
            consumed = true
            statusPtr.pointee = .haveData
            return buffer
        }
        if status == .error { throw DictationError.conversionFailed(nsError) }
        return out
    }
}

enum DictationError: Error {
    case converterUnavailable
    case conversionFailed(NSError?)
}
