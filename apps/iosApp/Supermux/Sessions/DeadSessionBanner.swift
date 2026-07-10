import SwiftUI

/// "Not responding" treatment for a dead agent (broker agent_state == "dead").
/// Parity with the web + Android dead-session banners.
struct DeadSessionBanner: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            VStack(alignment: .leading, spacing: 2) {
                Text("Not responding").font(.callout.weight(.semibold))
                // Deliberately non-prescriptive: when the agent is dead the broker reports
                // working=false, so no Stop/interrupt control is on screen to "interrupt" with.
                Text("The agent process is not responding.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        // One coherent VoiceOver announcement instead of three separate stops.
        .accessibilityElement(children: .combine)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 10)
        .padding(.top, 6)
    }
}
