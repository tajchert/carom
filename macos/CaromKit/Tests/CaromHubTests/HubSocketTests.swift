import CaromProtocol
import Foundation
import Testing
import os

@testable import CaromHub

final class FakeConnection: WebSocketConnection, @unchecked Sendable {
    private let lock = NSLock()
    private let stream: AsyncThrowingStream<WebSocketMessage, any Error>
    private let continuation: AsyncThrowingStream<WebSocketMessage, any Error>.Continuation
    private var iterator: AsyncThrowingStream<WebSocketMessage, any Error>.AsyncIterator
    private var _sent: [String] = []
    private var _pings = 0
    private var _closed = false
    private var _closeCode: Int?
    let answersPings: Bool

    init(answersPings: Bool) {
        self.answersPings = answersPings
        (stream, continuation) = AsyncThrowingStream.makeStream(of: WebSocketMessage.self)
        iterator = stream.makeAsyncIterator()
    }

    var sent: [String] { lock.withLock { _sent } }
    var pings: Int { lock.withLock { _pings } }
    var closed: Bool { lock.withLock { _closed } }

    func deliver(_ text: String) { continuation.yield(.text(text)) }

    /// The server closes the connection (optionally with a close code).
    func serverClose(code: Int?) {
        lock.withLock { _closeCode = code }
        continuation.finish(throwing: URLError(.networkConnectionLost))
    }

    func receive() async throws -> WebSocketMessage {
        // Only the HubSocket receive loop calls this, one call at a time.
        var it = lock.withLock { iterator }
        let next = try await it.next()
        lock.withLock { iterator = it }
        guard let next else { throw URLError(.networkConnectionLost) }
        return next
    }

    func send(_ text: String) async throws {
        lock.withLock { _sent.append(text) }
    }

    func sendPing() async throws {
        lock.withLock { _pings += 1 }
        if !answersPings { try await Task.sleep(for: .seconds(3600)) }
    }

    func close(grace: Duration) {
        lock.withLock { _closed = true }
        continuation.finish(throwing: URLError(.cancelled))
    }

    var closeCode: Int? { lock.withLock { _closeCode } }
}

final class FakeConnector: WebSocketConnector, @unchecked Sendable {
    private let lock = NSLock()
    private var _connections: [FakeConnection] = []
    private(set) var lastToken: String?
    let answersPings: Bool

    init(answersPings: Bool = true) { self.answersPings = answersPings }

    var connections: [FakeConnection] { lock.withLock { _connections } }

    func connect(url: URL, token: String) -> any WebSocketConnection {
        let c = FakeConnection(answersPings: answersPings)
        lock.withLock {
            _connections.append(c)
            lastToken = token
        }
        return c
    }
}

actor EventLog {
    private(set) var events: [HubSocket.Event] = []
    func add(_ e: HubSocket.Event) { events.append(e) }
}

/// Polls `condition` until it holds or `timeout` passes.
func eventually(_ timeout: Duration = .seconds(2), _ condition: @Sendable () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}

func fastConfig() -> HubSocket.Configuration {
    var c = HubSocket.Configuration()
    c.pingInterval = .milliseconds(20)
    c.watchdogTimeout = .milliseconds(150)
    c.watchdogTick = .milliseconds(10)
    c.backoffBase = .milliseconds(10)
    c.backoffMax = .milliseconds(80)
    c.closeGrace = .milliseconds(10)
    return c
}

func makeSocket(_ connector: FakeConnector, config: HubSocket.Configuration = fastConfig()) -> (HubSocket, EventLog) {
    let socket = HubSocket(url: URL(string: "wss://hub.test/v1/ws")!, token: "ct1.t", connector: connector,
                           configuration: config, jitter: { 1 })
    let log = EventLog()
    Task {
        for await e in socket.events { await log.add(e) }
    }
    return (socket, log)
}

