import Foundation

/// A WebSocket frame as seen by `HubSocket`.
public enum WebSocketMessage: Sendable, Equatable {
    case text(String)
    case data(Data)
}

/// One WebSocket connection. Abstracted so `HubSocket` can be tested without a network.
public protocol WebSocketConnection: AnyObject, Sendable {
    /// Waits for the next frame; throws when the connection fails or closes.
    func receive() async throws -> WebSocketMessage
    func send(_ text: String) async throws
    /// Sends a protocol ping and returns when the pong arrives.
    func sendPing() async throws
    /// Sends a close frame and, without waiting for the server's half of the handshake,
    /// cancels the connection after at most `grace` (SPEC §7).
    func close(grace: Duration)
    /// The close code received from the server, when known (4001 means revoked).
    var closeCode: Int? { get }
}

/// Opens connections.
public protocol WebSocketConnector: Sendable {
    func connect(url: URL, token: String) -> any WebSocketConnection
}

/// `URLSessionWebSocketTask` implementation.
public struct URLSessionWebSocketConnector: WebSocketConnector {
    public let session: URLSession

    public init(session: URLSession = .shared) { self.session = session }

    public func connect(url: URL, token: String) -> any WebSocketConnection {
        var req = URLRequest(url: url)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let task = session.webSocketTask(with: req)
        task.maximumMessageSize = 2 * 1024 * 1024
        task.resume()
        return URLSessionWebSocketConnection(task: task)
    }
}

public final class URLSessionWebSocketConnection: WebSocketConnection {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) { self.task = task }

    public func receive() async throws -> WebSocketMessage {
        switch try await task.receive() {
        case .string(let s): return .text(s)
        case .data(let d): return .data(d)
        @unknown default: return .data(Data())
        }
    }

    public func send(_ text: String) async throws { try await task.send(.string(text)) }

    public func sendPing() async throws {
        let task = self.task
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, any Error>) in
            task.sendPing { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            }
        }
    }

    public func close(grace: Duration) {
        let task = self.task
        task.cancel(with: .normalClosure, reason: nil)
        // Never wait for the server's close reply: hard-cancel after the grace period.
        Task.detached {
            try? await Task.sleep(for: grace)
            task.cancel()
        }
    }

    public var closeCode: Int? {
        let code = task.closeCode
        return code == .invalid ? nil : code.rawValue
    }
}
