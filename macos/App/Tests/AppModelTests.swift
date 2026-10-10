import CaromProtocol
import CaromStore
import CaromSync
import Foundation
import Testing
@testable import Carom
#if canImport(CaromDesktop)
import CaromDesktop
#endif

private let pixel = record(phoneA, "Pixel", kind: .phone)
private let galaxy = record(phoneB, "Galaxy", kind: .phone)
private let laptop = record(laptopC, "Laptop", kind: .desktop)

private func posted(_ key: String = "k1", app: String = "com.chat", title: String? = "Ana",
                    text: String? = "Your code is 482913") -> Message {
    .notificationPosted(NotificationPosted(key: key, app: .init(id: app, name: "Chat"), title: title, text: text,
                                           postedAt: 1))
}

@MainActor
@Suite("AppModel")
struct AppModelTests {
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
        await h.model.dispatch(.notificationRemoved(NotificationRemoved(key: "k1")), msgId: "m2", sender: pixel)
        #expect(h.presenter.withdrawn.count == 1)
        #expect(h.presenter.withdrawn.first?.0 == phoneA && h.presenter.withdrawn.first?.1 == "k1")
    }

    @Test func dismissActionRepliesToTheOriginalMessage() async {
        let h = Harness()
        await h.start()
        await h.model.perform(.dismissOnPhone(phoneId: phoneA, key: "k1", msgId: "m1"))
        let sent = await h.sync.sent
        #expect(sent == [FakeSync.Sent(type: .notificationDismiss, body: ["key": "k1"], replyTo: "m1", only: nil)])
    }

    @Test func recentNotificationsKeepTheLastTwenty() async {
        let h = Harness()
        await h.start()
        for i in 0..<25 { await h.model.dispatch(posted("k\(i)"), msgId: "m\(i)", sender: pixel) }
        #expect(h.model.activity.notifications.count == 20)
        #expect(h.model.activity.notifications.first?.key == "k24")
        // An update of the same key replaces the row.
        await h.model.dispatch(posted("k24", title: "Updated"), msgId: "m99", sender: pixel)
        #expect(h.model.activity.notifications.count == 20)
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

    @Test func automaticModeDefaultsOnOnlyWithAlwaysAllow() async {
        let allowed = Harness(access: .alwaysAllow)
        await allowed.start()
        #expect(allowed.model.automaticClipboard)
        #expect(allowed.clipboard.running)

        for access: PasteboardAccess in [.ask, .standard, .alwaysDeny, .unknown] {
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
        #expect(h.model.statusMessage == "Sent to Pixel.")
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
        h.model.transport(.opening(id: 1))
        #expect(h.model.connection == .connecting)
        try await Task.sleep(for: .milliseconds(150))
        #expect(h.model.banners.map(\.kind) == [.hubUnreachable])
        h.model.transport(.up(id: 1))
        #expect(h.model.connection == .connected)
        #expect(h.model.banners.isEmpty)
        // A late failure of an older connection is ignored.
        h.model.transport(.opening(id: 2))
        h.model.transport(.up(id: 2))
        h.model.transport(.down(id: 1))
        #expect(h.model.connection == .connected)
    }

    @Test func newDeviceJoinsNarrowedRules() async throws {
        let h = Harness(directory: [entry(pixel), entry(galaxy)])
        await h.start()
        try await h.model.setReceive(.clipboard, from: phoneB, false)
        h.model.setSendClipboard(to: phoneB, false)
        #expect(await h.sync.receiveRules[MessageType.clipboardSet.rawValue] == .only([phoneA]))

        await h.sync.set(directory: [entry(pixel), entry(galaxy), entry(laptop)])
        await h.model.handle(event: .directoryChanged)
        await settle()
        #expect(await h.sync.receiveRules[MessageType.clipboardSet.rawValue] == .only([phoneA, laptopC]))
        #expect(h.model.preferences.clipboardSendTo == [phoneA, laptopC])
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
