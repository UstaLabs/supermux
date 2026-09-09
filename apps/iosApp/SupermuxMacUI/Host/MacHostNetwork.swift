#if os(macOS)
import Foundation

enum MacHostNetwork {
    static func directURL(
        port: Int,
        addresses: [String] = Host.current().addresses
    ) -> String {
        let ipv4 = addresses.filter(isIPv4).filter { !$0.hasPrefix("127.") }
        let address = ipv4.first(where: isPrivateLAN)
            ?? ipv4.first(where: isCarrierGradeNAT)
            ?? ipv4.first
            ?? "127.0.0.1"
        return "http://\(address):\(port)"
    }

    private static func isIPv4(_ value: String) -> Bool {
        let parts = value.split(separator: ".")
        return parts.count == 4 && parts.allSatisfy { part in
            guard let value = Int(part) else { return false }
            return value >= 0 && value <= 255
        }
    }

    private static func isPrivateLAN(_ value: String) -> Bool {
        if value.hasPrefix("10.") || value.hasPrefix("192.168.") { return true }
        let parts = value.split(separator: ".")
        guard parts.count == 4, parts[0] == "172", let second = Int(parts[1]) else { return false }
        return (16...31).contains(second)
    }

    private static func isCarrierGradeNAT(_ value: String) -> Bool {
        let parts = value.split(separator: ".")
        guard parts.count == 4, parts[0] == "100", let second = Int(parts[1]) else { return false }
        return (64...127).contains(second)
    }
}
#endif
