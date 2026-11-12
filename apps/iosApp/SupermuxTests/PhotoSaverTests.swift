import Photos
import UIKit
import XCTest
@testable import Supermux

// #95: Save on iOS puts a picture into Photos, with no dialog once access is given (the share sheet came up before).
// The simulator's Photos access is granted by the run (`simctl privacy … grant photos-add`).
final class PhotoSaverTests: XCTestCase {
    func testAPictureLandsInPhotos() throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 8)).image { ctx in
            UIColor.systemTeal.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("save95.png")
        try XCTUnwrap(image.pngData()).write(to: url)
        let saved = expectation(description: "saved")
        var result = false

        PhotoSaver.save(url, video: false) { ok in
            result = ok
            saved.fulfill()
        }

        wait(for: [saved], timeout: 10)
        XCTAssertTrue(result)
    }

    // A file Photos cannot take reports false, so Kotlin exports it to Files instead of saving nothing.
    func testWhatPhotosRefusesReportsFalse() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("not-a-picture.png")
        try Data("plain text".utf8).write(to: url)
        let done = expectation(description: "done")
        var result = true

        PhotoSaver.save(url, video: false) { ok in
            result = ok
            done.fulfill()
        }

        wait(for: [done], timeout: 10)
        XCTAssertFalse(result)
    }
}
