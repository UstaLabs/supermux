import SwiftUI
import Shared

#if os(iOS)

// MARK: - Palette (mirrors supermux.dev "A day, scrolled")

private enum Sky {
    static let paper = Color(red: 0.98, green: 0.969, blue: 0.949)
    static let ink = Color(red: 0.063, green: 0.094, blue: 0.157)
    static let inkSoft = Color(red: 0.239, green: 0.290, blue: 0.369)
    static let teal = Color(red: 0.059, green: 0.710, blue: 0.639)
    static let tealDeep = Color(red: 0.039, green: 0.553, blue: 0.502)
    static let amber = Color(red: 0.961, green: 0.647, blue: 0.141)
    static let coral = Color(red: 1.0, green: 0.420, blue: 0.341)
    static let blue = Color(red: 0.290, green: 0.659, blue: 0.910)
    static let green = Color(red: 0.373, green: 0.761, blue: 0.427)
}

// MARK: - Onboarding root

// The whole onboarding is one fixed illustration — a light "day sky" with ink text,
// white agent chips and a dark terminal card. Those colors are hand-picked against
// each other, so the screen is pinned to the light scheme (like ManifestoIntroView
// pins itself to dark) instead of following the system/app appearance.
struct OnboardingView: View {
    var onPaired: (PairToken) -> Void
    @State private var page = 0

    private let pageCount = 4

    var body: some View {
        ZStack {
            SkyBackground(page: page, pageCount: pageCount)
            GrainOverlay()

            VStack(spacing: 0) {
                TabView(selection: $page) {
                    HookPage().tag(0)
                    AgentsPage().tag(1)
                    AlwaysOnPage().tag(2)
                    ConnectPage(onPaired: onPaired).tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(nil, value: page)

                DayRail(page: page, pageCount: pageCount) { tapped in
                    if tapped == pageCount - 1 {
                        withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) { page = tapped }
                    } else {
                        page = tapped
                    }
                }
                .padding(.bottom, 14)

                primaryButton
                    .padding(.horizontal, 24)
                    .padding(.bottom, 36)
            }
        }
        .environment(\.colorScheme, .light)
        .preferredColorScheme(.light)
    }

    private var primaryButton: some View {
        Button {
            guard page < pageCount - 1 else { return }
            withAnimation(.spring(response: 0.5, dampingFraction: 0.85)) {
                page += 1
            }
        } label: {
            HStack(spacing: 10) {
                Text(buttonLabel)
                    .font(.headline)
                if page < pageCount - 1 {
                    Image(systemName: "arrow.right")
                        .font(.subheadline.weight(.bold))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 17)
            .foregroundStyle(.white)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Sky.ink)
                    .shadow(color: Sky.ink.opacity(0.25), radius: 14, y: 6)
            )
        }
        .buttonStyle(PressableButton())
        .opacity(page == pageCount - 1 ? 0 : 1)
        .allowsHitTesting(page != pageCount - 1)
    }

    private var buttonLabel: String {
        switch page {
        case 0: return "See what it does"
        case 1: return "It never sleeps"
        case 2: return "Connect your computer"
        default: return "Continue"
        }
    }
}

private struct PressableButton: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.7), value: configuration.isPressed)
    }
}

// MARK: - Sky background (a day, condensed)

private struct SkyBackground: View {
    let page: Int
    let pageCount: Int

    private struct Phase {
        let top: Color, mid: Color, low: Color
        let orb: Color
        let orbX: CGFloat
        let orbY: CGFloat
        let orbScale: CGFloat
        let orbGlow: CGFloat
    }

    private var phases: [Phase] {
        [
            Phase(top: Color(red: 1.0, green: 0.89, blue: 0.72), mid: Color(red: 0.81, green: 0.91, blue: 0.96), low: Sky.paper, orb: Color(red: 1.0, green: 0.83, blue: 0.41), orbX: 0.16, orbY: 0.20, orbScale: 1.0, orbGlow: 0.55),
            Phase(top: Color(red: 0.81, green: 0.91, blue: 0.96), mid: Color(red: 0.88, green: 0.95, blue: 0.96), low: Sky.paper, orb: Color(red: 1.0, green: 0.87, blue: 0.52), orbX: 0.5, orbY: 0.10, orbScale: 0.85, orbGlow: 0.45),
            Phase(top: Color(red: 1.0, green: 0.83, blue: 0.71), mid: Color(red: 0.96, green: 0.87, blue: 0.82), low: Sky.paper, orb: Color(red: 1.0, green: 0.70, blue: 0.42), orbX: 0.82, orbY: 0.24, orbScale: 0.9, orbGlow: 0.5),
            Phase(top: Color(red: 0.85, green: 0.87, blue: 0.93), mid: Color(red: 0.90, green: 0.91, blue: 0.95), low: Sky.paper, orb: Color(red: 0.85, green: 0.88, blue: 0.95), orbX: 0.82, orbY: 0.14, orbScale: 0.55, orbGlow: 0.3),
        ]
    }

