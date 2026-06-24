import XCTest
import UIKit
@testable import Supermux

/// Unit tests for the pure parts of `BrokerRelay` (the phone-side relay handler):
/// request validation, photo downscaling, and the skip/passthrough body policy.
final class BrokerRelayTests: XCTestCase {

    private func solidImage(_ size: CGSize) -> UIImage {
        UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor.red.setFill(); ctx.fill(CGRect(origin: .zero, size: size))
        }
    }

    func testHandleRejectsMalformedMessage() async {
        let reply = await BrokerRelay.handle([RelayEnvelope.kMethod: "GET"]) // no path
        XCTAssertEqual(RelayEnvelope.decodeReply(reply).status, RelayEnvelope.phoneFailureStatus)
        XCTAssertEqual(reply[RelayEnvelope.kError] as? String, "bad request")
    }

    func testDownscaleReducesLongestSideToMax() {
        let big = solidImage(CGSize(width: 2000, height: 1000))
        let small = BrokerRelay.downscale(big, maxDimension: 640)
        XCTAssertEqual(max(small.size.width, small.size.height), 640, accuracy: 1)
        XCTAssertEqual(min(small.size.width, small.size.height), 320, accuracy: 1)
    }

    func testDownscaleLeavesAlreadySmallImageUntouched() {
        let small = solidImage(CGSize(width: 100, height: 100))
        let out = BrokerRelay.downscale(small, maxDimension: 640)
        XCTAssertEqual(out.size, CGSize(width: 100, height: 100))
    }

    func testPrepareBodyPassesThroughSmallNonFileBody() {
        let json = Data("[]".utf8)
        XCTAssertEqual(BrokerRelay.prepareBody(path: "/sessions", status: 200, data: json), json)
    }

    func testPrepareBodySkipsOversizedNonImage() {
        let blob = Data(count: BrokerRelay.hardCapBytes + 1)
        XCTAssertNil(BrokerRelay.prepareBody(path: "/files/x", status: 200, data: blob))
    }

    func testPrepareBodyShrinksLargeImageUnderCap() {
        // A large image rendered as PNG; prepareBody must return JPEG bytes under the cap.
        let big = solidImage(CGSize(width: 3000, height: 3000))
        let png = big.pngData()!
        let out = BrokerRelay.prepareBody(path: "/files/photo", status: 200, data: png)
        XCTAssertNotNil(out)
        XCTAssertLessThanOrEqual(out!.count, BrokerRelay.hardCapBytes)
        XCTAssertNotNil(UIImage(data: out!)) // still a valid image
    }

    func testPrepareBodyPassesThroughLargeNonFileBody() {
        // Non-/files/ responses (e.g. a long message log) are never size-capped.
        let big = Data(count: BrokerRelay.hardCapBytes * 2)
        XCTAssertEqual(BrokerRelay.prepareBody(path: "/sessions/x/messages", status: 200, data: big), big)
    }

    func testPrepareBodyPassesThroughSmallImageUntouched() {
        // A small image (≤ smallEnoughBytes) is relayed without re-encoding.
        let small = solidImage(CGSize(width: 50, height: 50))
        let jpeg = small.jpegData(compressionQuality: 0.9)!
        XCTAssertLessThanOrEqual(jpeg.count, BrokerRelay.smallEnoughBytes)
        XCTAssertEqual(BrokerRelay.prepareBody(path: "/files/x", status: 200, data: jpeg), jpeg)
    }
}
