import CaromProtocol
import CaromStore
import CaromSync
import Foundation
import Testing
@testable import Carom
import CaromDesktop

private let pixel = record(phoneA, "Pixel", kind: .phone)
private let galaxy = record(phoneB, "Galaxy", kind: .phone)
private let laptop = record(laptopC, "Laptop", kind: .desktop)

private func posted(_ key: String = "k1", app: String = "com.chat", title: String? = "Ana",
                    text: String? = "Your code is 482913") -> Message {
    .notificationPosted(NotificationPosted(key: key, app: .init(id: app, name: "Chat"), title: title, text: text,
                                           postedAt: 1))
}

private func replyable(_ key: String = "k1") -> Message {
    var n = NotificationPosted(key: key, app: .init(id: "com.chat", name: "Chat"), title: "Ana", text: "Dinner?",
                               postedAt: 1)
    n.actions = [.init(index: 0, title: "Mark as read", semantic: 2, reply: nil),
                 .init(index: 1, title: "Reply", semantic: 1, reply: .init(label: nil, choices: ["Yes"]))]
    return .notificationPosted(n)
}

@MainActor
@Suite("AppModel")
struct AppModelTests {
    // MARK: Rich notifications

    @Test func nativeOnlyStyleSkipsThePanelAndIsSaved() async {
        let h = Harness()
        await h.start()
        h.model.setNotificationStyle(.nativeOnly)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.panel.shows == 0)
        #expect(h.presenter.presented.first?.0.passive == false)
        await settle()
        let saved = Preferences(json: await h.sync.settings[Preferences.settingsKey])
        #expect(saved.notificationStyle == .nativeOnly)
    }

    @Test func postedCardCarriesThePhonesActionCap() async {
        let phone = actionPhone(phoneA, "Pixel")
        let h = Harness(directory: [entry(phone)])
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: phone)
        let card = h.model.notifications.cards[CardItem.id(phoneId: phoneA, key: "k1")]
        #expect(card?.phoneAcceptsActions == true)
        #expect(card?.msgId == "m1")
        #expect(card?.phoneName == nil, "the phone name shows only with more than one phone")
        #expect(h.panel.shows == 1)
        #expect(h.presenter.presented.first?.0.passive == true, "the native copy is quiet while the panel shows")
    }

    @Test func replyFromACardSendsTheActionAndShowsTheResult() async {
        let phone = actionPhone(phoneA, "Pixel")
        let h = Harness(directory: [entry(phone)])
        await h.start()
        await h.model.dispatch(replyable(), msgId: "m1", sender: phone)
        let id = CardItem.id(phoneId: phoneA, key: "k1")
        #expect(h.model.notifications.perform(.reply(index: 1, title: "Reply", text: "On my way"), on: id))
        await settle()
        let expected = CaromProtocol.NotificationAction(
            key: "k1", action: .button(index: 1, title: "Reply", replyText: "On my way"))
        #expect(await h.sync.sent.last == FakeSync.Sent(type: .notificationAction, body: expected.json, replyTo: "m1",
                                                       only: nil))
        #expect(h.model.notifications.status(for: id) == .sending)
        await h.model.dispatch(.notificationActionResult(NotificationActionResult(ok: true, error: nil)),
                               msgId: "r1", sender: phone, replyTo: "act-1")
        #expect(h.model.notifications.status(for: id) == .sent)
    }

    @Test func phoneWithoutTheActionCapGetsNoActions() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(replyable(), msgId: "m1", sender: pixel)
        let id = CardItem.id(phoneId: phoneA, key: "k1")
        h.model.notifications.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        #expect(await h.sync.sent.isEmpty)
        #expect(h.model.notifications.status(for: id) == .idle)
        #expect(h.presenter.presented.first?.0.reply == nil)
    }

    @Test func droppedActionShowsFailed() async {
        let phone = actionPhone(phoneA, "Pixel")
        let h = Harness(directory: [entry(phone)])
        await h.start()
        await h.sync.set(outcome: .dropped(code: "bad_request"))
        await h.model.dispatch(replyable(), msgId: "m1", sender: phone)
        let id = CardItem.id(phoneId: phoneA, key: "k1")
        h.model.notifications.perform(.button(index: 0, title: "Mark as read"), on: id)
        await settle()
        #expect(h.model.notifications.status(for: id) == .failed)
    }

    @Test func nativeReplyGoesThroughTheCard() async {
        let phone = actionPhone(phoneA, "Pixel")
        let h = Harness(directory: [entry(phone)])
        await h.start()
        await h.model.dispatch(replyable(), msgId: "m1", sender: phone)
        await h.model.perform(.reply(phoneId: phoneA, key: "k1", msgId: "m1", index: 1, title: "Reply", text: "Yes"))
        await settle()
        #expect(await h.sync.sent.last?.type == .notificationAction)
        #expect(h.model.notifications.status(for: CardItem.id(phoneId: phoneA, key: "k1")) == .sending)
    }

    @Test func cardDismissRepliesAndWithdraws() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        h.model.notifications.perform(.dismiss, on: CardItem.id(phoneId: phoneA, key: "k1"))
        await settle()
        #expect(await h.sync.sent == [FakeSync.Sent(type: .notificationDismiss, body: ["key": "k1"], replyTo: "m1",
                                                   only: nil)])
        #expect(h.presenter.withdrawn.first?.1 == "k1")
        #expect(h.model.notifications.cards.isEmpty)
    }

    @Test func copiedCodeFromACardIsNotSentBack() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        h.model.notifications.perform(.copyCode("482913"), on: CardItem.id(phoneId: phoneA, key: "k1"))
        #expect(h.clipboard.written == [ClipboardItem(text: "482913", sensitive: false)])
        h.clipboard.onCopy?(ClipboardItem(text: "482913", sensitive: false))
        await settle()
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func removedPhoneDropsItsCards() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.sync.set(directory: [entry(galaxy)])
        await h.model.handle(event: .directoryChanged)
        #expect(h.model.notifications.cards.isEmpty)
    }

    @Test func directoryChangeRecomputesTheActionCapOfExistingCards() async {
        let h = Harness(directory: [entry(pixel)])
        await h.start()
        await h.model.dispatch(replyable(), msgId: "m1", sender: pixel)
        let id = CardItem.id(phoneId: phoneA, key: "k1")
        #expect(h.model.notifications.cards[id]?.phoneAcceptsActions == false)

        await h.sync.set(directory: [entry(actionPhone(phoneA, "Pixel"))])
        await h.model.handle(event: .directoryChanged)
        #expect(h.model.notifications.cards[id]?.phoneAcceptsActions == true)
        #expect(h.model.notifications.cards[id]?.replyAction != nil)

        await h.sync.set(directory: [entry(pixel)])
        await h.model.handle(event: .directoryChanged)
        #expect(h.model.notifications.cards[id]?.phoneAcceptsActions == false)
        #expect(h.model.notifications.cards[id]?.phoneButtons.isEmpty == true)
    }

    @Test func focusAccessIsRequestedWhenReady() async {
        let h = Harness()
        await h.start()
        await settle()
        #expect(h.focus.authorizationRequests == 1)
    }

    // MARK: Lifecycle

    @Test func startsReadyWhenConfiguredAndOnboarded() async {
        let h = Harness()
        await h.start()
        #expect(h.model.phase == .ready)
        #expect(h.model.hubHost == "carom-hub.example.workers.dev")
        #expect(h.model.directory.map(\.record.id) == [phoneA])
        #expect(h.hotkey.handler != nil)
        #expect(h.model.hotkeyAvailable)
    }

    @Test func showsOnboardingWhenNotConfigured() async {
        let h = Harness(configured: false)
        await h.start()
        #expect(h.model.phase == .onboarding)
        #expect(h.model.windowRequest?.window == .onboarding)
        #expect(h.hotkey.handler == nil)
    }

    @Test func resumesOnboardingWhenConnectedButNotFinished() async {
        let h = Harness(onboarded: false)
        await h.start()
        #expect(h.model.phase == .onboarding)
        await h.model.finishOnboarding()
        #expect(h.model.phase == .ready)
        await settle()
        let saved = Preferences(json: await h.sync.settings[Preferences.settingsKey])
        #expect(saved.onboardingComplete)
    }

    @Test func takenHotkeyIsReported() async {
        let h = Harness()
        h.hotkey.fail = true
        await h.start()
        #expect(!h.model.hotkeyAvailable)
    }

    // MARK: Notifications

    @Test func nativeReplySendsAnAction() async {
        let h = Harness()
        await h.start()
        await h.model.perform(.reply(phoneId: phoneA, key: "k1", msgId: "m1", index: 1, title: "Reply",
                                     text: "On my way"))
        let expected = CaromProtocol.NotificationAction(
            key: "k1", action: .button(index: 1, title: "Reply", replyText: "On my way"))
        #expect(await h.sync.sent == [FakeSync.Sent(type: .notificationAction, body: expected.json, replyTo: "m1",
                                                    only: nil)])
    }

    @Test func presentsPostedNotification() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.presenter.presented.count == 1)
        let (n, showPhone) = h.presenter.presented[0]
        #expect(n.phoneId == phoneA && n.key == "k1" && n.msgId == "m1" && n.appName == "Chat" && n.title == "Ana")
        #expect(!showPhone, "phone name is shown only with more than one phone")
        #expect(h.model.activity.notifications.first?.code == "482913")
        #expect(h.model.preferences.seenApps == [SeenApp(id: "com.chat", name: "Chat")])
    }

    // MARK: Late messages

    private func sentAgo(_ h: Harness, _ seconds: TimeInterval) -> Date { h.clock.now.addingTimeInterval(-seconds) }

    @Test func freshNotificationIsPresentedAndAutoCopied() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel, sentAt: sentAgo(h, 5))
        #expect(h.presenter.presented.count == 1)
        #expect(h.clipboard.written == [ClipboardItem(text: "482913", sensitive: true)])
    }

    @Test func lateNotificationIsQuietButListedAndNotCopied() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel, sentAt: sentAgo(h, 600))
        #expect(h.presenter.presented.isEmpty)
        #expect(h.clipboard.written.isEmpty)
        #expect(h.model.activity.notifications.first?.code == "482913")
    }

    @Test func freshUpdateAfterALateOneIsPresented() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel, sentAt: sentAgo(h, 600))
        await h.model.dispatch(posted(), msgId: "m2", sender: pixel, sentAt: sentAgo(h, 1))
        #expect(h.presenter.presented.count == 1)
        #expect(h.presenter.presented[0].0.msgId == "m2")
    }

    @Test func fourLateNotificationsPostOneSummary() async throws {
        let h = Harness()
        await h.start()
        for i in 0..<4 {
            await h.model.dispatch(posted("k\(i)"), msgId: "m\(i)", sender: pixel, sentAt: sentAgo(h, 600))
        }
        try await Task.sleep(for: .milliseconds(200))
        #expect(h.presenter.presented.isEmpty)
        #expect(h.presenter.notices.map(\.body) == ["4 notifications while you were away"])
    }

    @Test func threeLateNotificationsPostNoSummary() async throws {
        let h = Harness()
        await h.start()
        for i in 0..<3 {
            await h.model.dispatch(posted("k\(i)"), msgId: "m\(i)", sender: pixel, sentAt: sentAgo(h, 600))
        }
        try await Task.sleep(for: .milliseconds(200))
        #expect(h.presenter.notices.isEmpty)
        #expect(h.model.activity.notifications.count == 3)
    }

    @Test func lateClipboardGoesToHistoryOnly() async {
        let h = Harness()
        await h.start()
        let sent = sentAgo(h, 600)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "old", sensitive: false)), msgId: "m1", sender: pixel,
                               sentAt: sent)
        #expect(h.clipboard.written.isEmpty)
        #expect(h.model.activity.clips.first?.text == "old")
        #expect(h.model.activity.clips.first?.date == sent)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "new", sensitive: false)), msgId: "m2", sender: pixel,
                               sentAt: sentAgo(h, 3))
        #expect(h.clipboard.written == [ClipboardItem(text: "new", sensitive: false)])
    }

    @Test func historyIsDatedByPostedAtAndClampedToNow() async {
        let h = Harness()
        await h.start()
        let posted1 = h.clock.now.addingTimeInterval(-30)
        var n = NotificationPosted(key: "a", app: .init(id: "com.chat", name: "Chat"), title: "t", text: "x",
                                   postedAt: Int(posted1.timeIntervalSince1970 * 1000))
        await h.model.dispatch(.notificationPosted(n), msgId: "m1", sender: pixel)
        n.key = "b"
        n.postedAt = Int(h.clock.now.addingTimeInterval(3600).timeIntervalSince1970 * 1000)
        await h.model.dispatch(.notificationPosted(n), msgId: "m2", sender: pixel)
        let dates = Dictionary(uniqueKeysWithValues: h.model.activity.notifications.map { ($0.key, $0.date) })
        #expect(abs(dates["a"]!.timeIntervalSince(posted1)) < 0.01)
        #expect(dates["b"] == h.clock.now)
    }

    @Test func showsPhoneNameWithTwoPhones() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: galaxy)
        #expect(h.presenter.presented.first?.1 == true)
    }

    @Test func mutedAppIsNotShownButStillListed() async {
        let h = Harness()
        await h.start()
        h.model.setMuted("com.chat", true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.presenter.presented.isEmpty)
        #expect(h.model.activity.notifications.isEmpty)
        #expect(h.model.preferences.seenApps.map(\.id) == ["com.chat"])
    }

    @Test func phoneTurnedOffIsNotShown() async throws {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        try await h.model.setReceive(.notifications, from: phoneA, false)
        #expect(await h.sync.receiveRules[MessageType.notificationPosted.rawValue] == .only([phoneB]))
        #expect(await h.sync.receiveRules[MessageType.notificationRemoved.rawValue] == .only([phoneB]))
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.model.dispatch(posted(), msgId: "m2", sender: galaxy)
        #expect(h.presenter.presented.map(\.0.phoneId) == [phoneB])
    }

    @Test func pausedShowsAndWritesNothing() async {
        let h = Harness()
        await h.start()
        h.model.setPaused(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "hi")), msgId: "m2", sender: pixel)
        #expect(h.presenter.presented.isEmpty)
        #expect(h.clipboard.written.isEmpty)
        #expect(!h.clipboard.running)
    }

    @Test func removedWithdraws() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.model.activity.notifications.count == 1)
        await h.model.dispatch(.notificationRemoved(NotificationRemoved(key: "k1")), msgId: "m2", sender: pixel)
        #expect(h.model.activity.notifications.isEmpty, "a removed notification leaves the menu too")
        #expect(h.presenter.withdrawn.count == 1)
        #expect(h.presenter.withdrawn.first?.0 == phoneA && h.presenter.withdrawn.first?.1 == "k1")
        #expect(h.model.notifications.cards.isEmpty)
    }

    @Test func recentNotificationKeepsTheAppIcon() async {
        let h = Harness()
        await h.start()
        let icon = BlobRef(id: "icon1", key: "", mime: "image/webp", size: 1, hash: "AAAAAAAAAAAAAAAAAAAAAA==")
        var n = NotificationPosted(key: "k1", app: .init(id: "com.chat", name: "Chat", icon: icon), title: "Ana",
                                   text: "Hi", postedAt: 1)
        await h.model.dispatch(.notificationPosted(n), msgId: "m1", sender: pixel)
        #expect(h.model.activity.notifications.first?.icon == icon)
        n.key = "k2"
        n.app.icon = nil
        await h.model.dispatch(.notificationPosted(n), msgId: "m2", sender: pixel)
        #expect(h.model.activity.notifications.first?.icon == nil)
    }

    @Test func dismissActionRepliesToTheOriginalMessage() async {
        let h = Harness()
        await h.start()
        await h.model.perform(.dismissOnPhone(phoneId: phoneA, key: "k1", msgId: "m1"))
        let sent = await h.sync.sent
        #expect(sent == [FakeSync.Sent(type: .notificationDismiss, body: ["key": "k1"], replyTo: "m1", only: nil)])
    }

    @Test func recentNotificationsKeepTheLastFifty() async {
        let h = Harness()
        await h.start()
        for i in 0..<55 { await h.model.dispatch(posted("k\(i)"), msgId: "m\(i)", sender: pixel) }
        #expect(h.model.activity.notifications.count == 50)
        #expect(h.model.activity.notifications.first?.key == "k54")
        // An update of the same key replaces the row.
        await h.model.dispatch(posted("k54", title: "Updated"), msgId: "m99", sender: pixel)
        #expect(h.model.activity.notifications.count == 50)
        #expect(h.model.activity.notifications.first?.title == "Updated")
    }

    @Test func removedPhoneWithdrawsItsNotifications() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.sync.set(directory: [entry(galaxy)])
        await h.model.handle(event: .directoryChanged)
        #expect(h.presenter.withdrawnAll == [phoneA])
        #expect(h.model.activity.notifications.isEmpty)
    }

    // MARK: Clipboard

    @Test func receivedClipboardIsWritten() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "secret", sensitive: true)), msgId: "m1", sender: pixel)
        #expect(h.clipboard.written == [ClipboardItem(text: "secret", sensitive: true)])
        #expect(h.model.activity.clips.first?.preview == "Hidden (sensitive)")
    }

    @Test func clipboardFromBlockedDeviceIsIgnored() async throws {
        let h = Harness(directory: [entry(pixel), entry(laptop)])
        await h.start()
        try await h.model.setReceive(.clipboard, from: laptopC, false)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "x")), msgId: "m1", sender: laptop)
        #expect(h.clipboard.written.isEmpty)
        await h.model.dispatch(.clipboardSet(ClipboardSet(text: "y")), msgId: "m2", sender: pixel)
        #expect(h.clipboard.written.map(\.text) == ["y"])
    }

    @Test func automaticModeDefaultsOnWithAlwaysAllowOrBefore15_4() async {
        let allowed = Harness(access: .alwaysAllow)
        await allowed.start()
        #expect(allowed.model.automaticClipboard)
        #expect(allowed.clipboard.running)

        // Before macOS 15.4 there is no pasteboard access control, so it is on by default too.
        let older = Harness(access: .unknown)
        await older.start()
        #expect(older.model.automaticClipboard)
        #expect(older.clipboard.running)

        for access: PasteboardAccess in [.ask, .standard, .alwaysDeny] {
            let h = Harness(access: access)
            await h.start()
            #expect(!h.model.automaticClipboard)
            #expect(!h.clipboard.running)
            h.model.setAutomaticClipboard(true)
            #expect(h.clipboard.running, "the user can still turn it on")
        }
    }

    @Test func copyIsSentWhenAutomatic() async {
        let h = Harness()
        await h.start()
        h.clipboard.onCopy?(ClipboardItem(text: "hello", sensitive: false))
        await settle()
        let sent = await h.sync.sent
        #expect(sent.count == 1)
        #expect(sent.first?.type == .clipboardSet)
        #expect(sent.first?.body["text"] == .string("hello"))
        #expect(sent.first?.only == nil)
        #expect(h.model.statusMessage == "Sent to Pixel. It arrives when Carom is opened on your phone.")
    }

    @Test func copyIsNotSentWhenAutomaticIsOff() async {
        let h = Harness(access: .ask)
        await h.start()
        h.clipboard.onCopy?(ClipboardItem(text: "hello", sensitive: false))
        await settle()
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func copiedFromNotificationIsNotSentBack() async {
        let h = Harness()
        await h.start()
        await h.model.perform(.copiedCode("482913"))
        h.clipboard.onCopy?(ClipboardItem(text: "482913", sensitive: true))
        await settle()
        #expect(await h.sync.sent.isEmpty)
        h.clock.advance(5)
        h.clipboard.onCopy?(ClipboardItem(text: "later", sensitive: false))
        await settle()
        #expect(await h.sync.sent.count == 1)
    }

    @Test func hotkeySendsCurrentClipboard() async {
        let h = Harness(access: .ask)
        await h.start()
        h.clipboard.current = ClipboardItem(text: "via hotkey", sensitive: true)
        h.hotkey.handler?()
        await settle()
        let sent = await h.sync.sent
        #expect(sent.first?.body["text"] == .string("via hotkey"))
        #expect(sent.first?.body["sensitive"] == .bool(true))
    }

    @Test func emptyClipboardExplains() async {
        let h = Harness()
        await h.start()
        await h.model.sendClipboardNow()
        #expect(h.model.statusMessage == UserMessages.nothingToSend)
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func sendToRestrictsRecipients() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        h.model.setSendClipboard(to: phoneB, false)
        #expect(h.model.preferences.clipboardSendTo == [phoneA])
        await h.model.sendClipboard(ClipboardItem(text: "x", sensitive: false))
        #expect(await h.sync.sent.first?.only == [phoneA])
        h.model.setSendClipboard(to: phoneB, true)
        #expect(h.model.preferences.clipboardSendTo == nil)
    }

    @Test func noClipboardTargetsExplains() async {
        let blocked = record(phoneA, "Pixel", kind: .phone, receive: [:])
        let h = Harness(directory: [entry(blocked)])
        await h.start()
        await h.model.sendClipboard(ClipboardItem(text: "x", sensitive: false))
        #expect(h.model.statusMessage == UserMessages.noClipboardTargets)
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func queuedSendExplains() async {
        let h = Harness()
        await h.start()
        await h.sync.set(outcome: .queued)
        await h.model.sendClipboard(ClipboardItem(text: "x", sensitive: false))
        #expect(h.model.statusMessage == UserMessages.queued)
    }

    // MARK: Engine events

    @Test func tokenRejectedStopsSyncingAndAsksToPairAgain() async {
        let h = Harness()
        await h.start()
        await h.model.handle(event: .tokenRejected)
        await h.model.handle(event: .tokenRejected)
        await settle()
        #expect(h.model.connection == .stopped)
        #expect(h.model.banners.map(\.kind) == [.tokenRejected])
        #expect(h.model.banners.first?.message == UserMessages.tokenRejected)
        #expect(await h.sync.stopRealtimeCalls >= 1)

        await h.model.resetAndPairAgain()
        #expect(await h.sync.wiped)
        #expect(h.model.phase == .onboarding)
        #expect(h.model.banners.isEmpty)
    }

    @Test func clockDriftShowsABannerAndOneNoticePerEpisode() async {
        let h = Harness()
        await h.start()
        h.model.clockDriftChanged(600_000)
        h.model.clockDriftChanged(-720_000)  // same episode, new value
        await settle()
        #expect(h.model.banners.map(\.kind) == [.clockSkew])
        #expect(h.model.banners.first?.message == UserMessages.clockOff(skewMs: -720_000))
        #expect(h.model.banners.first?.message.hasPrefix("This Mac's clock is ahead by about 12 minutes.") == true)
        #expect(h.presenter.notices.count == 1)
        h.model.clockDriftChanged(nil)
        #expect(h.model.banners.isEmpty)
        h.model.clockDriftChanged(400_000)  // a new episode
        await settle()
        #expect(h.presenter.notices.count == 2)
    }

    @Test func revokedReturnsToOnboarding() async {
        let h = Harness()
        await h.start()
        await h.model.handle(event: .revoked)
        #expect(h.model.phase == .onboarding)
        #expect(h.model.banners.map(\.kind) == [.revoked])
        #expect(h.model.windowRequest?.window == .onboarding)
        #expect(h.hotkey.handler == nil)
        #expect(!h.clipboard.running)
    }

    @Test func removalNoticesBecomeBanners() async {
        let h = Harness()
        await h.start()
        await h.model.handle(event: .deviceRemoved(name: "Old Phone", by: "Pixel"))
        await h.model.handle(event: .deviceRemoved(name: "Tablet", by: nil))
        #expect(h.model.banners.map(\.message) == ["Pixel removed Old Phone.",
                                                   "Tablet was removed without a key change (no rekey)."])
        h.model.dismissBanner(h.model.banners[0])
        #expect(h.model.banners.count == 1)
    }

    @Test func hubUnreachableAfterTimeout() async throws {
        let h = Harness()
        await h.start()
        h.model.realtimeChanged(.connecting)
        #expect(h.model.connection == .connecting)
        try await Task.sleep(for: .milliseconds(150))
        #expect(h.model.banners.map(\.kind) == [.hubUnreachable])
        h.model.realtimeChanged(.connected)
        #expect(h.model.connection == .connected)
        #expect(h.model.banners.isEmpty)
        // A dropped connection goes back to connecting; asleep and no network win over it.
        h.model.realtimeChanged(.connecting)
        #expect(h.model.connection == .connecting)
        h.model.networkChanged(false)
        h.model.realtimeChanged(.connecting)
        #expect(h.model.connection == .noNetwork)
    }

    @Test func selfLeaveNoticeAndRedactedNotification() async {
        let h = Harness()
        await h.start()
        await h.model.handle(event: .deviceRemoved(name: "Laptop", by: "Laptop"))
        #expect(h.model.banners.last?.message == "Laptop left your devices.")
        let hidden = NotificationPosted(key: "k1", app: .init(id: "com.bank", name: "Bank"), postedAt: 1, redacted: true)
        await h.model.dispatch(.notificationPosted(hidden), msgId: "m1", sender: pixel)
        #expect(h.presenter.presented.last?.0.text == UserMessages.contentHiddenOnPhone)
    }

    @Test func connectionFollowsTheEngineStream() async throws {
        let h = Harness()
        await h.start()
        h.sync.realtime.continuation.yield(.connecting)
        h.sync.realtime.continuation.yield(.connected)
        for _ in 0..<50 where h.model.connection != .connected { await settle() }
        #expect(h.model.connection == .connected)
    }

    @Test func quittingReleasesTheHotkey() async {
        let h = Harness()
        await h.start()
        #expect(h.hotkey.handler != nil)
        h.model.shutdown()
        #expect(h.hotkey.handler == nil)
        #expect(h.hotkey.unregisterCalls >= 1)
        #expect(!h.clipboard.running)
    }

    @Test func loginItemToggle() async {
        let h = Harness()
        await h.start()
        h.model.setLaunchAtLogin(true)
        #expect(h.model.launchAtLogin)
        #expect(h.model.loginItemMessage == nil)
        h.model.setLaunchAtLogin(false)
        #expect(!h.model.launchAtLogin)
        // Registered but waiting for approval in System Settings: the toggle stays off and says why.
        h.loginItem.needsApproval = true
        h.model.setLaunchAtLogin(true)
        #expect(!h.model.launchAtLogin)
        #expect(h.model.loginItemMessage == UserMessages.loginItemNeedsApproval)
    }

    @Test func onboardingClipboardCheckReadsOnce() async {
        let h = Harness(access: .standard, onboarded: false)
        await h.start()
        #expect(h.clipboard.reads == 0)
        h.model.checkClipboardAccess()
        #expect(h.clipboard.reads == 1)
        #expect(h.clipboard.written.isEmpty)
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func newDeviceIsNotAddedToNarrowedRules() async throws {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        try await h.model.setReceive(.clipboard, from: phoneB, false)
        h.model.setSendClipboard(to: phoneB, false)
        #expect(await h.sync.receiveRules[MessageType.clipboardSet.rawValue] == .only([phoneA]))

        await h.sync.set(directory: [entry(pixel), entry(galaxy), entry(laptop)])
        await h.model.handle(event: .directoryChanged)
        await settle()
        // A list of ids is an explicit choice: the new device is not added and shows unchecked.
        #expect(await h.sync.receiveRules[MessageType.clipboardSet.rawValue] == .only([phoneA]))
        #expect(h.model.preferences.clipboardSendTo == [phoneA])
        #expect(!h.model.receives(.clipboard, from: laptopC))
        #expect(!h.model.isSendingClipboard(to: laptopC))
        // "*" includes devices paired later.
        #expect(h.model.receives(.notifications, from: laptopC))
    }

    // MARK: Pairing

    @Test func pairingApproveFlow() async {
        let h = Harness()
        await h.start()
        await h.model.startPairing()
        guard case .showing(let invite) = h.model.pairing else {
            Issue.record("expected an invite")
            return
        }
        #expect(invite.pairId == "pair-1")
        h.model.candidateArrived(Candidate(pairId: "other", deviceId: phoneB))
        #expect(h.model.pairing == .showing(invite), "a candidate for another pairing is ignored")
        h.model.candidateArrived(Candidate(pairId: "pair-1", deviceId: phoneB))
        #expect(h.model.pairing == .confirming(invite, Candidate(pairId: "pair-1", deviceId: phoneB)))
        await h.model.approveCandidate()
        #expect(h.model.pairing == .added(fingerprint: DeviceID.fingerprint(phoneB)))
        #expect(await h.sync.approved == ["pair-1"])
    }

    @Test func pairingPollFindsCandidate() async throws {
        let h = Harness()
        await h.start()
        await h.sync.set(candidate: Candidate(pairId: "pair-1", deviceId: phoneB))
        await h.model.startPairing()
        try await Task.sleep(for: .milliseconds(100))
        guard case .confirming(_, let candidate) = h.model.pairing else {
            Issue.record("expected a candidate, got \(h.model.pairing)")
            return
        }
        #expect(candidate.fingerprint == DeviceID.fingerprint(phoneB))
        await h.model.rejectCandidate()
        #expect(await h.sync.rejected == ["pair-1"])
    }

    @Test func pairingRejectedCandidateWarns() async {
        let h = Harness()
        await h.start()
        await h.model.startPairing()
        await h.model.handle(event: .pairingRejected(pairId: "pair-1", reason: .badMac))
        #expect(h.model.banners.map(\.kind) == [.pairingRejected])
        #expect(h.model.pairing == .failed(UserMessages.pairingRejected))
    }

    // MARK: Onboarding

    @Test func setupRejectsAPairLink() async {
        let h = Harness(configured: false)
        await h.start()
        let pair = "carom://pair?v=1&h=https%3A%2F%2Fhub.example.com&c=AAAAAAAAAAAAAAAAAAAAAA&s=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        await #expect(throws: AppError(UserMessages.pairURIExpected)) { try await h.model.setUp(uri: pair) }
        await #expect(throws: AppError(UserMessages.notURI)) { try await h.model.setUp(uri: "hello") }
        #expect(await h.sync.setupURIs.isEmpty)
    }

    @Test func removeDeviceRevokes() async throws {
        let h = Harness()
        await h.start()
        try await h.model.remove(deviceId: phoneA)
        #expect(await h.sync.revoked == [phoneA])
    }
}

