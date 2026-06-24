// apps/iosApp/SupermuxTests/ComposerModelTests.swift
import XCTest
import Shared
@testable import Supermux

/// Unit tests for `ComposerModel`'s pure logic (draft, slash parsing, consume). The mic /
/// dictation pipeline touches hardware (AVAudioEngine / SFSpeechRecognizer) and is covered by
/// manual smoke, not here. `ComposerModel` is `@MainActor`, so these run on the main actor.
@MainActor
final class ComposerModelTests: XCTestCase {

    private func model(draft: String = "") -> ComposerModel {
        ComposerModel(context: ComposerContext(), initialDraft: draft)
    }

    /// Build a minimal `SlashCommand` (full positional init — avoids SKIE default-arg overloads).
    private func cmd(_ name: String, family: String = "fam",
                     insertText: String? = nil, action: ControlAction? = nil) -> SlashCommand {
        SlashCommand(id: name, family: family, name: name, sigil: "/",
                     description: nil, insertText: insertText, action: action)
    }

    func testCanSubmitFalseWhenEmpty() {
        XCTAssertFalse(model().canSubmit)
    }
    func testCanSubmitFalseWhenWhitespaceOnly() {
        XCTAssertFalse(model(draft: "   \n").canSubmit)
    }
    func testCanSubmitTrueWithDraft() {
        XCTAssertTrue(model(draft: "hello").canSubmit)
    }
    func testHasContent() {
        XCTAssertFalse(model().hasContent)
        XCTAssertTrue(model(draft: "x").hasContent)
    }
    func testConsumeReturnsAndClears() {
        let m = model(draft: "build the thing")
        let out = m.consume()
        XCTAssertEqual(out.text, "build the thing")
        XCTAssertTrue(out.attachments.isEmpty)
        XCTAssertEqual(m.draft, "")
        XCTAssertTrue(m.pending.isEmpty)
    }
    func testAppendToDraftSpaceJoins() {
        let m = model(draft: "hello")
        m.appendToDraft("world")
        XCTAssertEqual(m.draft, "hello world")
    }
    func testAppendToDraftFromEmpty() {
        let m = model()
        m.appendToDraft("hi")
        XCTAssertEqual(m.draft, "hi")
    }
    func testAppendToDraftBumpsRefocusToken() {
        let m = model()
        let before = m.refocusToken
        m.appendToDraft("hi")
        XCTAssertEqual(m.refocusToken, before + 1)
    }
    func testSlashQueryAtEndOfDraft() {
        XCTAssertEqual(model(draft: "do this /he").slashQuery, "he")
    }
    func testSlashQueryNilForMidWordSlash() {
        XCTAssertNil(model(draft: "path/to/file").slashQuery)
    }
    func testSlashQueryEmptyForBareSlash() {
        XCTAssertEqual(model(draft: "/").slashQuery, "")
    }
    func testSlashMatchesFiltersByQuery() {
        let m = model(draft: "/he")
        let matches = m.slashMatches(in: [cmd("help"), cmd("model"), cmd("hello")])
        XCTAssertEqual(matches.map(\.name), ["help", "hello"])
    }
    func testApplyInsertCommandReplacesToken() {
        let m = model(draft: "go /he")
        m.applyCommand(cmd("help", insertText: "/help "))
        XCTAssertEqual(m.draft, "go /help ")
    }
    func testApplyControlCommandClearsTokenAndSignals() {
        let m = model(draft: "stop it /sto")
        m.applyCommand(cmd("stop", action: ControlAction(kind: "stop", muted: nil)))
        XCTAssertEqual(m.draft, "stop it ")          // token removed, leading space kept
        XCTAssertEqual(m.controlCommandToHandle?.name, "stop")
    }
}