    var body: some View {
        let p = phases[min(page, phases.count - 1)]
        GeometryReader { geo in
            ZStack {
                LinearGradient(
                    colors: [p.top, p.mid, p.low],
                    startPoint: .top, endPoint: .bottom
                )

                Circle()
                    .fill(
                        RadialGradient(
                            colors: [p.orb, p.orb.opacity(0)],
                            center: .center, startRadius: 0, endRadius: 90
                        )
                    )
                    .frame(width: 180, height: 180)
                    .shadow(color: p.orb.opacity(p.orbGlow), radius: 60)
                    .position(x: geo.size.width * p.orbX, y: geo.size.height * p.orbY)
                    .scaleEffect(p.orbScale)

                DriftingClouds()
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.9), value: page)
    }
}

private struct DriftingClouds: View {
    @State private var drift = false
    var body: some View {
        GeometryReader { geo in
            ZStack {
                CloudShape()
                    .fill(.white.opacity(0.5))
                    .frame(width: 260, height: 60)
                    .blur(radius: 2)
                    .offset(x: drift ? geo.size.width * 0.7 : -geo.size.width * 0.4, y: geo.size.height * 0.12)
                    .animation(.linear(duration: 70).repeatForever(autoreverses: false), value: drift)
                CloudShape()
                    .fill(.white.opacity(0.35))
                    .frame(width: 190, height: 46)
                    .blur(radius: 3)
                    .offset(x: drift ? -geo.size.width * 0.5 : geo.size.width * 0.8, y: geo.size.height * 0.30)
                    .animation(.linear(duration: 95).repeatForever(autoreverses: false), value: drift)
            }
        }
        .onAppear { drift = true }
    }
}

private struct CloudShape: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let h = rect.height
        p.addEllipse(in: CGRect(x: 0, y: h * 0.35, width: h * 1.1, height: h * 0.65))
        p.addEllipse(in: CGRect(x: rect.width * 0.22, y: 0, width: h * 1.3, height: h * 0.9))
        p.addEllipse(in: CGRect(x: rect.width * 0.5, y: h * 0.25, width: h * 1.2, height: h * 0.75))
        return p
    }
}

private struct GrainOverlay: View {
    var body: some View {
        Canvas { context, size in
            var rng = SeededRandom(seed: 42)
            let step: CGFloat = 3
            var x: CGFloat = 0
            while x < size.width {
                var y: CGFloat = 0
                while y < size.height {
                    if rng.next() < 0.08 {
                        let gray = 0.4 + rng.next() * 0.4
                        context.fill(
                            Path(CGRect(x: x, y: y, width: 1, height: 1)),
                            with: .color(Color(white: gray).opacity(0.05))
                        )
                    }
                    y += step
                }
                x += step
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .blendMode(.overlay)
    }
}

private struct SeededRandom {
    private var state: UInt64
    init(seed: UInt64) { state = seed &+ 0x9E3779B97F4A7C15 }
    mutating func next() -> Double {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return Double(state % 10_000) / 10_000
    }
}

// MARK: - Shared atoms

private struct Stamp: View {
    let text: String
    var appear: Bool
    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Sky.teal)
                .frame(width: 7, height: 7)
                .opacity(appear ? 1 : 0)
                .scaleEffect(appear ? 1 : 0.3)
            Text(text.uppercased())
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .tracking(1.2)
                .foregroundStyle(Sky.inkSoft)
                .opacity(appear ? 1 : 0)
                .offset(y: appear ? 0 : 6)
        }
        .animation(.spring(response: 0.5, dampingFraction: 0.8).delay(0.1), value: appear)
    }
}

