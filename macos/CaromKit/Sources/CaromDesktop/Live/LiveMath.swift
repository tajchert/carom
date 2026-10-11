import CaromProtocol
import Foundation

/// The numbers behind a Live Update card: the segmented bar and the local chronometer. Pure.
public enum LiveMath {
    public struct SegmentSpec: Sendable, Equatable {
        public var length: Int
        public var color: Int?
        public init(length: Int, color: Int? = nil) {
            self.length = length
            self.color = color
        }
    }

    public struct PointSpec: Sendable, Equatable {
        public var position: Int
        public var color: Int?
        public init(position: Int, color: Int? = nil) {
            self.position = position
            self.color = color
        }
    }

    /// Where everything goes, as fractions of the bar width.
    public struct Bar: Sendable, Equatable {
        public struct Segment: Sendable, Equatable {
            /// Share of the bar width.
            public var width: Double
            /// How much of this segment is filled, 0...1.
            public var fill: Double
            public var color: Int?
        }

        public struct Point: Sendable, Equatable {
            /// Position along the bar, 0...1.
            public var at: Double
            public var color: Int?
        }

        public var segments: [Segment]
        public var points: [Point]
        /// Filled share of the whole bar, 0...1. This is where the tracker icon sits.
        public var fraction: Double
        public var indeterminate: Bool
    }

    /// Lays out the bar. With `segments`, the total is the sum of their lengths (as in Android's
    /// `ProgressStyle`); without, it is `max` and the bar is one segment. `value` is clamped to
    /// the total. Points outside the bar are dropped.
    public static func bar(
        value: Int, max: Int, indeterminate: Bool, segments: [SegmentSpec]?, points: [PointSpec]?
    ) -> Bar {
        let specs = (segments ?? []).filter { $0.length > 0 }
        let total = specs.isEmpty ? Swift.max(0, max) : specs.reduce(0) { $0 + $1.length }
        guard total > 0 else {
            return Bar(segments: [], points: [], fraction: 0, indeterminate: indeterminate)
        }
        let clamped = Swift.min(Swift.max(0, value), total)
        let layout = specs.isEmpty ? [SegmentSpec(length: total)] : specs
        var start = 0
        var out: [Bar.Segment] = []
        for spec in layout {
            let fill = Double(Swift.min(Swift.max(0, clamped - start), spec.length)) / Double(spec.length)
            out.append(.init(width: Double(spec.length) / Double(total), fill: fill, color: spec.color))
            start += spec.length
        }
        let marks = (points ?? []).filter { (0...total).contains($0.position) }
            .map { Bar.Point(at: Double($0.position) / Double(total), color: $0.color) }
        return Bar(segments: out, points: marks, fraction: Double(clamped) / Double(total),
                   indeterminate: indeterminate)
    }

    public static func bar(for progress: NotificationPosted.Progress) -> Bar {
        bar(value: progress.value, max: progress.max, indeterminate: progress.indeterminate,
            segments: progress.segments?.map { SegmentSpec(length: $0.length, color: $0.color) },
            points: progress.points?.map { PointSpec(position: $0.position, color: $0.color) })
    }

    /// Whole seconds a chronometer shows at `nowMs`: elapsed since `whenMs`, or left until it.
    /// Never negative.
    public static func chronometerSeconds(whenMs: Int, countDown: Bool, nowMs: Int) -> Int {
        let delta = countDown ? whenMs - nowMs : nowMs - whenMs
        return Swift.max(0, delta / 1000)
    }

    /// `m:ss`, or `h:mm:ss` from one hour.
    public static func clock(seconds: Int) -> String {
        let s = Swift.max(0, seconds)
        let h = s / 3600, m = s % 3600 / 60, sec = s % 60
        if h > 0 { return "\(h):" + twoDigits(m) + ":" + twoDigits(sec) }
        return "\(m):" + twoDigits(sec)
    }

    public static func chronometerText(whenMs: Int, countDown: Bool, now: Date) -> String {
        clock(seconds: chronometerSeconds(whenMs: whenMs, countDown: countDown, nowMs: milliseconds(now)))
    }

    /// The chronometer to show, or nil. It needs `when`, and `show_when: false` hides it.
    public static func chronometer(of posted: NotificationPosted) -> (whenMs: Int, countDown: Bool)? {
        guard let chronometer = posted.chronometer, let when = posted.when, posted.showWhen != false else {
            return nil
        }
        return (when, chronometer.countDown)
    }

    static func milliseconds(_ date: Date) -> Int { Int(date.timeIntervalSince1970 * 1000) }

    private static func twoDigits(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
}
