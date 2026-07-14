import XCTest
@testable import Supermux

final class PairTokenTests: XCTestCase {
    private let base = "https://h-example.relay.supermux.dev"
    private let token = String(repeating: "a", count: 43)

    func testParsesLegacyBareDeviceTokenWithKnownBaseURL() {
        XCTAssertEqual(
            PairToken.parse(token, fallbackBaseURL: base),
            PairToken(baseURL: base, token: token)
        )
    }

    func testDoesNotMisclassifyStructuredClaimAsLegacyBareToken() {
        let claim = #"{"v":1,"action":"pair","hostId":"gj7e23gb72vnkfu5rvtt5b4p7u","name":"Mac","relayUrl":"https://h-gj7e23gb72vnkfu5rvtt5b4p7u.relay.supermux.dev","claimSecret":"secret"}"#
        XCTAssertNil(PairToken.parse(claim, fallbackBaseURL: base))
    }

    func testRejectsArbitraryTextAsLegacyBareToken() {
        XCTAssertNil(PairToken.parse("not-a-device-token", fallbackBaseURL: base))
    }

    func testParsesLegacyHttpPairURLWithoutFallback() {
        XCTAssertEqual(
            PairToken.parse("http://192.168.1.20:9898/pair?t=old-token"),
            PairToken(baseURL: "http://192.168.1.20:9898", token: "old-token")
        )
    }

    func testParsesDeepLinkBaseFromTheLink() {
        XCTAssertEqual(
            PairToken.parse("supermux://pair?t=old-token&base=http%3A%2F%2F192.168.1.20%3A9898"),
            PairToken(baseURL: "http://192.168.1.20:9898", token: "old-token")
        )
    }
}
