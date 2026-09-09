#if os(macOS)
import AppKit
import SwiftUI

/// First-run cinematic ("The Manifesto") for the macOS app, shown ONCE per install. Third cut of
/// the intro (v1 "mux boot" lives on Compose desktop; v2 day-cycle was retired for being too
/// soft) — a KINETIC-TYPE FILM: near-black canvas, giant editorial type landing in beats, a slow
/// agent montage, a platform roll-call with spring-animated glass chips, then a moving dusk
/// mesh-gradient finale (Apple-glass) that HOLDS until dismissed. Copy riffs on supermux.dev's
/// headlines. One linear timeline `t ∈ 0..1` over 8.5s drives the film; the finale holds at t=1
/// (mesh keeps drifting) until a click/keypress — there is NO auto-exit.
///
///   0.02–0.125 "You leave." — giant paper-white, blur→sharp land
///   0.14–0.26  "It doesn't." — "doesn't" in luminous teal
///   0.28–0.615 montage: five agent ticks roll in slowly (real logos, 19pt mono, predecessors dim)
///   0.63–0.885 "Access from anywhere." + six platform chips springing in (Apple-style stagger, full hold)
///   0.83–0.90  the dusk sunset mesh gradient washes in (animated drift, points clamped in-bounds)
///   0.90–0.997 white mark + wordmark + "AFK. STILL SHIPPING." + a glass "Start Now" button
///              (no glow behind the mark — flat glass on sunset); the frame HOLDS until the
///              button (or a keypress) dismisses — nothing auto-exits, and clicking the sunset
///              itself does nothing.
///
/// Mid-film click JUMPS to the finale (doesn't exit). The window zooms to the screen's visible
/// frame as the film starts and glides back to its pre-intro size on dismiss.
/// Debug hooks: SM_INTRO=1/0 force show/suppress (forced runs don't persist the seen flag),
/// SM_INTRO_FREEZE=<t 0..1> freezes the timeline.
struct ManifestoIntroView: View {
    let onFinished: () -> Void

    static let duration: Double = 8.5
    /// Where the persistent finale begins — mid-film taps jump here instead of dismissing.
    static let finaleStart: Double = 0.87

    @State private var start = Date()
    @State private var finished = false
    @State private var skipAlpha: Double = 1
    /// Pre-intro window frame, restored on dismiss (nil = never expanded / already restored).
    @State private var savedFrame: NSRect?
    @FocusState private var focused: Bool

    private let freeze: Double? = ProcessInfo.processInfo.environment["SM_INTRO_FREEZE"]
        .flatMap(Double.init).map { min(max($0, 0), 0.999) }

    var body: some View {
        GeometryReader { geo in
            TimelineView(.animation) { context in
                let elapsed = context.date.timeIntervalSince(start)
                let t: Double = freeze ?? min(1, elapsed / Self.duration)
                ZStack {
                    Palette.bg
                    // Ultra-subtle breathing teal vignette so the black never reads dead-flat.
                    Canvas { gc, size in
                        let c = CGPoint(x: size.width / 2, y: size.height * 0.46)
                        let breathe = (0.05 + 0.015 * sin(elapsed * 1.6)) * (1 - seg(t, 0.83, 0.89))
                        let r = max(size.width, size.height) * 0.72
                        gc.fill(
                            Path(ellipseIn: CGRect(center: c, radius: r)),
                            with: .radialGradient(
                                Gradient(colors: [Palette.teal.opacity(breathe), .clear]),
                                center: c, startRadius: 0, endRadius: r,
                            ),
                        )
                    }
                    if t > 0.83 { sunsetLayer(t: t, elapsed: elapsed) }
                    phraseLayer(t: t, width: geo.size.width)
                    montageLayer(t: t)
                    finaleLayer(t: t, elapsed: elapsed)
                    hintLayer(t: t)
                }
                .opacity(skipAlpha)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.bg)
        // The film is ALWAYS dark — force the dark scheme so adaptive assets (e.g. the cursor
        // logo's `.primary` template) don't render near-black on our canvas in light mode.
        .environment(\.colorScheme, .dark)
        .contentShape(Rectangle())
        .onTapGesture { advance() }
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onKeyPress { _ in keyAdvance(); return .handled }
        .onAppear {
            focused = true
            expandToFullScreen()
        }
        // Safety net: however this view leaves the hierarchy, never strand a blown-up window.
        .onDisappear { restoreWindow() }
    }

    /// The film wants the whole screen: grow the window to the screen's visible frame (menu bar
    /// stays — this is a zoom, not native full-screen, so there's no Space switch and the
    /// restore is instant). Skipped if the user is already in native full-screen.
    private func expandToFullScreen() {
        guard let window = NSApp.windows.first(where: { $0.isKeyWindow }) ?? NSApp.windows.first,
              !window.styleMask.contains(.fullScreen),
              let target = window.screen?.visibleFrame ?? NSScreen.main?.visibleFrame
        else { return }
        savedFrame = window.frame
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.55
            ctx.timingFunction = CAMediaTimingFunction(name: .easeOut)
            window.animator().setFrame(target, display: true)
        }
    }

