import SwiftUI
import Shared

/// Background-task chips (direction B of the waiting-state design): one mono chip per
/// RUNNING bg shell / subagent / workflow, with its own live elapsed. The caller passes
/// running-only tasks, so a chip clears the moment its task finishes — chips never
/// accumulate; the outcome (done/failed) lives in the chat stream.
struct BgTaskChipsView: View {
    let tasks: [ServerFrameBgTask]

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(tasks, id: \.id) { task in
                        chip(task, now: context.date)
                    }
                }
            }
        }
    }

    @ViewBuilder private func chip(_ t: ServerFrameBgTask, now: Date) -> some View {
        HStack(spacing: 5) {
            PulsingHourglass()
            Text("\(t.label) · \(Self.elapsed(fromMs: t.startedAt, now: now))")
                .font(.system(size: 11, design: .monospaced))
                .lineLimit(1)
                .foregroundStyle(Color.secondary)
        }
        .padding(.horizontal, 10).padding(.vertical, 3)
        .overlay(Capsule().stroke(Color.secondary.opacity(0.3), lineWidth: 1))
        .help(t.summary ?? t.label)
    }

    static func elapsed(fromMs startedAt: Int64, now: Date) -> String {
        let s = max(0, Int(now.timeIntervalSince1970) - Int(startedAt / 1000))
        if s < 60 { return "\(s)s" }
        let m = s / 60
        return m < 60 ? "\(m)m \(s % 60)s" : "\(m / 60)h \(m % 60)m"
    }
}

/// Soft opacity pulse for the running-task hourglass — attention without alarm.
private struct PulsingHourglass: View {
    var body: some View {
        Text("⧗")
            .font(.system(size: 11, design: .monospaced))
            .foregroundStyle(Color.orange)
            .modifier(WaitingPulse())
    }
}

/// Shared soft pulse (chips + the ChatPane waiting status line).
struct WaitingPulse: ViewModifier {
    @State private var dim = false
    func body(content: Content) -> some View {
        content
            .opacity(dim ? 0.35 : 1)
            .animation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true), value: dim)
            .onAppear { dim = true }
    }
}
