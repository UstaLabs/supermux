import Foundation

/// Parses a pairing input — a full pair URL (`https://host/pair?t=TOKEN`), a
/// `supermux://…?t=TOKEN` deep link, or a bare token — into a broker base URL +
/// device token. The base URL (`https://host[:port]`) is what BrokerClient /
/// BrokerApi take (BrokerClient appends `/ws`).
struct PairToken: Equatable {
    let baseURL: String
    let token: String

    static func parse(_ input: String, fallbackBaseURL: String? = nil) -> PairToken? {
        let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        if let url = URL(string: trimmed), let scheme = url.scheme {
            if scheme.hasPrefix("http") {
                guard let token = queryToken(url), let host = url.host else { return nil }
                var base = "\(scheme)://\(host)"
                if let port = url.port { base += ":\(port)" }
                return PairToken(baseURL: base, token: token)
            }
            if scheme == "supermux", let token = queryToken(url), let base = fallbackBaseURL {
                return PairToken(baseURL: base, token: token)
            }
        }
        // Bare token — only usable once a broker URL is already known.
        if let base = fallbackBaseURL { return PairToken(baseURL: base, token: trimmed) }
        return nil
    }

    private static func queryToken(_ url: URL) -> String? {
        let t = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?.first(where: { $0.name == "t" })?.value
        return (t?.isEmpty == false) ? t : nil
    }
}
