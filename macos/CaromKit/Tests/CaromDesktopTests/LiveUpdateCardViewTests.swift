import SwiftUI
import Testing

@testable import CaromDesktop

@MainActor
@Suite struct LiveUpdateCardViewTests {
    @Test func laysOutWithoutImages() {
        let live = cardItem([
            "live": ["promoted": true, "short_critical_text": "4 min"],
            "progress": ["value": 40, "max": 100, "indeterminate": false,
                         "segments": [["length": 50], ["length": 50]], "points": [["position": 50]]],
            "when": 1_000, "chronometer": ["count_down": true], "actions": [["index": 0, "title": "Cancel"]],
        ])
        let size = fittingSize(LiveUpdateCardView(item: live, images: NoImages(), status: .idle, perform: { _ in }))
        #expect(size.height > 60)
    }

    @Test func laysOutWithIndeterminateProgressAndAStatusLine() {
        let live = cardItem(["progress": ["value": 0, "max": 0, "indeterminate": true]])
        let size = fittingSize(LiveUpdateCardView(item: live, images: NoImages(), status: .sending, perform: { _ in }))
        #expect(size.height > 40)
    }

    @Test func replyActionsAreNotDrawnAsButtons() {
        let live = cardItem([
            "live": ["promoted": true],
            "actions": [["index": 0, "title": "Reply", "reply": ["label": "Message"]],
                        ["index": 1, "title": "Cancel"], ["index": 2, "title": "Call"],
                        ["index": 3, "title": "Share"], ["index": 4, "title": "More"]],
        ])
        #expect(LiveUpdateCardView.visibleButtons(of: live).map(\.index) == [1, 2, 3])
        let quiet = cardItem(["actions": [["index": 0, "title": "Cancel"]]], acts: false)
        #expect(LiveUpdateCardView.visibleButtons(of: quiet).isEmpty)
    }

    @Test func statusLinesAreTheSpecStrings() {
        #expect(CardStatusText.text(for: .idle) == nil)
        #expect(CardStatusText.text(for: .sending) == "Sending…")
        #expect(CardStatusText.text(for: .sent) == "Sent")
        #expect(CardStatusText.text(for: .asleep) == "Phone is asleep — will send when it wakes")
        #expect(CardStatusText.text(for: .gone) == "Notification is gone")
        #expect(CardStatusText.text(for: .changed) == "App changed its buttons")
        #expect(CardStatusText.text(for: .blocked) == "Phone blocked opening the app")
        #expect(CardStatusText.text(for: .failed) == "Couldn't send")
    }

    @Test func argbColoursKeepTheirAlpha() {
        // 0xFF0000FF as a signed 32-bit int, the way Android sends it.
        #expect(CardColors.color(argb: -16_776_961) == Color(.sRGB, red: 0, green: 0, blue: 1, opacity: 1))
        #expect(CardColors.color(argb: 0x80FF0000) == Color(.sRGB, red: 1, green: 0, blue: 0, opacity: 128.0 / 255))
    }
}
