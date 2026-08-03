import SwiftUI
import Shared
import UniformTypeIdentifiers

// MARK: - Pure helpers (Android SessionReorder / web moveId parity)

/// Move the item at `from` to index `to` (inclusive). No-op when out of range or unchanged.
func moveSessionId(_ ids: [String], from: Int, to: Int) -> [String] {
    guard from != to, ids.indices.contains(from), ids.indices.contains(to) else { return ids }
    var out = ids
    let item = out.remove(at: from)
    out.insert(item, at: to)
    return out
}

/// Move `fromId` so it occupies the index currently held by `toId`.
func moveSessionId(_ ids: [String], fromId: String, toId: String) -> [String]? {
    guard fromId != toId,
          let from = ids.firstIndex(of: fromId),
          let to = ids.firstIndex(of: toId)
    else { return nil }
    return moveSessionId(ids, from: from, to: to)
}

/// Apply a List.onMove-style IndexSet transform (kept for tests / fallbacks).
func reorderedSessionIds(_ ids: [String], from indices: IndexSet, to newOffset: Int) -> [String] {
    var next = ids
    next.move(fromOffsets: indices, toOffset: newOffset)
    return next
}

// MARK: - Live drag state

/// Section-scoped whole-row drag reorder (Android long-press / desktop press-drag / web
/// useSectionReorder). Owns a live working order while a drag is active; commits once on drop.
@MainActor
@Observable
final class SessionSectionReorderState {
    /// Section key currently being reordered, or nil when idle.
    private(set) var activeSectionKey: String?
    /// Id of the row being dragged.
    private(set) var draggingId: String?
    /// Live order of the active section while dragging.
    private(set) var liveOrder: [String]?
    private var startOrder: [String] = []

    var isDragging: Bool { draggingId != nil }

    func displayOrder(sectionKey: String, fallback: [String]) -> [String] {
        guard activeSectionKey == sectionKey, let live = liveOrder else { return fallback }
        return live
    }

    func displaySessions(sectionKey: String, fallback: [SessionInfo]) -> [SessionInfo] {
        let ids = fallback.map(\.id)
        let ordered = displayOrder(sectionKey: sectionKey, fallback: ids)
        let byId = Dictionary(uniqueKeysWithValues: fallback.map { ($0.id, $0) })
        var seen = Set<String>()
        var out: [SessionInfo] = []
        out.reserveCapacity(fallback.count)
        for id in ordered {
            guard let s = byId[id], seen.insert(id).inserted else { continue }
            out.append(s)
        }
        for s in fallback where seen.insert(s.id).inserted {
            out.append(s)
        }
        return out
    }

    /// Begin a drag of `id` within `sectionKey` (ids = current visible order).
    func begin(sectionKey: String, id: String, ids: [String]) {
        guard ids.contains(id) else { return }
        if activeSectionKey == sectionKey, liveOrder != nil {
            draggingId = id
            return
        }
        activeSectionKey = sectionKey
        draggingId = id
        startOrder = ids
        liveOrder = ids
    }

    /// Hover/enter another row in the same section — live-reorder under the finger.
    @discardableResult
    func moveOver(targetId: String) -> Bool {
        guard let draggingId,
              let section = activeSectionKey,
              draggingId != targetId,
              var order = liveOrder,
              order.contains(targetId)
        else { return false }
        guard let next = moveSessionId(order, fromId: draggingId, toId: targetId),
              next != order
        else { return false }
        liveOrder = next
        _ = section // silence unused if optimized
        return true
    }

    /// End the gesture. Returns ordered ids to persist when the order changed; nil otherwise.
    @discardableResult
    func finish(commit: Bool) -> [String]? {
        let result: [String]?
        if commit,
           let live = liveOrder,
           live != startOrder,
           activeSectionKey != nil
        {
            result = live
        } else {
            result = nil
        }
        activeSectionKey = nil
        draggingId = nil
        liveOrder = nil
        startOrder = []
        return result
    }

    func cancel() {
        _ = finish(commit: false)
    }
}

// MARK: - Drop delegate (free drag on macOS; long-press drag on iOS)

/// Continuous list reordering while a row drag crosses siblings — not List.onMove / edit mode.
struct SessionReorderDropDelegate: DropDelegate {
    let targetId: String
    let sectionKey: String
    let sectionIds: [String]
    var state: SessionSectionReorderState
    var onCommit: ([String]) -> Void

    func validateDrop(info: DropInfo) -> Bool {
        state.activeSectionKey == nil || state.activeSectionKey == sectionKey
    }

