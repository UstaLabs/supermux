import Foundation

/// Wire format for relaying a broker REST request from the watch to the paired iPhone over
/// WatchConnectivity. The watch packs a request into a `[String: Any]` `WCSession` message;
/// the phone runs it and replies with status + bytes. Uses property-list types only (String,
/// Int, Data) so values cross `WCSession` without base64.
///
/// Shared by the watch transports (`BrokerTransport.swift`) and the phone handler
/// (`BrokerRelay.swift`). Compiled into the watch app and the iOS app; reachable from the
/// iOS test bundle via `@testable import Supermux`.
enum RelayEnvelope {
    // Request keys (watch → phone)
    static let kMethod = "m"
    static let kPath = "p"
    static let kBody = "b"
    static let kContentType = "ct"

    // Reply keys (phone → watch)
    static let kStatus = "status"
    static let kReplyBody = "body"
    static let kError = "err"

    /// `status` sentinel: the phone could not complete the request (unpaired, broker
    /// unreachable from the phone, or an oversized file it chose to skip). The watch treats
    /// this as a transport failure and falls back to its own direct connection.
    static let phoneFailureStatus = 0

    /// Pack a request into a WCSession message dictionary.
    static func encodeRequest(method: String, path: String, body: Data?, contentType: String?) -> [String: Any] {
        var msg: [String: Any] = [kMethod: method, kPath: path]
        if let body { msg[kBody] = body }
        if let contentType { msg[kContentType] = contentType }
        return msg
    }

    /// Read a request dictionary on the phone. Returns nil if required fields are missing or
    /// the path is not broker-relative (must start with a single "/", no scheme/host) — so a
    /// malformed message can't redirect the bearer-authed request to another host.
    static func decodeRequest(_ msg: [String: Any]) -> (method: String, path: String, body: Data?, contentType: String?)? {
        guard let method = msg[kMethod] as? String,
              let path = msg[kPath] as? String,
              path.hasPrefix("/"), !path.hasPrefix("//"),
              !path.contains("://") else { return nil }
        return (method, path, msg[kBody] as? Data, msg[kContentType] as? String)
    }

    /// Pack a success reply on the phone.
    static func encodeReply(status: Int, body: Data) -> [String: Any] {
        [kStatus: status, kReplyBody: body]
    }

    /// Pack a phone-side failure reply.
    static func encodeFailure(_ reason: String) -> [String: Any] {
        [kStatus: phoneFailureStatus, kReplyBody: Data(), kError: reason]
    }

    /// Read a reply dictionary on the watch.
    static func decodeReply(_ reply: [String: Any]) -> (status: Int, body: Data) {
        let status = reply[kStatus] as? Int ?? phoneFailureStatus
        let body = reply[kReplyBody] as? Data ?? Data()
        return (status, body)
    }
}
