//
//  AppletDocViews.swift
//  SupermuxWidgets
//
//  #165: an applet's widget drawn from its native blocks (AppletWidgetDoc.swift): the layout for the family it was
//  placed as, in the system's colours and the applet's accent, rows that open the applet at their item (medium and up;
//  a small widget is one link), buttons and toggles that run the applet's actions (App Intents), and the pictures the
//  app drew from its widget.html, each with its VoiceOver label. The Lock Screen families are always native.
//

import AppIntents
import SwiftUI
import WidgetKit

struct DocContext {
    let host: String
    let applet: WidgetApplet
    let doc: WidgetDoc
    let size: WidgetSizeKey
    let accent: Color
    let dark: Bool
    /// Rows can link to their own item (not in a small widget: it has one link).
    let rowLinks: Bool

    func confirm(_ action: String) -> Bool { applet.confirm?.contains(action) == true }
    func picture(_ part: String) -> UIImage? {
        let key = "\(size.rawValue)/\(part)/\(dark ? "dark" : "light")"
        return AppletSnapshot.picture(applet.pictures?[key] ?? widgetPictureFile(host: host, applet: applet.name, size: size, part: part, dark: dark))
    }
    func intent(_ b: WidgetButtonRef) -> RunWidgetActionIntent {
        RunWidgetActionIntent(host: host, applet: applet.name, action: b.action, args: b.args?.json ?? "{}")
    }
    func open(_ at: String?) -> URL? { AppletSnapshot.url(host: host, name: applet.name, at: at) }
}

func hexColor(_ hex: String?) -> Color {
    guard let hex, hex.count == 7, hex.hasPrefix("#"), let v = UInt32(hex.dropFirst(), radix: 16) else { return Color(red: 0.29, green: 0.42, blue: 0.92) }
    return Color(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255, blue: Double(v & 0xff) / 255)
}

func toneColor(_ t: String?) -> Color? {
    switch t {
    case "up", "ok": return .green
    case "down", "bad": return .red
    case "warn": return .orange
    default: return nil
    }
}

struct DocBlocksView: View {
    let ctx: DocContext
    let blocks: [WidgetBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(blocks.prefix(12).enumerated()), id: \.offset) { _, b in BlockView(ctx: ctx, block: b) }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

struct BlockView: View {
    let ctx: DocContext
    let block: WidgetBlock

