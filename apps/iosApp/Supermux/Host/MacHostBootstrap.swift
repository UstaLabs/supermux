#if os(macOS)
import Foundation

struct MacHostPreparedClaim: Equatable {
    let localToken: String
    let payloadJSON: String
}

struct MacHostBootstrap {
    typealias Request = (URLRequest) async throws -> (Data, HTTPURLResponse)
    private let request: Request

    init(request: @escaping Request = MacHostBootstrap.liveRequest) {
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
            guard let fresh = await bootstrapFirstDevice(base: base, name: hostName) else { return nil }
            token = fresh
        }

        guard let secret = await mintClaim(base: base, token: token) else { return nil }
        let payload = Payload(
            v: 1,
            action: "pair",
            hostId: hostId,
            name: hostName,
            directUrl: pairingDirectURL ?? localBaseURL,
            claimSecret: secret
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(payload),
              let json = String(data: data, encoding: .utf8) else { return nil }
        return MacHostPreparedClaim(localToken: token, payloadJSON: json)
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

    private func mintClaim(base: URL, token: String) async -> String? {
        guard let url = URL(string: "/pair/mint-claim", relativeTo: base) else { return nil }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await self.request(request), response.statusCode == 200,
              let result = try? JSONDecoder().decode(MintResult.self, from: data),
              !result.claimSecret.isEmpty else { return nil }
        return result.claimSecret
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

    nonisolated static func liveRequest(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        return (data, http)
    }

    private struct ClaimBody: Codable {
        let deviceName: String
    }

    private struct MintResult: Codable {
        let claimSecret: String
    }

    private struct Payload: Codable {
        let v: Int
        let action: String
        let hostId: String
        let name: String
        let directUrl: String
        let claimSecret: String
    }
}
#endif
