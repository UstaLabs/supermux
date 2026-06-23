import SwiftUI

/// The iPad header's Chat ⇄ Native main-view switch — native port of the PWA
/// `AgentViewToggle.vue` (`src/web-app/src/components/AgentViewToggle.vue`): a two-segment
/// rounded pill that flips the main column between the chat transcript and the agent's raw
/// ("Native") terminal. Drives the per-session `WorkspaceLayoutModel.nativeView(for:)`
/// (PWA `panel.mainView`). The caller shows this only for agents that have a native view
/// (claude), matching the PWA's `v-if="isClaude"`.
///
/// The selected segment reads filled/teal — the same on-state styling as `PaneToggleCluster`,
/// so the header's two control clusters look like one family.
struct AgentViewToggle: View {
    @Bindable var layout: WorkspaceLayoutModel
    /// The session whose main-view mode this switch controls (PWA `panels[sessionId].mainView`).
    let sessionId: String

    private struct Segment: Identifiable {
        let id: String
        let label: String
        let icon: String
        let native: Bool        // the `nativeView` value this segment selects
    }

    private let segments: [Segment] = [
        Segment(id: "chat", label: "Chat", icon: "bubble.left", native: false),
        Segment(id: "native", label: "Native", icon: "terminal", native: true),
    ]

    var body: some View {
        let isNative = layout.nativeView(for: sessionId)
        HStack(spacing: 2) {
            ForEach(segments) { seg in
                let on = seg.native == isNative
                Button {
                    layout.setNativeView(seg.native, for: sessionId)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: seg.icon).font(.system(size: 12, weight: .semibold))
                        Text(seg.label).font(.caption.weight(.semibold))
                    }
                    .padding(.horizontal, 10).frame(height: 28)
                    .foregroundStyle(on ? Color.white : Color.secondary)
                    .background {
                        if on {
                            RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Theme.teal)
                        }
                    }
                    .contentShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                }
                .buttonStyle(.plain)
                .hoverEffect(.highlight)
                .accessibilityLabel("\(seg.label) view")
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }
        .padding(2)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color(.tertiarySystemFill))
        )
        .animation(.snappy(duration: 0.2), value: isNative)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Agent view")
    }
}