    private func restoreWindow() {
        guard let frame = savedFrame,
              let window = NSApp.windows.first(where: { $0.isKeyWindow }) ?? NSApp.windows.first
        else { return }
        savedFrame = nil
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.45
            ctx.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
            window.animator().setFrame(frame, display: true)
        }
    }

    /// Mid-film: jump to the finale. At the finale: do nothing — only the Start Now button
    /// (or a keypress) dismisses, so an idle click on the sunset can't skip the moment.
    private func advance() {
        if freeze != nil { finish(); return }
        let now = Date().timeIntervalSince(start) / Self.duration
        if now < Self.finaleStart {
            start = Date().addingTimeInterval(-Self.finaleStart * Self.duration)
        }
    }

    /// Keypresses dismiss from anywhere (accessibility escape hatch).
    private func keyAdvance() {
        if freeze != nil { finish(); return }
        let now = Date().timeIntervalSince(start) / Self.duration
        if now < Self.finaleStart {
            start = Date().addingTimeInterval(-Self.finaleStart * Self.duration)
        } else {
            finish()
        }
    }

    private func finish() {
        guard !finished else { return }
        finished = true
        // Shrink while the overlay fades — the workspace surfaces as the window settles.
        restoreWindow()
        withAnimation(.linear(duration: 0.3)) { skipAlpha = 0 }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.32) { onFinished() }
    }

    // MARK: - Giant type

    /// Per-beat choreography: blur→sharp land with a small rise, then a soft blur-out exit.
    private func beatStyle(_ t: Double, _ a: Double, _ b: Double) -> (opacity: Double, y: CGFloat, blur: CGFloat) {
        let p = easeOutCubic(seg(t, a, a + 0.045))
        let x = seg(t, b - 0.025, b)
        return (p * (1 - x), (1 - p) * 42 - x * 26, (1 - p) * 10 + x * 8)
    }

    private func displaySize(_ w: CGFloat) -> CGFloat { min(w * 0.105, 150) }

    private func phraseLayer(t: Double, width: CGFloat) -> some View {
        let s1 = beatStyle(t, 0.02, 0.125)
        let s2 = beatStyle(t, 0.14, 0.26)
        let s4 = beatStyle(t, 0.63, 0.865)
        return ZStack {
            if t < 0.135 {
                Text("You leave.")
                    .font(.system(size: displaySize(width), weight: .bold))
                    .tracking(-3)
                    .foregroundColor(Palette.paper)
                    .opacity(s1.opacity).offset(y: s1.y).blur(radius: s1.blur)
            }
            if t >= 0.13, t < 0.27 {
                (Text("It ").foregroundColor(Palette.paper)
                    + Text("doesn’t.").foregroundColor(Palette.tealBright))
                    .font(.system(size: displaySize(width), weight: .bold))
                    .tracking(-3)
                    .opacity(s2.opacity).offset(y: s2.y).blur(radius: s2.blur)
            }
            if t >= 0.62, t < 0.87 {
                Text("Access from anywhere.")
                    .font(.system(size: min(width * 0.062, 88), weight: .bold))
                    .tracking(-2)
                    .foregroundColor(Palette.paper)
                    .opacity(s4.opacity).offset(y: s4.y - 58).blur(radius: s4.blur)
            }
        }
    }

    // MARK: - Agent montage (the proof beat — slow roll)

    private struct Tick {
        let agent: String
        let message: String
        let accent: Accent
        enum Accent { case none, ok, warn }
    }

    private static let ticks: [Tick] = [
        .init(agent: "claude", message: "bun test — 212 passed", accent: .ok),
        .init(agent: "codex", message: "edit src/channels/web/session.ts", accent: .none),
        .init(agent: "cursor", message: "commit “wire session resume”", accent: .none),
        .init(agent: "opencode", message: "opening review — waiting on you", accent: .warn),
        .init(agent: "grok", message: "build — ok", accent: .ok),
    ]

    private func montageLayer(t: Double) -> some View {
        let gone = 1 - seg(t, 0.585, 0.615)
        return VStack(alignment: .leading, spacing: 15) {
            ForEach(Array(Self.ticks.enumerated()), id: \.offset) { i, tick in
                let beat = 0.28 + Double(i) * 0.058
                let p = easeOutCubic(seg(t, beat, beat + 0.055))
                // Predecessors step back as the next line lands — a rolling log, not a list.
                let age = seg(t, beat + 0.058, beat + 0.11)
                if p > 0 {
                    HStack(spacing: 12) {
                        AgentLogo(agent: tick.agent, size: 26)
                        Text(tick.agent)
                            .font(.system(size: 19, weight: .medium, design: .monospaced))
                            .foregroundColor(Palette.montageInk)
                            .frame(width: 104, alignment: .leading)
                        (Text("▸ ").foregroundColor(Palette.montageDim)
                            + Text(tick.message).foregroundColor(accentColor(tick.accent)))
                        .font(.system(size: 19, design: .monospaced))
                    }
                    .opacity(p * gone * (1 - 0.45 * age))
                    .offset(y: (1 - p) * 16)
                    .blur(radius: (1 - p) * 4)
                }
            }
        }
        .offset(y: -20)
        .opacity(gone)
    }

    private func accentColor(_ accent: Tick.Accent) -> Color {
        switch accent {
        case .ok: return Palette.ok
        case .warn: return Palette.warn
        case .none: return Palette.montageInk
        }
    }

    // MARK: - Platform roll-call (glass chips, Apple-style spring stagger)

    private static let platforms: [(name: String, glyph: PlatformGlyph)] = [
        ("mac", .apple), ("windows", .windows), ("linux", .linux),
        ("browser", .globe), ("ios", .iphone), ("android", .android),
    ]

    private func platformRow(t: Double, elapsed: Double) -> some View {
        HStack(spacing: 16) {
            ForEach(Array(Self.platforms.enumerated()), id: \.offset) { i, platform in
                let beat = 0.665 + Double(i) * 0.030
                let p = seg(t, beat, beat + 0.05)
                let e = easeOutBack(p) // spring overshoot — the Apple landing
                let settled = seg(t, beat + 0.05, beat + 0.12)
                let bob = sin(elapsed * 1.3 + Double(i) * 0.9) * 2.5 * settled
                VStack(spacing: 7) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(.white.opacity(0.08))
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(.white.opacity(0.16), lineWidth: 0.75)
                        PlatformGlyphView(glyph: platform.glyph)
                            .frame(width: 24, height: 24)
                            .foregroundColor(.white)
                    }
                    .frame(width: 52, height: 52)
                    Text(platform.name)
                        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                        .foregroundColor(.white.opacity(0.62))
                }
                .scaleEffect(0.5 + 0.5 * e)
                .opacity(p)
                .blur(radius: (1 - p) * 5)
                .offset(y: (1 - e) * 18 + bob)
            }
        }
        .offset(y: 34)
        .opacity(1 - seg(t, 0.855, 0.885))
    }

    // MARK: - Dusk sunset finale (moving Apple-glass mesh, holds until dismissed)

    /// Deep dusk sunset — wine/coral/amber kept DARK enough that white glass pops, but still
    /// unmistakably sunset. Deliberately no bright gold fields under the text.
    private static let sunsetColors: [Color] = [
        Color(red: 0.09, green: 0.06, blue: 0.18), // indigo night
        Color(red: 0.24, green: 0.11, blue: 0.32), // plum
        Color(red: 0.52, green: 0.19, blue: 0.36), // wine
        Color(red: 0.36, green: 0.14, blue: 0.33), // orchid dusk
        Color(red: 0.72, green: 0.27, blue: 0.28), // burnt coral
        Color(red: 0.82, green: 0.40, blue: 0.24), // muted tangerine
        Color(red: 0.16, green: 0.10, blue: 0.26), // violet
        Color(red: 0.58, green: 0.22, blue: 0.33), // rose ember
        Color(red: 0.88, green: 0.52, blue: 0.28), // amber horizon
    ]

    private func sunsetLayer(t: Double, elapsed: Double) -> some View {
        let washIn = seg(t, 0.83, 0.90)
        // Slow sinusoidal drift of the interior mesh points — the "living wallpaper" feel.
        // Every point stays clamped to the unit square: letting one wander past an edge paints
        // a black sliver (the flash that plagued the earlier cut).
        func cl(_ v: Float) -> Float { min(max(v, 0), 1) }
        let d1 = Float(sin(elapsed * 0.45) * 0.08)
        let d2 = Float(cos(elapsed * 0.38) * 0.08)
        let d3 = Float(sin(elapsed * 0.30 + 1.7) * 0.05)
        return MeshGradient(
            width: 3, height: 3,
            points: [
                [0, 0], [0.5, 0], [1, 0],
                [0, cl(0.5 + d3)], [cl(0.5 + d1), cl(0.5 + d2)], [1, cl(0.5 - d3)],
                [0, 1], [cl(0.5 - d2), 1], [1, 1],
            ],
            colors: Self.sunsetColors,
        )
        .opacity(washIn)
    }

    private func finaleLayer(t: Double, elapsed: Double) -> some View {
        let markP = easeOutCubic(seg(t, 0.90, 0.955))
        let markScale = 1.12 - 0.12 * easeOutCubic(seg(t, 0.90, 0.97))
        let markBlur = (1 - markP) * 12
        let wmA = seg(t, 0.945, 0.975)
        let tgA = seg(t, 0.955, 0.985)
        let btnA = easeOutCubic(seg(t, 0.975, 0.997))
        return ZStack {
            if t >= 0.62, t < 0.895 { platformRow(t: t, elapsed: elapsed) }
            Canvas { gc, size in
                guard markP > 0 else { return }
                let center = CGPoint(x: size.width / 2, y: size.height * 0.38)
                let s: CGFloat = 140 * markScale
                var m = gc
                m.opacity = markP
                m.translateBy(x: center.x - s / 2, y: center.y - s / 2)
                m.scaleBy(x: s / SupermuxMark.viewBox, y: s / SupermuxMark.viewBox)
                // Flat glass-white on the sunset — no glow, no teal.
                let grad = GraphicsContext.Shading.linearGradient(
                    Gradient(colors: [.white, .white.opacity(0.82)]),
                    startPoint: SupermuxMark.gradientStart, endPoint: SupermuxMark.gradientEnd,
                )
                for path in SupermuxMark.paths { m.fill(path, with: grad) }
            }
            .blur(radius: markBlur)

            VStack(spacing: 12) {
                Text("supermux")
                    .font(.system(size: 34, weight: .semibold))
                    .tracking(-0.7)
                    .foregroundColor(.white)
                    .opacity(wmA)
                Text("AFK. STILL SHIPPING.")
                    .font(.system(size: 14, weight: .medium, design: .monospaced))
                    .tracking(4)
                    .foregroundColor(.white.opacity(0.85))
                    .opacity(tgA)
                StartNowButton(action: finish)
                    .opacity(btnA)
                    .offset(y: (1 - btnA) * 14)
                    .blur(radius: (1 - btnA) * 4)
                    .padding(.top, 16)
            }
            .offset(y: 128)
        }
    }

    private func hintLayer(t: Double) -> some View {
        let early = seg(t, 0.10, 0.18) * 0.7 * (1 - seg(t, 0.60, 0.70))
        return Text("click anywhere to skip")
            .font(.system(size: 11, design: .monospaced))
            .foregroundColor(.white.opacity(0.6))
            .opacity(early)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            .padding(20)
    }

    // MARK: - Timeline helpers

    private func seg(_ t: Double, _ a: Double, _ b: Double) -> Double {
        t <= a ? 0 : t >= b ? 1 : (t - a) / (b - a)
    }
    private func easeOutCubic(_ p: Double) -> Double { let u = 1 - p; return 1 - u * u * u }
    private func easeOutBack(_ p: Double) -> Double {
        let c1 = 1.70158, c3 = c1 + 1
        return 1 + c3 * pow(p - 1, 3) + c1 * pow(p - 1, 2)
    }
}