// MARK: - Blobs and one-time codes

/// A notification built from the wire JSON, with `extra` fields on top of a minimal body.
private func rich(_ key: String = "k1", app: String = "com.ride", _ extra: JSONObject = [:]) -> Message {
    var body: JSONObject = ["key": .string(key), "app": ["id": .string(app), "name": "Rides"], "posted_at": 1,
                            "redacted": false]
    for (k, v) in extra { body[k] = v }
    return Message(type: .notificationPosted, body: body)!
}

@MainActor
@Suite("Blobs and codes")
struct BlobAndCodeTests {
    @Test func menuCodeIsFoundInTheWholeNotification() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", ["text": "New message", "big_text": "Your code is 1234567890"]), msgId: "m1",
                               sender: pixel)
        #expect(h.model.activity.notifications.first?.code == "1234567890")
    }

    @Test func redactedNotificationHasNoCode() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", ["text": "Your code is 482913", "redacted": true]), msgId: "m1", sender: pixel)
        #expect(h.model.activity.notifications.first?.code == nil)
    }

    @Test func blobsAreFetchedThroughTheSyncEngine() async {
        let h = Harness()
        await h.start()
        let ref = BlobRef(id: "blob1", key: "", mime: "image/webp", size: 1, hash: "AAAAAAAAAAAAAAAAAAAAAA==")
        #expect(await h.model.blobs.image(for: ref) == nil)  // the fake has no such blob: a placeholder
        #expect(await h.sync.fetchedBlobs == ["blob1"])
    }
}

