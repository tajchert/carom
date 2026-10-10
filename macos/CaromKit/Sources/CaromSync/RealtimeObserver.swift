import CaromHub
import Foundation
import os

/// Publishes `RealtimeState` to any number of observers. Thread-safe; used from the engine actor
/// and from WebSocket callbacks.
final class RealtimeStateBroadcaster: Sendable {
    private struct Inner {
        var state: RealtimeState = .offline
        /// Reports are ignored while stopped, so a late callback of a closed socket cannot
        /// move the state away from `.offline`.
        var active = false
        var latestConnection = 0
        var nextObserver = 0
        var observers: [Int: AsyncStream<RealtimeState>.Continuation] = [:]
    }

    private let inner = OSAllocatedUnfairLock(initialState: Inner())

    var current: RealtimeState { inner.withLock { $0.state } }

    /// A stream that yields the current state first, then every change.
    func stream() -> AsyncStream<RealtimeState> {
        let (stream, continuation) = AsyncStream.makeStream(of: RealtimeState.self, bufferingPolicy: .bufferingNewest(8))
        let id = inner.withLock { s -> Int in
            s.nextObserver += 1
            s.observers[s.nextObserver] = continuation
            continuation.yield(s.state)
            return s.nextObserver
        }
        continuation.onTermination = { [weak self] _ in
            _ = self?.inner.withLock { $0.observers.removeValue(forKey: id) }
        }
        return stream
    }

    func started() {
        inner.withLock { $0.active = true }
        set(.connecting)
    }

    func stopped() {
        inner.withLock { $0.active = false }
        set(.offline)
    }

    func report(_ event: LivenessConnector.Event) {
        let next: RealtimeState? = inner.withLock { s in
            guard s.active else { return nil }
            switch event {
            case .opening(let id):
                s.latestConnection = id
                return .connecting
            case .up(let id):
                return id == s.latestConnection ? .connected : nil
            case .down(let id):
                return id == s.latestConnection ? .connecting : nil
            }
        }
        if let next { set(next) }
    }

    private func set(_ state: RealtimeState) {
        let observers: [AsyncStream<RealtimeState>.Continuation] = inner.withLock { s in
            guard s.state != state else { return [] }
            s.state = state
            return Array(s.observers.values)
        }
        for o in observers { o.yield(state) }
    }
}

/// Wraps a WebSocket connector to learn when a connection is really up.
///
/// `HubSocket` emits `.connected` as soon as it starts connecting, before the handshake. This
/// wrapper reports `up` after the first frame or pong of a connection (it sends one protocol ping
/// right away, so an idle Hub with no backlog still shows as connected), and `down` when the
/// connection fails or is closed. Events carry a connection id so a late failure of an old
/// connection is ignored.
struct LivenessConnector: WebSocketConnector {
    enum Event: Sendable, Equatable {
        case opening(id: Int)
        case up(id: Int)
        case down(id: Int)
    }

    private let inner: any WebSocketConnector
    private let report: @Sendable (Event) -> Void
    private let counter = OSAllocatedUnfairLock(initialState: 0)

    init(inner: any WebSocketConnector, report: @escaping @Sendable (Event) -> Void) {
        self.inner = inner
        self.report = report
    }

    func connect(url: URL, token: String) -> any WebSocketConnection {
        let id = counter.withLock { value in
            value += 1
            return value
        }
        report(.opening(id: id))
        let connection = LivenessConnection(id: id, inner: inner.connect(url: url, token: token), report: report)
        connection.probe()
        return connection
    }
}

final class LivenessConnection: WebSocketConnection {
    private let id: Int
    private let inner: any WebSocketConnection
    private let report: @Sendable (LivenessConnector.Event) -> Void

    init(id: Int, inner: any WebSocketConnection, report: @escaping @Sendable (LivenessConnector.Event) -> Void) {
        self.id = id
        self.inner = inner
        self.report = report
    }

    func probe() {
        let inner = self.inner, report = self.report, id = self.id
        Task {
            do {
                try await inner.sendPing()
                report(.up(id: id))
            } catch {}
        }
    }

    func receive() async throws -> WebSocketMessage {
        do {
            let message = try await inner.receive()
            report(.up(id: id))
            return message
        } catch {
            report(.down(id: id))
            throw error
        }
    }

    func send(_ text: String) async throws { try await inner.send(text) }

    func sendPing() async throws {
        try await inner.sendPing()
        report(.up(id: id))
    }

    func close(grace: Duration) {
        report(.down(id: id))
        inner.close(grace: grace)
    }

    var closeCode: Int? { inner.closeCode }
}