// MARK: - Start Now button (finale CTA)

/// Glass capsule over the sunset: translucent white fill + hairline, hover lifts and brightens
/// (arrow nudges right), press squishes. The ONLY click target that dismisses the finale.
private struct StartNowButton: View {
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text("Start Now")
                    .font(.system(size: 15, weight: .semibold))
                    .tracking(0.2)
                Image(systemName: "arrow.right")
                    .font(.system(size: 12.5, weight: .bold))
                    .offset(x: hovering ? 3 : 0)
            }
            .foregroundColor(.white)
            .padding(.horizontal, 28)
            .padding(.vertical, 13)
            .background(Capsule().fill(.white.opacity(hovering ? 0.24 : 0.13)))
            .overlay(Capsule().strokeBorder(.white.opacity(hovering ? 0.5 : 0.28), lineWidth: 1))
            .shadow(color: .black.opacity(hovering ? 0.28 : 0.16), radius: hovering ? 16 : 9, y: 5)
        }
        .buttonStyle(PressSquishStyle())
        .scaleEffect(hovering ? 1.045 : 1)
        .onHover { hovering = $0 }
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: hovering)
    }
}

private struct PressSquishStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.955 : 1)
            .animation(.spring(response: 0.22, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Platform glyphs

private enum PlatformGlyph { case apple, windows, linux, globe, iphone, android }

/// SF Symbols where Apple ships a trustworthy mark; hand-drawn Shapes where trademarks force it
/// (the Windows flag and the Android head — two tiny paths, not third-party assets).
private struct PlatformGlyphView: View {
    let glyph: PlatformGlyph
    var body: some View {
        switch glyph {
        case .apple: Image(systemName: "apple.logo").font(.system(size: 21, weight: .medium))
        case .linux: Image(systemName: "terminal").font(.system(size: 19, weight: .medium))
        case .globe: Image(systemName: "globe").font(.system(size: 19, weight: .medium))
        case .iphone: Image(systemName: "iphone").font(.system(size: 20, weight: .medium))
        case .windows: WindowsShape().fill(.white)
        case .android:
            ZStack {
                AndroidHeadShape().fill(.white, style: FillStyle(eoFill: true))
                AndroidAntennasShape().stroke(.white, style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
            }
        }
    }
}

/// The four-pane flag, slightly perspective-skewed like the real mark.
private struct WindowsShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let gap: CGFloat = r.width * 0.08
        let paneW = (r.width - gap) / 2
        let paneH = (r.height - gap) / 2
        // Left column leans shorter (the flag's tilt); right column is full height.
        let leftTop = r.minY + r.height * 0.10
        let leftBottom = r.maxY - r.height * 0.10
        p.addRect(CGRect(x: r.minX, y: leftTop, width: paneW, height: (leftBottom - leftTop - gap) / 2))
        p.addRect(CGRect(x: r.minX, y: leftTop + (leftBottom - leftTop + gap) / 2, width: paneW, height: (leftBottom - leftTop - gap) / 2))
        p.addRect(CGRect(x: r.minX + paneW + gap, y: r.minY, width: paneW, height: paneH))
        p.addRect(CGRect(x: r.minX + paneW + gap, y: r.minY + paneH + gap, width: paneW, height: paneH))
        return p
    }
}

/// Android head: dome + flat chin, eyes punched out with even-odd fill.
private struct AndroidHeadShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let c = CGPoint(x: r.midX, y: r.minY + r.height * 0.64)
        let rad = r.width * 0.40
        p.addArc(center: c, radius: rad, startAngle: .degrees(180), endAngle: .degrees(0), clockwise: false)
        p.closeSubpath()
        let eyeR = r.width * 0.05
        let eyeY = c.y - rad * 0.38
        p.addEllipse(in: CGRect(x: c.x - rad * 0.45 - eyeR, y: eyeY - eyeR, width: eyeR * 2, height: eyeR * 2))
        p.addEllipse(in: CGRect(x: c.x + rad * 0.45 - eyeR, y: eyeY - eyeR, width: eyeR * 2, height: eyeR * 2))
        return p
    }
}

