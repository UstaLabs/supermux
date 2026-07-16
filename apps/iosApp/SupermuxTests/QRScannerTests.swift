#if os(iOS)
import XCTest
@testable import Supermux

final class QRScannerTests: XCTestCase {
    func testDecodedPayloadIsDeliveredOnlyOnce() {
        let delivered = expectation(description: "decoded QR delivered")
        var values: [String] = []
        let coordinator = QRScannerView.Coordinator { value in
            values.append(value)
            delivered.fulfill()
        }

        coordinator.accept("first")
        coordinator.accept("second")

        wait(for: [delivered], timeout: 1)
        XCTAssertEqual(values, ["first"])
    }
}
#endif
