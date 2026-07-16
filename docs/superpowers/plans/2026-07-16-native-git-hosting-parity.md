# Native Git Hosting Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the shared native Git-account sheet render exactly two provider controls and offer the same pre-filled token links as the web flow.

**Architecture:** Introduce a small provider model and pure token-template helper beside `GitHostingSettingsView`, then drive the existing add-account sheet from those values. Replace the macOS-fragile segmented picker with two explicit buttons, retain broker APIs and persistence unchanged, and verify both URL semantics and the real Mac rendering.

**Tech Stack:** Swift 6, SwiftUI, Foundation `URLComponents`, XCTest, XcodeGen, remote macOS `xcodebuild`

---

## File Structure

- Create `apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift`: specify provider order and token-template semantics.
- Modify `apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift`: add the provider model, URL helper, two-button selector, link, state reset, and success-only dismissal.

No web, broker, persistence, or onboarding-navigation files change.

### Task 1: Specify providers and token templates

**Files:**
- Create: `apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift`

- [ ] **Step 1: Write the failing provider and token-template tests**

Create the file with:

```swift
import Foundation
import XCTest
@testable import Supermux

final class GitHostingSettingsTests: XCTestCase {
    func testProviderSelectorContainsExactlyGitHubAndGitLab() {
        XCTAssertEqual(ForgeProvider.allCases.map(\.displayName), ["GitHub", "GitLab"])
    }

    func testGitHubDotComTemplatePrefillsFineGrainedPermissions() throws {
        let url = try XCTUnwrap(ForgeTokenTemplate.url(provider: .github, baseURL: ""))
        XCTAssertEqual(url.scheme, "https")
        XCTAssertEqual(url.host, "github.com")
        XCTAssertEqual(url.path, "/settings/personal-access-tokens/new")
        XCTAssertEqual(query(url), [
            "name": "supermux",
            "description": "Clone, create & push repos from supermux",
            "contents": "write",
            "administration": "write",
        ])
    }

    func testGitHubEnterpriseTemplateUsesClassicScopesOnCustomHost() throws {
        let url = try XCTUnwrap(ForgeTokenTemplate.url(
            provider: .github,
            baseURL: "https://github.acme.com/api/v3"
        ))
        XCTAssertEqual(url.host, "github.acme.com")
        XCTAssertEqual(url.path, "/settings/tokens/new")
        XCTAssertEqual(query(url), [
            "description": "Clone, create & push repos from supermux",
            "scopes": "repo,read:org",
        ])
    }

    func testGitLabTemplatesUseApiScopeOnSaaSAndCustomHosts() throws {
        let saas = try XCTUnwrap(ForgeTokenTemplate.url(provider: .gitlab, baseURL: ""))
        let custom = try XCTUnwrap(ForgeTokenTemplate.url(
            provider: .gitlab,
            baseURL: "gitlab.acme.com/api/v4"
        ))
        XCTAssertEqual(saas.host, "gitlab.com")
        XCTAssertEqual(custom.host, "gitlab.acme.com")
        XCTAssertEqual(saas.path, "/-/user_settings/personal_access_tokens")
        XCTAssertEqual(query(custom), [
            "name": "supermux",
            "scopes": "api",
            "description": "Clone, create & push repos from supermux",
        ])
    }

    func testMalformedCustomHostDoesNotFallBackToSaaS() {
        XCTAssertNil(ForgeTokenTemplate.url(provider: .github, baseURL: "not a host"))
        XCTAssertNil(ForgeTokenTemplate.url(provider: .gitlab, baseURL: "https://"))
    }

    private func query(_ url: URL) -> [String: String] {
        Dictionary(uniqueKeysWithValues:
            (URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [])
                .compactMap { item in item.value.map { (item.name, $0) } }
        )
    }
}
```

- [ ] **Step 2: Sync the complete `apps/` tree to a dedicated Mac build directory**

Run from the repository root:

```bash
tar --exclude 'apps/.gradle' --exclude 'apps/**/build' -czf - apps \
  | ssh mac 'rm -rf ~/supermux-git-hosting && mkdir -p ~/supermux-git-hosting && tar -xzf - -C ~/supermux-git-hosting'
```

- [ ] **Step 3: Run the focused test and verify RED**

Run:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-git-hosting/apps/iosApp; xcodegen generate; xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" -only-testing:SupermuxMacTests/GitHostingSettingsTests -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