private struct AndroidAntennasShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        let c = CGPoint(x: r.midX, y: r.minY + r.height * 0.64)
        let rad = r.width * 0.40
        let apexY = c.y - rad
        p.move(to: CGPoint(x: c.x - rad * 0.5, y: apexY + rad * 0.14))
        p.addLine(to: CGPoint(x: c.x - rad * 0.85, y: apexY - rad * 0.45))
        p.move(to: CGPoint(x: c.x + rad * 0.5, y: apexY + rad * 0.14))
        p.addLine(to: CGPoint(x: c.x + rad * 0.85, y: apexY - rad * 0.45))
        return p
    }
}

// MARK: - Palette + helpers

/// High-contrast film palette: dead-of-night canvas, warm paper type, luminous teal accents
/// (site --teal brightened for dark), dim mono for the montage. Everything earns its contrast.
private enum Palette {
    static let bg = Color(red: 7 / 255, green: 9 / 255, blue: 11 / 255)
    static let paper = Color(red: 242 / 255, green: 240 / 255, blue: 236 / 255)
    static let teal = Color(red: 15 / 255, green: 181 / 255, blue: 163 / 255) // site --teal
    static let tealBright = Color(red: 46 / 255, green: 230 / 255, blue: 208 / 255)
    static let ok = Color(red: 62 / 255, green: 207 / 255, blue: 142 / 255)
    static let warn = Color(red: 245 / 255, green: 165 / 255, blue: 36 / 255) // site --amber
    static let montageInk = Color(red: 200 / 255, green: 214 / 255, blue: 208 / 255)
    static let montageDim = Color(red: 91 / 255, green: 112 / 255, blue: 105 / 255)
}

