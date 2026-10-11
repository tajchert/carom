import CaromProtocol
import Testing

@testable import CaromDesktop

@MainActor
private final class RecordingHost: LiveChipHost {
    enum Call: Equatable { case show(LiveChipContent), hide }
    var calls: [Call] = []
    func show(_ content: LiveChipContent) { calls.append(.show(content)) }
    func hide() { calls.append(.hide) }
}

private func posted(promoted: Bool? = true, critical: String? = nil, progress: JSON? = nil) -> NotificationPosted {
    var body: JSONObject = ["key": "k", "app": ["id": "a", "name": "A"], "posted_at": 1, "redacted": false]
    if let promoted {
        var live: JSONObject = ["promoted": .bool(promoted)]
        if let critical { live["short_critical_text"] = .string(critical) }
        body["live"] = .object(live)
    }
    if let progress { body["progress"] = progress }
    return NotificationPosted(json: body)!
}

private let determinate: JSON = ["value": 50, "max": 100, "indeterminate": false]

@Suite struct LiveChipPolicyTests {
    @Test func criticalTextWins() {
        #expect(LiveChipPolicy.content(for: [posted(critical: "5 min", progress: determinate)]) == .text("5 min"))
    }

    @Test func longCriticalTextIsCut() {
        #expect(LiveChipPolicy.content(for: [posted(critical: "Arriving in 12 minutes")]) == .text("Arriving i"))
    }

    @Test func ringForDeterminateProgress() {
        #expect(LiveChipPolicy.content(for: [posted(progress: determinate)]) == .progress(0.5))
    }

    @Test func nothingForIndeterminateOrBareUpdates() {
        let spinning: JSON = ["value": 0, "max": 0, "indeterminate": true]
        #expect(LiveChipPolicy.content(for: [posted(progress: spinning)]) == nil)
        #expect(LiveChipPolicy.content(for: [posted()]) == nil)
        #expect(LiveChipPolicy.content(for: [posted(critical: "   ")]) == nil)
    }

    @Test func onlyPromotedUpdatesCount() {
        #expect(LiveChipPolicy.content(for: [posted(promoted: false, critical: "x"), posted(promoted: nil)]) == nil)
        #expect(LiveChipPolicy.content(for: [posted(promoted: false, critical: "no"), posted(critical: "yes")])
            == .text("yes"))
    }

    @Test func newestPromotedUpdateWins() {
        #expect(LiveChipPolicy.content(for: [posted(critical: "new"), posted(critical: "old")]) == .text("new"))
    }
}

@MainActor
@Suite struct LiveChipTests {
    @Test func showsOnceAndRemovesWhenTheUpdateEnds() {
        let host = RecordingHost()
        let chip = LiveChip(host: host)
        chip.update([posted(critical: "5 min")])
        chip.update([posted(critical: "5 min")])
        chip.update([posted(critical: "4 min")])
        chip.update([])
        chip.update([])
        #expect(host.calls == [.show(.text("5 min")), .show(.text("4 min")), .hide])
    }

    @Test func disabledSettingHidesAndKeepsHidden() {
        let host = RecordingHost()
        let chip = LiveChip(host: host)
        chip.update([posted(critical: "5 min")])
        chip.enabled = false
        chip.update([posted(critical: "4 min")])
        #expect(host.calls == [.show(.text("5 min")), .hide])
        chip.enabled = true
        #expect(host.calls.last == .show(.text("4 min")))
    }

    @Test func neverShownWhenDisabledFromTheStart() {
        let host = RecordingHost()
        let chip = LiveChip(host: host, enabled: false)
        chip.update([posted(critical: "5 min")])
        #expect(host.calls.isEmpty)
    }

    @Test func ringImageIsATemplate() {
        #expect(StatusItemChipHost.ring(0.4).isTemplate)
    }
}
