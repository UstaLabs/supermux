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
