import CaromProtocol
import Foundation
import Testing

@testable import CaromDesktop

@MainActor
@Suite struct NotificationCoordinatorTests {
    let timers = CoordinatorTimers()

    @Test func bindsThePanel() {
        let (_, _, panel, _) = makeCoordinator(timers: timers)
        #expect(panel.bound)
    }

    @Test func postedShowsOnPanelAndPostsAQuietNativeCopy() async {
        let (c, native, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.posted()))
        #expect(c.onPanel == [Fixture.id("k1")])
        #expect(panel.shows == 1)
        #expect(panel.alerts.map { $0.sound } == [true])
        #expect(panel.alerts.first?.announcement == "Chat: Ana")
        #expect(native.presented.count == 1)
        #expect(native.presented[0].notification.passive)
        #expect(!native.presented[0].showPhoneName)
    }

    @Test func actionCapChangeUpdatesThatPhonesCardsWithoutReposting() async {
        let (c, native, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted()), actions: false))
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted("k2")), phone: "other", actions: false))
        let id = Fixture.id("k1")
        #expect(c.cards[id]?.phoneButtons.isEmpty == true)

        c.setPhoneAcceptsActions(true, phoneId: Fixture.phone)
        #expect(c.cards[id]?.phoneAcceptsActions == true)
        #expect(c.cards[id]?.phoneButtons.isEmpty == false)
        #expect(c.cards[CardItem.id(phoneId: "other", key: "k2")]?.phoneAcceptsActions == false)
        #expect(native.presented.count == 2 && panel.alerts.count == 2, "nothing is presented again")

        c.setPhoneAcceptsActions(false, phoneId: Fixture.phone)
        #expect(c.cards[id]?.phoneButtons.isEmpty == true)
    }

    @Test func phoneNameAndReplyReachTheNativeCopy() async {
        let (c, native, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted()), phoneName: "Pixel"))
        #expect(native.presented[0].showPhoneName)
        #expect(native.presented[0].notification.phoneName == "Pixel")
        #expect(native.presented[0].notification.reply == ReplyTarget(index: 1, title: "Reply"))
    }

    @Test func nativeOnlyPostsAnActiveNativeAndNoPanel() async {
        let (c, native, panel, _) = makeCoordinator(timers: timers)
        c.setStyle(.nativeOnly)
        await c.posted(Fixture.card(Fixture.posted()))
        #expect(c.onPanel.isEmpty && panel.shows == 0 && panel.alerts.isEmpty)
        #expect(native.presented.first?.notification.passive == false)
    }

    @Test func switchingToNativeOnlyClearsThePanel() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.messaging()))
        c.setStyle(.nativeOnly)
        #expect(c.onPanel.isEmpty)
        #expect(panel.hides == 1)
    }

    @Test func focusSkipsPanelAndSoundButNativeStillPosts() async {
        let (c, native, panel, _) = makeCoordinator(focused: true, timers: timers)
        await c.posted(Fixture.card(Fixture.posted()))
        #expect(c.onPanel.isEmpty && panel.shows == 0 && panel.alerts.isEmpty)
        #expect(native.presented.count == 1)
        #expect(c.cards.count == 1, "the card is kept for later updates and actions")
    }

    @Test func liveUpdateChangesInPlaceWithoutSound() async {
        let (c, native, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.live(value: 10)))
        await c.posted(Fixture.card(Fixture.live(value: 60), msgId: "m2"))
        #expect(panel.alerts.count == 1)
        #expect(panel.shows == 1)
        #expect(c.cards[Fixture.id("ride")]?.posted.progress?.value == 60)
        #expect(c.cards[Fixture.id("ride")]?.msgId == "m2")
        #expect(native.presented.count == 2)
    }

    @Test func plainCardFadesAndHoverHoldsIt() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.posted()))
        c.setHovering(true)
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.count == 1, "held while the pointer is over the panel")
        c.setHovering(false)
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.isEmpty)
        #expect(panel.hides == 1)
        #expect(c.cards.count == 1, "fading only leaves the panel; the phone still has it")
    }

    /// The panel is ordered out under the pointer: no hover exit comes, so the hold must end.
    @Test func hoverHoldEndsWhenTheLastCardLeaves() async {
        let (c, _, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.posted()))
        c.setHovering(true)
        c.close(Fixture.id("k1"))
        #expect(!c.isHovering)
        await c.posted(Fixture.card(Fixture.posted("k2")))
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.isEmpty, "the next card fades")
    }

    @Test func fadeWaitsForTheFullDelay() async {
        let (c, _, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.posted()))
        await timers.advance(by: .seconds(5))
        #expect(c.onPanel.count == 1)
        await timers.advance(by: .seconds(1))
        #expect(c.onPanel.isEmpty)
    }

    @Test func conversationCallAndLiveCardsStayUntilRemoved() async {
        let (c, native, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.messaging()))
        await c.posted(Fixture.card(Fixture.incomingCall()))
        await c.posted(Fixture.card(Fixture.live()))
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.count == 3)
        c.removed(phoneId: Fixture.phone, key: "chat1")
        #expect(c.onPanel.count == 2)
        #expect(c.cards[Fixture.id("chat1")] == nil)
        #expect(native.withdrawn == ["chat1"])
    }

    @Test func removedAllForgetsThatPhonesCards() async {
        let (c, native, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.messaging()))
        await c.posted(Fixture.card(Fixture.messaging("chat2"), phone: Fixture.otherPhone))
        c.removedAll(phoneId: Fixture.phone)
        #expect(native.withdrawnAll == [Fixture.phone])
        #expect(c.cards.keys.sorted() == [Fixture.id("chat2", phone: Fixture.otherPhone)])
    }

    @Test func closeOnlyLeavesThePanel() async {
        let (c, native, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.messaging()))
        c.close(Fixture.id("chat1"))
        #expect(c.onPanel.isEmpty)
        #expect(c.cards.count == 1)
        #expect(native.withdrawn.isEmpty)
    }

    @Test func onlyTheSummaryAlertsWhenTheGroupSaysSo() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.grouped("a", alert: "summary")))
        #expect(c.onPanel.count == 1)
        #expect(panel.alerts.isEmpty)
        await c.posted(Fixture.card(Fixture.grouped("s", summary: true, alert: "summary")))
        #expect(panel.alerts.count == 1)
    }

    @Test func panelShowsFourStacksAndCountsTheRest() async {
        let (c, _, _, _) = makeCoordinator(timers: timers)
        for i in 0..<6 { await c.posted(Fixture.card(Fixture.messaging("k\(i)"))) }
        #expect(c.visibleStacks.count == 4)
        #expect(c.hiddenStackCount == 2)
        #expect(c.visibleStacks.first?.header.posted.key == "k5", "newest first")
    }

    @Test func groupedCardsShareAStack() async {
        let (c, _, _, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.grouped("a")))
        await c.posted(Fixture.card(Fixture.grouped("b")))
        #expect(c.stacks.count == 1)
        #expect(c.stacks.first?.title == "Chat · 2 new")
    }

    // MARK: Index Review Focus 4 (coordinator side)

    @Test func panelBecomesKeyOnlyForReply() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        #expect(panel.replyModes.isEmpty, "showing a card never asks for the keyboard")
        c.beginReply(Fixture.id("k1"))
        #expect(panel.replyModes == [true])
        #expect(c.replyingTo == Fixture.id("k1"))
        c.endReply()
        #expect(panel.replyModes == [true, false])
        #expect(c.replyingTo == nil)
    }

    @Test func noReplyModeWithoutAReplyActionOrCaps() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.posted("plain")))
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted("nocaps")), actions: false))
        c.beginReply(Fixture.id("plain"))
        c.beginReply(Fixture.id("nocaps"))
        #expect(panel.replyModes.isEmpty)
        #expect(c.replyingTo == nil)
    }

    @Test func replyingHoldsTheFadeAndClosingTheCardEndsIt() async {
        let (c, _, panel, _) = makeCoordinator(timers: timers)
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.beginReply(Fixture.id("k1"))
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.count == 1)
        c.close(Fixture.id("k1"))
        #expect(c.replyingTo == nil)
        #expect(panel.replyModes == [true, false])
    }
}
