import XCTest
@testable import Supermux

/// Unit tests for `RelayEnvelope` — the WCSession wire format for relaying a broker REST
/// request from the watch through the paired iPhone.
final class RelayEnvelopeTests: XCTestCase {
    func testEncodeDecodeRequestRoundTrip() {
        let body = Data("{\"text\":\"hi\"}".utf8)
        let msg = RelayEnvelope.encodeRequest(method: "POST", path: "/sessions/a/message",
                                              body: body, contentType: "application/json")
        let decoded = RelayEnvelope.decodeRequest(msg)
        XCTAssertEqual(decoded?.method, "POST")
        XCTAssertEqual(decoded?.path, "/sessions/a/message")
        XCTAssertEqual(decoded?.body, body)
        XCTAssertEqual(decoded?.contentType, "application/json")
    }

    func testEncodeRequestOmitsNilBodyAndContentType() {
        let msg = RelayEnvelope.encodeRequest(method: "GET", path: "/sessions",
                                              body: nil, contentType: nil)
        XCTAssertNil(msg[RelayEnvelope.kBody])
        XCTAssertNil(msg[RelayEnvelope.kContentType])
        let decoded = RelayEnvelope.decodeRequest(msg)
        XCTAssertEqual(decoded?.method, "GET")
        XCTAssertNil(decoded?.body)
    }

    func testDecodeRequestRejectsMissingFields() {
        XCTAssertNil(RelayEnvelope.decodeRequest([RelayEnvelope.kPath: "/sessions"])) // no method
        XCTAssertNil(RelayEnvelope.decodeRequest([RelayEnvelope.kMethod: "GET"]))     // no path
    }

    func testDecodeRequestRejectsNonRelativePaths() {
        func req(_ p: String) -> [String: Any] { [RelayEnvelope.kMethod: "GET", RelayEnvelope.kPath: p] }
        XCTAssertNil(RelayEnvelope.decodeRequest(req("https://evil.com/x"))) // absolute URL
        XCTAssertNil(RelayEnvelope.decodeRequest(req("//evil.com/x")))       // protocol-relative
        XCTAssertNil(RelayEnvelope.decodeRequest(req("sessions")))           // no leading slash
        XCTAssertNotNil(RelayEnvelope.decodeRequest(req("/sessions")))       // OK
    }

    func testReplyRoundTrip() {
        let body = Data([1, 2, 3])
        let reply = RelayEnvelope.encodeReply(status: 200, body: body)
        let decoded = RelayEnvelope.decodeReply(reply)
        XCTAssertEqual(decoded.status, 200)
        XCTAssertEqual(decoded.body, body)
    }

    func testFailureReplyDecodesToPhoneFailureStatus() {
        let reply = RelayEnvelope.encodeFailure("unpaired")
        let decoded = RelayEnvelope.decodeReply(reply)
        XCTAssertEqual(decoded.status, RelayEnvelope.phoneFailureStatus)
        XCTAssertEqual(reply[RelayEnvelope.kError] as? String, "unpaired")
    }

    func testDecodeReplyDefaultsToFailureWhenStatusMissing() {
        XCTAssertEqual(RelayEnvelope.decodeReply([:]).status, RelayEnvelope.phoneFailureStatus)
    }
}
