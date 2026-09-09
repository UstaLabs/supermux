import SwiftUI

/// Headless XCUITest surface: real `SessionStatusRail` instances laid out like session-list
/// leading marks, without needing a live broker. Enable with launch env
/// `SM_UITEST_RAIL_FIXTURE=1` (see `SupermuxMacUITests/SessionListRailUITests.swift`).
///
/// Not shown in normal app launches — pure test/feel-test fixture.
struct SessionListRailUIFixtureView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Session list rail fixture")
                .font(.headline)
                .accessibilityIdentifier("session-rail-fixture-title")

            fixtureRow(title: "Working Chat", rail: { SessionStatusRail(git: nil, working: true, unread: true) })
            fixtureRow(title: "Unread Chat", rail: { SessionStatusRail(git: nil, working: false, unread: true) })
            fixtureRow(title: "Read Chat", rail: { SessionStatusRail(git: nil, working: false, unread: false) })
            Spacer()
        }
        .padding(24)
        .frame(minWidth: 360, minHeight: 280)
        .accessibilityIdentifier("session-rail-fixture")
    }

    private func fixtureRow<R: View>(title: String, @ViewBuilder rail: () -> R) -> some View {
        HStack(spacing: 12) {
            rail()
            Text(title)
                .font(.body)
            Spacer()
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(Color.primary.opacity(0.04), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("session-rail-fixture-row-\(title.lowercased().replacingOccurrences(of: " ", with: "-"))")
    }
}
