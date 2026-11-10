import AVFoundation
import XCTest
@testable import Supermux

@MainActor
final class MessageSpeechTests: XCTestCase {
    func testPlainTextStripsMultilineMarkdownPrefixes() {
        let markdown = """
        # Heading
        - item
        > quote

        1. second
        """

        XCTAssertEqual(
            MessageSpeech.plainTextForSpeech(markdown),
            "Heading item quote second"
        )
    }

    // A dictation hands the session back as `.record` was found to leave it: read-aloud then played into a session
    // that cannot sound, and the iPhone stayed silent (#118).
    func testReadAloudRetakesASessionADictationLeftRecording() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement)
        AudioCapture.live = false

        MessageSpeech.activatePlayback()

        XCTAssertEqual(session.category, .playback)
        XCTAssertEqual(session.mode, .spokenAudio)
    }

    // A capture still running keeps its session: the voice is heard through it.
    func testReadAloudKeepsALiveCapturesSession() throws {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .default)
        AudioCapture.live = true
        defer { AudioCapture.live = false }

        MessageSpeech.activatePlayback()

        XCTAssertEqual(session.category, .playAndRecord)
    }

    // The codex voice plays as the broker's mp3 bytes through AVAudioPlayer. AVPlayer was handed the live
    // `/speak/audio` URL before, and a stream of unknown length never starts there: it said "playing" with
    // its clock at 0, and the iPhone stayed silent (#118).
    func testABrokerMp3PlaysToItsEnd() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "tone-1s", withExtension: "mp3"))
        let data = try Data(contentsOf: url)
        let started = Date()
        let played = expectation(description: "played out")

        MessageSpeech.shared.playChunk(data) { played.fulfill() }

        await fulfillment(of: [played], timeout: 5)
        XCTAssertGreaterThan(Date().timeIntervalSince(started), 0.8, "it ended at once: nothing played")
    }
}
