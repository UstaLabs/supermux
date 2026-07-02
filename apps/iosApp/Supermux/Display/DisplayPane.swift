import SwiftUI
import Shared
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

/// The Display tab for a chat session: resolves the session's newest running display and
/// streams it live (VNC framebuffer or scrcpy H.264), with a status chip + an input
/// cluster. When there's no display, offers to start one. Mirrors `TerminalPane`'s
/// ZStack + status-overlay + onAppear/onDisappear lifecycle.
///
/// The actual live surface is `DisplayStreamView`, extracted so the management
/// `fullScreenCover` (InfoPages `DisplaysView`) reuses the exact same rendering + input.
struct DisplayPane: View {
    let broker: BrokerSession
    let session: SessionInfo

    @State private var starting = false

    private var stream: DisplayStream? { broker.runningDisplay(for: session.name) }

    var body: some View {
        ZStack {
            Theme.terminalBackground.ignoresSafeArea()
            if let stream {
                DisplayStreamView(broker: broker, stream: stream)
            } else {
                emptyState
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("No display for this session", systemImage: "display")
        } description: {
            Text("Ask the agent to show the app, or start one here.")
        } actions: {
            Button {
                start()
            } label: {
                if starting { ProgressView() } else { Text("Start display") }
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.teal)
            .disabled(starting)
        }
    }

    private func start() {
        starting = true
        let name = session.name
        Task {
            _ = try? await broker.api.startDisplay(
                sessionName: name, provider: nil, device: nil, width: nil, height: nil)
            // The display_added frame will flip `stream` non-nil; refresh as a backstop.
            await broker.refreshDisplays()
            starting = false
        }
    }
}

/// The live display surface for ONE resolved `DisplayStream` plus its on-screen controls.
/// Reused by `DisplayPane` (chat tab) and the management full-screen viewer.
///
/// - `transport == "h264"` → `ScrcpyVideoView` fed by a `ScrcpySession` (touch + text/keys).
/// - otherwise (`"vnc"`)   → `VncMetalView` fed by a `VncSession` (pointer drag + keys),
///   with a macOS Screen-Sharing password sheet when `provider == "macos-screen"` and the
///   RFB handshake reports `.needsPassword`.
struct DisplayStreamView: View {
    let broker: BrokerSession
    let stream: DisplayStream

    private var isScrcpy: Bool { stream.transport == "h264" }

    var body: some View {
        Group {
            if isScrcpy {
                ScrcpyStreamView(broker: broker, stream: stream)
            } else {
                VncStreamView(broker: broker, stream: stream)
            }
        }
    }
}

// MARK: - Persistent surface hosts (re-parent the cached host's backing view)

/// Thin SwiftUI host for a PERSISTENT VNC Metal surface. The view (its `CAMetalLayer` +
/// framebuffer texture + renderer wiring) is owned by a `VncHost` cached in `BrokerSession`;
/// this representable only re-parents that one instance into the current mount — it creates
/// nothing and tears nothing down. Keeping the same `MetalLayerView` alive is what preserves
/// the rendered framebuffer across remounts. Mirrors `SwiftTermView`.
private struct VncSurfaceView: PlatformViewRepresentable {
    let view: VncMetalView.MetalLayerView

    func makePlatformView(context: Context) -> VncMetalView.MetalLayerView {
        // Detach from any prior mount before SwiftUI re-parents this cached, reused view —
        // guards the toggle / split transition from a "view already has a superview" assertion.
        view.removeFromSuperview()
        return view
    }

    func updatePlatformView(_ view: VncMetalView.MetalLayerView, context: Context) {}
}

/// Thin SwiftUI host for a PERSISTENT scrcpy video surface. The view (its
/// `AVSampleBufferDisplayLayer` + decoder state) is owned by a `ScrcpyHost` cached in
/// `BrokerSession`; this representable only re-parents that one instance. Mirrors `SwiftTermView`.
private struct ScrcpySurfaceView: PlatformViewRepresentable {
    let view: ScrcpyVideoView.SampleBufferView

    func makePlatformView(context: Context) -> ScrcpyVideoView.SampleBufferView {
        view.removeFromSuperview()
        return view
    }

    func updatePlatformView(_ view: ScrcpyVideoView.SampleBufferView, context: Context) {}
}

// MARK: - VNC surface

private struct VncStreamView: View {
    let broker: BrokerSession
    let stream: DisplayStream