Expected: compilation fails because `ForgeProvider` and `ForgeTokenTemplate` do not exist. This is the required RED result.

### Task 2: Implement the native provider model and token helper

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift`
- Test: `apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift`

- [ ] **Step 1: Add the provider model and URL builder before `AddForgeSheet`**

Add:

```swift
enum ForgeProvider: String, CaseIterable {
    case github
    case gitlab

    var displayName: String { self == .github ? "GitHub" : "GitLab" }
    var cliName: String { self == .github ? "gh" : "glab" }
    var tokenPlaceholder: String { self == .github ? "github_pat_…" : "glpat-…" }
}

enum ForgeTokenTemplate {
    static let tokenName = "supermux"
    static let tokenDescription = "Clone, create & push repos from supermux"

    static func url(provider: ForgeProvider, baseURL: String) -> URL? {
        let rawBase = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let customHost: String?
        if rawBase.isEmpty {
            customHost = nil
        } else {
            guard let host = host(from: rawBase) else { return nil }
            customHost = host
        }

        var components = URLComponents()
        components.scheme = "https"
        switch provider {
        case .github where customHost != nil && customHost != "github.com":
            components.host = customHost
            components.path = "/settings/tokens/new"
            components.queryItems = [
                URLQueryItem(name: "description", value: tokenDescription),
                URLQueryItem(name: "scopes", value: "repo,read:org"),
            ]
        case .github:
            components.host = "github.com"
            components.path = "/settings/personal-access-tokens/new"
            components.queryItems = [
                URLQueryItem(name: "name", value: tokenName),
                URLQueryItem(name: "description", value: tokenDescription),
                URLQueryItem(name: "contents", value: "write"),
                URLQueryItem(name: "administration", value: "write"),
            ]
        case .gitlab:
            components.host = customHost ?? "gitlab.com"
            components.path = "/-/user_settings/personal_access_tokens"
            components.queryItems = [
                URLQueryItem(name: "name", value: tokenName),
                URLQueryItem(name: "scopes", value: "api"),
                URLQueryItem(name: "description", value: tokenDescription),
            ]
        }
        return components.url
    }

    static func scopesHint(provider: ForgeProvider, baseURL: String) -> String {
        guard provider == .github else { return "api" }
        guard let customHost = host(from: baseURL), customHost != "github.com" else {
            return "Contents + Administration (read & write)"
        }
        return "repo, read:org"
    }

    private static func host(from baseURL: String) -> String? {
        let trimmed = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains(where: \.isWhitespace) else { return nil }
        let candidate = trimmed.contains("://") ? trimmed : "https://\(trimmed)"
        return URLComponents(string: candidate)?.host?.lowercased()
    }
}
```

- [ ] **Step 2: Re-sync and run the focused tests to verify GREEN**

Repeat the Task 1 sync command, then run the focused `xcodebuild test` command.

Expected: `GitHostingSettingsTests` passes and the command ends with `** TEST SUCCEEDED **`.

- [ ] **Step 3: Commit the tested provider model and token helper**

Run:

```bash
git add apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift
git commit -m "feat(mac): add Git token templates"
```

Expected: the commit contains only the new tests and pure provider/template code.

### Task 3: Replace the four-segment control and complete the native flow

**Files:**
- Modify: `apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift`

- [ ] **Step 1: Drive `AddForgeSheet` from `ForgeProvider`**

Change its state and derived values to:

```swift
@State private var kind: ForgeProvider = .github

private var canImportCli: Bool {
    guard let cli = cliStatus else { return false }
    return kind == .github ? cli.github.available : cli.gitlab.available
}

private var cliLoginLabel: String {
    guard let cli = cliStatus else { return "" }
    let login = kind == .github ? cli.github.login : cli.gitlab.login
    return login.map { " (@\($0))" } ?? ""
}

private var tokenCreationURL: URL? {
    ForgeTokenTemplate.url(provider: kind, baseURL: hostUrl)
}

