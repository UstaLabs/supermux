import SwiftUI
#if os(macOS)
import AppKit
#endif

/// Client "Check for updates" page.
///
/// - **iOS:** shows the installed marketing version. Updates ship via TestFlight /
///   the App Store — no GitHub release check and no one-click sideload.
/// - **macOS:** polls `https://supermux.dev/versions.json` (GitHub fallback) and
///   offers one-click download + open of the release DMG.
struct AppUpdateView: View {
    @State private var latest: String?
    @State private var notesUrl: String?
    @State private var downloadUrl: String?
    @State private var updateAvailable = false
    @State private var loading = false
    @State private var installing = false
    @State private var errorText: String?

    private var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    private var currentBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
    }

    var body: some View {
        Form {
            Section("App") {
                VStack(alignment: .leading, spacing: 3) {
                    Text("supermux \(currentVersion)")
                        .font(.subheadline)
                    Text("build \(currentBuild)")
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 2)
            }

            #if os(iOS)
            Section("Updates") {
                Text("iOS updates are delivered through TestFlight or the App Store. This app does not sideload releases from GitHub.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Link("Search App Store", destination: URL(string: "https://apps.apple.com/search?term=supermux")!)
            }
            #else
            Section("Updates") {
                if loading && latest == nil && errorText == nil {
                    ProgressView("Checking…")
                } else if let errorText, latest == nil {
                    Text(errorText).font(.caption).foregroundStyle(.red)
                } else if updateAvailable {
                    Label("Update available: \(latest ?? "")", systemImage: "arrow.down.circle")
                        .foregroundStyle(Theme.teal)
                    if let notesUrl, let url = URL(string: notesUrl) {
                        Link("Release notes", destination: url)
                    }
                    if let downloadUrl {
                        Button {
                            Task { await install(from: downloadUrl) }
                        } label: {
                            if installing {
                                ProgressView().controlSize(.small)
                                Text("Downloading…")
                            } else {
                                Text("Download & install")
                            }
                        }
                        .disabled(installing)
                    }
                } else if latest != nil {
                    Label("You're up to date", systemImage: "checkmark.circle")
                        .foregroundStyle(.secondary)
                } else {
                    Text("Tap Recheck to look for a newer release.")
                        .foregroundStyle(.secondary)
                }
                if let errorText, latest != nil {
                    Text(errorText).font(.caption).foregroundStyle(.red)
                }
            }
            #endif
        }
        .navigationTitle("Check for updates")
        .smInlineNavigationTitle()
        .tint(Theme.teal)
        #if os(macOS)
        .task { await check() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Recheck") { Task { await check() } }
                    .disabled(loading || installing)
            }
        }
        #endif
    }

    #if os(macOS)
    @MainActor
    private func check() async {
        loading = true
        errorText = nil
        do {
            let result = try await AppUpdateChecker.check(
                currentVersion: currentVersion,
                currentBuild: Int(currentBuild),
            )
            latest = result.latestVersion
            notesUrl = result.notesUrl
            downloadUrl = result.downloadUrl
            updateAvailable = result.updateAvailable
        } catch {
            errorText = error.localizedDescription
        }
        loading = false
    }

    @MainActor
    private func install(from urlString: String) async {
        guard let url = URL(string: urlString) else {
            errorText = "Invalid download URL"
            return
        }
        installing = true
        errorText = nil
        do {
            let (temp, _) = try await URLSession.shared.download(from: url)
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent("supermux-update.dmg")
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.moveItem(at: temp, to: dest)
            NSWorkspace.shared.open(dest)
        } catch {
            errorText = error.localizedDescription
        }
        installing = false
    }
    #endif
}

// MARK: - Checker (macOS)

#if os(macOS)
enum AppUpdateChecker {
    static let versionsURL = URL(string: "https://supermux.dev/versions.json")!
    static let githubLatestURL = URL(string: "https://api.github.com/repos/UstaLabs/supermux/releases/latest")!

    struct Result {
        var latestVersion: String?
        var notesUrl: String?
        var downloadUrl: String?
        var updateAvailable: Bool
    }

    static func check(currentVersion: String, currentBuild: Int?) async throws -> Result {
        do {
            return try await checkVersionsJson(currentVersion: currentVersion, currentBuild: currentBuild)
        } catch {
            return try await checkGitHub(currentVersion: currentVersion)
        }
    }