    func dropEntered(info: DropInfo) {
        // If the system starts the drag without our begin() (rare), seed from section ids.
        if state.draggingId == nil {
            // Provider payload isn't available in dropEntered; only reorder once begin() ran.
            return
        }
        guard state.activeSectionKey == sectionKey else { return }
        withAnimation(.interactiveSpring(response: 0.22, dampingFraction: 0.86)) {
            _ = state.moveOver(targetId: targetId)
        }
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: .move)
    }

    func performDrop(info: DropInfo) -> Bool {
        if let ordered = state.finish(commit: true) {
            onCommit(ordered)
        } else {
            state.cancel()
        }
        return true
    }

    func dropExited(info: DropInfo) {
        // Keep live order; user may re-enter another row.
    }
}

// MARK: - Row drag / drop modifiers

/// Where the drag *source* lives. On macOS the whole-row source fights trackpad
/// scroll (AppKit installs a drag on the NSTableView cell → rubber-band jumps a
/// few times when scroll and drag race). Grip-only keeps vertical scroll clean.
enum SessionRowDragSourcePlacement {
    /// Entire row is the drag source (iPad free-drag).
    case wholeRow
    /// Only the trailing grip is the drag source (macOS).
    case grip
}

/// Drag preview shared by whole-row and grip sources.
@ViewBuilder
func sessionRowDragPreview(name: String) -> some View {
    HStack(spacing: 8) {
        Image(systemName: "line.3.horizontal")
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(.secondary)
        Text(name)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 10)
    .background(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(.regularMaterial)
            .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
    )
    .frame(width: 220, alignment: .leading)
}

/// Begins a section reorder drag and returns the item provider payload.
@MainActor
func beginSessionRowDrag(
    state: SessionSectionReorderState,
    sectionKey: String,
    sessionId: String,
    sectionIds: [String]
) -> NSItemProvider {
    state.begin(sectionKey: sectionKey, id: sessionId, ids: sectionIds)
    #if os(iOS)
    #if canImport(UIKit)
    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    #endif
    #endif
    return NSItemProvider(object: sessionId as NSString)
}

/// Config for a drag *source* (whole-row or grip). Drop stays on the row separately.
struct SessionRowDragSourceConfig {
    let sessionId: String
    let sessionName: String
    let sectionKey: String
    let sectionIds: [String]
    var state: SessionSectionReorderState
}

/// Attaches free-drag source + drop target to a list row (iPad whole-row path).
/// Live reorders via [SessionSectionReorderState]; persists once on drop.
struct SessionRowDragReorderModifier: ViewModifier {
    let enabled: Bool
    let sessionId: String
    let sessionName: String
    let sectionKey: String
    let sectionIds: [String]
    var state: SessionSectionReorderState
    var onCommit: ([String]) -> Void
    /// macOS uses `.grip` + [SessionRowDropTargetModifier]; iPad keeps `.wholeRow`.
    var sourcePlacement: SessionRowDragSourcePlacement = .wholeRow

    func body(content: Content) -> some View {
        if !enabled {
            content
        } else if sourcePlacement == .wholeRow {
            content
                .onDrag {
                    beginSessionRowDrag(
                        state: state,
                        sectionKey: sectionKey,
                        sessionId: sessionId,
                        sectionIds: sectionIds
                    )
                } preview: {
                    sessionRowDragPreview(name: sessionName)
                }
                .modifier(SessionRowDropTargetModifier(
                    enabled: true,
                    sessionId: sessionId,
                    sectionKey: sectionKey,
                    sectionIds: sectionIds,
                    state: state,
                    onCommit: onCommit
                ))
        } else {
            // Grip is the source (applied inside SessionRow); row is drop-only.
            content
                .modifier(SessionRowDropTargetModifier(
                    enabled: true,
                    sessionId: sessionId,
                    sectionKey: sectionKey,
                    sectionIds: sectionIds,
                    state: state,
                    onCommit: onCommit
                ))
        }
    }
}

/// Drop target only — used for macOS rows when the drag source is the grip.
struct SessionRowDropTargetModifier: ViewModifier {
    let enabled: Bool
    let sessionId: String
    let sectionKey: String
    let sectionIds: [String]
    var state: SessionSectionReorderState
    var onCommit: ([String]) -> Void

    func body(content: Content) -> some View {
        if enabled {
            content
                .onDrop(
                    of: [UTType.text, UTType.plainText],
                    delegate: SessionReorderDropDelegate(
                        targetId: sessionId,
                        sectionKey: sectionKey,
                        sectionIds: sectionIds,
                        state: state,
                        onCommit: onCommit
                    )
                )
        } else {
            content
        }
    }
}

/// Drag source for the macOS grip handle (does not wrap the scrollable row).
struct SessionRowDragSourceModifier: ViewModifier {
    let config: SessionRowDragSourceConfig?

    func body(content: Content) -> some View {
        if let config {
            content
                .onDrag {
                    beginSessionRowDrag(
                        state: config.state,
                        sectionKey: config.sectionKey,
                        sessionId: config.sessionId,
                        sectionIds: config.sectionIds
                    )
                } preview: {
                    sessionRowDragPreview(name: config.sessionName)
                }
        } else {
            content
        }
    }
}