private extension CGRect {
    init(center: CGPoint, radius: CGFloat) {
        self.init(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2)
    }
}

/// Show policy (evaluated once at startup):
/// - SM_INTRO=1 → always show (screenshot/dev runs; the seen flag is NOT persisted, so a forced
///   run never consumes a real user's one viewing).
/// - SM_INTRO=0 → never show.
/// - SM_PAIR_TOKEN set → never show: that's a seeded dev/CI run, and the intro overlay would
///   hijack every headless verification screenshot.
/// - otherwise → show exactly once per [version], until [markSeen].
enum IntroPolicy {
    private static let seenKey = "macIntroSeenVersion"
    /// v1 "mux boot", v2 "A Day, Shipped" (retired), v3 "The Manifesto" (kinetic type → sunset).
    private static let version = 3

    static func shouldShow() -> Bool {
        let env = ProcessInfo.processInfo.environment
        if env["SM_INTRO"] == "1" { return true }
        if env["SM_INTRO"] == "0" { return false }
        if let t = env["SM_PAIR_TOKEN"], !t.isEmpty { return false }
        return UserDefaults.standard.integer(forKey: seenKey) < version
    }

    /// Persist the seen flag unless this viewing was forced (SM_INTRO=1 is for screenshots).
    static func markSeen() {
        if ProcessInfo.processInfo.environment["SM_INTRO"] != "1" {
            UserDefaults.standard.set(version, forKey: seenKey)
        }
    }
}
#endif