private var scopesHint: String {
    ForgeTokenTemplate.scopesHint(provider: kind, baseURL: hostUrl)
}
```

Convert broker calls to `kind.rawValue`, labels to `kind.displayName`, CLI copy to `kind.cliName`, and the token placeholder to `kind.tokenPlaceholder`.

- [ ] **Step 2: Replace the segmented picker with exactly two buttons**

Replace the picker section with:

```swift
Section {
    HStack(spacing: 8) {
        ForEach(ForgeProvider.allCases, id: \.self) { provider in
            Button {
                selectProvider(provider)
            } label: {
                HStack(spacing: 7) {
                    ForgeLogo(kind: provider.rawValue, size: 17)
                    Text(provider.displayName)
                        .font(.subheadline.weight(.semibold))
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 9)
                .contentShape(Rectangle())
                .background(
                    kind == provider ? Theme.teal.opacity(0.14) : Color.smSecondaryBackground,
                    in: RoundedRectangle(cornerRadius: 9)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 9)
                        .strokeBorder(kind == provider ? Theme.teal : Color.smSeparator, lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("forge_provider_\(provider.rawValue)")
            .accessibilityAddTraits(kind == provider ? .isSelected : [])
        }
    }
    .padding(.vertical, 2)
}
```

Add the state reset:

```swift
private func selectProvider(_ provider: ForgeProvider) {
    guard kind != provider else { return }
    kind = provider
    token = ""
    hostUrl = ""
    transport = "https"
    showAdvanced = false
    error = nil
}
```

- [ ] **Step 3: Add the pre-filled token link and success-only dismissal**

Replace the token footer with:

```swift
} footer: {
    VStack(alignment: .leading, spacing: 4) {
        if let tokenCreationURL {
            Link("Create a pre-filled token ↗", destination: tokenCreationURL)
                .foregroundStyle(Theme.teal)
        }
        Text("Needs scopes: \(scopesHint)")
        if let error {
            Text(error).foregroundStyle(.red)
        }
    }
}
```

In the CLI task, dismiss only when `await broker.importForge(kind: kind.rawValue, transport: transport)` returns non-nil; otherwise set `error` to `Couldn't import from <cli> — sign in there and try again.` and leave the sheet open.

In `connect()`, pass `kind.rawValue`, keep the sheet open on nil, and use `Couldn't connect to <provider> — check your token and try again.`.

- [ ] **Step 4: Apply the preset through the typed provider model**

Use:

```swift
if let presetKind, let preset = ForgeProvider(rawValue: presetKind) {
    kind = preset
}
```

- [ ] **Step 5: Re-sync and run focused plus full macOS verification**

Run the Task 1 sync, then:

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-git-hosting/apps/iosApp; xcodegen generate; xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" -only-testing:SupermuxMacTests/GitHostingSettingsTests -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

```bash
ssh mac 'source ~/ios-build-env.sh; cd ~/supermux-git-hosting/apps/iosApp; xcodebuild test -scheme SupermuxMac -destination "platform=macOS,arch=arm64" -skip-testing:SupermuxMacUITests -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO; xcodebuild build -scheme SupermuxMac -destination "platform=macOS,arch=arm64" -derivedDataPath build/dd-mac CODE_SIGNING_ALLOWED=NO'
```

Expected: both test commands end with `** TEST SUCCEEDED **`; the build ends with `** BUILD SUCCEEDED **`.

### Task 4: Inspect the real Mac experience

**Files:**
- Verify: built `Supermux.app` from the dedicated remote build directory

- [ ] **Step 1: Install the verified Mac build and return to the open Git-account sheet**

Quit the current Supermux app, replace `/Applications/Supermux.app` with the built app from `~/supermux-git-hosting/apps/iosApp/build/dd-mac/Build/Products/Debug/Supermux.app`, relaunch it, and open the Git Hosting add-account sheet.

- [ ] **Step 2: Inspect the provider selector and token affordance**

Capture the real Mac screen and verify:

- one GitHub button and one GitLab button;
- each logo remains attached to its label;
- selected and unselected states are visually distinct;
- `Create a pre-filled token ↗` appears below the token field;
- Advanced remains collapsed by default;
- the Connect action remains legible and disabled with an empty token.

- [ ] **Step 3: Verify token-link destinations**

Open the GitHub and GitLab links and inspect their address-bar query parameters against the unit-test expectations. Return to the sheet and confirm provider switching clears provider-specific input.

- [ ] **Step 4: Review, verify, and commit the UI change**

Run locally:

```bash
git diff --check
git diff -- apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift
git status --short
```

Then stage only those two files and commit:

```bash
git add apps/iosApp/Supermux/Sessions/GitHostingSettingsView.swift apps/iosApp/SupermuxTests/GitHostingSettingsTests.swift
git commit -m "fix(mac): polish Git hosting setup"
```

Expected: the final implementation commit contains the shared native UI changes and their focused tests only.
