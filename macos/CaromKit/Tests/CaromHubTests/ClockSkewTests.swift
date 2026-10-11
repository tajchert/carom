import Foundation
import Testing

@testable import CaromHub

@Suite("ClockSkewTracker", .serialized)
struct ClockSkewTests {
    /// 2026-10-11 22:34:07 GMT.
    static let hubNow = 1_791_758_047_000
    static let header = "Sun, 11 Oct 2026 22:34:07 GMT"

    /// A tracker whose local clock is `offsetMs` away from the Hub's (negative: behind).
    func tracker(localOffsetMs: Int) -> ClockSkewTracker {
        ClockSkewTracker(now: { Self.hubNow + localOffsetMs })
    }

    @Test func parsesRFC1123() {
        #expect(ClockSkewTracker.parseHTTPDate(Self.header) == Self.hubNow)
        #expect(ClockSkewTracker.parseHTTPDate("Thu, 01 Jan 1970 00:00:00 GMT") == 0)
        #expect(ClockSkewTracker.parseHTTPDate("") == nil)
        #expect(ClockSkewTracker.parseHTTPDate("2026-10-11T22:34:07Z") == nil)
        #expect(ClockSkewTracker.parseHTTPDate("Sun, 11 Oct 2026 22:34 GMT") == nil)
    }

    @Test func correctClockIsFine() {
        let t = tracker(localOffsetMs: 1500)
        t.observe(dateHeader: Self.header)
        #expect(t.drift == nil)
    }

    @Test func localClockBehind() {
        let t = tracker(localOffsetMs: -360_000)  // 6 minutes behind
        t.observe(dateHeader: Self.header)
        #expect(t.drift == 360_000)
    }

    @Test func localClockAhead() {
        let t = tracker(localOffsetMs: 3_600_000)  // an hour ahead
        t.observe(dateHeader: Self.header)
        #expect(t.drift == -3_600_000)
    }

    @Test func exactlyFiveMinutesDoesNotWarn() {
        let t = tracker(localOffsetMs: -300_000)
        t.observe(dateHeader: Self.header)
        #expect(t.drift == nil)
    }

    @Test func hysteresisKeepsWarningUntilUnderFourMinutes() {
        let local = LockedBox(Self.hubNow - 400_000)
        let t = ClockSkewTracker(now: { local.value })
        t.observe(dateHeader: Self.header)
        #expect(t.drift == 400_000)
        local.value = Self.hubNow - 270_000  // between 4 and 5 minutes: still warning
        t.observe(dateHeader: Self.header)
        #expect(t.drift == 270_000)
        local.value = Self.hubNow - 240_000  // exactly 4 minutes: not yet cleared
        t.observe(dateHeader: Self.header)
        #expect(t.drift == 240_000)
        local.value = Self.hubNow - 239_000
        t.observe(dateHeader: Self.header)
        #expect(t.drift == nil)
        local.value = Self.hubNow - 270_000  // once clear, 4.5 minutes does not warn again
        t.observe(dateHeader: Self.header)
        #expect(t.drift == nil)
    }

    @Test func unparseableAndMissingDatesAreIgnored() {
        let t = tracker(localOffsetMs: -600_000)
        t.observe(dateHeader: Self.header)
        #expect(t.drift == 600_000)
        t.observe(dateHeader: nil)
        t.observe(dateHeader: "not a date")
        #expect(t.drift == 600_000)
    }

    @Test func hubTimeFromABadRequest() {
        let t = tracker(localOffsetMs: 900_000)
        t.observe(hubTimeMs: Self.hubNow)
        #expect(t.drift == -900_000)
    }

    @Test func callbackFiresOnChangesOnly() {
        let local = LockedBox(Self.hubNow - 600_000)
        let t = ClockSkewTracker(now: { local.value })
        let seen = LockedBox<[Int?]>([])
        t.onChange = { drift in seen.value.append(drift) }
        t.observe(dateHeader: Self.header)
        t.observe(dateHeader: Self.header)  // same drift: no second call
        local.value = Self.hubNow
        t.observe(dateHeader: Self.header)
        t.observe(dateHeader: Self.header)
        #expect(seen.value == [600_000, nil])
    }

    @Test(arguments: ["Pacific/Kiritimati", "America/Los_Angeles", "Asia/Kolkata", "UTC"])
    func timeZoneAndLocaleDoNotChangeTheResult(zone: String) {
        let saved = NSTimeZone.default
        defer { NSTimeZone.default = saved }
        NSTimeZone.default = TimeZone(identifier: zone)!
        #expect(ClockSkewTracker.parseHTTPDate(Self.header) == Self.hubNow)
        let t = tracker(localOffsetMs: 2000)
        t.observe(dateHeader: Self.header)
        #expect(t.drift == nil)
        let late = tracker(localOffsetMs: -420_000)
        late.observe(dateHeader: Self.header)
        #expect(late.drift == 420_000)
    }
}

final class LockedBox<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var stored: T
    init(_ value: T) { stored = value }
    var value: T {
        get { lock.withLock { stored } }
        set { lock.withLock { stored = newValue } }
    }
}