// MARK: - Live Update chip

private let promoted: JSONObject = ["live": ["promoted": true, "short_critical_text": "5 min"]]

@MainActor
@Suite("Live Update chip")
struct LiveChipWiringTests {
    @Test func promotedLiveUpdateShowsTheChipUntilItIsRemoved() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", promoted), msgId: "m1", sender: pixel)
        #expect(h.chipHost.calls == ["show text:5 min"])
        await h.model.dispatch(.notificationRemoved(NotificationRemoved(key: "k1")), msgId: "m2", sender: pixel)
        #expect(h.chipHost.calls == ["show text:5 min", "hide"])
    }

    @Test func anUpdateThatIsNoLongerPromotedEndsTheChip() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", promoted), msgId: "m1", sender: pixel)
        await h.model.dispatch(rich("k1"), msgId: "m2", sender: pixel)
        #expect(h.chipHost.calls == ["show text:5 min", "hide"])
    }

    @Test func plainNotificationsNeverShowTheChip() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.model.dispatch(rich("k2", ["live": ["promoted": false, "short_critical_text": "x"]]), msgId: "m2",
                               sender: pixel)
        #expect(h.chipHost.calls.isEmpty)
    }

    @Test func chipSettingAndPauseHideIt() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", promoted), msgId: "m1", sender: pixel)
        h.model.setLiveChip(false)
        #expect(h.chipHost.calls.last == "hide")
        h.model.setLiveChip(true)
        #expect(h.chipHost.calls.last == "show text:5 min")
        h.model.setPaused(true)
        #expect(h.chipHost.calls.last == "hide")
    }

    @Test func mutingTheAppEndsItsChip() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(rich("k1", promoted), msgId: "m1", sender: pixel)
        h.model.setMuted("com.other", true)
        #expect(h.chipHost.calls.last == "show text:5 min")
        h.model.setMuted("com.ride", true)
        #expect(h.chipHost.calls.last == "hide")
        // Muted updates never bring it back.
        await h.model.dispatch(rich("k1", promoted), msgId: "m2", sender: pixel)
        #expect(h.chipHost.calls.last == "hide")
    }

    @Test func removingThePhoneEndsItsChip() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(rich("k1", promoted), msgId: "m1", sender: pixel)
        await h.sync.set(directory: [entry(galaxy)])
        await h.model.handle(event: .directoryChanged)
        #expect(h.chipHost.calls == ["show text:5 min", "hide"])
    }
}

