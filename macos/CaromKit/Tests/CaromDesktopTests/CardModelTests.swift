import CaromProtocol
import Foundation
import Testing

@testable import CaromDesktop

@Suite struct CardModelTests {
    @Test func idIsStablePerPhoneAndKey() {
        let a = Fixture.card(Fixture.posted("k1"), msgId: "m1")
        let b = Fixture.card(Fixture.posted("k1", text: "v2"), msgId: "m2")
        #expect(a.id == Fixture.phone + "/k1")
        #expect(a.id == b.id)
        #expect(a.id != Fixture.card(Fixture.posted("k1"), phone: Fixture.otherPhone).id)
    }

    @Test func codeComesFromTextButNeverFromRedacted() {
        #expect(Fixture.card(Fixture.posted(text: "Your code is 482913")).code == "482913")
        var hidden = Fixture.posted(text: "Your code is 482913")
        hidden.redacted = true
        #expect(Fixture.card(hidden).code == nil)
        #expect(Fixture.card(Fixture.posted(text: nil)).code == nil)
    }

    @Test func pinnedAndStickyKinds() {
        let live = Fixture.card(Fixture.live())
        let call = Fixture.card(Fixture.incomingCall())
        let chat = Fixture.card(Fixture.messaging())
        let plain = Fixture.card(Fixture.posted())
        #expect(live.isLive && live.isPinned && live.staysUntilHandled)
        #expect(call.isIncomingCall && call.isPinned && call.staysUntilHandled)
        #expect(chat.isConversation && !chat.isPinned && chat.staysUntilHandled)
        #expect(!plain.isPinned && !plain.staysUntilHandled)
        var ongoingCall = Fixture.incomingCall()
        ongoingCall.call?.type = "ongoing"
        #expect(!Fixture.card(ongoingCall).isIncomingCall)
    }

    @Test func actionsNeedThePhonesCaps() {
        let p = Fixture.withActions(Fixture.posted())
        let on = Fixture.card(p, actions: true)
        #expect(on.phoneButtons.map(\.title) == ["Mark as read"])
        #expect(on.replyAction?.index == 1)
        #expect(on.canTap)
        let off = Fixture.card(p, actions: false)
        #expect(off.phoneButtons.isEmpty && off.replyAction == nil && !off.canTap)
    }

    @Test func atMostThreePlainButtons() {
        var p = Fixture.posted()
        p.actions = (0..<6).map { .init(index: $0, title: "B\($0)", semantic: nil, reply: nil) }
        #expect(Fixture.card(p).phoneButtons.map(\.index) == [0, 1, 2])
    }

    @Test func stackKeyGroupsByPhoneAppAndGroup() {
        let a = Fixture.card(Fixture.grouped("a"))
        let b = Fixture.card(Fixture.grouped("b"))
        let other = Fixture.card(Fixture.grouped("c", group: "g2"))
        let otherPhone = Fixture.card(Fixture.grouped("d"), phone: Fixture.otherPhone)
        #expect(a.stackKey == b.stackKey)
        #expect(a.stackKey != other.stackKey)
        #expect(a.stackKey != otherPhone.stackKey)
        #expect(Fixture.card(Fixture.posted("x")).stackKey != Fixture.card(Fixture.posted("y")).stackKey)
    }

    @Test func groupAlertRule() {
        #expect(Fixture.card(Fixture.posted()).alertsByGroupRule)
        #expect(!Fixture.card(Fixture.grouped("a", alert: "summary")).alertsByGroupRule)
        #expect(Fixture.card(Fixture.grouped("s", summary: true, alert: "summary")).alertsByGroupRule)
        #expect(Fixture.card(Fixture.grouped("a", alert: "children")).alertsByGroupRule)
        #expect(!Fixture.card(Fixture.grouped("s", summary: true, alert: "children")).alertsByGroupRule)
    }

    @Test func displayDatePrefersWhen() {
        #expect(Fixture.card(Fixture.posted()).displayDate == Date(timeIntervalSince1970: 1_800_000_000))
        #expect(Fixture.card(Fixture.grouped("a", when: 1_800_000_060_000)).displayDate
                == Date(timeIntervalSince1970: 1_800_000_060))
    }

    @Test func statusLabelsAreVerbatim() {
        #expect(ActionStatus.idle.label == nil)
        #expect(ActionStatus.sending.label == "Sending…")
        #expect(ActionStatus.sent.label == "Sent")
        #expect(ActionStatus.asleep.label == "Phone is asleep — will send when it wakes")
        #expect(ActionStatus.gone.label == "Notification is gone")
        #expect(ActionStatus.changed.label == "App changed its buttons")
        #expect(ActionStatus.failed.label == "Couldn't send")
    }

    @Test(arguments: [
        (true, nil, ActionStatus.sent),
        (false, "gone", .gone),
        (false, "changed", .changed),
        (false, "blocked", .failed),
        (false, "failed", .failed),
        (false, nil, .failed),
    ] as [(Bool, String?, ActionStatus)])
    func resultMapsToStatus(ok: Bool, error: String?, expected: ActionStatus) {
        #expect(ActionStatus(result: NotificationActionResult(ok: ok, error: error)) == expected)
    }

    @Test func styleRawValues() {
        #expect(NotificationStyle.rich.rawValue == "rich")
        #expect(NotificationStyle.nativeOnly.rawValue == "native")
    }
}
