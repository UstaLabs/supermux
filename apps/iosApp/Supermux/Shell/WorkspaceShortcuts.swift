import SwiftUI

/// PWA-parity ⌘ keyboard shortcuts for the regular-width iPad workspace.
/// Semantics + chords mirror the web PWA 1:1 — see `src/web-app/src/lib/keybindings/commands.ts`.
/// `apply(to:)` is PURE (synchronous, view-host-free) so the invariants are unit-testable;
/// `newSession` routing is intentionally NOT a layout mutation, so it's handled by the caller.
enum WorkspaceCommand: CaseIterable {
    case toggleSidebar    // ⌘B  workspace.toggleSidebar
    case toggleChat       // ⌘L  workspace.toggleChat
    case toggleTerminal   // ⌘T  workspace.toggleTerminal
    case toggleEditor     // ⌘E  workspace.toggleEditor
    case toggleDisplay    // ⌘D  workspace.toggleDisplay
    case newSession       // ⌘N  workspace.newSession (routing, not layout)

    /// Mutates the layout per the PWA semantics. `toggleSidebar` flips the GLOBAL sidebar; the
    /// pane toggles read/write only `sessionId`'s pane state (PWA `panels[sessionId]`), so they
    /// never affect another session. Never leaves the detail area empty: chat may only be hidden
    /// while a work pane is open, and closing the last work pane re-shows chat. `newSession` is a
    /// no-op here (routing is the caller's job).
    func apply(to layout: WorkspaceLayoutModel, session sessionId: String) {
        if self == .toggleSidebar {
            layout.sidebarCollapsed.toggle()   // still global
            return
        }
        if self == .newSession { return }      // routing handled by the caller's closure

        var v = layout.panes(for: sessionId)
        switch self {
        case .toggleChat:
            // Chat can be hidden only when another pane is visible; it can always be re-shown.
            if v.chatOpen {
                if v.editorOpen || v.terminalOpen || v.displayOpen { v.chatOpen = false }
            } else {
                v.chatOpen = true
            }
        case .toggleTerminal:
            v.terminalOpen.toggle()
            Self.ensureSomethingVisible(&v)
        case .toggleEditor:
            v.editorOpen.toggle()
            Self.ensureSomethingVisible(&v)
        case .toggleDisplay:
            v.displayOpen.toggle()
            Self.ensureSomethingVisible(&v)
        case .toggleSidebar, .newSession:
            return   // handled above
        }
        layout.setPanes(v, for: sessionId)
    }

    /// Guards the invariant "the detail area is never empty": if every pane is closed,
    /// fall back to chat (so closing the last work pane while chat is hidden re-shows it).
    private static func ensureSomethingVisible(_ v: inout PaneVisibility) {
        if !v.chatOpen && !v.editorOpen && !v.terminalOpen && !v.displayOpen { v.chatOpen = true }
    }

    /// Human-readable command name (parity with the PWA command labels). Used for the
    /// hidden shortcut buttons' titles + future discoverability.
    var title: String {
        switch self {
        case .toggleSidebar:  "Toggle session list"
        case .toggleChat:     "Toggle chat"
        case .toggleTerminal: "Toggle terminal"
        case .toggleEditor:   "Toggle editor"
        case .toggleDisplay:  "Toggle display"
        case .newSession:     "New session"
        }
    }

    /// The chord key paired with `.command` (mirrors the PWA `defaultChord.key`).
    var key: KeyEquivalent {
        switch self {
        case .toggleSidebar:  "b"
        case .toggleChat:     "l"
        case .toggleTerminal: "t"
        case .toggleEditor:   "e"
        case .toggleDisplay:  "d"
        case .newSession:     "n"
        }
    }
}

/// Attaches every `WorkspaceCommand` as a ⌘-chord via hidden, in-hierarchy titled buttons.
/// SwiftUI fires `keyboardShortcut` on in-hierarchy buttons even when they're not visible, so
/// the buttons are collapsed to zero size and made non-interactive (no layout/hit-test impact).
private struct WorkspaceShortcutsModifier: ViewModifier {
    @Bindable var layout: WorkspaceLayoutModel
    /// The session the pane toggles act on, re-read on every press so it tracks `selected`.
    /// Pane commands no-op when nil (no session selected); ⌘N (newSession) still fires.
    let session: () -> String?
    let onNewSession: () -> Void

    func body(content: Content) -> some View {
        content.background {
            ZStack {
                ForEach(Array(WorkspaceCommand.allCases.enumerated()), id: \.offset) { _, cmd in
                    Button(cmd.title) {
                        switch cmd {
                        case .newSession:
                            onNewSession()
                        case .toggleSidebar:
                            cmd.apply(to: layout, session: "")   // global; session id is ignored
                        default:
                            // Pane toggles act on the selected session; no-op when none is selected.
                            if let id = session() { cmd.apply(to: layout, session: id) }
                        }
                    }
                    .keyboardShortcut(cmd.key, modifiers: .command)
                }
            }
            .frame(width: 0, height: 0)
            .opacity(0)
            .accessibilityHidden(true)
            .allowsHitTesting(false)
        }
    }
}

extension View {
    /// Wires the PWA-parity ⌘ shortcuts (⌘B/L/T/E/D/N) onto a workspace subtree.
    /// `onNewSession` runs for ⌘N (routing); the pane toggles mutate the CURRENT `session`'s
    /// panes via `apply(to:session:)` and no-op when `session()` returns nil (⌘B sidebar still
    /// works — it's global). `session` is a closure so it's re-evaluated per press, tracking
    /// the live selection rather than a value captured when the modifier was created.
    func workspaceShortcuts(
        layout: WorkspaceLayoutModel,
        session: @autoclosure @escaping () -> String?,
        onNewSession: @escaping () -> Void
    ) -> some View {
        modifier(WorkspaceShortcutsModifier(layout: layout, session: session, onNewSession: onNewSession))
    }
}
