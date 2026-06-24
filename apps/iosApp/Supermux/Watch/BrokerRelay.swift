import Foundation
import UIKit

/// Phone-side handler for a watch relay request: runs the broker REST call with the phone's
/// stored credentials (`BrokerConfig`) and returns the bytes to the watch. Image responses
/// (`GET /files/...`) are downscaled to a watch-sized thumbnail — and oversized/undecodable
/// ones skipped — so they fit the low-bandwidth `WCSession` link. `handle` is the entry
/// point called from `PhoneWatchProvisioner`; `prepareBody`/`downscale` are pure + tested.
enum BrokerRelay {
    static let thumbMaxDimension: CGFloat = 640
    static let jpegQuality: CGFloat = 0.6
    static let hardCapBytes = 256 * 1024
    static let smallEnoughBytes = 64 * 1024

    /// Run a decoded WCSession message against the broker, returning a reply dictionary.
    static func handle(_ message: [String: Any]) async -> [String: Any] {
        guard let req = RelayEnvelope.decodeRequest(message) else {
            return RelayEnvelope.encodeFailure("bad request")
        }
        guard let base = BrokerConfig.baseURL, let token = BrokerConfig.token,
              !base.isEmpty, !token.isEmpty,
              let url = URL(string: base + req.path) else {
            return RelayEnvelope.encodeFailure("unpaired")
        }
        var urlReq = URLRequest(url: url)
        urlReq.timeoutInterval = 20
        urlReq.httpMethod = req.method
        urlReq.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let ct = req.contentType { urlReq.setValue(ct, forHTTPHeaderField: "Content-Type") }
        urlReq.httpBody = req.body
        do {
            let (data, resp) = try await URLSession.shared.data(for: urlReq)
            let status = (resp as? HTTPURLResponse)?.statusCode ?? RelayEnvelope.phoneFailureStatus
            if status == RelayEnvelope.phoneFailureStatus {
                return RelayEnvelope.encodeFailure("no http response")
            }
            guard let payload = prepareBody(path: req.path, status: status, data: data) else {
                return RelayEnvelope.encodeFailure("payload too large to relay")
            }
            return RelayEnvelope.encodeReply(status: status, body: payload)
        } catch {
            return RelayEnvelope.encodeFailure(error.localizedDescription)
        }
    }

    /// Decide what bytes to send back over the link. File images are downscaled; everything
    /// else passes through if it fits the hard cap, otherwise is skipped (returns nil).
    static func prepareBody(path: String, status: Int, data: Data) -> Data? {
        let isFileImage = (200..<300).contains(status) && path.hasPrefix("/files/")
        guard isFileImage else {
            return data.count <= hardCapBytes ? data : nil
        }
        if data.count <= smallEnoughBytes, UIImage(data: data) != nil {
            return data   // already small enough to relay untouched
        }
        guard let image = UIImage(data: data) else {
            return data.count <= hardCapBytes ? data : nil   // not an image → cap-gate
        }
        let shrunk = downscale(image, maxDimension: thumbMaxDimension)
        guard let jpeg = shrunk.jpegData(compressionQuality: jpegQuality),
              jpeg.count <= hardCapBytes else { return nil }   // still too big → skip
        return jpeg
    }

    /// Aspect-preserving downscale so the longest side ≤ `maxDimension`. Smaller images are
    /// returned unchanged.
    static func downscale(_ image: UIImage, maxDimension: CGFloat) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        guard longest > maxDimension else { return image }
        let scale = maxDimension / longest
        let newSize = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        return UIGraphicsImageRenderer(size: newSize).image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }
}
