import AppKit

/// Plays and stops the ring sound.
@MainActor
public protocol RingPlaying: AnyObject {
    func start()
    func stop()
}

/// A system sound on a loop.
@MainActor
public final class SystemRingPlayer: RingPlaying {
    private var sound: NSSound?

    public init() {}

    public func start() {
        guard sound == nil, let sound = NSSound(named: NSSound.Name("Hero")) else { return }
        sound.loops = true
        sound.play()
        self.sound = sound
    }

    public func stop() {
        sound?.stop()
        sound = nil
    }
}

/// Rings while at least one incoming-call card wants it. A card asks on appearing and releases on
/// disappearing (it was removed by the phone, or the user answered or declined). A short delay
/// before stopping keeps the ring going when SwiftUI rebuilds a card, which disappears and
/// appears again in the same moment.
@MainActor
public final class CallRinger {
    public static let shared = CallRinger(player: SystemRingPlayer())

    private let player: any RingPlaying
    private let stopDelay: Duration
    private var wanted: Set<String> = []
    private var playing = false
    private var stopTask: Task<Void, Never>?

    public init(player: any RingPlaying, stopDelay: Duration = .milliseconds(250)) {
        self.player = player
        self.stopDelay = stopDelay
    }

    public func start(_ id: String) {
        wanted.insert(id)
        stopTask?.cancel()
        stopTask = nil
        if !playing {
            playing = true
            player.start()
        }
    }

    public func stop(_ id: String) {
        wanted.remove(id)
        guard wanted.isEmpty, playing else { return }
        stopTask?.cancel()
        let delay = stopDelay
        stopTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self, self.wanted.isEmpty else { return }
            self.playing = false
            self.stopTask = nil
            self.player.stop()
        }
    }
}
