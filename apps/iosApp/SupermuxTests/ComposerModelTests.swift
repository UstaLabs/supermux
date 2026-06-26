// apps/iosApp/SupermuxTests/ComposerModelTests.swift
import XCTest
import Shared
import UIKit
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

    // MARK: - Paste staging

    /// A real 2×2 raster so `jpegData(...)` returns non-nil (SF Symbols can't encode to JPEG).
    private func tinyImage() -> UIImage {
        UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { ctx in
            UIColor.red.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
    }

    func testAddPastedImageStagesJpeg() {
        let m = model()
        m.addPastedImage(tinyImage())
        XCTAssertEqual(m.pending.count, 1)
        XCTAssertEqual(m.pending.first?.mime, "image/jpeg")
        XCTAssertEqual(m.pending.first?.filename, "pasted-1.jpg")
        XCTAssertFalse(m.pending.first?.data.isEmpty ?? true)
    }

    func testPastedImagesIncrementFilenames() {
        let m = model()
        m.addPastedImage(tinyImage())
        m.addPastedImage(tinyImage())
        XCTAssertEqual(m.pending.map(\.filename), ["pasted-1.jpg", "pasted-2.jpg"])
    }

    /// Pasted-image numbering counts existing staged items (parity with the +-menu pickers).
    func testPastedImageNumberingFollowsExistingPending() {
        let m = model()
        m.addCameraImage(tinyImage())           // photo-1.jpg
        m.addPastedImage(tinyImage())           // pasted-2.jpg
        XCTAssertEqual(m.pending.count, 2)
        XCTAssertEqual(m.pending.last?.filename, "pasted-2.jpg")
    }

    func testPastedImageMakesComposerSubmittable() {
        let m = model()
        XCTAssertFalse(m.canSubmit)
        m.addPastedImage(tinyImage())
        XCTAssertTrue(m.canSubmit)
    }

    // MARK: - Paste interception (PasteTextView — the WhatsApp-style in-box paste)

    func testPasteTextViewStagesImageInsteadOfInserting() {
        let pb = UIPasteboard.withUniqueName()
        pb.image = tinyImage()
        let tv = PasteTextView()
        tv.pasteboard = pb
        var staged = false
        tv.onPasteAttachment = { staged = true; return true }
        // Edit-menu "Paste" must be enabled for an image clipboard…
        XCTAssertTrue(tv.canPerformAction(#selector(UIResponder.paste(_:)), withSender: nil))
        // …and pasting stages it as an attachment rather than inserting into the text.
        tv.paste(nil)
        XCTAssertTrue(staged)
        XCTAssertEqual(tv.text, "")
        UIPasteboard.remove(withName: pb.name)
    }

    func testPasteTextViewLetsPlainTextFallThrough() {
        let pb = UIPasteboard.withUniqueName()
        pb.string = "hello"
        let tv = PasteTextView()
        tv.pasteboard = pb
        var staged = false
        tv.onPasteAttachment = { staged = true; return true }
        tv.paste(nil)                 // no image/PDF → must NOT stage; falls through to text paste
        XCTAssertFalse(staged)
        UIPasteboard.remove(withName: pb.name)
    }
}
