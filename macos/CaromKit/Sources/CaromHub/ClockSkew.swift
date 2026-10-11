import Foundation
import os

/// Watches how far this Mac's clock is from the Hub's (decisions: clock-drift warning).
///
/// Everything is Unix epoch milliseconds: the Hub's time comes from the `Date` header of any
/// HTTP answer or `hub_time` of a `clock_skew` 400, the local time is read when the answer
/// arrives. No `Calendar` or time zone is involved, so a correct clock has a skew near 0 in
/// every time zone.
///
/// Drift is reported when |skew| exceeds `warnAfterMs` and cleared only when |skew| falls below
/// `clearBelowMs` (hysteresis, so it does not flicker).
public final class ClockSkewTracker: @unchecked Sendable {
    public static let warnAfterMs = 300_000
    public static let clearBelowMs = 240_000

    private struct State {
        var drift: Int?
        var onChange: (@Sendable (Int?) -> Void)?
    }

    private let now: @Sendable () -> Int
    private let state = OSAllocatedUnfairLock(initialState: State())

    /// `now` returns the local Unix time in milliseconds.
    public init(now: @escaping @Sendable () -> Int = { Int((Date().timeIntervalSince1970 * 1000).rounded()) }) {
        self.now = now
    }

    /// Hub time minus local time in ms while the drift is being reported; nil when the clock is
    /// fine. A negative value means this Mac's clock is ahead.
    public var drift: Int? { state.withLock { $0.drift } }

    /// Called (on whatever thread observed the answer) when `drift` changes from nil to a value,
    /// from a value to nil, or to a different value.
    public var onChange: (@Sendable (Int?) -> Void)? {
        get { state.withLock { $0.onChange } }
        set { state.withLock { $0.onChange = newValue } }
    }

    /// Feeds the `Date` header value of an HTTP answer. Ignored when it does not parse.
    public func observe(dateHeader: String?) {
        let received = now()
        guard let dateHeader, let hubMs = Self.parseHTTPDate(dateHeader) else { return }
        record(hubMs: hubMs, localMs: received)
    }

    /// Feeds `hub_time` (epoch ms) of a `clock_skew` 400.
    public func observe(hubTimeMs: Int) {
        record(hubMs: hubTimeMs, localMs: now())
    }

    private func record(hubMs: Int, localMs: Int) {
        let skew = hubMs - localMs
        let (changed, value, callback) = state.withLock { s -> (Bool, Int?, (@Sendable (Int?) -> Void)?) in
            let new: Int?
            if abs(skew) > Self.warnAfterMs {
                new = skew
            } else if s.drift != nil, abs(skew) >= Self.clearBelowMs {
                new = skew  // still inside the hysteresis band: keep warning
            } else {
                new = nil
            }
            // The Date header has one-second resolution; ignore wobble of under a second.
            let changed: Bool
            switch (s.drift, new) {
            case (nil, nil): changed = false
            case (let a?, let b?): changed = abs(a - b) >= 1000
            default: changed = true
            }
            if changed { s.drift = new }
            return (changed, new, s.onChange)
        }
        if changed { callback?(value) }
    }

    // MARK: RFC 1123 (IMF-fixdate) parsing

    /// `Sun, 11 Oct 2026 22:34:07 GMT` to Unix epoch milliseconds; nil when malformed.
    public static func parseHTTPDate(_ s: String) -> Int? {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "GMT")
        f.dateFormat = "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
        guard let date = f.date(from: s.trimmingCharacters(in: .whitespaces)) else { return nil }
        return Int((date.timeIntervalSince1970 * 1000).rounded())
    }
}
