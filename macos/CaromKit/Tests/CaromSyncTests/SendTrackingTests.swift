import CaromProtocol
import Foundation
import Testing

@testable import CaromSync

@Suite("Sync engine: msg_id of a sent message")
struct SendTrackingTests {
    @Test("sendReturningMsgId returns the msg_id the recipient sees")
    func returnsTheMsgId() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        let phone = await TestDevice.make("Pixel", kind: .phone, hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        try await pair(phone, via: mac)

        let posted = NotificationPosted(key: "0|com.chat|1|null|1", app: .init(id: "com.chat", name: "Chat"),
                                        title: "Ana", text: "Hi", postedAt: 1_791_647_999_500)
        guard case .sent = try await phone.engine.send(posted) else {
            Issue.record("not sent")
            return
        }
        #expect(try await mac.engine.fetchMessages() == 1)
        let original = try #require(await mac.recorder.messages.first)

        let result = try await mac.engine.sendReturningMsgId(NotificationDismiss(key: posted.key),
                                                             replyTo: original.msgId)
        guard case .sent = result.outcome else {
            Issue.record("dismiss not sent")
            return
        }
        let msgId = try #require(result.msgId)
        #expect(try await phone.engine.fetchMessages() == 1)
        #expect(await phone.recorder.messages.last?.msgId == msgId)
    }

    @Test("nothing sealed, no msg_id")
    func noRecipientsHasNoMsgId() async throws {
        let hub = FakeHub()
        let mac = await TestDevice.make("Mac", hub: hub)
        try await mac.engine.setup(hubURL: hubURL, setupToken: hub.setupToken)
        let result = try await mac.engine.sendReturningMsgId(NotificationDismiss(key: "k"),
                                                             replyTo: "0190a000-0000-7000-8000-000000000000")
        #expect(result.msgId == nil)
        #expect(result.outcome == .noRecipients)
    }
}
