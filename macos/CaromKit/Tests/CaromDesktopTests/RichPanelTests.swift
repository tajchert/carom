import AppKit
import Testing

@testable import CaromDesktop

@MainActor
final class SpyPanel: RichPanel {
    var makeKeyCalls = 0
    override func makeKey() {
        makeKeyCalls += 1
        super.makeKey()
    }
    override func makeKeyAndOrderFront(_ sender: Any?) {
        makeKeyCalls += 1
        super.makeKeyAndOrderFront(sender)
    }
}

@MainActor
@Suite struct RichPanelTests {
    init() { _ = NSApplication.shared }

    @Test func windowIsNonActivatingAboveEverythingAndHiddenFromCapture() {
        let p = RichPanel(width: RichPanelPresenter.width)
        #expect(p.styleMask.contains(.nonactivatingPanel))
        #expect(!p.styleMask.contains(.titled))
        #expect(p.level == .statusBar)
        #expect(p.collectionBehavior.contains([.canJoinAllSpaces, .fullScreenAuxiliary, .stationary]))
        #expect(p.sharingType == .none)
        #expect(!p.canBecomeKey)
        #expect(!p.canBecomeMain)
    }

    /// Index Review Focus 4: key only in reply mode.
    @Test func becomesKeyOnlyInReplyMode() {
        let spy = SpyPanel(width: RichPanelPresenter.width)
        let presenter = RichPanelPresenter(makePanel: { spy })
        presenter.show()
        #expect(!spy.canBecomeKey)
        #expect(spy.makeKeyCalls == 0)
        #expect(!spy.isKeyWindow)
        presenter.setReplyMode(true)
        #expect(spy.canBecomeKey)
        #expect(spy.makeKeyCalls == 1)
        presenter.setReplyMode(false)
        #expect(!spy.canBecomeKey)
        presenter.hide()
    }

    /// Index Review Focus 4: cards arriving while the user types elsewhere never take the keyboard.
    @Test func showingCardsNeverMakesThePanelKey() async {
        let spy = SpyPanel(width: RichPanelPresenter.width)
        let presenter = RichPanelPresenter(makePanel: { spy })
        presenter.playSound = {}
        let c = NotificationCoordinator(native: FakeNative(), panel: presenter, focus: FakeFocus())
        for i in 0..<6 { await c.posted(Fixture.card(Fixture.withActions(Fixture.posted("k\(i)")))) }
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted("k5", text: "update")), msgId: "m2"))
        #expect(spy.makeKeyCalls == 0)
        #expect(!spy.canBecomeKey)
        #expect(!spy.isKeyWindow)
        c.beginReply(Fixture.id("k5"))
        #expect(spy.canBecomeKey)
        #expect(spy.makeKeyCalls == 1)
        c.endReply()
        #expect(!spy.canBecomeKey)
        presenter.hide()
    }

    @Test func framesTopRightOfTheVisibleArea() {
        let f = RichPanelPresenter.frame(contentHeight: 200, in: NSRect(x: 0, y: 0, width: 1440, height: 875))
        #expect(f == NSRect(x: 1440 - 380 - 12, y: 875 - 12 - 200, width: 380, height: 200))
        let tall = RichPanelPresenter.frame(contentHeight: 5000, in: NSRect(x: 100, y: 50, width: 800, height: 600))
        #expect(tall.height == CGFloat(600 - 24))
        #expect(tall.maxY == CGFloat(650 - 12))
        #expect(tall.maxX == CGFloat(900 - 12))
    }
}
