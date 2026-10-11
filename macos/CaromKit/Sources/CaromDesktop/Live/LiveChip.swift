import AppKit
import CaromProtocol

/// What the menu-bar chip of a promoted Live Update shows.
public enum LiveChipContent: Sendable, Equatable {
    case text(String)
    /// A progress ring, filled 0...1.
    case progress(Double)
}

public enum LiveChipPolicy {
    /// Most characters the chip shows. Android asks apps for 7 or fewer.
    public static let maxCharacters = 10

    /// The chip for the newest promoted update among `items` (newest first): its short critical
    /// text, else a ring for a determinate progress, else nothing.
    public static func content(for items: [NotificationPosted]) -> LiveChipContent? {
        guard let item = items.first(where: { $0.live?.promoted == true }) else { return nil }
        if let text = item.live?.shortCriticalText?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty {
            return .text(String(text.prefix(maxCharacters)))
        }
        if let progress = item.progress, !progress.indeterminate {
            let bar = LiveMath.bar(for: progress)
            if !bar.segments.isEmpty { return .progress(bar.fraction) }
        }
        return nil
    }
}

/// Where the chip is drawn. The real one is a status item; tests use a recording fake.
@MainActor
public protocol LiveChipHost: AnyObject {
    func show(_ content: LiveChipContent)
    func hide()
}

@MainActor
public final class NullLiveChipHost: LiveChipHost {
    public init() {}
    public func show(_ content: LiveChipContent) {}
    public func hide() {}
}

/// A second `NSStatusItem` next to the app's own menu-bar icon.
@MainActor
public final class StatusItemChipHost: LiveChipHost {
    private var item: NSStatusItem?

    public init() {}

    public func show(_ content: LiveChipContent) {
        let item = self.item ?? NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        self.item = item
        guard let button = item.button else { return }
        button.setAccessibilityLabel("Live update from your phone")
        switch content {
        case .text(let text):
            button.image = nil
            button.title = text
            button.font = .menuBarFont(ofSize: 0)
        case .progress(let fraction):
            button.title = ""
            button.image = Self.ring(fraction)
        }
    }

    public func hide() {
        if let item { NSStatusBar.system.removeStatusItem(item) }
        item = nil
    }

    /// A template image, so the menu bar tints it.
    static func ring(_ fraction: Double) -> NSImage {
        let image = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
            let center = NSPoint(x: rect.midX, y: rect.midY)
            let radius = rect.width / 2 - 3
            let track = NSBezierPath()
            track.appendArc(withCenter: center, radius: radius, startAngle: 0, endAngle: 360)
            track.lineWidth = 2
            NSColor.black.withAlphaComponent(0.3).setStroke()
            track.stroke()
            if fraction > 0 {
                let arc = NSBezierPath()
                arc.appendArc(withCenter: center, radius: radius, startAngle: 90,
                              endAngle: 90 - 360 * CGFloat(min(1, fraction)), clockwise: true)
                arc.lineWidth = 2
                arc.lineCapStyle = .round
                NSColor.black.setStroke()
                arc.stroke()
            }
            return true
        }
        image.isTemplate = true
        return image
    }
}

/// Keeps the chip in step with the promoted Live Updates and the setting.
@MainActor
public final class LiveChip {
    private let host: any LiveChipHost
    private var items: [NotificationPosted] = []
    private var shown: LiveChipContent?

    /// The "Show Live Updates in the menu bar" setting (and not paused).
    public var enabled: Bool {
        didSet { render() }
    }

    public init(host: any LiveChipHost, enabled: Bool = true) {
        self.host = host
        self.enabled = enabled
    }

    /// The current Live Updates, newest first. An empty list (the update ended) removes the chip.
    public func update(_ items: [NotificationPosted]) {
        self.items = items
        render()
    }

    private func render() {
        guard enabled, let content = LiveChipPolicy.content(for: items) else {
            if shown != nil { host.hide() }
            shown = nil
            return
        }
        if content != shown {
            host.show(content)
            shown = content
        }
    }
}
