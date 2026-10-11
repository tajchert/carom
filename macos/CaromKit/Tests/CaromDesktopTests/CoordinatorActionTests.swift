import CaromProtocol
import Foundation
import Testing

@testable import CaromDesktop

@MainActor
final class ActionLog {
    struct Sent: Equatable {
        var msgId: String
        var action: ActionRef
    }
    var sent: [Sent] = []
    var dismissed: [String] = []
    var copies: [String] = []
}

@MainActor
@Suite struct CoordinatorActionTests {
    /// A coordinator whose sends are logged and answered with `delivery`.
    let timers = CoordinatorTimers()

    func make(delivery: ActionDelivery = .accepted(msgId: "a1"))
        -> (NotificationCoordinator, ActionLog, FakeNative, FakePanel)
    {
        let (c, native, panel, _) = makeCoordinator(timers: timers)
        let log = ActionLog()
        c.sendAction = { item, ref in
            log.sent.append(.init(msgId: item.msgId, action: ref))
            return delivery
        }
        c.sendDismiss = { item in log.dismissed.append(item.posted.key) }
        c.copy = { log.copies.append($0) }
        return (c, log, native, panel)
    }

    let id = Fixture.id("k1")

    @Test func buttonSendsTheActionAndShowsSending() async {
        let (c, log, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        #expect(c.perform(.button(index: 0, title: "Mark as read"), on: id))
        #expect(c.status(for: id) == .sending)
        await settle()
        #expect(log.sent == [.init(msgId: "m1", action: .button(index: 0, title: "Mark as read", replyText: nil))])
    }

    @Test func tapAnswerAndDeclineMapToActionRefs() async {
        let (c, log, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.tap, on: id)
        c.perform(.answer, on: id)
        c.perform(.decline, on: id)
        await settle()
        #expect(log.sent.map(\.action) == [.tap, .answer, .decline])
    }

    @Test func replySendsTheTextAndEndsReplyMode() async {
        let (c, log, _, panel) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.beginReply(id)
        c.perform(.reply(index: 1, title: "Reply", text: "On my way"), on: id)
        await settle()
        #expect(log.sent.map(\.action) == [.button(index: 1, title: "Reply", replyText: "On my way")])
        #expect(c.replyingTo == nil)
        #expect(panel.replyModes == [true, false])
    }

    @Test(arguments: [
        (true, nil, ActionStatus.sent),
        (false, "gone", .gone),
        (false, "changed", .changed),
        (false, "blocked", .failed),
    ] as [(Bool, String?, ActionStatus)])
    func resultSetsTheStatus(ok: Bool, error: String?, expected: ActionStatus) async {
        let (c, _, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: ok, error: error))
        #expect(c.status(for: id) == expected)
    }

    /// Plan Review Focus 3: a late result after the asleep switch still lands.
    @Test func asleepThenLateResultStillLands() async {
        let (c, _, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await timers.advance(by: .seconds(60))
        #expect(c.status(for: id) == .asleep)
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
        #expect(c.status(for: id) == .sent)
    }

    /// Plan Review Focus 3: the socket can deliver the result before the POST returns.
    @Test func resultBeforeSendReturnsIsKept() async {
        let (c, _, _, _) = makeCoordinator(timers: timers)
        c.sendAction = { [weak c] _, _ in
            c?.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
            return .accepted(msgId: "a1")
        }
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        #expect(c.status(for: id) == .sent)
    }

    /// Plan Review Focus 3: a result for a card the phone already removed changes nothing.
    @Test func removalForgetsThePendingAction() async {
        let (c, _, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        c.removed(phoneId: Fixture.phone, key: "k1")
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
        #expect(c.statuses.isEmpty)
        await timers.advance(by: .seconds(60))
        #expect(c.statuses.isEmpty, "no asleep switch for a forgotten card")
    }

    @Test func unknownResultChangesNothing() async {
        let (c, _, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.actionResult(replyTo: "zzz", NotificationActionResult(ok: true, error: nil))
        #expect(c.status(for: id) == .idle)
    }

    @Test func failedSendShowsFailed() async {
        let (c, _, _, _) = make(delivery: .failed)
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted())))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        #expect(c.status(for: id) == .failed)
    }

    @Test func noCapsMeansNothingIsSent() async {
        let (c, log, _, _) = make()
        await c.posted(Fixture.card(Fixture.withActions(Fixture.posted()), actions: false))
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        c.perform(.tap, on: id)
        await settle()
        #expect(log.sent.isEmpty)
        #expect(c.status(for: id) == .idle)
    }

    @Test func unknownCardReturnsFalse() {
        let (c, _, _, _) = make()
        #expect(!c.perform(.tap, on: "nope"))
    }

    @Test func dismissSendsAndWithdraws() async {
        let (c, log, native, _) = make()
        await c.posted(Fixture.card(Fixture.posted()))
        c.perform(.dismiss, on: id)
        await settle()
        #expect(log.dismissed == ["k1"])
        #expect(native.withdrawn == ["k1"])
        #expect(c.cards.isEmpty)
    }

    @Test func copyCodeCopiesLocallyOnly() async {
        let (c, log, _, _) = make()
        await c.posted(Fixture.card(Fixture.posted(text: "Your code is 482913"), actions: false))
        c.perform(.copyCode("482913"), on: id)
        #expect(log.copies == ["482913"])
        #expect(log.sent.isEmpty)
    }

    /// A conversation whose newest message is from someone else.
    func incoming(_ text: String? = nil) -> NotificationPosted {
        var p = Fixture.messaging("k1")
        p.messages = Array(p.messages!.dropLast())
        if let text {
            p.messages!.append(.init(sender: .init(name: "Ana", key: "ana", icon: nil), text: text,
                                     ts: 1_800_000_009_000, image: nil))
        }
        return p
    }

    /// The phone re-posts the conversation with the user's reply appended (sender nil).
    @Test func ownReplyRepostIsSilentAndStillFades() async {
        let (c, _, _, panel) = make()
        await c.posted(Fixture.card(incoming()))
        #expect(panel.alerts.count == 1)
        c.perform(.reply(index: 1, title: "Reply", text: "On my way"), on: id)
        await settle()
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
        var echoed = incoming()
        echoed.messages!.append(.init(sender: nil, text: "On my way", ts: 1_800_000_008_000, image: nil))
        await c.posted(Fixture.card(echoed, msgId: "m2"))
        #expect(panel.alerts.count == 1, "no sound for the user's own reply")
        #expect(c.status(for: id) == .sent)
        #expect(c.cards[id]?.msgId == "m2")
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.isEmpty, "still handled, so it fades")
    }

    @Test func newMessageAfterAReplyAlertsAndClearsTheStatus() async {
        let (c, _, _, panel) = make()
        await c.posted(Fixture.card(incoming()))
        c.perform(.reply(index: 1, title: "Reply", text: "On my way"), on: id)
        await settle()
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
        await c.posted(Fixture.card(incoming("Great"), msgId: "m3"))
        #expect(panel.alerts.count == 2)
        #expect(c.status(for: id) == .idle)
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.count == 1, "a new message stays until handled")
    }

    @Test func handledConversationFades() async {
        let (c, _, _, _) = make()
        await c.posted(Fixture.card(Fixture.messaging("k1")))
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.count == 1, "a conversation stays until handled")
        c.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        c.actionResult(replyTo: "a1", NotificationActionResult(ok: true, error: nil))
        await timers.advance(by: .seconds(60))
        #expect(c.onPanel.isEmpty)
    }
}
