import CaromProtocol
import Foundation

/// Server → client frames (SPEC §7).
public enum ServerFrame: Sendable, Equatable {
    case msg(seq: Int, envelope: JSON)
    case dir
    case pair(pairId: String, candidate: JSON)
    case revoked
    /// Reply to the text `ping`.
    case pong
    /// A frame this client does not understand (ignored).
    case unknown

    public static func parse(_ text: String) -> ServerFrame {
        if text == "pong" { return .pong }
        guard let j = try? JSON.parse(text), let t = j["t"]?.stringValue else { return .unknown }
        switch t {
        case "msg":
            guard let seq = j["seq"]?.safeInt, let env = j["envelope"] else { return .unknown }
            return .msg(seq: seq, envelope: env)
        case "dir": return .dir
        case "pair":
            guard let id = j["pair_id"]?.stringValue, let c = j["candidate"] else { return .unknown }
            return .pair(pairId: id, candidate: c)
        case "revoked": return .revoked
        default: return .unknown
        }
    }
}

/// The always-on Hub WebSocket for the Mac (docs/CLIENTS.md, connection policy):
///
/// - protocol ping every 30 s;
/// - a watchdog drops the socket after 75 s without any frame or pong;
/// - reconnect with jittered exponential backoff, 1 s doubling to 60 s;
/// - disconnect on sleep, reconnect on wake or when the network comes back;
/// - never wait for the server's half of a close handshake (SPEC §7).
///
/// The app forwards system events through `systemWillSleep()`, `systemDidWake()` and
/// `networkPathChanged(isSatisfied:)` (see `NetworkPathObserver`), and consumes `events`.
public actor HubSocket {
    public enum Event: Sendable, Equatable {
        /// A connection was opened (the backlog will follow as `msg` frames).
        case connected
        case frame(ServerFrame)
        /// The connection ended; a reconnect is scheduled unless the socket is stopped or asleep.
        case disconnected
        /// Closed by the Hub with code 4001 or a `revoked` frame. The socket stops for good.
        case revoked
    }

    public struct Configuration: Sendable {
        public var pingInterval: Duration = .seconds(30)
        public var watchdogTimeout: Duration = .seconds(75)
        public var watchdogTick: Duration = .seconds(5)
        public var backoffBase: Duration = .seconds(1)
        public var backoffMax: Duration = .seconds(60)
        public var closeGrace: Duration = .seconds(1)
        public init() {}
    }

    public nonisolated let events: AsyncStream<Event>
    private let continuation: AsyncStream<Event>.Continuation

    private let url: URL
    private let token: String
    private let connector: any WebSocketConnector
    private let config: Configuration
    private let jitter: @Sendable () -> Double

    private var running = false
    private var asleep = false
    private var networkUp = true
    private var connection: (any WebSocketConnection)?
    private var generation = 0
    private var attempt = 0
    private var lastActivity = ContinuousClock.now
    private var tasks: [Task<Void, Never>] = []
    private var reconnectTask: Task<Void, Never>?

    /// - Parameter jitter: returns a value in 0...1; injectable for tests.
    public init(url: URL, token: String, connector: any WebSocketConnector = URLSessionWebSocketConnector(),
                configuration: Configuration = Configuration(),
                jitter: @escaping @Sendable () -> Double = { Double.random(in: 0...1) }) {
        self.url = url
        self.token = token
        self.connector = connector
        config = configuration
        self.jitter = jitter
        (events, continuation) = AsyncStream.makeStream(of: Event.self)
    }

    public var isConnected: Bool { connection != nil }

    /// Starts connecting and keeps the connection up until `stop()`.
    public func start() {
        guard !running else { return }
        running = true
        attempt = 0
        connectIfAllowed()
    }

    /// Closes the connection and stops reconnecting. Ends `events`.
    public func stop() {
        running = false
        dropConnection(reconnect: false)
        continuation.finish()
    }

    /// Call from `NSWorkspace.willSleepNotification`.
    public func systemWillSleep() {
        asleep = true
        dropConnection(reconnect: false)
    }

    /// Call from `NSWorkspace.didWakeNotification`. Reconnects at once.
    public func systemDidWake() {
        asleep = false
        attempt = 0
        reconnectNow()
    }

    /// Call from `NWPathMonitor` updates. A network that comes back reconnects at once.
    public func networkPathChanged(isSatisfied: Bool) {
        let wasUp = networkUp
        networkUp = isSatisfied
        if !isSatisfied {
            dropConnection(reconnect: false)
        } else if !wasUp || connection == nil {
            attempt = 0
            reconnectNow()
        } else {
            // The path changed (for example Wi-Fi to Ethernet): the old socket may be dead.
            attempt = 0
            reconnectNow()
        }
    }

    /// Sends `{"t":"ack","up_to":N}`. Best effort: the HTTP ack is the fallback.
    @discardableResult
    public func sendAck(upTo: Int) async -> Bool {
        guard let connection else { return false }
        do {
            try await connection.send(JSON.object(["t": "ack", "up_to": JSON(upTo)]).serialized())
            return true
        } catch {
            return false
        }
    }

    // MARK: - Internals

    private var allowed: Bool { running && !asleep && networkUp }

    private func reconnectNow() {
        reconnectTask?.cancel()
        reconnectTask = nil
        dropConnection(reconnect: false)
        connectIfAllowed()
    }

    private func connectIfAllowed() {
        guard allowed, connection == nil else { return }
        reconnectTask?.cancel()
        reconnectTask = nil
        generation += 1
        let gen = generation
        let conn = connector.connect(url: url, token: token)
        connection = conn
        lastActivity = .now
        continuation.yield(.connected)

        tasks.append(Task { [weak self] in await self?.receiveLoop(conn, gen) })
        tasks.append(Task { [weak self] in await self?.pingLoop(conn, gen) })
        tasks.append(Task { [weak self] in await self?.watchdogLoop(gen) })
    }

    private func receiveLoop(_ conn: any WebSocketConnection, _ gen: Int) async {
        while !Task.isCancelled {
            let message: WebSocketMessage
            do {
                message = try await conn.receive()
            } catch {
                connectionEnded(gen, closeCode: conn.closeCode)
                return
            }
            guard gen == generation else { return }
            lastActivity = .now
            attempt = 0
            let frame: ServerFrame
            switch message {
            case .text(let t): frame = ServerFrame.parse(t)
            case .data(let d): frame = String(data: d, encoding: .utf8).map(ServerFrame.parse) ?? .unknown
            }
            if frame == .revoked {
                revoked()
                return
            }
            continuation.yield(.frame(frame))
        }
    }

    private func pingLoop(_ conn: any WebSocketConnection, _ gen: Int) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: config.pingInterval)
            guard !Task.isCancelled, gen == generation else { return }
            // The pong may never come on a dead connection, so do not block the loop on it;
            // the watchdog handles silence.
            Task { [weak self] in
                do {
                    try await conn.sendPing()
                    await self?.pongReceived(gen)
                } catch {}
            }
        }
    }

    private func pongReceived(_ gen: Int) {
        guard gen == generation else { return }
        lastActivity = .now
        attempt = 0
    }

    private func watchdogLoop(_ gen: Int) async {
        while !Task.isCancelled {
            try? await Task.sleep(for: config.watchdogTick)
            guard !Task.isCancelled, gen == generation else { return }
            if ContinuousClock.now - lastActivity > config.watchdogTimeout {
                connectionEnded(gen, closeCode: nil)
                return
            }
        }
    }

    private func connectionEnded(_ gen: Int, closeCode: Int?) {
        guard gen == generation, connection != nil else { return }
        if closeCode == 4001 {
            revoked()
            return
        }
        dropConnection(reconnect: true)
    }

    private func revoked() {
        running = false
        dropConnection(reconnect: false, notify: false)
        continuation.yield(.revoked)
        continuation.finish()
    }

    private func dropConnection(reconnect: Bool, notify: Bool = true) {
        for t in tasks { t.cancel() }
        tasks.removeAll()
        if let connection {
            connection.close(grace: config.closeGrace)
            self.connection = nil
            generation += 1
            if notify { continuation.yield(.disconnected) }
        }
        if reconnect { scheduleReconnect() }
    }

    /// Delay for the current attempt: base * 2^attempt capped at max, with jitter in 50–100 %,
    /// never below the base.
    func backoffDelay(attempt: Int) -> Duration {
        let base = config.backoffBase.seconds
        let capped = min(config.backoffMax.seconds, base * pow(2, Double(min(attempt, 30))))
        let jittered = max(base, capped * (0.5 + 0.5 * jitter()))
        return .milliseconds(Int(jittered * 1000))
    }

    private func scheduleReconnect() {
        guard allowed else { return }
        reconnectTask?.cancel()
        let delay = backoffDelay(attempt: attempt)
        attempt += 1
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            await self?.connectIfAllowed()
        }
    }
}

extension Duration {
    var seconds: Double {
        let c = components
        return Double(c.seconds) + Double(c.attoseconds) / 1e18
    }
}
