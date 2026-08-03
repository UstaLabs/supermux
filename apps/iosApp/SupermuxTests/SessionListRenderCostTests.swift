import XCTest
import Shared
@testable import Supermux

/// What ONE evaluation of the sessions-list body costs.
///
/// The sidebar re-evaluates on every fleet-wide observable change — a `message_append` or an
/// `agent_state` tick for ANY session on ANY host — so it runs many times a second while agents
/// work, including in the middle of a trackpad scroll. Anything expensive in here is main-thread
/// time stolen from that scroll, which is what "yanky, jumps a few times" is made of.
///
/// Measured before this suite existed (40 rows, one pass, Debug): 29 ms — `relTime` 23.6 ms
/// (an `ISO8601DateFormatter` per row), preview stripping 7.2 ms (regexes recompiled per row),
/// project labels 11 ms (asked twice per row through Kotlin). The budgets below sit an order of
/// magnitude above the fixed cost so they never flake, but they DO fail if a per-row date
/// formatter, a per-row regex compile, or a per-row fleet scan creeps back in.
final class SessionListRenderCostTests: XCTestCase {
    private static let rows = 40

    private func session(_ i: Int) -> SessionInfo {
        SessionInfo(
            id: "sess-\(i)", name: "Session \(i)",
            workdir: "/home/ahmet/projects/project-\(i % 7)",
            agent: "claude",
            status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
            repo_root: nil, role: nil, session_branch: nil, git: nil, finish_job: nil,
            userStatus: i % 5 == 0 ? "settled" : "in_progress", sortOrder: Int32(i), draftPayload: nil
        )
    }

    private var fixture: [SessionInfo] { (0..<Self.rows).map(session) }

    private static let previewSample = """
    **Done** — rebuilt the `SessionsListView` derivation so it runs once per pass.
    - dropped the per-row fleet scan
    - see [the plan](https://example.com/plan) for the rest
    """

    private func elapsedMs(_ body: () -> Void) -> Double {
        let t0 = Date()
        body()
        return Date().timeIntervalSince(t0) * 1000
    }

    // MARK: - Cost

    /// Resolving a row's owning broker by scanning every host's session array is O(rows × sessions)
    /// and every comparison bridges a Kotlin `String`. The list did it TWICE per row
    /// (`broker(for:)` + `broker(forSessionOrArchived:)`); `Fleet.index()` does it once per render.
    func testOwnerLookupIsIndexedNotScanned() {
        let sessions = fixture
        let ids = sessions.map(\.id)

        let scanned = elapsedMs {
            for id in ids {
                _ = sessions.contains(where: { $0.id == id })
                _ = sessions.contains(where: { $0.id == id })
            }
        }
        let indexed = elapsedMs {
            let index = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            for id in ids {
                _ = index[id]
                _ = index[id]
            }
        }
        print("[cost] owner lookup: scan=\(scanned)ms index=\(indexed)ms (rows=\(Self.rows))")
        XCTAssertLessThan(indexed, 2.0, "one indexed pass over the fleet must stay sub-millisecond")
    }

    /// `relTime` renders on every visible row. It must not build an `ISO8601DateFormatter` per
    /// call (each wraps a CFDateFormatter), and re-rendering rows whose timestamps have not
    /// changed — which is what a fleet-wide frame does — must not re-parse them at all.
    func testRelativeTimeIsCheapPerRow() {
        let stamps = (0..<Self.rows).map { i in "2026-08-03T09:\(String(format: "%02d", i % 60)):00.000Z" }
        let cold = elapsedMs { for s in stamps { _ = relTime(s) } }
        let warm = elapsedMs { for s in stamps { _ = relTime(s) } }
        print("[cost] relTime × \(Self.rows): cold=\(cold)ms warm=\(warm)ms")
        XCTAssertLessThan(cold, 8.0, "a date formatter per call costs ~3× this")
        XCTAssertLessThan(warm, 1.0, "re-rendering unchanged timestamps must not re-parse them")
    }

    /// Markdown-stripping the preview line runs per visible row per pass, over text that almost
    /// never changes between passes. The rules must be compiled once, not per call.
    func testPreviewStrippingIsCheapPerRow() {
        _ = sessionPreviewPlainText(Self.previewSample)   // warm the compiled rules
        let ms = elapsedMs { for _ in 0..<Self.rows { _ = sessionPreviewPlainText(Self.previewSample) } }
        print("[cost] preview strip × \(Self.rows): \(ms)ms")
        XCTAssertLessThan(ms, 1.0, "re-rendering the same preview text must be a lookup, not a re-strip")
    }

