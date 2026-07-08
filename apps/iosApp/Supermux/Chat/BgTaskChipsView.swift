import SwiftUI
import Shared

/// Background-task chips (direction B of the waiting-state design): one mono chip per
/// bg shell / subagent / workflow, its own elapsed timer while running, ✓/✕ once closed.
/// Visibility gating (chips linger only while the agent still has open tasks or is
/// reacting to a finished one) is done by the caller — this view renders what it's given.
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
        let running = t.status == "running"
        let failed = t.status == "failed"
        HStack(spacing: 5) {
            if running {
                PulsingHourglass()
            } else {
                Text(failed ? "✕" : "✓")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(failed ? Color.red : Color.green)
            }
            Text("\(t.label) · \(running ? Self.elapsed(fromMs: t.startedAt, now: now) : t.status)")
                .font(.system(size: 11, design: .monospaced))
                .lineLimit(1)
                .foregroundStyle(failed ? Color.red : Color.secondary)
        }
        .padding(.horizontal, 10).padding(.vertical, 3)
        .overlay(Capsule().stroke(failed ? Color.red.opacity(0.4) : Color.secondary.opacity(0.3), lineWidth: 1))
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