// MARK: - Now Playing

private func media(_ state: String = "playing", session: String = "s1", app: String = "com.music",
                   _ extra: JSONObject = [:]) -> Message {
    var body: JSONObject = ["session": .string(session), "app": ["id": .string(app), "name": "Music"],
                            "title": "Song", "state": .string(state), "controls": ["play", "pause", "next", "seek"]]
    for (k, v) in extra { body[k] = v }
    return Message(type: .mediaState, body: body)!
}

/// A phone that advertises `media.command@1`.
private let controllable: DeviceRecord = {
    var r = record(phoneA, "Pixel", kind: .phone)
    r.caps.append(MessageType.mediaCommand.rawValue)
    return r
}()

@MainActor
@Suite("Now Playing")
struct NowPlayingModelTests {
    @Test func mediaStateIsKeptPerSessionAndStoppedRemovesIt() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(media(session: "s1"), msgId: "m1", sender: pixel)
        await h.model.dispatch(media(session: "s2"), msgId: "m2", sender: pixel)
        await h.model.dispatch(media("paused", session: "s1"), msgId: "m3", sender: pixel)
        #expect(h.model.mediaSessions.map(\.state.session) == ["s2", "s1"])
        #expect(h.model.mediaSessions.last?.msgId == "m3")
        #expect(h.model.mediaSessions.last?.isPlaying == false)
        await h.model.dispatch(media("stopped", session: "s1"), msgId: "m4", sender: pixel)
        #expect(h.model.mediaSessions.map(\.state.session) == ["s2"])
    }

    @Test func twoPhonesCanUseTheSameSessionId() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: pixel)
        await h.model.dispatch(media(), msgId: "m2", sender: galaxy)
        #expect(h.model.mediaSessions.count == 2)
    }

    @Test func mediaIsHiddenWhilePausedAndForMutedApps() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: pixel)
        h.model.setPaused(true)
        #expect(h.model.nowPlaying.isEmpty)
        h.model.setPaused(false)
        #expect(h.model.nowPlaying.count == 1)
        h.model.setMuted("com.music", true)
        #expect(h.model.mediaSessions.isEmpty)
        await h.model.dispatch(media(), msgId: "m2", sender: pixel)
        #expect(h.model.mediaSessions.isEmpty)
    }

    @Test func aStateMissedWhilePausedDropsTheStaleSession() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: pixel)
        h.model.setPaused(true)
        await h.model.dispatch(media("paused"), msgId: "m2", sender: pixel)
        h.model.setPaused(false)
        #expect(h.model.mediaSessions.isEmpty)
        #expect(h.model.nowPlaying.isEmpty)
    }

    @Test func removingThePhoneEndsItsSessions() async {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: pixel)
        await h.sync.set(directory: [entry(galaxy)])
        await h.model.handle(event: .directoryChanged)
        #expect(h.model.mediaSessions.isEmpty)
    }

    @Test func commandRepliesToTheLatestStateAndGoesToThatPhoneOnly() async throws {
        let h = Harness(directory: [entry(controllable)])
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: controllable)
        await h.model.dispatch(media(), msgId: "m2", sender: controllable)
        let session = try #require(h.model.mediaSessions.first)
        #expect(h.model.canControlMedia(session))
        await h.model.sendMedia(.pause, sessionID: session.id)
        await h.model.sendMedia(.seek(positionMs: 42_000), sessionID: session.id)
        let sent = await h.sync.sent
        #expect(sent.map(\.type) == [.mediaCommand, .mediaCommand])
        #expect(sent.map(\.replyTo) == ["m2", "m2"])
        #expect(sent.map(\.only) == [[phoneA], [phoneA]])
        #expect(sent[0].body["session"] == .string("s1") && sent[0].body["command"] == .string("pause"))
        #expect(sent[1].body["command"] == .string("seek") && sent[1].body["position_ms"] == JSON(42_000))
    }

    @Test func noCommandWhenThePhoneLacksTheCapability() async throws {
        let h = Harness()  // the default phone does not advertise media.command@1
        await h.start()
        await h.model.dispatch(media(), msgId: "m1", sender: pixel)
        let session = try #require(h.model.mediaSessions.first)
        #expect(!h.model.canControlMedia(session))
        await h.model.sendMedia(.pause, sessionID: session.id)
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func noCommandForAControlThePhoneDoesNotOffer() async throws {
        let h = Harness(directory: [entry(controllable)])
        await h.start()
        await h.model.dispatch(media("playing", ["controls": ["play", "pause"]]), msgId: "m1", sender: controllable)
        let session = try #require(h.model.mediaSessions.first)
        await h.model.sendMedia(.next, sessionID: session.id)
        #expect(await h.sync.sent.isEmpty)
    }
}