private struct WordReveal: View {
    let words: [String]
    var appear: Bool
    var font: Font = .system(size: 46, weight: .heavy, design: .rounded)
    var baseColor: Color = Sky.ink
    var accentWord: String? = nil
    var accentColor: Color = Sky.teal
    var delayStep: Double = 0.12

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(Array(words.enumerated()), id: \.offset) { i, word in
                Text(word)
                    .font(font)
                    .foregroundStyle(word == accentWord ? accentColor : baseColor)
                    .opacity(appear ? 1 : 0)
                    .offset(y: appear ? 0 : 22)
                    .blur(radius: appear ? 0 : 6)
                    .animation(
                        .spring(response: 0.55, dampingFraction: 0.75)
                            .delay(0.25 + Double(i) * delayStep),
                        value: appear
                    )
            }
        }
    }
}

private struct DayRail: View {
    let page: Int
    let pageCount: Int
    let onTap: (Int) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<pageCount, id: \.self) { i in
                Button { onTap(i) } label: {
                    Capsule()
                        .fill(i <= page ? Sky.teal : Sky.ink.opacity(0.15))
                        .frame(width: i == page ? 26 : 8, height: 8)
                        .overlay(
                            Capsule().strokeBorder(Sky.ink.opacity(0.08), lineWidth: 0.5)
                        )
                }
                .buttonStyle(.plain)
                if i < pageCount - 1 {
                    Rectangle()
                        .fill(Sky.ink.opacity(0.1))
                        .frame(width: 10, height: 1.5)
                }
            }
        }
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: page)
    }
}

// MARK: - Page 1 · the hook

private struct HookPage: View {
    @State private var appear = false

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Spacer().frame(height: 40)

            Stamp(text: "open-source · mobile-first · ADE", appear: appear)

            WordReveal(
                words: ["AFK.", "Still shipping."],
                appear: appear,
                accentWord: "Still shipping."
            )

            Text("supermux runs your coding agents on a computer you own — and hands you every session on every screen you carry.")
                .font(.system(size: 17, weight: .regular, design: .rounded))
                .foregroundStyle(Sky.inkSoft)
                .lineSpacing(5)
                .opacity(appear ? 1 : 0)
                .offset(y: appear ? 0 : 16)
                .animation(.spring(response: 0.55, dampingFraction: 0.8).delay(0.62), value: appear)

            HStack(spacing: 6) {
                ForEach(["MIT", "self-hosted", "no vendor cloud", "no account"], id: \.self) { item in
                    if item != "MIT" {
                        Circle().fill(Sky.ink.opacity(0.25)).frame(width: 2.5, height: 2.5)
                    }
                    Text(item)
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundStyle(Sky.inkSoft.opacity(0.85))
                }
            }
            .opacity(appear ? 1 : 0)
            .animation(.easeOut(duration: 0.5).delay(0.85), value: appear)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 28)
        .onAppear { appear = true }
    }
}

// MARK: - Page 2 · bring your own subscription

private struct AgentsPage: View {
    @State private var appear = false

    private let agents: [(name: String, color: Color)] = [
        ("claude", Sky.coral),
        ("codex", Sky.green),
        ("cursor", Sky.blue),
        ("opencode", Sky.amber),
        ("grok", Sky.teal),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer().frame(height: 40)

            Stamp(text: "bring your own subscription", appear: appear)

            WordReveal(
                words: ["Your agents.", "Your keys."],
                appear: appear,
                accentWord: "Your keys."
            )

            HStack(spacing: -6) {
                ForEach(Array(agents.enumerated()), id: \.offset) { i, agent in
                    ZStack {
                        Circle()
                            .fill(.white.opacity(0.9))
                            .frame(width: 56, height: 56)
                            .shadow(color: agent.color.opacity(0.35), radius: 10, y: 4)
                            .overlay(
                                Circle().strokeBorder(agent.color.opacity(0.5), lineWidth: 1.5)
                            )
                        AgentLogo(agent: agent.name, size: 30)
                    }
                    .opacity(appear ? 1 : 0)
                    .scaleEffect(appear ? 1 : 0.4)
                    .offset(y: appear ? floatOffset(i) : 30)
                    .animation(
                        .spring(response: 0.5, dampingFraction: 0.65)
                            .delay(0.35 + Double(i) * 0.09),
                        value: appear
                    )
                    .zIndex(Double(agents.count - i))
                }
            }
            .padding(.leading, 4)

            Text("Claude Code, Codex, Cursor, OpenCode & Grok — running on your machine, with your plan. No middleman, no markup.")
                .font(.system(size: 17, weight: .regular, design: .rounded))
                .foregroundStyle(Sky.inkSoft)
                .lineSpacing(5)
                .opacity(appear ? 1 : 0)
                .offset(y: appear ? 0 : 16)
                .animation(.spring(response: 0.55, dampingFraction: 0.8).delay(0.75), value: appear)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 28)
        .onAppear { appear = true }
    }

    private func floatOffset(_ i: Int) -> CGFloat {
        CGFloat(i % 2 == 0 ? -3 : 3)
    }
}