    /// The flat list needs a project label for every session to decide whether to show the tag at
    /// all; it must not then ask Kotlin again per row.
    func testProjectTagsAreDerivedOncePerPass() {
        let sessions = fixture
        let twice = elapsedMs {
            _ = Set(sessions.map { projectLabel(session: $0, home: inferHomeDir(workdir: $0.workdir)) }).count
            for s in sessions { _ = projectLabel(session: s, home: inferHomeDir(workdir: s.workdir)) }
        }
        let once = elapsedMs {
            let tags = projectTagsBySession(sessions)
            _ = Set(tags.values).count
        }
        print("[cost] project tags: twice=\(twice)ms once=\(once)ms (rows=\(Self.rows))")
        XCTAssertLessThan(once, 5.0, "one project-label pass per render must stay cheap")
    }

    /// Section building crosses into Kotlin with the whole list (and back). Measured so the
    /// remaining per-pass cost is attributable rather than guessed at.
    func testSectionBuildCost() {
        let sessions = fixture
        let stamps = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, "2026-08-03T09:30:00.000Z") })
        _ = buildTaskSections(list: combinedTaskSessions(live: sessions, archived: []), lastTs: { _ in "" })
        let ms = elapsedMs {
            let combined = combinedTaskSessions(live: sessions, archived: [])
            _ = buildTaskSections(list: combined, lastTs: { stamps[$0.id] ?? "" })
        }
        print("[cost] section build (\(Self.rows) rows): \(ms)ms")
        XCTAssertLessThan(ms, 5.0, "one section build per render must stay well inside a frame")
    }

    /// The whole derivation, as the body runs it.
    func testWholePassBudget() {
        let sessions = fixture
        let stamps = Dictionary(uniqueKeysWithValues: sessions.map { ($0.id, "2026-08-03T09:30:00.000Z") })
        _ = relTime("2026-08-03T09:30:00.000Z")
        _ = sessionPreviewPlainText(Self.previewSample)

        let ms = elapsedMs {
            let combined = combinedTaskSessions(live: sessions, archived: [])
            let sections = buildTaskSections(list: combined, lastTs: { stamps[$0.id] ?? "" })
            let tags = projectTagsBySession(sessions)
            let index = Dictionary(sessions.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
            for section in sections {
                for s in section.sessions {
                    _ = index[s.id]
                    _ = tags[s.id]
                    _ = sessionPreviewPlainText(Self.previewSample)
                    _ = relTime("2026-08-03T09:30:00.000Z")
                }
            }
        }
        print("[cost] whole pass (\(Self.rows) rows): \(ms)ms")
        // Measured at 29 ms before this cleanup; ~0.8 ms after. A frame is 16.7 ms, and the
        // sidebar is not the only thing in it.
        XCTAssertLessThan(ms, 4.0, "one sidebar pass must stay well inside a 60fps frame")
    }

    // MARK: - Correctness of the derivations the cost fixes introduced

    /// Deriving the tags in one pass (with a per-project cache) must produce exactly what asking
    /// per row produced.
    func testProjectTagsMatchPerRowLabels() {
        let sessions = fixture
        let tags = projectTagsBySession(sessions)
        XCTAssertEqual(tags.count, sessions.count)
        for s in sessions {
            XCTAssertEqual(tags[s.id],
                           projectLabel(session: s, home: inferHomeDir(workdir: s.workdir)),
                           "tag for \(s.id) must match the per-row label")
        }
    }

    /// Sessions in the same project share a label; different projects keep distinct ones (this is
    /// what `showProjectTag` reads to decide whether the tag says anything at all).
    func testProjectTagsSharePerProjectAndStaySpecific() {
        let tags = projectTagsBySession(fixture)
        XCTAssertEqual(Set(tags.values).count, 7, "seven distinct projects in the fixture")
        XCTAssertEqual(tags["sess-0"], tags["sess-7"], "same workdir must yield the same label")
        XCTAssertNotEqual(tags["sess-0"], tags["sess-1"])
    }

    /// A repo-rooted worktree is labelled by its repo, not the worktree path (web parity) — the
    /// per-project cache keys on the same path the label is derived from.
    func testProjectTagsFollowRepoRoot() {
        let worktree = SessionInfo(
            id: "wt", name: "wt", workdir: "/home/ahmet/.mux/worktrees/supermux-1", agent: "claude",
            status: nil, mute: nil, connected: nil, model: nil, reasoningLevel: nil,
            repo_root: "/home/ahmet/projects/supermux", role: nil, session_branch: nil,
            git: nil, finish_job: nil, userStatus: nil, sortOrder: 0, draftPayload: nil
        )
        let tags = projectTagsBySession([worktree])
        XCTAssertEqual(tags["wt"], projectLabel(session: worktree, home: inferHomeDir(workdir: worktree.workdir)))
        XCTAssertEqual(tags["wt"], "supermux")
    }
}