// MARK: - Copy codes automatically

@MainActor
@Suite("Copy codes automatically")
struct AutoCopyTests {
    @Test func offByDefault() async {
        let h = Harness()
        await h.start()
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.clipboard.written.isEmpty)
    }

    @Test func writesAConcealedCodeAndClearsItLater() async throws {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        #expect(h.clipboard.written == [ClipboardItem(text: "482913", sensitive: true)])
        #expect(h.clipboard.clears == 0)
        try await Task.sleep(for: .milliseconds(150))
        #expect(h.clipboard.clears == 1)
    }

    @Test func doesNotClearAfterSomethingElseWasCopied() async throws {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        h.clipboard.changeCount += 1  // the user copied something else
        try await Task.sleep(for: .milliseconds(150))
        #expect(h.clipboard.clears == 0)
    }

    @Test func theCopyIsNotSentBackToThePhone() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        h.clipboard.onCopy?(ClipboardItem(text: "482913", sensitive: true))
        await settle()
        #expect(await h.sync.sent.isEmpty)
    }

    @Test func anUpdateOfTheSameNotificationCopiesOnce() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        await h.model.dispatch(posted(), msgId: "m2", sender: pixel)
        await h.model.dispatch(posted(text: "Your code is 777777"), msgId: "m3", sender: pixel)
        #expect(h.clipboard.written.map(\.text) == ["482913", "777777"])
    }

    @Test func redactedAndPausedNotificationsAreNotCopied() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(rich("k1", ["text": "Your code is 482913", "redacted": true]), msgId: "m1", sender: pixel)
        h.model.setPaused(true)
        await h.model.dispatch(posted(), msgId: "m2", sender: pixel)
        #expect(h.clipboard.written.isEmpty)
    }

    @Test func quittingClearsAPendingCode() async {
        let h = Harness()
        await h.start()
        h.model.setAutoCopyCodes(true)
        await h.model.dispatch(posted(), msgId: "m1", sender: pixel)
        h.model.shutdown()
        #expect(h.clipboard.clears == 1)
    }
}
