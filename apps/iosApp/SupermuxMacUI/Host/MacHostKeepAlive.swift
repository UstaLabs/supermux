#if os(macOS)
import Darwin
import Foundation

enum MacHostKeepAlive {
    static let label = "dev.supermux.host"

    static func plistURL(home: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
        home.appendingPathComponent("Library/LaunchAgents/\(label).plist")
    }

    static func plist(
        brokerPath: String,
        port: Int,
        binDirectory: String,
        stateDirectory: String,
        relayDomain: String = "relay.supermux.dev",
        hostName: String? = nil
    ) -> String {
        let path = xml(brokerPath)
        let bin = xml(binDirectory)
        let state = xml(stateDirectory)
        let log = xml(URL(fileURLWithPath: stateDirectory).appendingPathComponent("native-host-launchd.log").path)
        let hostNameEnvironment = hostName
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
            .map { "\n            <key>MUX_HOST_NAME</key>\n            <string>\(xml($0))</string>" } ?? ""
        return """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
          <key>Label</key>
          <string>\(label)</string>
          <key>ProgramArguments</key>
          <array>
            <string>\(path)</string>
          </array>
          <key>EnvironmentVariables</key>
          <dict>
            <key>MUX_WEB_PORT</key>
            <string>\(port)</string>
            <key>MUX_WEB_PUBLIC_URL</key>
            <string>http://127.0.0.1:\(port)</string>
            <key>MUX_STATE_DIR</key>
            <string>\(state)</string>
            <key>MUX_RELAY_DOMAIN</key>
            <string>\(xml(relayDomain))</string>\(hostNameEnvironment)
            <key>PATH</key>
            <string>\(bin):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
          </dict>
          <key>RunAtLoad</key>
          <true/>
          <key>KeepAlive</key>
          <true/>
          <key>ProcessType</key>
          <string>Interactive</string>
          <key>StandardOutPath</key>
          <string>\(log)</string>
          <key>StandardErrorPath</key>
          <string>\(log)</string>
        </dict>
        </plist>
        """
    }

    @discardableResult
    static func install(
        brokerURL: URL,
        port: Int,
        binDirectory: URL,
        stateDirectory: URL,
        home: URL = FileManager.default.homeDirectoryForCurrentUser
    ) -> Bool {
        let file = plistURL(home: home)
        do {
            try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
            try FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true)
            let contents = plist(
                brokerPath: brokerURL.path,
                port: port,
                binDirectory: binDirectory.path,
                stateDirectory: stateDirectory.path,
                hostName: MacBrokerSidecar.localHostDisplayName()
            )
            try contents.write(to: file, atomically: true, encoding: .utf8)
            _ = runLaunchctl(["bootout", "gui/\(getuid())/\(label)"])
            return runLaunchctl(["bootstrap", "gui/\(getuid())", file.path])
        } catch {
            return false
        }
    }

    @discardableResult
    static func remove(home: URL = FileManager.default.homeDirectoryForCurrentUser) -> Bool {
        _ = runLaunchctl(["bootout", "gui/\(getuid())/\(label)"])
        let file = plistURL(home: home)
        do {
            if FileManager.default.fileExists(atPath: file.path) {
                try FileManager.default.removeItem(at: file)
            }
            return true
        } catch {
            return false
        }
    }

    private static func runLaunchctl(_ arguments: [String]) -> Bool {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/launchctl")
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        do {
            try process.run()
            process.waitUntilExit()
            return process.terminationStatus == 0
        } catch {
            return false
        }
    }

    private static func xml(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}
#endif
