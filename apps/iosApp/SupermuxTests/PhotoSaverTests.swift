import Photos
import XCTest
@testable import Supermux

// #95: Save on iOS puts a picture or a clip into Photos, with no dialog once access is given (the share sheet came
// up before). Driven with a fake library: CI's simulator never answers the real Photos prompt.
final class PhotoSaverTests: XCTestCase {
    private let url = URL(fileURLWithPath: "/tmp/save95.png")

    /// A library that answers [status] and whose add reports [added]; it records what it was asked to add.
    private final class FakeLibrary {
        var asked: [(URL, Bool)] = []
        func library(status: PHAuthorizationStatus, added: Bool = true) -> PhotoSaver.Library {
            PhotoSaver.Library(
                authorize: { done in DispatchQueue.global().async { done(status) } },
                add: { url, video, done in
                    self.asked.append((url, video))
                    DispatchQueue.global().async { done(added) }
                }
            )
        }
    }

    private func save(video: Bool, with library: PhotoSaver.Library) -> Bool {
        let done = expectation(description: "done")
        var result: Bool?
        PhotoSaver.save(url, video: video, library: library) { ok in
            XCTAssertTrue(Thread.isMainThread, "the result comes back on the main thread")
            result = ok
            done.fulfill()
        }
        wait(for: [done], timeout: 5)
        return result ?? false
    }

    func testAPictureIsAddedOnceAccessIsGiven() {
        let fake = FakeLibrary()
        XCTAssertTrue(save(video: false, with: fake.library(status: .authorized)))
        XCTAssertEqual(fake.asked.map(\.0), [url])
        XCTAssertEqual(fake.asked.map(\.1), [false])
    }

    func testAClipIsAddedAsAVideo() {
        let fake = FakeLibrary()
        XCTAssertTrue(save(video: true, with: fake.library(status: .authorized)))
        XCTAssertEqual(fake.asked.map(\.1), [true])
    }

    // "Selected photos" access still lets an app add.
    func testLimitedAccessStillAdds() {
        let fake = FakeLibrary()
        XCTAssertTrue(save(video: false, with: fake.library(status: .limited)))
        XCTAssertEqual(fake.asked.count, 1)
    }

    // Refused: nothing is added and it reports false, so Kotlin exports to Files instead of saving nothing.
    func testRefusedAccessAddsNothingAndReportsFalse() {
        let fake = FakeLibrary()
        XCTAssertFalse(save(video: false, with: fake.library(status: .denied)))
        XCTAssertTrue(fake.asked.isEmpty)
    }

    // A file Photos will not take (not a picture) reports false: Kotlin falls back to Files.
    func testWhatPhotosRefusesReportsFalse() {
        XCTAssertFalse(save(video: false, with: FakeLibrary().library(status: .authorized, added: false)))
    }
}