@Suite("HubSocket connection policy")
struct HubSocketTests {
    @Test func framesAreParsedAndDelivered() async throws {
        let connector = FakeConnector()
        let (socket, log) = makeSocket(connector)
        await socket.start()
        #expect(connector.lastToken == "ct1.t")
        let conn = try #require(connector.connections.first)
        conn.deliver(#"{"t":"msg","seq":3,"envelope":{"v":1}}"#)
        conn.deliver(#"{"t":"dir"}"#)
        conn.deliver(#"{"t":"pair","pair_id":"AAECAwQFBgcICQoLDA0ODw","candidate":{"device_id":"dev_x"}}"#)
        conn.deliver("pong")
        conn.deliver(#"{"t":"future-frame"}"#)
        #expect(await eventually { await log.events.count >= 6 })
        #expect(await log.events == [
            .connected,
            .frame(.msg(seq: 3, envelope: ["v": 1])),
            .frame(.dir),
            .frame(.pair(pairId: "AAECAwQFBgcICQoLDA0ODw", candidate: ["device_id": "dev_x"])),
            .frame(.pong),
            .frame(.unknown),
        ])
        #expect(await socket.sendAck(upTo: 3))
        #expect(conn.sent == [#"{"t":"ack","up_to":3}"#])
        await socket.stop()
        #expect(conn.closed)
    }

    @Test func protocolPingsAreSent() async throws {
        let connector = FakeConnector()
        let (socket, _) = makeSocket(connector)
        await socket.start()
        let conn = try #require(connector.connections.first)
        #expect(await eventually { conn.pings >= 3 })
        // Answered pings keep the connection alive past the watchdog timeout.
        try await Task.sleep(for: .milliseconds(250))
        #expect(connector.connections.count == 1)
        #expect(!conn.closed)
        await socket.stop()
    }

    @Test func watchdogDropsASilentConnectionAndReconnects() async throws {
        let connector = FakeConnector(answersPings: false)
        let (socket, log) = makeSocket(connector)
        await socket.start()
        #expect(await eventually { connector.connections.count >= 2 })
        #expect(connector.connections[0].closed)
        #expect(await eventually { await log.events.count >= 3 })
        #expect(await log.events.prefix(3) == [.connected, .disconnected, .connected])
        await socket.stop()
    }

    @Test func serverCloseReconnectsWithBackoff() async throws {
        let connector = FakeConnector()
        let (socket, log) = makeSocket(connector)
        await socket.start()
        connector.connections[0].serverClose(code: 1006)
        #expect(await eventually { connector.connections.count == 2 })
        connector.connections[1].serverClose(code: nil)
        #expect(await eventually { connector.connections.count == 3 })
        #expect(await eventually { await log.events.filter { $0 == .disconnected }.count == 2 })
        await socket.stop()
    }

    @Test func revokedCloseCodeStopsForGood() async throws {
        let connector = FakeConnector()
        let (socket, log) = makeSocket(connector)
        await socket.start()
        connector.connections[0].serverClose(code: 4001)
        #expect(await eventually { await log.events.last == .revoked })
        try await Task.sleep(for: .milliseconds(100))
        #expect(connector.connections.count == 1)
    }

    @Test func revokedFrameStopsForGood() async throws {
        let connector = FakeConnector()
        let (socket, log) = makeSocket(connector)
        await socket.start()
        connector.connections[0].deliver(#"{"t":"revoked"}"#)
        #expect(await eventually { await log.events.last == .revoked })
        #expect(connector.connections[0].closed)
        try await Task.sleep(for: .milliseconds(100))
        #expect(connector.connections.count == 1)
    }

    @Test func sleepDisconnectsAndWakeReconnects() async throws {
        let connector = FakeConnector()
        let (socket, _) = makeSocket(connector)
        await socket.start()
        await socket.systemWillSleep()
        #expect(connector.connections[0].closed)
        try await Task.sleep(for: .milliseconds(100))
        #expect(connector.connections.count == 1)
        #expect(await !socket.isConnected)
        await socket.systemDidWake()
        #expect(connector.connections.count == 2)
        #expect(await socket.isConnected)
        await socket.stop()
    }

    @Test func networkLossAndReturn() async throws {
        let connector = FakeConnector()
        let (socket, _) = makeSocket(connector)
        await socket.start()
        await socket.networkPathChanged(isSatisfied: false)
        #expect(connector.connections[0].closed)
        try await Task.sleep(for: .milliseconds(100))
        #expect(connector.connections.count == 1)
        await socket.networkPathChanged(isSatisfied: true)
        #expect(connector.connections.count == 2)
        // A path change while connected replaces the socket immediately.
        await socket.networkPathChanged(isSatisfied: true)
        #expect(connector.connections.count == 3)
        #expect(connector.connections[1].closed)
        await socket.stop()
    }

    @Test func backoffDoublesFromOneToSixtySecondsWithJitter() async {
        let full = HubSocket(url: URL(string: "wss://h/v1/ws")!, token: "t", connector: FakeConnector(), jitter: { 1 })
        let none = HubSocket(url: URL(string: "wss://h/v1/ws")!, token: "t", connector: FakeConnector(), jitter: { 0 })
        var delays: [Duration] = []
        for a in 0..<9 { delays.append(await full.backoffDelay(attempt: a)) }
        #expect(delays == [1, 2, 4, 8, 16, 32, 60, 60, 60].map { Duration.seconds($0) })
        #expect(await none.backoffDelay(attempt: 0) == .seconds(1))  // never below 1 s
        #expect(await none.backoffDelay(attempt: 3) == .seconds(4))  // 50 % of 8 s
        #expect(await none.backoffDelay(attempt: 20) == .seconds(30))
    }

    @Test func frameParsing() {
        #expect(ServerFrame.parse("not json") == .unknown)
        #expect(ServerFrame.parse(#"{"t":"msg","seq":"x","envelope":{}}"#) == .unknown)
        #expect(ServerFrame.parse(#"{"t":"revoked"}"#) == .revoked)
    }
}
