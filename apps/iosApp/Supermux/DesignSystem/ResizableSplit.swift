import SwiftUI
#if os(macOS)
import AppKit
#endif

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

/// A two-child resizable split. iOS uses a SwiftUI drag divider; macOS is backed by
/// `NSSplitView`, which supplies the platform's native tracking, cursor, hit target, and resize
/// behavior rather than trying to emulate all of those with a `DragGesture`.
struct ResizableSplit<First: View, Second: View>: View {
    let axis: SplitAxis
    @Binding var pct: Double
    let range: ClosedRange<Double>
    @ViewBuilder var first: First
    @ViewBuilder var second: Second

    var body: some View {
        #if os(macOS)
        MacNativeSplit(axis: axis, percentage: $pct, range: range) {
            first
        } second: {
            second
        }
        #else
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
        #endif
    }

    @ViewBuilder private func container<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        if axis == .horizontal { HStack(spacing: 0, content: content) }
        else { VStack(spacing: 0, content: content) }
    }
}

#if !os(macOS)
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
            .smHoverHighlight()
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
#endif

#if os(macOS)
/// AppKit bridge used for every Mac workspace divider. The metric can be a percentage (inner
/// panes) or an absolute point width (the sessions sidebar).
struct MacNativeSplit<First: View, Second: View>: NSViewRepresentable {
    private enum Metric { case percentage, points }

    let axis: SplitAxis
    @Binding private var value: Double
    private let range: ClosedRange<Double>
    private let metric: Metric
    private let first: First
    private let second: Second

    init(
        axis: SplitAxis,
        percentage: Binding<Double>,
        range: ClosedRange<Double>,
        @ViewBuilder first: () -> First,
        @ViewBuilder second: () -> Second
    ) {
        self.axis = axis
        _value = percentage
        self.range = range
        metric = .percentage
        self.first = first()
        self.second = second()
    }

    init(
        axis: SplitAxis,
        firstWidth: Binding<Double>,
        range: ClosedRange<Double>,
        @ViewBuilder first: () -> First,
        @ViewBuilder second: () -> Second
    ) {
        self.axis = axis
        _value = firstWidth
        self.range = range
        metric = .points
        self.first = first()
        self.second = second()
    }

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }

    func makeNSView(context: Context) -> NSSplitView {
        let split = NSSplitView()
        split.isVertical = axis == .horizontal
        split.dividerStyle = .thin
        split.delegate = context.coordinator

        let firstHost = NSHostingView(rootView: first)
        let secondHost = NSHostingView(rootView: second)
        firstHost.translatesAutoresizingMaskIntoConstraints = true
        secondHost.translatesAutoresizingMaskIntoConstraints = true
        split.addArrangedSubview(firstHost)
        split.addArrangedSubview(secondHost)
        context.coordinator.firstHost = firstHost
        context.coordinator.secondHost = secondHost

        DispatchQueue.main.async { [weak split, weak coordinator = context.coordinator] in
            guard let split, let coordinator else { return }
            coordinator.applyBoundPosition(to: split)
        }
        return split
    }

    func updateNSView(_ split: NSSplitView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.firstHost?.rootView = first
        context.coordinator.secondHost?.rootView = second
        context.coordinator.applyBoundPosition(to: split)
    }

    final class Coordinator: NSObject, NSSplitViewDelegate {
        var parent: MacNativeSplit
        var firstHost: NSHostingView<First>?
        var secondHost: NSHostingView<Second>?
        private var applyingPosition = false

        init(parent: MacNativeSplit) {
            self.parent = parent
        }

        private func total(for split: NSSplitView) -> CGFloat {
            split.isVertical ? split.bounds.width : split.bounds.height
        }

        private func clampedPosition(for split: NSSplitView) -> CGFloat {
            let total = total(for: split)
            guard total > 0 else { return 0 }
            switch parent.metric {
            case .percentage:
                let pct = min(parent.range.upperBound, max(parent.range.lowerBound, parent.value))
                return SplitMath.width(pct: pct, total: total)
            case .points:
                return CGFloat(min(parent.range.upperBound, max(parent.range.lowerBound, parent.value)))
            }
        }

        private func bounds(for split: NSSplitView) -> ClosedRange<CGFloat> {
            let total = total(for: split)
            switch parent.metric {
            case .percentage:
                let lower = SplitMath.width(pct: parent.range.lowerBound, total: total)
                let upper = SplitMath.width(pct: parent.range.upperBound, total: total)
                return lower...upper
            case .points:
                return CGFloat(parent.range.lowerBound)...CGFloat(parent.range.upperBound)
            }
        }

        func applyBoundPosition(to split: NSSplitView) {
            guard split.subviews.count == 2, total(for: split) > 0 else { return }
            let target = clampedPosition(for: split)
            let current = split.isVertical ? split.subviews[0].frame.width : split.subviews[0].frame.height
            guard abs(current - target) > 0.5 else { return }
            applyingPosition = true
            split.setPosition(target, ofDividerAt: 0)
            applyingPosition = false
        }

        func splitView(_ splitView: NSSplitView, canCollapseSubview subview: NSView) -> Bool {
            false
        }

        func splitView(
            _ splitView: NSSplitView,
            constrainMinCoordinate proposedMinimumPosition: CGFloat,
            ofSubviewAt dividerIndex: Int
        ) -> CGFloat {
            max(proposedMinimumPosition, bounds(for: splitView).lowerBound)
        }

        func splitView(
            _ splitView: NSSplitView,
            constrainMaxCoordinate proposedMaximumPosition: CGFloat,
            ofSubviewAt dividerIndex: Int
        ) -> CGFloat {
            min(proposedMaximumPosition, bounds(for: splitView).upperBound)
        }

        func splitViewDidResizeSubviews(_ notification: Notification) {
            guard !applyingPosition,
                  let split = notification.object as? NSSplitView,
                  let first = split.subviews.first else { return }
            let position = split.isVertical ? first.frame.width : first.frame.height
            let next: Double
            switch parent.metric {
            case .percentage:
                next = SplitMath.pct(at: position, total: total(for: split),
                                     min: parent.range.lowerBound, max: parent.range.upperBound)
            case .points:
                next = min(parent.range.upperBound, max(parent.range.lowerBound, Double(position)))
            }
            if abs(next - parent.value) > 0.01 {
                parent.value = next
            }
        }
    }
}
#endif