    @State private var keyboardActive = false
    @State private var passwordSheet = false
    @State private var password = ""

    private var isMacScreen: Bool { stream.provider == "macos-screen" }

    // The cached, persistent VNC host (live RFB connection + framebuffer texture). Computed,
    // but always returns the SAME instance for this stream.id — the cache owns its lifecycle,
    // so toggling the pane / switching sessions REUSES the warm stream instead of re-streaming.
    private var host: VncHost? {
        if case .vnc(let h) = broker.displayHost(for: stream) { return h }
        return nil   // impossible: DisplayStreamView only builds this for non-h264 transports
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if let host {
                let session = host.session
                GeometryReader { geo in
                    // Re-parent the host's persistent Metal surface (the framebuffer + its
                    // renderer live on; the session's onUpdate is already wired to it).
                    VncSurfaceView(view: host.view)
                        .contentShape(Rectangle())
                        // simultaneousGesture (not .gesture) so the floating controls/exit on
                        // top still receive their taps — a full-bleed minimumDistance:0 drag
                        // otherwise wins SwiftUI's arbitration and swallows button taps.
                        .simultaneousGesture(pointerDrag(in: geo.size, session: session))
                }
                .ignoresSafeArea(.container, edges: .bottom)

                chip(for: session.status)
                    .padding(8)

                controls(session: session)
            }
            keyboardField(session: host?.session)
        }
        .onChange(of: vncNeedsPassword) { _, needs in
            if needs && isMacScreen { passwordSheet = true }
        }
        // No onAppear create+start / onDisappear stop: the BrokerSession cache owns the
        // connection lifecycle (started on first access, torn down only on display/session
        // removal). Toggling the pane off must NOT tear down the live stream.
        .sheet(isPresented: $passwordSheet) { passwordSheetBody }
    }

    private var vncNeedsPassword: Bool { host?.session.status == .needsPassword }

    // Map the RFB connection state into the shared chip's 4-state enum.
    private func chip(for status: VncSession.Status) -> DisplayStatusChip {
        let state: DisplayStatusChip.State = switch status {
        case .connecting: .connecting
        case .connected: .connected
        case .disconnected: .disconnected
        case .needsPassword: .needsPassword
        }
        return DisplayStatusChip(state: state)
    }

    // Left-button drag: down (mask 0x1) on start, move (mask 0x1), up (mask 0) on end.
    private func pointerDrag(in viewSize: CGSize, session: VncSession) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                // Ignore touches that begin on the bottom control strip so tapping the
                // keyboard / Ctrl-Alt-Del buttons doesn't also send a click to the remote.
                guard value.startLocation.y < viewSize.height - 88, let remote = session.size else { return }
                let p = DisplayInput.mapToRemote(point: value.location, viewSize: viewSize, remote: remote)
                session.sendPointer(x: p.x, y: p.y, buttonMask: 1)
            }
            .onEnded { value in
                guard value.startLocation.y < viewSize.height - 88, let remote = session.size else { return }
                let p = DisplayInput.mapToRemote(point: value.location, viewSize: viewSize, remote: remote)
                session.sendPointer(x: p.x, y: p.y, buttonMask: 0)
            }
    }

    @ViewBuilder
    private func controls(session: VncSession) -> some View {
        DisplayControlBar(
            keyboardActive: $keyboardActive,
            extraLeading: {
                Button { session.sendCtrlAltDel() } label: {
                    Text("⌃⌥⌦").font(.system(size: 13, weight: .semibold))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        )
    }

    @ViewBuilder
    private func keyboardField(session: VncSession?) -> some View {
        // Hidden first responder: characters → keysym (down+up), specials → keysym. The
        // bodies hop onto the main actor (UIKit delivers on the main thread; `VncSession`
        // is @MainActor) — same idiom as SwiftTermView's delegate bridge.
        DisplayKeyboardField(
            isActive: $keyboardActive,
            onCharacter: { ch in
                MainActor.assumeIsolated {
                    guard let session, let keysym = DisplayInput.vncKeysym(forCharacter: ch) else { return }
                    session.sendKey(keysym: keysym, down: true)
                    session.sendKey(keysym: keysym, down: false)
                }
            },
            onSpecial: { special in
                MainActor.assumeIsolated {
                    guard let session, let keysym = DisplayInput.vncKeysym(forSpecial: special) else { return }
                    session.sendKey(keysym: keysym, down: true)
                    session.sendKey(keysym: keysym, down: false)
                }
            }
        )
        .frame(width: 1, height: 1)
        .opacity(0.01)
        .allowsHitTesting(false)
    }

    private var passwordSheetBody: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("Screen Sharing password", text: $password)
                        .textContentType(.password)
                } footer: {
                    Text("This Mac's Screen Sharing requires a password to connect.")
                }
            }
            .navigationTitle("Password required")
            .smInlineNavigationTitle()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { passwordSheet = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Connect") {
                        host?.session.setPassword(password)
                        password = ""
                        passwordSheet = false
                    }
                    .disabled(password.isEmpty)
                }
            }
        }
        .smPresentationDetents([.medium])
    }
}

