import CaromProtocol
import Foundation

/// One phone media session as the Mac knows it: the latest `media.state@1`.
public struct MediaSession: Identifiable, Sendable, Equatable {
    /// Sessions are per phone: two phones can use the same session id.
    public var id: String { phoneId + "/" + state.session }
    public var phoneId: String
    public var phoneName: String?
    /// The latest `media.state` msg_id. Commands reply to it (`reply_to`, SPEC §3.5).
    public var msgId: String
    public var state: MediaState
    public var receivedAt: Date

    public init(phoneId: String, phoneName: String?, msgId: String, state: MediaState, receivedAt: Date) {
        self.phoneId = phoneId
        self.phoneName = phoneName
        self.msgId = msgId
        self.state = state
        self.receivedAt = receivedAt
    }

    public var isPlaying: Bool { state.state == "playing" }

    public func offers(_ control: String) -> Bool { state.controls.contains(control) }
}

/// What a Now Playing button asks for.
public enum MediaControl: Sendable, Equatable {
    case play, pause, previous, next
    case seek(positionMs: Int)

    /// The `media.command@1` command string.
    public var command: String {
        switch self {
        case .play: "play"
        case .pause: "pause"
        case .previous: "previous"
        case .next: "next"
        case .seek: "seek"
        }
    }

    public var positionMs: Int? {
        if case .seek(let ms) = self { return ms }
        return nil
    }
}

/// The playback position, extrapolated between `media.state` messages. Pure.
public enum MediaMath {
    /// Where playback is at `nowMs`. The phone reports `positionMs` as of `positionAtMs`; while
    /// playing it has moved on since. A clock that runs behind the phone's is not allowed to move
    /// the position backwards, and the result stays within `0...durationMs`.
    public static func positionMs(
        positionMs: Int?, positionAtMs: Int?, durationMs: Int?, playing: Bool, nowMs: Int
    ) -> Int? {
        guard let positionMs else { return nil }
        var position = positionMs
        if playing, let positionAtMs { position += max(0, nowMs - positionAtMs) }
        if let durationMs, durationMs > 0 { position = min(position, durationMs) }
        return max(0, position)
    }

    public static func positionMs(of session: MediaSession, now: Date) -> Int? {
        positionMs(positionMs: session.state.positionMs, positionAtMs: session.state.positionAt,
                   durationMs: session.state.durationMs, playing: session.isPlaying,
                   nowMs: Int(now.timeIntervalSince1970 * 1000))
    }

    /// Progress 0...1, or nil without a position or a duration.
    public static func fraction(positionMs: Int?, durationMs: Int?) -> Double? {
        guard let positionMs, let durationMs, durationMs > 0 else { return nil }
        return min(1, max(0, Double(positionMs) / Double(durationMs)))
    }

    /// `m:ss` or `h:mm:ss`.
    public static func clock(ms: Int) -> String { LiveMath.clock(seconds: max(0, ms) / 1000) }
}
