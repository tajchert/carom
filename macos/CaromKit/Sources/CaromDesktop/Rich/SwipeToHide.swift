import AppKit
import SwiftUI

/// One physical trackpad gesture. Momentum and unphased mouse wheels never hide a card.
struct NotificationSwipe {
    enum Update: Equatable {
        case ignored
        case changed(CGFloat)
        case ended(dismiss: Bool)
    }

    private enum Axis { case undecided, horizontal, vertical }
    private var axis = Axis.undecided
    private var x: CGFloat = 0
    private var y: CGFloat = 0
    private(set) var isTracking = false

    mutating func update(dx: CGFloat, dy: CGFloat, phase: NSEvent.Phase,
                         momentum: NSEvent.Phase = [], precise: Bool = true) -> Update {
        guard precise, momentum.isEmpty, !phase.isEmpty else { return .ignored }
        if phase.contains(.began) {
            self = NotificationSwipe()
            isTracking = true
        }
        guard isTracking else { return .ignored }
        if phase.contains(.cancelled) {
            let horizontal = axis == .horizontal
            self = NotificationSwipe()
            return horizontal ? .ended(dismiss: false) : .ignored
        }
        x += dx
        y += dy
        if axis == .undecided, max(abs(x), abs(y)) >= 8 {
            axis = abs(x) > abs(y) ? .horizontal : .vertical
        }
        if phase.contains(.ended) {
            let result: Update = axis == .horizontal ? .ended(dismiss: x >= 100) : .ignored
            self = NotificationSwipe()
            return result
        }
        return axis == .horizontal ? .changed(max(0, x)) : .ignored
    }
}

/// Shares close semantics with the X button: only the banner leaves, never the phone's item.
struct SwipeToHide: ViewModifier {
    let close: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var offset: CGFloat = 0
    @State private var dismissing = false

    func body(content: Content) -> some View {
        content
            .offset(x: reduceMotion ? 0 : offset)
            .opacity(dismissing ? 0 : 1)
            .background {
                SwipeEventView { update in
                    guard !dismissing else { return }
                    switch update {
                    case .ignored: break
                    case .changed(let distance): offset = distance
                    case .ended(dismiss: false):
                        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) { offset = 0 }
                    case .ended(dismiss: true):
                        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
                            offset = RichPanelPresenter.width + 20
                            dismissing = true
                        } completion: {
                            close()
                        }
                    }
                }
            }
            .allowsHitTesting(!dismissing)
    }
}

private struct SwipeEventView: NSViewRepresentable {
    let onUpdate: (NotificationSwipe.Update) -> Void

    func makeNSView(context: Context) -> SwipeMonitorView {
        let view = SwipeMonitorView()
        view.onUpdate = onUpdate
        return view
    }

    func updateNSView(_ view: SwipeMonitorView, context: Context) { view.onUpdate = onUpdate }
    static func dismantleNSView(_ view: SwipeMonitorView, coordinator: ()) { view.stopMonitoring() }
}

/// A background event monitor leaves buttons and reply editors in the normal hit-test path.
/// Only a gesture that starts inside this card is tracked, even if its pointer later leaves.
final class SwipeMonitorView: NSView {
    var onUpdate: ((NotificationSwipe.Update) -> Void)?
    private var monitor: Any?
    private var swipe = NotificationSwipe()

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        stopMonitoring()
        guard window != nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self else { return event }
            return self.handle(event)
        }
    }

    func stopMonitoring() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        swipe = NotificationSwipe()
    }

    private func handle(_ event: NSEvent) -> NSEvent? {
        guard let window, window.isVisible, event.window === window else { return event }
        if !swipe.isTracking || event.phase.contains(.began) {
            guard event.phase.contains(.began), bounds.contains(convert(event.locationInWindow, from: nil))
            else { return event }
        }
        let update = swipe.update(dx: event.scrollingDeltaX, dy: event.scrollingDeltaY,
                                  phase: event.phase, momentum: event.momentumPhase,
                                  precise: event.hasPreciseScrollingDeltas)
        guard update != .ignored else { return event }
        onUpdate?(update)
        return nil
    }
}