// MARK: - scrcpy surface

private struct ScrcpyStreamView: View {
    let broker: BrokerSession
    let stream: DisplayStream

    @State private var keyboardActive = false

    // The cached, persistent scrcpy host (live H.264 connection + VideoToolbox decoder).
    // Computed, but always the SAME instance for this stream.id — the cache owns lifecycle, so
    // toggling the pane / switching sessions REUSES the warm stream instead of re-streaming.
    private var host: ScrcpyHost? {
        if case .scrcpy(let h) = broker.displayHost(for: stream) { return h }
        return nil   // impossible: DisplayStreamView only builds this for the h264 transport
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()
            if let host {
                let session = host.session
                GeometryReader { geo in
                    // Re-parent the host's persistent video surface (the decoder + last frame
                    // live on; the session's onFrame is already wired to it).
                    ScrcpySurfaceView(view: host.view)
                        .contentShape(Rectangle())
                        .simultaneousGesture(touchDrag(in: geo.size, session: session))
                }
                .ignoresSafeArea(.container, edges: .bottom)

                chip(for: session.status)
                    .padding(8)

                DisplayControlBar(keyboardActive: $keyboardActive)
            }
            keyboardField(session: host?.session)
        }
        // No onAppear create+start / onDisappear stop: the BrokerSession cache owns the
        // connection lifecycle. Toggling the pane off must NOT tear down the live stream.
    }

    private func chip(for status: ScrcpySession.Status) -> DisplayStatusChip {
        let state: DisplayStatusChip.State = switch status {
        case .connecting: .connecting
        case .connected: .connected
        case .disconnected: .disconnected
        }
        return DisplayStatusChip(state: state)
    }

    // `onChanged` fires repeatedly; the first event of a drag is the DOWN, the rest MOVEs.
    @State private var dragStarted = false

    // scrcpy touch: action 0 = down, 2 = move, 1 = up. Coordinates are remote device px;
    // `w`/`h` are the remote size the server scales from (NOT the on-screen view size).
    private func touchDrag(in viewSize: CGSize, session: ScrcpySession) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                // Skip the bottom control strip (keyboard button) — see VNC pointerDrag.
                guard value.startLocation.y < viewSize.height - 88 else { return }
                let action = dragStarted ? 2 : 0
                dragStarted = true
                send(value.location, action: action, viewSize: viewSize, session: session)
            }
            .onEnded { value in
                guard value.startLocation.y < viewSize.height - 88 else { dragStarted = false; return }
                send(value.location, action: 1, viewSize: viewSize, session: session)
                dragStarted = false
            }
    }

    private func send(_ point: CGPoint, action: Int, viewSize: CGSize, session: ScrcpySession) {
        guard let remote = session.size else { return }
        let p = DisplayInput.mapToRemote(point: point, viewSize: viewSize, remote: remote)
        session.sendTouch(x: p.x, y: p.y, action: action, w: remote.0, h: remote.1)
    }

    @ViewBuilder
    private func keyboardField(session: ScrcpySession?) -> some View {
        DisplayKeyboardField(
            isActive: $keyboardActive,
            onCharacter: { ch in
                MainActor.assumeIsolated { session?.sendText(String(ch)) }
            },
            onSpecial: { special in
                MainActor.assumeIsolated {
                    guard let session, let name = DisplayInput.scrcpyKeyName(forSpecial: special) else { return }
                    session.sendKey(name: name, down: true)
                    session.sendKey(name: name, down: false)
                }
            }
        )
        .frame(width: 1, height: 1)
        .opacity(0.01)
        .allowsHitTesting(false)
    }
}

// MARK: - Shared bottom control cluster

