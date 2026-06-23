import XCTest
@testable import Supermux

/// Unit tests for `previewFilename` — produces a filename with a Quick Look-friendly
/// extension so downloaded non-photo attachments preview as their real type instead of a
/// generic blob.
final class PreviewFilenameTests: XCTestCase {
    func testKeepsNameThatAlreadyHasExtension() {
        XCTAssertEqual(previewFilename(name: "report.pdf", mime: "application/pdf"), "report.pdf")
        XCTAssertEqual(previewFilename(name: "report.pdf", mime: nil), "report.pdf")
        XCTAssertEqual(previewFilename(name: "archive.tar.gz", mime: nil), "archive.tar.gz")
    }

    func testAddsExtensionFromMimeWhenNameHasNone() {
        XCTAssertEqual(previewFilename(name: "report", mime: "application/pdf"), "report.pdf")
    }

    func testUsesFallbackBaseWhenNameMissingOrEmpty() {
        XCTAssertEqual(previewFilename(name: nil, mime: "application/pdf"), "file.pdf")
        XCTAssertEqual(previewFilename(name: "", mime: "application/pdf"), "file.pdf")
    }

    func testPrefersUTIMappingOverNaiveSubtypeSplit() {
        // text/plain → "txt"; a naive subtype split would wrongly yield "plain".
        XCTAssertEqual(previewFilename(name: nil, mime: "text/plain"), "file.txt")
    }

    func testBareBaseWhenNoMime() {
        XCTAssertEqual(previewFilename(name: nil, mime: nil), "file")
    }

    func testImageDefaultsPreservedViaFallbacks() {
        XCTAssertEqual(
            previewFilename(name: nil, mime: nil, fallbackBase: "image", defaultExt: "jpg"),
            "image.jpg")
        XCTAssertEqual(
            previewFilename(name: nil, mime: "image/png", fallbackBase: "image", defaultExt: "jpg"),
            "image.png")
    }

    func testStripsMimeParametersAndLowercases() {
        XCTAssertEqual(previewFilename(name: nil, mime: "text/plain; charset=utf-8"), "file.txt")
        XCTAssertEqual(previewFilename(name: nil, mime: "APPLICATION/PDF"), "file.pdf")
    }

    func testKeepsDotfileNameAsIs() {
        XCTAssertEqual(previewFilename(name: ".gitignore", mime: "text/plain"), ".gitignore")
    }
}
