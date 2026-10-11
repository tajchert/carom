import Foundation
import Testing

@testable import CaromDesktop

@Suite struct CardStyleTests {
    /// Plan Review Focus 5: Android sends `Notification.color` as a signed 32-bit int.
    @Test func negativeARGBDecodes() {
        let signed = ARGB(-14_575_885)   // 0xFF2196F3 as a Java int
        let unsigned = ARGB(0xFF2196F3)
        #expect(signed == unsigned)
        #expect(signed.alpha == 1)
        #expect(abs(signed.red - 0x21 / 255.0) < 0.0001)
        #expect(abs(signed.green - 0x96 / 255.0) < 0.0001)
        #expect(abs(signed.blue - 0xF3 / 255.0) < 0.0001)
    }

    @Test func zeroAlphaIsNoColor() {
        let s = CardStyle.make(color: 0, colorized: true)
        #expect(s.accent == nil && s.fill == nil && !s.darkText)
        #expect(CardStyle.make(color: nil, colorized: true).fill == nil)
    }

    @Test func colorizedFillsAndPicksContrast() {
        let red = CardStyle.make(color: 0xFFD32F2F, colorized: true)
        #expect(red.fill == ARGB(0xFFD32F2F))
        #expect(!red.darkText, "white text on dark red")
        let yellow = CardStyle.make(color: 0xFFFFEB3B, colorized: true)
        #expect(yellow.darkText, "black text on yellow")
        let accentOnly = CardStyle.make(color: 0xFFD32F2F, colorized: false)
        #expect(accentOnly.accent == ARGB(0xFFD32F2F) && accentOnly.fill == nil)
    }

    @Test func luminanceExtremes() {
        #expect(ARGB(0xFF000000).luminance == 0)
        #expect(abs(ARGB(0xFFFFFFFF).luminance - 1) < 0.0001)
    }

    @Test func shortTimes() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(CardTime.short(now, now: now) == "now")
        #expect(CardTime.short(now.addingTimeInterval(30), now: now) == "now", "a future when reads now")
        #expect(CardTime.short(now.addingTimeInterval(-59), now: now) == "now")
        #expect(CardTime.short(now.addingTimeInterval(-300), now: now) == "5m")
        #expect(CardTime.short(now.addingTimeInterval(-7_200), now: now) == "2h")
        let old = now.addingTimeInterval(-3 * 86_400)
        #expect(CardTime.short(old, now: now) == old.formatted(.dateTime.month(.abbreviated).day()))
    }
}
