import AppKit
import SwiftUI

/// The rich panel's window: borderless and non-activating, above normal windows, on every
/// Space and over full-screen apps, hidden from screen sharing. It never becomes key except
/// while the user types a reply.
class RichPanel: NSPanel {
    static let panelStyle: NSWindow.StyleMask = [.borderless, .nonactivatingPanel]

    /// Set by `RichPanelPresenter.setReplyMode`.
    var allowsKey = false

    convenience init(width: CGFloat) {
        self.init(contentRect: NSRect(x: 0, y: 0, width: width, height: 10), styleMask: Self.panelStyle,
                  backing: .buffered, defer: true)
        isFloatingPanel = true
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]
        sharingType = .none
        hidesOnDeactivate = false
        becomesKeyOnlyIfNeeded = true
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        isReleasedWhenClosed = false
        isMovable = false
        acceptsMouseMovedEvents = true
    }

    override var canBecomeKey: Bool { allowsKey }
    override var canBecomeMain: Bool { false }
}

/// Lets the first click on a card act even though the panel is not key.
final class FirstMouseHostingView<Content: View>: NSHostingView<Content> {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

/// Shows the coordinator's cards in a `RichPanel` at the top right of the screen under the pointer.
@MainActor
public final class RichPanelPresenter: CardPanelHosting {
    static let width: CGFloat = 380
    static let margin: CGFloat = 12

    /// Plays when a new card alerts. Replaced in tests.
    public var playSound: @MainActor () -> Void = { _ = NSSound(named: NSSound.Name("Tink"))?.play() }

    private let makePanel: @MainActor () -> RichPanel
    private(set) var panel: RichPanel?
    private weak var coordinator: NotificationCoordinator?
    private var contentHeight: CGFloat = 0

    public convenience init() { self.init(makePanel: { RichPanel(width: RichPanelPresenter.width) }) }

    init(makePanel: @escaping @MainActor () -> RichPanel) { self.makePanel = makePanel }

    public func bind(_ coordinator: NotificationCoordinator) { self.coordinator = coordinator }

    public func show() {
        let panel = ensurePanel()
        reposition(panel)
        // Never makeKey here: a card must not take the keyboard from the app the user types in.
        panel.orderFrontRegardless()
    }

    public func hide() {
        guard let panel else { return }
        panel.allowsKey = false
        panel.orderOut(nil)
    }

    public func setReplyMode(_ on: Bool) {
        guard let panel else { return }
        panel.allowsKey = on
        if on {
            panel.makeKey()
        } else if panel.isKeyWindow {
            // Hand the keyboard back to the app in front: ordering out drops key status.
            panel.orderOut(nil)
            panel.orderFrontRegardless()
        }
    }

    public func alert(announcement: String, sound: Bool) {
        if sound { playSound() }
        guard let panel else { return }
        NSAccessibility.post(element: panel, notification: .announcementRequested,
                             userInfo: [.announcement: announcement,
                                        .priority: NSAccessibilityPriorityLevel.high.rawValue])
    }

    func contentHeightChanged(_ height: CGFloat) {
        contentHeight = height
        if let panel, panel.isVisible { reposition(panel) }
    }

    private func ensurePanel() -> RichPanel {
        if let panel { return panel }
        let panel = makePanel()
        if let coordinator {
            let root = RichPanelContentView(coordinator: coordinator) { [weak self] height in
                self?.contentHeightChanged(height)
            }
            panel.contentView = FirstMouseHostingView(rootView: root)
        }
        self.panel = panel
        return panel
    }

    private func reposition(_ panel: RichPanel) {
        guard let screen = Self.screenUnderPointer() else { return }
        let height = contentHeight > 0 ? contentHeight : (panel.contentView?.fittingSize.height ?? 0)
        panel.setFrame(Self.frame(contentHeight: max(height, 1), in: screen.visibleFrame), display: true)
    }

    /// Top right of `visible`, `margin` from both edges, never taller than the screen.
    static func frame(contentHeight: CGFloat, in visible: NSRect) -> NSRect {
        let height = min(contentHeight, visible.height - 2 * margin)
        return NSRect(x: visible.maxX - width - margin, y: visible.maxY - margin - height, width: width,
                      height: height)
    }

    static func screenUnderPointer() -> NSScreen? {
        let point = NSEvent.mouseLocation
        return NSScreen.screens.first { NSMouseInRect(point, $0.frame, false) } ?? NSScreen.main
    }
}
