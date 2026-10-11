import CaromProtocol
import Foundation
import Testing

@testable import CaromDesktop

private func state(_ extra: JSONObject = [:]) -> MediaState {
    var body: JSONObject = ["session": "s1", "app": ["id": "com.music", "name": "Music"], "state": "playing",
                            "controls": ["play", "pause", "next"]]
    for (k, v) in extra { body[k] = v }
    return MediaState(json: body)!
}

@Suite struct MediaMathTests {
    @Test func playingMovesOnFromThePositionTimestamp() {
        let p = MediaMath.positionMs(positionMs: 10_000, positionAtMs: 1_000_000, durationMs: 200_000,
                                     playing: true, nowMs: 1_004_500)
        #expect(p == 14_500)
    }

    @Test func pausedStaysPut() {
        let p = MediaMath.positionMs(positionMs: 10_000, positionAtMs: 1_000_000, durationMs: 200_000,
                                     playing: false, nowMs: 1_900_000)
        #expect(p == 10_000)
    }

    @Test func clockBehindThePhoneNeverGoesBackwards() {
        let p = MediaMath.positionMs(positionMs: 10_000, positionAtMs: 1_000_000, durationMs: nil,
                                     playing: true, nowMs: 990_000)
        #expect(p == 10_000)
    }

    @Test func stopsAtTheDuration() {
        let p = MediaMath.positionMs(positionMs: 199_000, positionAtMs: 0, durationMs: 200_000, playing: true,
                                     nowMs: 60_000)
        #expect(p == 200_000)
    }

    @Test func unknownPositionStaysUnknown() {
        #expect(MediaMath.positionMs(positionMs: nil, positionAtMs: 5, durationMs: 9, playing: true, nowMs: 10) == nil)
    }

    @Test func playingWithoutTimestampUsesThePosition() {
        #expect(MediaMath.positionMs(positionMs: 4000, positionAtMs: nil, durationMs: nil, playing: true,
                                     nowMs: 99) == 4000)
    }

    @Test func fractionNeedsADuration() {
        #expect(MediaMath.fraction(positionMs: 50, durationMs: 200) == 0.25)
        #expect(MediaMath.fraction(positionMs: 500, durationMs: 200) == 1)
        #expect(MediaMath.fraction(positionMs: 50, durationMs: nil) == nil)
        #expect(MediaMath.fraction(positionMs: 50, durationMs: 0) == nil)
        #expect(MediaMath.fraction(positionMs: nil, durationMs: 200) == nil)
    }

    @Test func clockText() {
        #expect(MediaMath.clock(ms: 65_999) == "1:05")
        #expect(MediaMath.clock(ms: -5) == "0:00")
    }

    @Test func sessionExtrapolatesFromItsState() {
        let s = MediaSession(phoneId: "p", phoneName: nil, msgId: "m1",
                             state: state(["position_ms": 1000, "position_at": 1_800_000_000_000, "duration_ms": 60_000]),
                             receivedAt: Date())
        #expect(MediaMath.positionMs(of: s, now: Date(timeIntervalSince1970: 1_800_000_003)) == 4000)
    }
}

@Suite struct MediaSessionTests {
    @Test func idIncludesThePhone() {
        let a = MediaSession(phoneId: "pA", phoneName: nil, msgId: "m", state: state(), receivedAt: Date())
        let b = MediaSession(phoneId: "pB", phoneName: nil, msgId: "m", state: state(), receivedAt: Date())
        #expect(a.id == "pA/s1" && a.id != b.id)
    }

    @Test func controlsAndCommands() {
        let s = MediaSession(phoneId: "p", phoneName: nil, msgId: "m", state: state(), receivedAt: Date())
        #expect(s.isPlaying)
        #expect(s.offers("next") && !s.offers("seek"))
        #expect(MediaControl.pause.command == "pause" && MediaControl.pause.positionMs == nil)
        #expect(MediaControl.seek(positionMs: 3000).command == "seek")
        #expect(MediaControl.seek(positionMs: 3000).positionMs == 3000)
    }
}
