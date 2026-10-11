import Testing

@testable import CaromDesktop

@MainActor
private final class FakePlayer: RingPlaying {
    var starts = 0
    var stops = 0
    func start() { starts += 1 }
    func stop() { stops += 1 }
}

@MainActor
@Suite struct CallRingerTests {
    /// A short fixed wait, only for checks that nothing happens: either no stop is scheduled at
    /// all, or the stop delay is 10 s, so no scheduling lag can make the check pass by accident.
    private func wait() async { try? await Task.sleep(for: .milliseconds(60)) }

    /// Polls until `player.stops` reaches `count`, for up to two seconds.
    private func waitForStops(_ player: FakePlayer, _ count: Int) async {
        let deadline = ContinuousClock.now + .seconds(2)
        while player.stops < count, ContinuousClock.now < deadline {
            try? await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test func ringsOnceForRepeatedStarts() {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.start("a")
        ringer.start("a")
        #expect(player.starts == 1)
    }

    @Test func stopsAfterTheDelay() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.start("a")
        ringer.stop("a")
        #expect(player.stops == 0, "not at once")
        await waitForStops(player, 1)
        #expect(player.stops == 1)
        withExtendedLifetime(ringer) {}  // the stop task holds it weakly
    }

    @Test func aRebuiltCardKeepsTheRingGoing() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .seconds(10))
        ringer.start("a")
        ringer.stop("a")
        ringer.start("a")
        await wait()
        #expect(player.starts == 1 && player.stops == 0)
    }

    @Test func ringsUntilTheLastCallEnds() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.start("a")
        ringer.start("b")
        ringer.stop("a")
        await wait()
        #expect(player.stops == 0, "b still rings")
        ringer.stop("b")
        await waitForStops(player, 1)
        #expect(player.stops == 1)
        withExtendedLifetime(ringer) {}
    }

    @Test func canRingAgainLater() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.start("a")
        ringer.stop("a")
        await waitForStops(player, 1)
        ringer.start("b")
        #expect(player.starts == 2)
    }

    @Test func stoppingAnUnknownCallIsHarmless() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.stop("zzz")
        await wait()
        #expect(player.stops == 0)
    }
}