/// Bottom-leading glass cluster: an optional transport-specific button (e.g. Ctrl-Alt-Del
/// for VNC) plus a keyboard toggle that summons the hidden first responder.
private struct DisplayControlBar<Leading: View>: View {
    @Binding var keyboardActive: Bool
    @ViewBuilder var extraLeading: () -> Leading

    init(keyboardActive: Binding<Bool>, @ViewBuilder extraLeading: @escaping () -> Leading = { EmptyView() }) {
        self._keyboardActive = keyboardActive
        self.extraLeading = extraLeading
    }

    var body: some View {
        VStack {
            Spacer()
            HStack(spacing: 12) {
                extraLeading()
                Button { keyboardActive.toggle() } label: {
                    Image(systemName: keyboardActive ? "keyboard.chevron.compact.down" : "keyboard")
                        .font(.system(size: 17, weight: .semibold))
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                Spacer()
            }
            .foregroundStyle(Theme.teal)
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .glassEffect(.regular, in: Capsule())
            .fixedSize(horizontal: true, vertical: false)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
    }
}

// MARK: - Hidden keyboard first responder

#if canImport(UIKit)
/// A zero-size `UITextField` that becomes/resigns first responder with `isActive`, used to
/// raise the iOS keyboard over a Display surface and forward keystrokes. Printable input is
/// delivered character-by-character via `onCharacter`; Return/Backspace/Tab/Esc/arrows via
/// `onSpecial`. The field never retains text — it's an input tap, not an editor.
private struct DisplayKeyboardField: UIViewRepresentable {
    @Binding var isActive: Bool
    var onCharacter: (Character) -> Void
    var onSpecial: (DisplayInput.SpecialKey) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(isActive: $isActive, onCharacter: onCharacter, onSpecial: onSpecial)
    }

    func makeUIView(context: Context) -> KeyCaptureField {
        let field = KeyCaptureField()
        field.coordinator = context.coordinator
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.smartDashesType = .no
        field.smartQuotesType = .no
        field.spellCheckingType = .no
        field.delegate = context.coordinator
        return field
    }

    func updateUIView(_ uiView: KeyCaptureField, context: Context) {
        context.coordinator.onCharacter = onCharacter
        context.coordinator.onSpecial = onSpecial
        if isActive, !uiView.isFirstResponder {
            DispatchQueue.main.async { uiView.becomeFirstResponder() }
        } else if !isActive, uiView.isFirstResponder {
            DispatchQueue.main.async { uiView.resignFirstResponder() }
        }
    }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var isActive: Binding<Bool>
        var onCharacter: (Character) -> Void
        var onSpecial: (DisplayInput.SpecialKey) -> Void

        init(isActive: Binding<Bool>,
             onCharacter: @escaping (Character) -> Void,
             onSpecial: @escaping (DisplayInput.SpecialKey) -> Void) {
            self.isActive = isActive
            self.onCharacter = onCharacter
            self.onSpecial = onSpecial
        }

        // Each inserted character (or paste) arrives here. Backspace is handled by the
        // subclass's `deleteBackward()` override (the delegate isn't called for deletes
        // on an empty field), so an empty `string` here is only a no-op safety net.
        func textField(_ textField: UITextField,
                       shouldChangeCharactersIn range: NSRange,
                       replacementString string: String) -> Bool {
            guard !string.isEmpty else { return false }
            for ch in string {
                if ch == "\n" || ch == "\r" { onSpecial(.enter) } else { onCharacter(ch) }
            }
            // Keep the field empty — it's an input tap, not a buffer.
            textField.text = ""
            return false
        }

        func textFieldShouldReturn(_ textField: UITextField) -> Bool {
            onSpecial(.enter)
            return false
        }

        func textFieldDidEndEditing(_ textField: UITextField) {
            if isActive.wrappedValue { isActive.wrappedValue = false }
        }
    }

    /// `UITextField` subclass that captures Backspace on an empty field (`deleteBackward`)
    /// plus hardware-keyboard arrow/escape keys (which don't flow through the delegate),
    /// forwarding them all as specials.
    final class KeyCaptureField: UITextField {
        weak var coordinator: Coordinator?

        // Fires for the on-screen Backspace key even when the field is empty (unlike the
        // delegate), giving reliable backspace forwarding to the remote.
        override func deleteBackward() {
            coordinator?.onSpecial(.backspace)
        }

