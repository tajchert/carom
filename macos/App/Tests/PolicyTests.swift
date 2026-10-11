import CaromProtocol
import CaromStore
import Foundation
import Testing
@testable import Carom

@Suite("ReceivePolicy")
struct ReceivePolicyTests {
    let all = [phoneA, phoneB, laptopC]

    @Test func blockingFromEveryoneListsTheRest() {
        #expect(ReceivePolicy.rule(.everyone, setting: phoneA, allowed: false, devices: all) == .only([phoneB, laptopC]))
    }

    @Test func allowingEveryoneCollapsesToStar() {
        #expect(ReceivePolicy.rule(.only([phoneB, laptopC]), setting: phoneA, allowed: true, devices: all) == .everyone)
        #expect(ReceivePolicy.rule(nil, setting: phoneA, allowed: true, devices: [phoneA]) == .everyone)
    }

    @Test func departedDevicesAreDropped() {
        #expect(ReceivePolicy.rule(.only([phoneA, devId("Z")]), setting: phoneB, allowed: false, devices: all)
            == .only([phoneA]))
    }

    @Test func clipboardTargetsFollowTheirRecords() {
        let wants = entry(record(phoneA, "A", kind: .phone))
        let blocks = entry(record(phoneB, "B", kind: .phone, receive: [MessageType.clipboardSet.rawValue: .only([laptopC])]))
        let targets = ReceivePolicy.clipboardTargets([wants, blocks], selfId: macId)
        #expect(targets.map(\.record.id) == [phoneA])
    }

    @Test func sendRestriction() {
        let eligible = [entry(record(phoneA, "A", kind: .phone)), entry(record(phoneB, "B", kind: .phone))]
        #expect(ReceivePolicy.sendRestriction(nil, eligible: eligible) == nil)
        #expect(ReceivePolicy.sendRestriction([phoneA, phoneB], eligible: eligible) == nil)
        #expect(ReceivePolicy.sendRestriction([phoneA, laptopC], eligible: eligible) == [phoneA])
        #expect(ReceivePolicy.sendTo(nil, setting: phoneA, enabled: false, eligible: [phoneA, phoneB]) == [phoneB])
        #expect(ReceivePolicy.sendTo([phoneB], setting: phoneA, enabled: true, eligible: [phoneA, phoneB]) == nil)
    }

    @Test func automaticClipboardDefault() {
        #expect(ReceivePolicy.automaticClipboard(nil, access: .alwaysAllow))
        #expect(!ReceivePolicy.automaticClipboard(nil, access: .ask))
        #expect(ReceivePolicy.automaticClipboard(nil, access: .unknown))  // macOS < 15.4
        #expect(!ReceivePolicy.automaticClipboard(nil, access: .standard))
        #expect(ReceivePolicy.automaticClipboard(true, access: .alwaysDeny))
        #expect(!ReceivePolicy.automaticClipboard(false, access: .alwaysAllow))
    }
}

@Suite("Preferences and activity")
struct PreferencesTests {
    @Test func roundTripsThroughJSON() {
        var p = Preferences()
        p.paused = true
        p.setMuted("com.a", true)
        p.noteApp(id: "com.b", name: "Bee")
        p.noteApp(id: "com.a", name: "Ant")
        p.automaticClipboard = false
        p.clipboardSendTo = [phoneA]
        p.onboardingComplete = true
        #expect(Preferences(json: p.json) == p)
        #expect(p.seenApps.map(\.name) == ["Ant", "Bee"])
    }

    @Test func missingOrBrokenJSONGivesDefaults() {
        #expect(Preferences(json: nil) == Preferences())
        #expect(Preferences(json: .string("nope")) == Preferences())
    }

    @Test func settingsSavedBeforeTheLiveChipOptionStillDecode() {
        let old: JSON = ["paused": true, "mutedApps": ["com.a"], "seenApps": [], "onboardingComplete": true]
        let p = Preferences(json: old)
        #expect(p.paused && p.onboardingComplete && p.mutedApps == ["com.a"])
        #expect(p.showsLiveChip, "the chip is on by default")
    }

    @Test func liveChipOptionRoundTrips() {
        var p = Preferences()
        p.liveChip = false
        let back = Preferences(json: p.json)
        #expect(back == p && !back.showsLiveChip)
    }

    @Test func autoCopyIsOffByDefaultAndRoundTrips() {
        #expect(!Preferences().copiesCodes)
        var p = Preferences()
        p.autoCopyCodes = true
        let back = Preferences(json: p.json)
        #expect(back == p && back.copiesCodes)
    }

    @Test func noteAppReportsChanges() {
        var p = Preferences()
        let first = p.noteApp(id: "a", name: "A")
        let again = p.noteApp(id: "a", name: "A")
        let renamed = p.noteApp(id: "a", name: "A2")
        #expect(first && !again && renamed)
        #expect(p.seenApps == [SeenApp(id: "a", name: "A2")])
    }

    @Test func clipPreviewIsOneShortLine() {
        let long = String(repeating: "x", count: 100)
        let clip = RecentClip(direction: .sent(to: []), text: "  first line \nsecond", sensitive: false, date: .now)
        #expect(clip.preview == "first line")
        let longClip = RecentClip(direction: .sent(to: []), text: long, sensitive: false, date: .now)
        #expect(longClip.preview.count == 81)
    }

    @Test func clipsAreCapped() {
        var a = RecentActivity()
        for i in 0..<15 { a.add(RecentClip(direction: .received(from: "P"), text: "\(i)", sensitive: false, date: .now)) }
        #expect(a.clips.count == RecentActivity.clipLimit)
        #expect(a.clips.first?.text == "14")
    }
}

@Suite("Messages and QR")
struct MessageTests {
    @Test func sentSummary() {
        #expect(UserMessages.sent(to: ["Pixel"]) == "Sent to Pixel.")
        #expect(UserMessages.sent(to: ["A", "B"]) == "Sent to 2 devices.")
        #expect(UserMessages.sent(to: ["Pixel"], onlyPhones: true)
                == "Sent to Pixel. It arrives when Carom is opened on your phone.")
        #expect(UserMessages.arrivesWhenOpened(on: ["Pixel"]) == "Arrives when Carom is opened on Pixel.")
    }

    @Test func qrRoundTrip() throws {
        let uri = "carom://pair?v=1&h=https%3A%2F%2Fhub.example.com&c=AAAAAAAAAAAAAAAAAAAAAA&s=BBBB"
        let image = try #require(QRCode.image(for: uri))
        #expect(QRCode.caromURI(in: image) == uri)
    }

    @Test func textInputAcceptsOnlyCaromLinks() {
        #expect(CodeInput.uri(fromText: "  carom://setup?v=1 \n") == "carom://setup?v=1")
        #expect(CodeInput.uri(fromText: "https://example.com") == nil)
    }
}
