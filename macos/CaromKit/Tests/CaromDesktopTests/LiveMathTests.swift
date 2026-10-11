import CaromProtocol
import Foundation
import Testing

@testable import CaromDesktop

@Suite struct LiveMathTests {
    // MARK: Bar

    @Test func plainProgressIsOneSegment() {
        let bar = LiveMath.bar(value: 25, max: 100, indeterminate: false, segments: nil, points: nil)
        #expect(bar.segments == [.init(width: 1, fill: 0.25, color: nil)])
        #expect(bar.fraction == 0.25)
        #expect(!bar.indeterminate)
    }

    @Test func segmentsFillInOrder() {
        let segments = [LiveMath.SegmentSpec(length: 40, color: 1), .init(length: 60, color: 2)]
        let bar = LiveMath.bar(value: 70, max: 100, indeterminate: false, segments: segments, points: nil)
        #expect(bar.segments.map(\.width) == [0.4, 0.6])
        #expect(bar.segments[0].fill == 1)
        #expect(abs(bar.segments[1].fill - 0.5) < 1e-9)
        #expect(bar.segments.map(\.color) == [1, 2])
        #expect(bar.fraction == 0.7)
    }

    @Test func totalIsTheSumOfSegmentLengthsNotMax() {
        let bar = LiveMath.bar(value: 5, max: 999, indeterminate: false,
                               segments: [.init(length: 5), .init(length: 5)], points: nil)
        #expect(bar.fraction == 0.5)
        #expect(bar.segments.map(\.fill) == [1, 0])
    }

    @Test func valueIsClamped() {
        let over = LiveMath.bar(value: 500, max: 100, indeterminate: false, segments: nil, points: nil)
        let under = LiveMath.bar(value: -5, max: 100, indeterminate: false, segments: nil, points: nil)
        #expect(over.fraction == 1 && over.segments[0].fill == 1)
        #expect(under.fraction == 0 && under.segments[0].fill == 0)
    }

    @Test func pointsSitOnTheBarAndOutsideOnesAreDropped() {
        let bar = LiveMath.bar(value: 0, max: 200, indeterminate: false, segments: nil,
                               points: [.init(position: 50, color: 7), .init(position: 200), .init(position: 201),
                                        .init(position: -1)])
        #expect(bar.points == [.init(at: 0.25, color: 7), .init(at: 1, color: nil)])
    }

    @Test func emptyOrZeroTotalGivesAnEmptyBar() {
        let zero = LiveMath.bar(value: 3, max: 0, indeterminate: true, segments: nil, points: [.init(position: 1)])
        #expect(zero.segments.isEmpty && zero.points.isEmpty && zero.fraction == 0 && zero.indeterminate)
        let junk = LiveMath.bar(value: 3, max: 0, indeterminate: false, segments: [.init(length: 0), .init(length: -4)],
                                points: nil)
        #expect(junk.segments.isEmpty)
    }

    // MARK: Chronometer

    @Test func countUpIsElapsedTime() {
        #expect(LiveMath.chronometerSeconds(whenMs: 1_000_000, countDown: false, nowMs: 1_065_900) == 65)
    }

    @Test func countDownIsTimeLeft() {
        #expect(LiveMath.chronometerSeconds(whenMs: 1_090_000, countDown: true, nowMs: 1_000_500) == 89)
    }

    @Test func neverNegative() {
        #expect(LiveMath.chronometerSeconds(whenMs: 2_000_000, countDown: false, nowMs: 1_000_000) == 0)
        #expect(LiveMath.chronometerSeconds(whenMs: 1_000_000, countDown: true, nowMs: 2_000_000) == 0)
    }

    @Test(arguments: [(0, "0:00"), (9, "0:09"), (65, "1:05"), (599, "9:59"), (3600, "1:00:00"), (3725, "1:02:05"),
                      (-3, "0:00")] as [(Int, String)])
    func clockFormat(seconds: Int, expected: String) {
        #expect(LiveMath.clock(seconds: seconds) == expected)
    }

    @Test func textFromADate() {
        let now = Date(timeIntervalSince1970: 1_800_000_090)
        #expect(LiveMath.chronometerText(whenMs: 1_800_000_000_000, countDown: false, now: now) == "1:30")
    }

    // MARK: From a notification

    private func posted(_ extra: JSONObject) -> NotificationPosted {
        var body: JSONObject = ["key": "k", "app": ["id": "a", "name": "A"], "posted_at": 1, "redacted": false]
        for (k, v) in extra { body[k] = v }
        return NotificationPosted(json: body)!
    }

    @Test func barFromProgressFields() throws {
        let n = posted(["progress": ["value": 30, "max": 100, "indeterminate": false,
                                     "segments": [["length": 50, "color": 1], ["length": 50]],
                                     "points": [["position": 50]]]])
        let bar = LiveMath.bar(for: try #require(n.progress))
        #expect(bar.segments.count == 2 && bar.points == [.init(at: 0.5, color: nil)])
        #expect(bar.fraction == 0.3)
    }

    @Test func chronometerNeedsWhenAndHonoursShowWhen() {
        let timer = posted(["when": 5000, "chronometer": ["count_down": true]])
        #expect(LiveMath.chronometer(of: timer)?.whenMs == 5000)
        #expect(LiveMath.chronometer(of: timer)?.countDown == true)
        #expect(LiveMath.chronometer(of: posted(["chronometer": ["count_down": false]])) == nil)
        #expect(LiveMath.chronometer(of: posted(["when": 5000])) == nil)
        #expect(LiveMath.chronometer(of: posted(["when": 5000, "show_when": false,
                                                 "chronometer": ["count_down": false]])) == nil)
    }
}
