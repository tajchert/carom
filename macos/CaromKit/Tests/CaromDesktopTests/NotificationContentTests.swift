import Testing

@testable import CaromDesktop

@MainActor
@Suite struct NotificationContentTests {
    func note(title: String? = "Alice", text: String? = "Hi there", key: String = "k1") -> PresentedNotification {
        PresentedNotification(
            phoneId: "dev_phone", phoneName: "Pixel", appId: "com.chat", appName: "Chat",
            title: title, text: text, key: key, msgId: "m1")
    }

    @Test func titleSubtitleThread() {
        let s = NotificationContentSpec.make(note(), showPhoneName: true)
        #expect(s.title == "Chat · Alice")
        #expect(s.subtitle == "Pixel")
        #expect(s.body == "Hi there")
        #expect(s.threadIdentifier == "dev_phone/com.chat")
        #expect(s.categoryIdentifier == NotificationPresenter.categoryPlain)
        #expect(s.code == nil)
    }

    @Test func subtitleHiddenForSinglePhone() {
        #expect(NotificationContentSpec.make(note(), showPhoneName: false).subtitle == "")
    }

    @Test func missingTitleUsesAppName() {
        #expect(NotificationContentSpec.make(note(title: nil), showPhoneName: false).title == "Chat")
        #expect(NotificationContentSpec.make(note(title: "  "), showPhoneName: false).title == "Chat")
    }

    @Test func codeSelectsCodeCategory() {
        let s = NotificationContentSpec.make(note(text: "Your code is 482913"), showPhoneName: false)
        #expect(s.code == "482913")
        #expect(s.categoryIdentifier == NotificationPresenter.categoryCode)
        #expect(s.userInfo["code"] == "482913")
    }

    @Test func identifierIsStablePerPhoneAndKey() {
        let a = NotificationContentSpec.make(note(text: "v1"), showPhoneName: false)
        let b = NotificationContentSpec.make(note(text: "v2"), showPhoneName: false)
        #expect(a.identifier == b.identifier)
        #expect(a.identifier == NotificationContentSpec.identifier(phoneId: "dev_phone", key: "k1"))
        #expect(a.identifier != NotificationContentSpec.identifier(phoneId: "dev_other", key: "k1"))
        #expect(a.identifier != NotificationContentSpec.identifier(phoneId: "dev_phone", key: "k2"))
    }

    @Test func userInfoCarriesRouting() {
        let s = NotificationContentSpec.make(note(), showPhoneName: false)
        #expect(s.userInfo["phoneId"] == "dev_phone")
        #expect(s.userInfo["key"] == "k1")
        #expect(s.userInfo["msgId"] == "m1")
    }

    @Test func actionsResolve() {
        let info = ["phoneId": "p", "key": "k", "msgId": "m", "code": "1234", "text": "body"]
        let d = NotificationPresenter.resolve(actionId: "carom.dismiss", info: info)
        #expect(d?.action == .dismissOnPhone(phoneId: "p", key: "k", msgId: "m"))
        #expect(d?.copy == nil)
        let c = NotificationPresenter.resolve(actionId: "carom.copyCode", info: info)
        #expect(c?.action == .copiedCode("1234") && c?.copy == "1234")
        let t = NotificationPresenter.resolve(actionId: "carom.copyText", info: info)
        #expect(t?.action == .copiedText && t?.copy == "body")
        #expect(NotificationPresenter.resolve(actionId: "carom.copyCode", info: [:]) == nil)
        #expect(NotificationPresenter.resolve(actionId: "other", info: info) == nil)
    }
}
