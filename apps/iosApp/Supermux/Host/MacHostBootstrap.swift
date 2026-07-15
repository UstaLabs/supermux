#if os(macOS)
import Foundation

struct MacHostPreparedClaim: Equatable {
    let localToken: String
    let payloadJSON: String
    let relayURL: String?
    let expiresAt: Date

    init(
        localToken: String,
        payloadJSON: String,
        relayURL: String?,
        expiresAt: Date = Date().addingTimeInterval(10 * 60)
    ) {
        self.localToken = localToken
        self.payloadJSON = payloadJSON
        self.relayURL = relayURL
        self.expiresAt = expiresAt
    }
}

struct MacHostBootstrap {
    typealias Request = (URLRequest) async throws -> (Data, HTTPURLResponse)
    private let request: Request
    private let relayAttempts: Int
    private let relayPollDelay: UInt64

    init(
        relayAttempts: Int = 1,
        relayPollDelay: UInt64 = 0,
        request: @escaping Request = MacHostBootstrap.liveRequest
    ) {
        self.relayAttempts = max(1, relayAttempts)
        self.relayPollDelay = relayPollDelay
        self.request = request
    }

    func prepare(
        localBaseURL: String,
        pairingDirectURL: String? = nil,
        hostId: String,
        hostName: String,
        existingToken: String?
    ) async -> MacHostPreparedClaim? {
        guard !hostId.isEmpty, let base = URL(string: localBaseURL) else { return nil }

        let token: String
        if let existingToken, !existingToken.isEmpty {
            token = existingToken
        } else {
            guard let fresh = await bootstrapFirstDevice(base: base, name: hostName) else {
                NSLog("%@", "[supermux] local host preparation failed at first-device bootstrap")
                return nil
            }
            token = fresh
        }

        guard let minted = await mintClaim(base: base, token: token) else {
            NSLog("%@", "[supermux] local host preparation failed while minting a pairing claim (existing credential: \(existingToken != nil))")
            return nil
        }
        let relayURL = await fetchRelayURL(base: base, token: token)
        let payload = Payload(
            v: 1,
            action: "pair",
            hostId: hostId,
            name: hostName,
            directUrl: pairingDirectURL ?? localBaseURL,
            relayUrl: relayURL,
            claimSecret: minted.secret
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(payload),
              let json = String(data: data, encoding: .utf8) else {
            NSLog("%@", "[supermux] local host preparation failed while encoding the pairing payload")
            return nil
        }
        return MacHostPreparedClaim(
            localToken: token,
            payloadJSON: json,
            relayURL: relayURL,
            expiresAt: minted.expiresAt
        )
    }

    private func bootstrapFirstDevice(base: URL, name: String) async -> String? {
        guard let url = URL(string: "/pair/claim", relativeTo: base) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONEncoder().encode(ClaimBody(deviceName: name))
        guard let (_, response) = try? await self.request(request), response.statusCode == 200 else {
            return nil
        }
        return Self.cookieToken(from: response)
    }

    private func mintClaim(base: URL, token: String) async -> (secret: String, expiresAt: Date)? {
        guard let url = URL(string: "/pair/mint-claim", relativeTo: base) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await self.request(request)
        } catch {
            NSLog("%@", "[supermux] mint-claim request failed: \(error.localizedDescription)")
            return nil
        }
        guard response.statusCode == 200 else {
            NSLog("%@", "[supermux] mint-claim request returned HTTP \(response.statusCode)")
            return nil
        }
        guard let result = try? JSONDecoder().decode(MintResult.self, from: data),
              !result.claimSecret.isEmpty else {
            NSLog("%@", "[supermux] mint-claim response could not be decoded")
            return nil
        }
        let expiresAt = result.expiresAt.flatMap(Self.parseISODate)
            ?? Date().addingTimeInterval(10 * 60)
        return (result.claimSecret, expiresAt)
    }

    private func fetchRelayURL(base: URL, token: String) async -> String? {
        guard let url = URL(string: "/me", relativeTo: base) else { return nil }
        for attempt in 0..<relayAttempts {
            var request = URLRequest(url: url)
            request.timeoutInterval = 5
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            if let (data, response) = try? await self.request(request), response.statusCode == 200,
               let result = try? JSONDecoder().decode(MeResult.self, from: data),
               let relayURL = result.relayUrl, !relayURL.isEmpty {
                return relayURL
            }
            if attempt + 1 < relayAttempts, relayPollDelay > 0 {
                try? await Task.sleep(nanoseconds: relayPollDelay)
            }
        }
        return nil
    }

    static func cookieToken(from response: HTTPURLResponse) -> String? {
        let header = response.allHeaderFields.first { key, _ in
            String(describing: key).caseInsensitiveCompare("Set-Cookie") == .orderedSame
        }?.value as? String
        return header?
            .split(separator: ";", maxSplits: 1)
            .first
            .map(String.init)
            .flatMap { first in
                guard first.hasPrefix("cmux_token=") else { return nil }
                let token = String(first.dropFirst("cmux_token=".count))
                return token.isEmpty ? nil : token
            }
    }

    private static func parseISODate(_ value: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    /// Native host bootstrap requests must never inherit browser cookies. A stale cmux_token
    /// cookie takes precedence over the explicit Bearer credential on the broker and can turn a
    /// valid local pair into a misleading 401 while moving through onboarding.
    static func nativeRequestConfiguration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpShouldSetCookies = false
        configuration.httpCookieStorage = nil
        return configuration
    }

    private static let nativeSession = URLSession(configuration: nativeRequestConfiguration())

    nonisolated static func liveRequest(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        var nativeRequest = request
        nativeRequest.httpShouldHandleCookies = false
        nativeRequest.setValue(nil, forHTTPHeaderField: "Cookie")
        let (data, response) = try await nativeSession.data(for: nativeRequest)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    private struct ClaimBody: Codable {
        let deviceName: String
    }

    private struct MintResult: Codable {
        let claimSecret: String
        let expiresAt: String?
    }

    private struct MeResult: Codable {
        let relayUrl: String?
    }

    private struct Payload: Codable {
        let v: Int
        let action: String
        let hostId: String
        let name: String
        let directUrl: String
        let relayUrl: String?
        let claimSecret: String
    }
}
#endif
