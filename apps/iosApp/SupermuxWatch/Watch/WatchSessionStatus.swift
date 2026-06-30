import Foundation

// Pure-Swift mirror of the shared `GitBadge.kt` session-status rule + the agent
// working-phase set. The watch can't link the KMP Shared.framework (arm64_32), so it
// re-states the tiny rule here. KEEP IN SYNC with
// apps/shared/src/commonMain/kotlin/dev/supermux/proto/GitBadge.kt
// (pinned by WatchSessionStatusTests here and :shared:jvmTest there).
// Deliberately depends on NO watch-only type (no SessionInfo) so it can also compile
// into the iOS app target for unit testing.

struct GitLite: Codable, Equatable {
    var mode: String          // "base" (worktree) | "remote"
    var ahead: Int
    var behind: Int
    var dirty: Int
    var touched: Bool?
    var unpublished: Bool?
}

enum WatchStatusKind: Equatable { case worktree, remote }
enum WatchStatusLevel: Equatable { case pristine, done, notDone }

/// Unified per-session git status for the list indicator; nil when none applies (git == nil).
func sessionStatus(_ git: GitLite?) -> (kind: WatchStatusKind, level: WatchStatusLevel)? {
    guard let git else { return nil }
    if git.mode == "base" {
        let level: WatchStatusLevel
        if git.ahead > 0 || git.dirty > 0 { level = .notDone }
        else if git.touched == true { level = .done }
        else { level = .pristine }
        return (.worktree, level)
    } else {
        let synced = git.ahead == 0 && git.behind == 0 && git.dirty == 0 && git.unpublished != true
        return (.remote, synced ? .done : .notDone)
    }
}

// The broker's legacy `phase` alias is one of idle|thinking|running|stalled; the
// watch infers "working" from the forwarded phase (it can't receive the agent_state frame).
private let workingPhases: Set<String> = ["thinking", "running"]

func isWorking(_ phase: String?) -> Bool {
    guard let phase else { return false }
    return workingPhases.contains(phase)
}

/// Flat-list triage bucket: 0 = needs you (finished + unseen), 1 = working, 2 = the rest.
/// Lower sorts first. A working session stays in the working bucket even if unread.
func attentionBucket(phase: String?, unread: Bool) -> Int {
    if !isWorking(phase) && unread { return 0 }
    if isWorking(phase) { return 1 }
    return 2
}

/// Parse a message timestamp (epoch-ms or ISO-8601) to a comparable Double; 0 when absent.
/// Mirrors the iPhone's ChatActivity.tsMs so watch + phone order identically.
func tsValue(_ s: String?) -> Double {
    guard let s, !s.isEmpty else { return 0 }
    if let d = Double(s) { return d > 1_000_000_000_000 ? d : d * 1000 }
    let iso = ISO8601DateFormatter(); iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let date = iso.date(from: s) ?? ISO8601DateFormatter().date(from: s) {
        return date.timeIntervalSince1970 * 1000
    }
    return 0
}
