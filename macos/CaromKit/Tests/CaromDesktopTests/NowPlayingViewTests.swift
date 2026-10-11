import CaromProtocol
import SwiftUI
import Testing

@testable import CaromDesktop

@MainActor
@Suite struct NowPlayingViewTests {
    private func session(_ extra: JSONObject = [:]) -> MediaSession {
        var body: JSONObject = ["session": "s", "app": ["id": "m", "name": "Music"], "state": "paused",
                                "title": "Song", "controls": []]
        for (k, v) in extra { body[k] = v }
        return MediaSession(phoneId: "p", phoneName: "Pixel", msgId: "m", state: MediaState(json: body)!,
                            receivedAt: Date())
    }

    @Test func laysOutWithoutArtOrControlsAndShowsTheHint() {
        let s = session(["duration_ms": 1000, "position_ms": 10])
        let size = fittingSize(NowPlayingView(session: s, images: NoImages(), canControl: false, send: { _ in }))
        #expect(size.height > 50)
    }

    @Test func laysOutWithAllControlsAndASeekBar() {
        let s = session(["state": "playing", "duration_ms": 200_000, "position_ms": 10_000,
                         "position_at": 1_800_000_000_000, "controls": ["play", "pause", "previous", "next", "seek"]])
        let size = fittingSize(NowPlayingView(session: s, images: NoImages(), canControl: true, send: { _ in }))
        #expect(size.height > 80)
    }

    @Test func laysOutWithoutAPosition() {
        let size = fittingSize(NowPlayingView(session: session(), images: NoImages(), canControl: true, send: { _ in }))
        #expect(size.height > 40)
    }
}
