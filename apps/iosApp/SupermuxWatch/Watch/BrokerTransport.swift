import Foundation
import WatchConnectivity

/// Which path served the watch's broker traffic — surfaced for the UI indicator.
enum BrokerRoute { case direct, phone, offline }

/// Thrown by a relay transport when the request could not be DELIVERED, or the phone could
/// not complete it. NOT thrown for a normal non-2xx broker response (that is returned as
/// `(body, status)`). `RoutingTransport` catches this to fall back to the direct connection.
struct RelayError: Error { let reason: String }

/// Abstracts "make a broker REST request" so `WatchBrokerSession` doesn't care whether the
/// bytes go directly over the watch's own network or get relayed through the paired iPhone.
/// Returns `(responseBody, httpStatus)`; throws ONLY on transport failure.
protocol BrokerTransport {
    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int)
}

/// Reports whether the paired iPhone is reachable right now. Injectable so `RoutingTransport`
/// is testable without `WCSession`.
protocol RelayReachability {
    var isReachable: Bool { get }
}

/// Live reachability backed by `WCSession`.
struct WCReachability: RelayReachability {
    var isReachable: Bool { WCSession.isSupported() && WCSession.default.isReachable }
}

/// Direct REST to the broker over the watch's own network (today's behavior, lifted from
/// `WatchBrokerSession`).
struct DirectTransport: BrokerTransport {
    let baseURL: String
    let token: String

    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        guard let url = URL(string: baseURL + path) else { throw URLError(.badURL) }
        var req = URLRequest(url: url)
        req.timeoutInterval = 15
        req.httpMethod = method
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        let (data, resp) = try await URLSession.shared.data(for: req)
        return (data, (resp as? HTTPURLResponse)?.statusCode ?? 0)
    }
}

/// Relays the request through the paired iPhone over `WCSession.sendMessage`. Throws
/// `RelayError` if the phone isn't reachable, doesn't answer, or reports a failure.
struct PhoneRelayTransport: BrokerTransport {
    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        guard WCSession.isSupported() else { throw RelayError(reason: "WCSession unsupported") }
        let session = WCSession.default
        guard session.isReachable else { throw RelayError(reason: "phone unreachable") }
        let msg = RelayEnvelope.encodeRequest(method: method, path: path, body: body, contentType: contentType)
        let reply: [String: Any] = try await withCheckedThrowingContinuation { cont in
            session.sendMessage(msg,
                                replyHandler: { cont.resume(returning: $0) },
                                errorHandler: { cont.resume(throwing: RelayError(reason: $0.localizedDescription)) })
        }
        let (status, data) = RelayEnvelope.decodeReply(reply)
        if status == RelayEnvelope.phoneFailureStatus {
            throw RelayError(reason: (reply[RelayEnvelope.kError] as? String) ?? "phone-side failure")
        }
        return (data, status)
    }
}

/// Phone-first failover: relay through the iPhone when reachable; fall back to the watch's
/// own direct connection otherwise — or if the relay attempt fails as a transport error.
/// Reports the chosen route via `onRoute`. (A non-2xx broker response is a *success* here —
/// it's returned, not retried — so the route reflects which device reached the broker.)
struct RoutingTransport: BrokerTransport {
    let direct: BrokerTransport
    let relay: BrokerTransport
    let reachability: RelayReachability
    let onRoute: @Sendable (BrokerRoute) -> Void

    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int) {
        if reachability.isReachable {
            do {
                let r = try await relay.request(method: method, path: path, body: body, contentType: contentType)
                onRoute(.phone)
                return r
            } catch is RelayError {
                // fall through to the direct backstop
            }
        }
        do {
            let r = try await direct.request(method: method, path: path, body: body, contentType: contentType)
            onRoute(.direct)
            return r
        } catch {
            onRoute(.offline)
            throw error
        }
    }
}