    private static func checkVersionsJson(currentVersion: String, currentBuild: Int?) async throws -> Result {
        let (data, resp) = try await URLSession.shared.data(from: versionsURL)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let stable = ((json?["channels"] as? [String: Any])?["stable"]) as? [String: Any]
        let clients = stable?["clients"] as? [String: Any]
        let desktop = clients?["desktop"] as? [String: Any]
        let ios = clients?["ios"] as? [String: Any]
        // macOS desktop client uses clients.desktop; fall back to channel version.
        let latest = (desktop?["version"] as? String)
            ?? (stable?["version"] as? String)
        let notes = stable?["notesUrl"] as? String
        let assets = stable?["assets"] as? [String: Any]
        let dmg = assets?["desktop-macos"] as? [String: Any]
        let download = dmg?["url"] as? String
        let available: Bool = {
            guard let latest else { return false }
            return compareVersions(latest, currentVersion) > 0
        }()
        _ = currentBuild // build reserved for future clients.ios-style desktop builds
        _ = ios
        return Result(latestVersion: latest, notesUrl: notes, downloadUrl: download, updateAvailable: available)
    }

    private static func checkGitHub(currentVersion: String) async throws -> Result {
        var req = URLRequest(url: githubLatestURL)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, resp) = try await URLSession.shared.data(for: req)
        guard let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let tag = (json?["tag_name"] as? String)?.trimmingCharacters(in: CharacterSet(charactersIn: "v"))
        let notes = json?["html_url"] as? String
        let assets = json?["assets"] as? [[String: Any]] ?? []
        let dmg = assets.first { ($0["name"] as? String) == "supermux-macos.dmg" }
        let download = dmg?["browser_download_url"] as? String
        let available = tag.map { compareVersions($0, currentVersion) > 0 } ?? false
        return Result(latestVersion: tag, notesUrl: notes, downloadUrl: download, updateAvailable: available)
    }

    /// Semver-lite: numeric dotted cores; unparseable ranks lowest.
    static func compareVersions(_ a: String, _ b: String) -> Int {
        func parse(_ v: String) -> [Int]? {
            let core = v.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? v
            let parts = core.split(separator: ".")
            var out: [Int] = []
            for p in parts {
                guard let n = Int(p) else { return nil }
                out.append(n)
            }
            return out.isEmpty ? nil : out
        }
        let pa = parse(a)
        let pb = parse(b)
        if pa == nil && pb == nil { return 0 }
        if pa == nil { return -1 }
        if pb == nil { return 1 }
        let len = max(pa!.count, pb!.count)
        for i in 0..<len {
            let av = i < pa!.count ? pa![i] : 0
            let bv = i < pb!.count ? pb![i] : 0
            if av < bv { return -1 }
            if av > bv { return 1 }
        }
        return 0
    }
}
#endif

// MARK: - Startup banner

/// Non-blocking strip when a client update is available.
/// iOS never polls (App Store only). macOS checks versions.json on appear.
struct AppUpdateBanner: View {
    var onOpen: () -> Void = {}
    @State private var latest: String?
    @State private var dismissed = false
    @AppStorage("app_update_dismissed_latest") private var dismissedLatest = ""

    var body: some View {
        Group {
            #if os(macOS)
            if !dismissed, let latest {
                HStack(spacing: 10) {
                    Image(systemName: "arrow.down.circle.fill")
                    Text("Update available: \(latest)")
                        .font(.subheadline)
                    Spacer()
                    Button("Update", action: onOpen)
                    Button {
                        dismissedLatest = latest
                        dismissed = true
                    } label: {
                        Image(systemName: "xmark")
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(Theme.teal.opacity(0.18))
                .contentShape(Rectangle())
                .onTapGesture(perform: onOpen)
            }
            #endif
        }
        #if os(macOS)
        .task {
            let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
            let build = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String).flatMap(Int.init)
            if let result = try? await AppUpdateChecker.check(currentVersion: current, currentBuild: build),
               result.updateAvailable,
               let latest = result.latestVersion,
               latest != dismissedLatest {
                self.latest = latest
            }
        }
        #endif
    }
}
