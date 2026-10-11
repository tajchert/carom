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
    private func wait() async { try? await Task.sleep(for: .milliseconds(60)) }

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
        await wait()
        #expect(player.stops == 1)
    }

    @Test func aRebuiltCardKeepsTheRingGoing() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(30))
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
        #expect(player.stops == 0)
        ringer.stop("b")
        await wait()
        #expect(player.stops == 1)
    }

    @Test func canRingAgainLater() async {
        let player = FakePlayer()
        let ringer = CallRinger(player: player, stopDelay: .milliseconds(10))
        ringer.start("a")
        ringer.stop("a")
        await wait()
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
