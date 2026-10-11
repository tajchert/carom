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

    @Test func drawsOnlyTheListedButtons() {
        let s = session(["controls": ["play", "next"]])
        let layout = NowPlayingLayout(session: s, canControl: true)
        #expect(layout.buttons.map(\.control) == [.play, .next])
        #expect(layout.buttons.first?.large == true)
    }

    @Test func showsPauseWhilePlayingAndHidesItWhenNotListed() {
        let playing = session(["state": "playing", "controls": ["pause", "previous"]])
        #expect(NowPlayingLayout(session: playing, canControl: true).buttons.map(\.control) == [.previous, .pause])
        let noPause = session(["state": "playing", "controls": ["play"]])
        #expect(NowPlayingLayout(session: noPause, canControl: true).buttons.isEmpty)
    }

    @Test func disablesButtonsAndShowsTheHintWithoutControl() {
        let s = session(["controls": ["play", "seek"], "duration_ms": 1000, "position_ms": 10])
        let off = NowPlayingLayout(session: s, canControl: false)
        #expect(off.buttonsEnabled == false)
        #expect(off.showsHint)
        #expect(off.usesSeekSlider == false)
        let on = NowPlayingLayout(session: s, canControl: true)
        #expect(on.buttonsEnabled)
        #expect(on.showsHint == false)
    }

    @Test func usesTheSliderOnlyWhenSeekIsListedWithADuration() {
        let withSeek = session(["controls": ["seek"], "duration_ms": 1000, "position_ms": 10])
        #expect(NowPlayingLayout(session: withSeek, canControl: true).usesSeekSlider)
        let noSeek = session(["controls": ["play"], "duration_ms": 1000, "position_ms": 10])
        #expect(NowPlayingLayout(session: noSeek, canControl: true).usesSeekSlider == false)
        let noDuration = session(["controls": ["seek"]])
        let l = NowPlayingLayout(session: noDuration, canControl: true)
        #expect(l.usesSeekSlider == false)
        #expect(l.showsProgress == false)
    }

    @Test func seekingSendsTheReleasedPosition() {
        #expect(NowPlayingLayout.seekCommand(to: 42_500.9) == .seek(positionMs: 42_500))
    }
}
