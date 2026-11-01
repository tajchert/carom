import AppKit
import SwiftUI
import Testing

@testable import CaromDesktop

struct NotificationSwipeTests {
    @MainActor
    @Test func newGroupMembersStayVisibleWhenTheyArriveDuringDismissal() async throws {
        _ = NSApplication.shared
        let (c, native, _, _) = makeCoordinator()
        for key in ["a", "b"] {
            var posted = Fixture.grouped(key)
            posted.color = 0xFFD32F2F
            posted.colorized = true
            await c.posted(Fixture.card(posted))
        }
        let initial = try #require(c.stacks.first)
        let host = NSHostingView(rootView: CardStackView(stack: initial, coordinator: c))
        host.frame = NSRect(x: 0, y: 0, width: 364, height: 140)
        let window = NSWindow(contentRect: host.frame, styleMask: .borderless, backing: .buffered, defer: false)
        window.contentView = host
        window.orderFront(nil)
        defer { window.orderOut(nil) }
        host.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(50))
        func monitors(in view: NSView) -> [SwipeMonitorView] {
            (view as? SwipeMonitorView).map { [$0] } ?? view.subviews.flatMap { monitors(in: $0) }
        }
        let monitor = try #require(monitors(in: host).first)
        monitor.onUpdate?(.ended(dismiss: true))
        try await Task.sleep(for: .milliseconds(20))
        for key in ["c", "d"] {
            var posted = Fixture.grouped(key)
            posted.color = 0xFFD32F2F
            posted.colorized = true
            await c.posted(Fixture.card(posted))
        }
        host.rootView = CardStackView(stack: try #require(c.stacks.first), coordinator: c)
        host.layoutSubtreeIfNeeded()
        try await Task.sleep(for: .milliseconds(350))
        host.rootView = CardStackView(stack: try #require(c.stacks.first), coordinator: c)
        host.layoutSubtreeIfNeeded()
        #expect(c.onPanel == [Fixture.id("c"), Fixture.id("d")])
        #expect(native.withdrawn.isEmpty)
        let bitmap = try #require(host.bitmapImageRepForCachingDisplay(in: host.bounds))
        host.cacheDisplay(in: host.bounds, to: bitmap)
        let hasRedCard = stride(from: 0, to: bitmap.pixelsWide, by: 10).contains { x in
            stride(from: 0, to: bitmap.pixelsHigh, by: 10).contains { y in
                guard let pixel = bitmap.colorAt(x: x, y: y)?.usingColorSpace(.sRGB) else { return false }
                return pixel.alphaComponent > 0.5 && pixel.redComponent > 0.7 && pixel.greenComponent < 0.35
            }
        }
        #expect(hasRedCard, "new notifications must render, rather than inherit the dismissed group's opacity")
    }

    @Test func rightSwipeFollowsTheFingersAndDismissesOnlyOnRelease() {
        var swipe = NotificationSwipe()
        #expect(swipe.update(dx: 30, dy: 1, phase: .began) == .changed(30))
        #expect(swipe.update(dx: 70, dy: 0, phase: .changed) == .changed(100))
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ended(dismiss: true))
        #expect(swipe.update(dx: 100, dy: 0, phase: .changed) == .ignored)
    }

    @Test func shortCancelledAndLeftwardSwipesDoNotDismiss() {
        for (dx, phase) in [(99.0, NSEvent.Phase.ended), (140.0, .cancelled), (-140.0, .ended)] {
            var swipe = NotificationSwipe()
            _ = swipe.update(dx: dx, dy: 0, phase: .began)
            #expect(swipe.update(dx: 0, dy: 0, phase: phase) == .ended(dismiss: false))
        }
    }

    @Test func reversingBeforeReleaseUsesTheFinalDistance() {
        var swipe = NotificationSwipe()
        _ = swipe.update(dx: 140, dy: 0, phase: .began)
        #expect(swipe.update(dx: -100, dy: 0, phase: .changed) == .changed(40))
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ended(dismiss: false))
    }

    @Test func verticalGestureStaysVerticalDespiteLaterHorizontalMovement() {
        var swipe = NotificationSwipe()
        #expect(swipe.update(dx: 1, dy: 12, phase: .began) == .ignored)
        #expect(swipe.update(dx: 160, dy: 0, phase: .changed) == .ignored)
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ignored)
        #expect(swipe.update(dx: 100, dy: 0, phase: .began) == .changed(100))
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ended(dismiss: true))
    }

    @Test func tinyInitialMovementWaitsForAnAxis() {
        var swipe = NotificationSwipe()
        #expect(swipe.update(dx: 1, dy: 2, phase: .began) == .ignored)
        #expect(swipe.update(dx: 20, dy: 0, phase: .changed) == .changed(21))
    }

    @Test func momentumAndMouseWheelsCannotStartOrCompleteASwipe() {
        var swipe = NotificationSwipe()
        #expect(swipe.update(dx: 150, dy: 0, phase: [], momentum: .began) == .ignored)
        #expect(swipe.update(dx: 150, dy: 0, phase: []) == .ignored)
        #expect(swipe.update(dx: 150, dy: 0, phase: .began, precise: false) == .ignored)
        _ = swipe.update(dx: 30, dy: 0, phase: .began)
        #expect(swipe.update(dx: 150, dy: 0, phase: [], momentum: .changed) == .ignored)
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ended(dismiss: false))
    }

    @Test func newGestureResetsThePreviousDistance() {
        var swipe = NotificationSwipe()
        _ = swipe.update(dx: 80, dy: 0, phase: .began)
        #expect(swipe.update(dx: 30, dy: 0, phase: .began) == .changed(30))
        #expect(swipe.update(dx: 0, dy: 0, phase: .ended) == .ended(dismiss: false))
    }
}