    var body: some View {
        switch block {
        case .header(let title, let sub):
            HStack(spacing: 6) {
                AppletIconView(applet: ctx.applet, size: 18)
                Text(title ?? ctx.applet.title).font(.system(size: 13, weight: .semibold)).foregroundStyle(ctx.accent).lineLimit(1).widgetAccentable()
                Spacer(minLength: 4)
                if let sub, ctx.size != .small { Text(sub).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
            }
            .padding(.bottom, 2)
        case .stat(let value, let label, let trend, let tone, let size):
            VStack(alignment: .leading, spacing: 1) {
                Text(value).font(.system(size: size == "sm" ? 24 : size == "lg" ? 44 : 34, weight: .bold)).monospacedDigit()
                    .foregroundStyle(trend == nil ? (toneColor(tone) ?? .primary) : .primary).minimumScaleFactor(0.5).lineLimit(1)
                if let label { Text(label).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(2) }
                if let trend { Text(trend).font(.system(size: 12, weight: .semibold)).foregroundStyle(toneColor(tone) ?? .secondary).lineLimit(1) }
            }
        case .stats(let items):
            HStack(spacing: 6) {
                ForEach(Array(items.prefix(4).enumerated()), id: \.offset) { _, it in
                    VStack(alignment: .leading, spacing: 0) {
                        Text(it.value).font(.system(size: 20, weight: .bold)).monospacedDigit().foregroundStyle(toneColor(it.tone) ?? .primary).lineLimit(1).minimumScaleFactor(0.6)
                        if let l = it.label { Text(l).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                    }
                    .padding(.horizontal, 9).padding(.vertical, 7)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        case .progress(let value, let label, let right):
            VStack(alignment: .leading, spacing: 4) {
                HStack { Text(label ?? ""); Spacer(); Text(right ?? "") }.font(.system(size: 12)).foregroundStyle(.secondary)
                ProgressView(value: value).tint(ctx.accent).widgetAccentable()
            }
        case .ring(let value, let text, let sub):
            ZStack {
                Circle().stroke(.quaternary, lineWidth: 7)
                Circle().trim(from: 0, to: value).stroke(ctx.accent, style: StrokeStyle(lineWidth: 7, lineCap: .round)).rotationEffect(.degrees(-90)).widgetAccentable()
                VStack(spacing: 0) {
                    if let text { Text(text).font(.system(size: 18, weight: .bold)) }
                    if let sub { Text(sub).font(.system(size: 11)).foregroundStyle(.secondary) }
                }
            }
            .frame(width: 64, height: 64).frame(maxWidth: .infinity)
        case .chart(let line, let points, _, let label):
            VStack(alignment: .leading, spacing: 2) {
                if let label { Text(label).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                ChartShape(points: points, line: line).fill(line ? AnyShapeStyle(ctx.accent.opacity(0.2)) : AnyShapeStyle(ctx.accent))
                    .overlay { if line { ChartShape(points: points, line: true, outline: true).stroke(ctx.accent, lineWidth: 2) } }
                    .frame(height: 40).widgetAccentable()
                    .accessibilityLabel(Text(label ?? ""))
            }
        case .list(let title, let rows):
            VStack(alignment: .leading, spacing: 0) {
                if let title { Text(title.uppercased()).font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary).lineLimit(1).padding(.bottom, 2) }
                ForEach(Array(rows.prefix(rowsFor(ctx.size)).enumerated()), id: \.offset) { i, r in
                    if i > 0 { Divider() }
                    RowView(ctx: ctx, row: r)
                }
            }
        case .toggles(let grid, let items):
            let shown = Array(items.prefix(ctx.size == .small ? 4 : 6))
            if grid {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)], spacing: 6) {
                    ForEach(Array(shown.enumerated()), id: \.offset) { _, t in ToggleRow(ctx: ctx, t: t, boxed: true) }
                }
            } else {
                VStack(alignment: .leading, spacing: 2) { ForEach(Array(shown.enumerated()), id: \.offset) { _, t in ToggleRow(ctx: ctx, t: t, boxed: false) } }
            }
        case .buttons(let items):
            HStack(spacing: 6) { ForEach(Array(items.enumerated()), id: \.offset) { _, b in ActionButton(ctx: ctx, b: b, wide: true) } }
        case .picture(let part, let height, let label, let caption, let alt):
            if let img = ctx.picture(part) {
                if let caption { Text(caption).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
                Image(uiImage: img).resizable().scaledToFill()
                    .frame(maxWidth: .infinity).frame(height: max(16, height - (caption != nil ? 17 : 0))).clipped()
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .accessibilityLabel(Text(label))
            } else if let alt {
                BlockView(ctx: ctx, block: alt)
            }
        case .text(let t):
            Text(t).font(.system(size: 13, weight: .medium)).lineLimit(2)
        case .columns(let cols, let weights):
            GeometryReader { g in
                let total = max(weights.reduce(0, +), 0.0001)
                let gap: CGFloat = 14
                let inner = g.size.width - gap * CGFloat(cols.count - 1)
                HStack(alignment: .top, spacing: gap) {
                    ForEach(Array(cols.enumerated()), id: \.offset) { i, c in
                        DocBlocksView(ctx: ctx, blocks: c).frame(width: inner * weights[i] / total)
                    }
                }
            }
        case .gap(let s):
            Spacer().frame(height: s == "s" ? 5 : s == "l" ? 12 : 8)
        case .grow:
            Spacer(minLength: 0)
        }
    }
}

private func rowsFor(_ s: WidgetSizeKey) -> Int {
    switch s { case .slim, .wide: return 1; case .small, .medium: return 3; case .tall: return 4; default: return 5 }
}

struct RowView: View {
    let ctx: DocContext
    let row: WidgetRow

    var body: some View {
        HStack(spacing: 8) {
            if ctx.rowLinks, let url = ctx.open(row.href ?? ctx.doc.href) {
                Link(destination: url) { label }
            } else {
                label
            }
            if let b = row.button { ActionButton(ctx: ctx, b: b, wide: false) }
        }
        .padding(.vertical, 4)
    }

    private var label: some View {
        HStack(spacing: 8) {
            Circle().fill(row.color.map(hexColor) ?? ctx.accent).frame(width: 8, height: 8).widgetAccentable()
            VStack(alignment: .leading, spacing: 0) {
                // A row with a button gives its title two lines and drops the meta line.
                Text(row.title).font(.system(size: 13, weight: .medium)).lineLimit(row.button != nil ? 2 : 1)
                if row.button == nil, let sub = row.sub { Text(sub).font(.system(size: 11)).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 4)
            if let v = row.value { Text(v).font(.system(size: 12)).foregroundStyle(.secondary).monospacedDigit() }
        }
        .contentShape(Rectangle())
    }
}

struct ActionButton: View {
    let ctx: DocContext
    let b: WidgetButtonRef
    let wide: Bool

    var body: some View {
        let label = Text(b.title).font(.system(size: 12.5, weight: .semibold)).lineLimit(1)
            .padding(.horizontal, 11).padding(.vertical, 6)
            .frame(maxWidth: wide ? .infinity : nil)
            .foregroundStyle(b.fill ? Color.white : ctx.accent)
            .background(b.fill ? AnyShapeStyle(ctx.accent) : AnyShapeStyle(.quaternary), in: Capsule())
        if ctx.confirm(b.action), let url = ctx.open(ctx.doc.href) {
            // Something hard to undo: the applet asks first.
            Link(destination: url) { label }
        } else {
            Button(intent: ctx.intent(b)) { label }.buttonStyle(.plain)
        }
    }
}

struct ToggleRow: View {
    let ctx: DocContext
    let t: WidgetToggle
    let boxed: Bool

    var body: some View {
        Toggle(isOn: t.on, intent: ctx.intent(WidgetButtonRef(title: t.title, action: t.action, args: t.args, fill: false))) {
            HStack(spacing: 6) {
                Text(t.title).font(.system(size: 13)).lineLimit(1).strikethrough(t.on).foregroundStyle(t.on ? .secondary : .primary)
                Spacer(minLength: 2)
                if let s = t.sub { Text(s).font(.system(size: 11)).foregroundStyle(.secondary) }
            }
        }
        .toggleStyle(CheckStyle(accent: ctx.accent))
        .padding(boxed ? 7 : 2)
        .background(boxed ? AnyShapeStyle(.quaternary) : AnyShapeStyle(.clear), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

struct CheckStyle: ToggleStyle {
    let accent: Color
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            ZStack {
                Circle().strokeBorder(configuration.isOn ? accent : Color.secondary, lineWidth: 1.6)
                if configuration.isOn {
                    Circle().fill(accent)
                    Image(systemName: "checkmark").font(.system(size: 10, weight: .bold)).foregroundStyle(.white)
                }
            }
            .frame(width: 20, height: 20).widgetAccentable()
            configuration.label
        }
    }
}

struct ChartShape: Shape {
    let points: [Double]
    let line: Bool
    var outline = false

    func path(in r: CGRect) -> Path {
        var p = Path()
        let mx = max(points.max() ?? 1, 0.0001)
        let n = points.count
        guard n > 1 else { return p }
        if line {
            let pts = points.enumerated().map { CGPoint(x: r.minX + CGFloat($0.offset) / CGFloat(n - 1) * r.width, y: r.minY + 3 + (1 - CGFloat($0.element / mx)) * (r.height - 6)) }
            p.move(to: pts[0])
            pts.dropFirst().forEach { p.addLine(to: $0) }
            if !outline { p.addLine(to: CGPoint(x: r.maxX, y: r.maxY)); p.addLine(to: CGPoint(x: r.minX, y: r.maxY)); p.closeSubpath() }
        } else {
            let gap: CGFloat = 2
            let bw = (r.width - gap * CGFloat(n - 1)) / CGFloat(n)
            for (i, v) in points.enumerated() {
                let h = max(2, CGFloat(v / mx) * r.height)
                p.addRoundedRect(in: CGRect(x: r.minX + CGFloat(i) * (bw + gap), y: r.maxY - h, width: bw, height: h), cornerSize: CGSize(width: 2, height: 2))
            }
        }
        return p
    }
}

// MARK: - Lock Screen: always native, one colour

struct AccessoryView: View {
    let family: WidgetFamily
    let host: String
    let applet: WidgetApplet
    let doc: WidgetDoc?

    var body: some View {
        switch family {
        case .accessoryCircular: circular
        case .accessoryRectangular: rectangular
        default: inline
        }
    }

    private var summaryLine: String {
        [applet.summary?.value, applet.summary?.label].compactMap { $0 }.joined(separator: " ")
    }

    @ViewBuilder private var circular: some View {
        let c = doc?.circular
        let text = c?.text ?? applet.summary?.value ?? String(applet.title.prefix(2))
        let content = Group {
            if let ring = c?.ring {
                Gauge(value: ring) { Text(c?.label ?? "") } currentValueLabel: { Text(text).font(.system(size: 15, weight: .bold)) }
                    .gaugeStyle(.accessoryCircularCapacity)
            } else {
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 0) {
                        Text(text).font(.system(size: 17, weight: .bold)).minimumScaleFactor(0.6)
                        if let l = c?.label ?? applet.summary?.label { Text(l).font(.system(size: 11)).lineLimit(1) }
                    }
                }
            }
        }
        if let a = c?.action, applet.confirm?.contains(a.action) != true {
            Button(intent: RunWidgetActionIntent(host: host, applet: applet.name, action: a.action, args: a.args?.json ?? "{}")) { content }.buttonStyle(.plain)
        } else {
            content
        }
    }

    @ViewBuilder private var rectangular: some View {
        let r = doc?.rectangular
        let lines = r?.lines ?? [applet.title, summaryLine].filter { !$0.isEmpty }
        VStack(alignment: .leading, spacing: 1) {
            ForEach(Array(lines.enumerated()), id: \.offset) { i, l in
                Text(l).font(i == 0 ? .headline : .system(size: 14)).lineLimit(1).widgetAccentable(i == 0)
            }
            if let b = r?.button, applet.confirm?.contains(b.action) != true {
                Button(intent: RunWidgetActionIntent(host: host, applet: applet.name, action: b.action, args: b.args?.json ?? "{}")) {
                    Text(b.title).font(.system(size: 13, weight: .semibold)).lineLimit(1)
                        .padding(.horizontal, 9).padding(.vertical, 2)
                        .background(.white.opacity(0.22), in: Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var inline: some View {
        Label(doc?.inline ?? (summaryLine.isEmpty ? applet.title : summaryLine), systemImage: "square.grid.2x2")
    }
}