        override var keyCommands: [UIKeyCommand]? {
            [
                UIKeyCommand(input: UIKeyCommand.inputUpArrow, modifierFlags: [], action: #selector(arrowUp)),
                UIKeyCommand(input: UIKeyCommand.inputDownArrow, modifierFlags: [], action: #selector(arrowDown)),
                UIKeyCommand(input: UIKeyCommand.inputLeftArrow, modifierFlags: [], action: #selector(arrowLeft)),
                UIKeyCommand(input: UIKeyCommand.inputRightArrow, modifierFlags: [], action: #selector(arrowRight)),
                UIKeyCommand(input: UIKeyCommand.inputEscape, modifierFlags: [], action: #selector(escape)),
            ]
        }
        @objc private func arrowUp() { coordinator?.onSpecial(.arrowUp) }
        @objc private func arrowDown() { coordinator?.onSpecial(.arrowDown) }
        @objc private func arrowLeft() { coordinator?.onSpecial(.arrowLeft) }
        @objc private func arrowRight() { coordinator?.onSpecial(.arrowRight) }
        @objc private func escape() { coordinator?.onSpecial(.escape) }
    }
}
#else
/// Invisible key-capture surface for the Mac — the AppKit twin of the iOS
/// `DisplayKeyboardField`, with the EXACT same interface (`isActive` focus binding,
/// `onCharacter` for printables, `onSpecial` for Return/Backspace/Esc/arrows). It becomes the
/// window's first responder while `isActive`; the iOS `becomeFirstResponder`/`resignFirstResponder`
/// dance maps onto `window?.makeFirstResponder(_:)`.
private struct DisplayKeyboardField: NSViewRepresentable {
    @Binding var isActive: Bool
    var onCharacter: (Character) -> Void
    var onSpecial: (DisplayInput.SpecialKey) -> Void

    func makeNSView(context: Context) -> KeyCaptureView {
        let v = KeyCaptureView()
        // Mirror the iOS `textFieldDidEndEditing`: losing focus clears `isActive` so the
        // keyboard-toggle button reflects reality. Deferred to avoid mutating SwiftUI state
        // during a view-update pass (a programmatic resign can originate from updateNSView).
        let active = $isActive
        v.onResign = {
            DispatchQueue.main.async {
                if active.wrappedValue { active.wrappedValue = false }
            }
        }
        return v
    }

    func updateNSView(_ v: KeyCaptureView, context: Context) {
        // Refresh the callbacks every update — the stream views rebuild them capturing the
        // current `session` (same reason the iOS coordinator refreshes them in updateUIView).
        v.onCharacter = onCharacter
        v.onSpecial = onSpecial
        let isFirst = (v.window?.firstResponder === v)
        if isActive, !isFirst {
            DispatchQueue.main.async { v.window?.makeFirstResponder(v) }
        } else if !isActive, isFirst {
            DispatchQueue.main.async { v.window?.makeFirstResponder(nil) }
        }
    }
}

/// `NSView` that captures `keyDown` while it is first responder, forwarding printable
/// characters via `onCharacter` and Return/Backspace/Escape/arrows via `onSpecial` — the
/// AppKit analog of the iOS `KeyCaptureField`. Everything is consumed (never forwarded to
/// `super`) so keystrokes reach the remote instead of the app; crucially that includes
/// Escape (keyCode 53), which would otherwise dismiss the enclosing sheet — here it is
/// routed to the remote as `.escape` (Cmd-based menu shortcuts still work: those are key
/// equivalents dispatched before `keyDown`).
private final class KeyCaptureView: NSView {
    var onCharacter: ((Character) -> Void)?
    var onSpecial: ((DisplayInput.SpecialKey) -> Void)?
    var onResign: (() -> Void)?

    override var acceptsFirstResponder: Bool { true }

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 51: onSpecial?(.backspace)          // delete / backspace
        case 36, 76: onSpecial?(.enter)          // return / keypad enter
        case 53: onSpecial?(.escape)             // escape → remote (NOT sheet dismissal)
        case 123: onSpecial?(.arrowLeft)
        case 124: onSpecial?(.arrowRight)
        case 125: onSpecial?(.arrowDown)
        case 126: onSpecial?(.arrowUp)
        default:
            // Everything else (incl. Tab as "\t") flows as characters, exactly as the iOS
            // field delivers them — `DisplayInput` maps "\t" onto the Tab keysym/name.
            if let s = event.characters, !s.isEmpty {
                for ch in s { onCharacter?(ch) }
            }
        }
    }

    override func resignFirstResponder() -> Bool {
        onResign?()
        return super.resignFirstResponder()
    }
}
#endif