// MARK: - Page 3 · always-on sessions

private struct AlwaysOnPage: View {
    @State private var appear = false

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Spacer().frame(height: 40)

            Stamp(text: "always-on sessions", appear: appear)

            WordReveal(
                words: ["You leave.", "It doesn't."],
                appear: appear,
                accentWord: "It doesn't."
            )

            MeanwhileTicker(appear: appear)

            Text("Sessions live on your box, not a browser tab. Walk out the door and the work keeps moving — a push lands the moment you're needed.")
                .font(.system(size: 17, weight: .regular, design: .rounded))
                .foregroundStyle(Sky.inkSoft)
                .lineSpacing(5)
                .opacity(appear ? 1 : 0)
                .offset(y: appear ? 0 : 16)
                .animation(.spring(response: 0.55, dampingFraction: 0.8).delay(0.7), value: appear)

            Spacer()
            Spacer()
        }
        .padding(.horizontal, 28)
        .onAppear { appear = true }
    }
}

private struct MeanwhileTicker: View {
    let appear: Bool
    @State private var typedLines = 0
    @State private var cursorVisible = true

    private let lines: [(text: String, tint: Color?)] = [
        ("▸ bun test — 212 passed", Sky.green),
        ("▸ edit src/channels/web/session.ts", nil),
        ("▸ commit \"wire session resume\"", nil),
        ("▸ opening review — waiting on you", Sky.amber),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Sky.teal)
                    .frame(width: 7, height: 7)
                    .opacity(cursorVisible ? 1 : 0.3)
                Text("meanwhile, on your computer")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .tracking(0.8)
                    .foregroundStyle(Color(red: 0.65, green: 0.71, blue: 0.80))
            }
            .padding(.bottom, 12)

            VStack(alignment: .leading, spacing: 9) {
                ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                    Text(line.text)
                        .font(.system(size: 13, weight: .medium, design: .monospaced))
                        .foregroundStyle(line.tint ?? Color(red: 0.82, green: 0.86, blue: 0.92))
                        .opacity(i < typedLines ? 1 : 0)
                        .offset(x: i < typedLines ? 0 : -10)
                        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: typedLines)
                }
            }
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color(red: 0.07, green: 0.07, blue: 0.09).opacity(0.96))
                .shadow(color: Sky.ink.opacity(0.3), radius: 20, y: 8)
        )
        .opacity(appear ? 1 : 0)
        .offset(y: appear ? 0 : 24)
        .animation(.spring(response: 0.55, dampingFraction: 0.8).delay(0.4), value: appear)
        .onAppear {
            Timer.scheduledTimer(withTimeInterval: 0.55, repeats: true) { _ in
                withAnimation { cursorVisible.toggle() }
            }
            Task {
                try? await Task.sleep(nanoseconds: 700_000_000)
                for i in 0...lines.count {
                    try? await Task.sleep(nanoseconds: 480_000_000)
                    withAnimation { typedLines = i }
                }
            }
        }
    }
}

// MARK: - Page 4 · connect

