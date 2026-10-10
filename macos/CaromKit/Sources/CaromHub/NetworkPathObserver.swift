import Foundation
import Network

/// Wraps `NWPathMonitor` as an `AsyncStream` of "is the network usable" values, deduplicated.
/// Wire it to `HubSocket.networkPathChanged(isSatisfied:)`:
///
///     let observer = NetworkPathObserver()
///     Task { for await up in observer.updates { await socket.networkPathChanged(isSatisfied: up) } }
public final class NetworkPathObserver: Sendable {
    public let updates: AsyncStream<Bool>
    private let monitor: NWPathMonitor

    public init(queue: DispatchQueue = DispatchQueue(label: "app.carom.network-path")) {
        let monitor = NWPathMonitor()
        self.monitor = monitor
        let (stream, continuation) = AsyncStream.makeStream(of: Bool.self, bufferingPolicy: .bufferingNewest(1))
        updates = stream
        let last = LastValue()
        monitor.pathUpdateHandler = { path in
            let up = path.status == .satisfied
            if last.swap(up) != up { continuation.yield(up) }
        }
        continuation.onTermination = { _ in monitor.cancel() }
        monitor.start(queue: queue)
    }

    deinit { monitor.cancel() }
}

private final class LastValue: @unchecked Sendable {
    private var value: Bool?
    private let lock = NSLock()
    /// Stores `new` and returns the previous value.
    func swap(_ new: Bool) -> Bool? {
        lock.lock()
        defer { lock.unlock() }
        let old = value
        value = new
        return old
    }
}
