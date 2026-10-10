import AppKit
import CaromHub

/// Forwards sleep, wake and network changes (SPEC §7 connection policy for the Mac).
@MainActor
final class SystemMonitor {
    private var observers: [NSObjectProtocol] = []
    private var pathTask: Task<Void, Never>?
    private let pathObserver = NetworkPathObserver()

    init(onSleep: @escaping @MainActor () -> Void, onWake: @escaping @MainActor () -> Void,
         onNetwork: @escaping @MainActor (Bool) -> Void) {
        let center = NSWorkspace.shared.notificationCenter
        observers.append(center.addObserver(forName: NSWorkspace.willSleepNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { onSleep() }
        })
        observers.append(center.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { onWake() }
        })
        let updates = pathObserver.updates
        pathTask = Task { @MainActor in
            for await up in updates { onNetwork(up) }
        }
    }

    func stop() {
        let center = NSWorkspace.shared.notificationCenter
        for o in observers { center.removeObserver(o) }
        observers = []
        pathTask?.cancel()
        pathTask = nil
    }
}