private struct ConnectPage: View {
    var onPaired: (PairToken) -> Void
    @State private var fleet = Fleet()
    @State private var input = ""
    @State private var error: String?
    @State private var busy = false
    @State private var showScanner = false
    @State private var appear = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 22) {
                    Stamp(text: "pair your device", appear: appear)

                    WordReveal(
                        words: ["Connect to", "your computer."],
                        appear: appear,
                        font: .system(size: 38, weight: .heavy, design: .rounded),
                        accentWord: "your computer."
                    )

                    Text("Open supermux on your computer and scan the pairing code — you'll be briefed in seconds.")
                        .font(.system(size: 16, weight: .regular, design: .rounded))
                        .foregroundStyle(Sky.inkSoft)
                        .lineSpacing(4)
                        .opacity(appear ? 1 : 0)
                        .animation(.easeOut(duration: 0.4).delay(0.5), value: appear)

                    scanButton
                        .opacity(appear ? 1 : 0)
                        .offset(y: appear ? 0 : 18)
                        .animation(.spring(response: 0.5, dampingFraction: 0.8).delay(0.6), value: appear)

                    if busy {
                        HStack(spacing: 10) {
                            ProgressView().tint(Sky.teal)
                            Text("Pairing…")
                                .font(.system(size: 14, weight: .medium, design: .rounded))
                                .foregroundStyle(Sky.inkSoft)
                        }
                        .padding(.top, 2)
                    }

                    if let error {
                        Text(error)
                            .font(.system(size: 13, weight: .medium, design: .rounded))
                            .foregroundStyle(Sky.coral)
                            .lineSpacing(3)
                    }

                    pasteSection
                        .opacity(appear ? 1 : 0)
                        .animation(.easeOut(duration: 0.4).delay(0.75), value: appear)
                }
                .padding(.horizontal, 28)
                .padding(.top, 36)
                .padding(.bottom, 20)
            }
        }
        .onAppear { appear = true }
        .smFullScreenCover(isPresented: $showScanner) {
            ZStack(alignment: .topTrailing) {
                QRScannerView { decoded in
                    showScanner = false
                    claim(decoded)
                }
                .ignoresSafeArea()
                Button { showScanner = false } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title)
                        .foregroundStyle(.white)
                        .padding()
                }
            }
        }
    }

    private var scanButton: some View {
        Button {
            error = nil
            showScanner = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "qrcode.viewfinder")
                    .font(.system(size: 22, weight: .medium))
                VStack(alignment: .leading, spacing: 1) {
                    Text("Scan pairing code")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                    Text("shown by the supermux desktop app")
                        .font(.system(size: 12, weight: .regular, design: .rounded))
                        .opacity(0.7)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .opacity(0.6)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 18)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Sky.tealDeep)
                    .shadow(color: Sky.teal.opacity(0.4), radius: 16, y: 6)
            )
        }
        .buttonStyle(PressableButton())
        .disabled(busy)
    }

    private var pasteSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Rectangle().fill(Sky.ink.opacity(0.12)).frame(height: 1)
                Text("or paste a pairing payload")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .tracking(0.6)
                    .foregroundStyle(Sky.inkSoft.opacity(0.7))
                    // Keep the label on one line — otherwise it wraps and the two rules
                    // slice straight through it.
                    .fixedSize(horizontal: true, vertical: false)
                Rectangle().fill(Sky.ink.opacity(0.12)).frame(height: 1)
            }

            HStack(spacing: 10) {
                TextField("Pairing payload…", text: $input, axis: .vertical)
                    .lineLimit(1...3)
                    .font(.system(size: 14, design: .monospaced))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .fill(.white.opacity(0.7))
                            .overlay(
                                RoundedRectangle(cornerRadius: 14, style: .continuous)
                                    .strokeBorder(Sky.ink.opacity(0.1), lineWidth: 1)
                            )
                    )
                    .autocorrectionDisabled()
                    .smNoAutocapitalization()
                    .onChange(of: input) { _, _ in error = nil }

                Button(action: pair) {
                    Image(systemName: "arrow.right.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(input.trimmed.isEmpty ? Sky.ink.opacity(0.2) : Sky.tealDeep)
                }
                .disabled(busy || input.trimmed.isEmpty)
            }
        }
    }

    private func pair() {
        error = nil
        if PairingPayload.companion.parse(raw: input) != nil {
            claim(input)
            return
        }
        if let p = PairToken.parse(input, fallbackBaseURL: BrokerConfig.baseURL) {
            BrokerConfig.pair(p)
            onPaired(p)
        } else {
            claim(input)
        }
    }

    private func claim(_ raw: String) {
        guard !busy else { return }
        busy = true
        let deviceName = UIDevice.current.name
        Task {
            let result = await fleet.claim(raw: raw, deviceName: deviceName)
            busy = false
            switch result {
            case .added(let host):
                guard let base = [host.relayUrl, host.directUrl]
                    .compactMap({ $0 })
                    .first(where: { !$0.isEmpty }) else {
                    error = "The host did not return a usable address."
                    return
                }
                let pair = PairToken(baseURL: base, token: host.token)
                fleet.stop()
                BrokerConfig.pair(pair)
                onPaired(pair)
            case .needsClaim:
                error = "That host needs a fresh pairing code from its desktop app."
            case .error(let message):
                error = message
            }
        }
    }
}

private extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
}

#endif
