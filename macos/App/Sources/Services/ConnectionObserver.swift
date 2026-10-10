import CaromHub
import Foundation
import os

/// What the socket transport reports about the newest connection.
enum TransportEvent: Sendable, Equatable {
    case opening(id: Int)
    case up(id: Int)
    case down(id: Int)
}

/// Wraps the URLSession WebSocket connector to learn when a connection is really up.
///
/// `HubSocket.events` is consumed by the engine, and its `.connected` event fires before the
/// handshake. This wrapper reports `up` after the first frame or pong, and `down` when the
/// connection fails or closes. Events carry a connection id so a late failure of an old
/// connection is ignored.
final class ObservedConnector: WebSocketConnector {
    private let inner: any WebSocketConnector
    private let report: @Sendable (TransportEvent) -> Void
    private let counter = OSAllocatedUnfairLock(initialState: 0)

    init(inner: any WebSocketConnector = URLSessionWebSocketConnector(),
         report: @escaping @Sendable (TransportEvent) -> Void) {
        self.inner = inner
        self.report = report
    }

    func connect(url: URL, token: String) -> any WebSocketConnection {
        let id = counter.withLock { value in
            value += 1
            return value
        }
        report(.opening(id: id))
        let connection = ObservedConnection(id: id, inner: inner.connect(url: url, token: token), report: report)
        connection.probe()
        return connection
    }
}

final class ObservedConnection: WebSocketConnection {
    private let id: Int
    private let inner: any WebSocketConnection
    private let report: @Sendable (TransportEvent) -> Void

    init(id: Int, inner: any WebSocketConnection, report: @escaping @Sendable (TransportEvent) -> Void) {
        self.id = id
        self.inner = inner
        self.report = report
    }

    /// One protocol ping right away, so an idle Hub (no backlog) still shows as connected.
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
