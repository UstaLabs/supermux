import SwiftUI

/// Which way a `ResizableSplit` divides space.
enum SplitAxis { case horizontal, vertical }

/// Pure geometry for a split divider — extracted so it's unit-testable without a view host.
enum SplitMath {
    /// The first child's size as a percentage of `total`, given a divider at offset `x`, clamped to [lo, hi].
    static func pct(at x: CGFloat, total: CGFloat, min lo: Double, max hi: Double) -> Double {
        guard total > 0 else { return lo }
        return Swift.min(hi, Swift.max(lo, Double(x / total) * 100))
    }
    /// The first child's length in points for a given percentage of `total`.
    static func width(pct: Double, total: CGFloat) -> CGFloat { total * CGFloat(pct / 100) }
}

/// A two-child resizable split (iOS has no `HSplitView`). The first child gets `pct`% of the
/// axis; a draggable `PaneDivider` adjusts `pct` within `range`. Pointer + hover on iPad.
struct ResizableSplit<First: View, Second: View>: View {
    let axis: SplitAxis
    @Binding var pct: Double
    let range: ClosedRange<Double>
    @ViewBuilder var first: First
    @ViewBuilder var second: Second

    var body: some View {
        GeometryReader { geo in
            let total = axis == .horizontal ? geo.size.width : geo.size.height
            let firstLen = SplitMath.width(pct: pct, total: total)
            container {
                first.frame(width: axis == .horizontal ? firstLen : nil,
                            height: axis == .vertical ? firstLen : nil)
                PaneDivider(axis: axis, pct: $pct, total: total, range: range)
                second.frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    @ViewBuilder private func container<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        if axis == .horizontal { HStack(spacing: 0, content: content) }
        else { VStack(spacing: 0, content: content) }
    }
}

/// A 1pt visible divider with a ~24pt draggable hit area. Captures the ratio at drag-start so the
/// cumulative `DragGesture.translation` is applied once (no double-counting).
struct PaneDivider: View {
    let axis: SplitAxis
    @Binding var pct: Double
    let total: CGFloat
    let range: ClosedRange<Double>
    @State private var dragStartPct: Double?

    var body: some View {
        Rectangle()
            .fill(Color.secondary.opacity(0.25))
            .frame(width: axis == .horizontal ? 1 : nil, height: axis == .vertical ? 1 : nil)
            .hoverEffect(.highlight)
            .overlay {
                Color.clear
                    .frame(width: axis == .horizontal ? 24 : nil, height: axis == .vertical ? 24 : nil)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 1)
                            .onChanged { g in
                                let start = dragStartPct ?? pct
                                if dragStartPct == nil { dragStartPct = start }
                                let startLen = SplitMath.width(pct: start, total: total)
                                let delta = axis == .horizontal ? g.translation.width : g.translation.height
                                pct = SplitMath.pct(at: startLen + delta, total: total,
                                                    min: range.lowerBound, max: range.upperBound)
                            }
                            .onEnded { _ in dragStartPct = nil }
                    )
            }
    }
}
